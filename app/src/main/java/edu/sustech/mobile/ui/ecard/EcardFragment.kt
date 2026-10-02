package edu.sustech.mobile.ui.ecard

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.ecard.EcardApi
import edu.sustech.mobile.sso.Session
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 校园卡。
 *
 * 原生亮码：二维码由 ZXing 本地生成，不走 WebView。
 *
 * 三个与「付款不失败」有关的设计：
 *  1. 亮码时保持常亮并把亮度拉满 —— 扫码成功率。
 *  2. 余额低于阈值时给出橙色预警（默认 30 元）—— 避免余额不足支付失败。
 *  3. 本页可见时轮询流水，识别到扫码消费后按设置执行 忽略 / 提醒 / 自动充值。
 */
class EcardFragment : Fragment() {

    private lateinit var client: EcardApi
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var nameView: TextView
    private lateinit var balanceView: TextView
    private lateinit var warnBox: LinearLayout
    private lateinit var warnText: TextView
    private lateinit var qrView: ImageView
    private lateinit var tipView: TextView
    private lateinit var billBox: LinearLayout

    private var kind = EcardApi.QrKind.CAMPUS
    private var refreshJob: Job? = null
    private var pollJob: Job? = null
    private var lastBillKey: String? = null
    private var balanceValue = 0.0
    private var errorText: String? = null
    private var hiddenSince = 0L
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_ecard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        client = App.ecard

        swipe = view.findViewById(R.id.ecard_swipe)
        nameView = view.findViewById(R.id.ecard_name)
        balanceView = view.findViewById(R.id.ecard_balance)
        warnBox = view.findViewById(R.id.ecard_warn)
        warnText = view.findViewById(R.id.ecard_warn_text)
        qrView = view.findViewById(R.id.ecard_qr)
        tipView = view.findViewById(R.id.ecard_tip)
        billBox = view.findViewById(R.id.ecard_bills)

        swipe.setOnRefreshListener { reloadAll() }
        view.findViewById<Button>(R.id.ecard_refresh).setOnClickListener { loadQr() }
        view.findViewById<Button>(R.id.ecard_face).setOnClickListener {
            startActivity(
                edu.sustech.mobile.ui.ServicePortalActivity.intent(
                    requireContext(),
                    "https://campuscard.sustech.edu.cn/epay/vcard/index",
                ),
            )
        }
        view.findViewById<Button>(R.id.ecard_recharge).setOnClickListener { openRecharge(0.0) }
        view.findViewById<Button>(R.id.ecard_warn_recharge).setOnClickListener { openRecharge(0.0) }
        view.findViewById<Button>(R.id.ecard_kind_campus).setOnClickListener {
            switchKind(EcardApi.QrKind.CAMPUS)
        }
        view.findViewById<Button>(R.id.ecard_kind_identity).setOnClickListener {
            switchKind(EcardApi.QrKind.IDENTITY)
        }
    }

    // ---------------------------------------------------------------- 加载

    private fun reloadAll() {
        loadQr()
        loadAccount()
        loadBill()
    }

    private fun loadQr() {
        runIo(
            block = { withRelogin { client.qrText(kind) } },
            onOk = { text ->
                errorText = null
                if (text.isBlank()) {
                    errorText = getString(R.string.ecard_qr_empty)
                } else {
                    val level = if (kind == EcardApi.QrKind.CAMPUS) ErrorCorrectionLevel.Q
                    else ErrorCorrectionLevel.L
                    qrView.setImageBitmap(renderQr(text, 760, level))
                    tipView.text = getString(
                        if (kind == EcardApi.QrKind.CAMPUS) R.string.ecard_tip_campus
                        else R.string.ecard_tip_identity,
                        (kind.refreshMs / 1000).toInt(),
                    )
                }
                refreshTip()
            },
            onErr = { t ->
                errorText = t.friendly(requireContext())
                refreshTip()
            },
        )
    }

    private fun loadAccount() {
        runIo(
            block = { withRelogin { client.accountInfo() } },
            onOk = { a ->
                nameView.text = listOf(a.name, a.className)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                balanceValue = a.balanceValue
                balanceView.text = getString(R.string.ecard_balance, a.balance.ifBlank { "—" })
                updateWarning(a.balance)
            },
            onErr = { t ->
                errorText = t.friendly(requireContext())
                refreshTip()
            },
        )
    }

    private fun loadBill() {
        runIo(
            block = { withRelogin { client.bill() } },
            onOk = { bills ->
                renderBills(bills)
                if (lastBillKey == null) lastBillKey = bills.firstOrNull()?.key
            },
            onErr = { t -> tipView.text = t.friendly(requireContext()) },
        )
        swipe.isRefreshing = false
    }

    /**
     * 会话失效 → 静默重登 → 重试一次。
     *
     * 必须放在 [runIo] 的 block **内部**（即 IO 线程上）：`runIo` 的 onErr 回调
     * 是在主线程执行的，在那里发网络会触发 NetworkOnMainThreadException。
     * 这也是同项目 TisApi.withRelogin / BbApi.withRelogin 的写法。
     */
    private suspend fun <T> withRelogin(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.signInRequired && Session.reloginCard()) block() else throw e
    }

    // ------------------------------------------------------------ 低余额预警

    private fun updateWarning(raw: String) {
        val threshold = EcardSettings.lowBalance(requireContext())
        if (balanceValue < threshold) {
            warnText.text = getString(R.string.ecard_warn, raw.ifBlank { "—" })
            warnBox.visibility = View.VISIBLE
        } else {
            warnBox.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------ 扫码联动

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(EcardSettings.pollMs(requireContext()))
                if (hiddenSince != 0L) continue
                val bills = runCatching { withContext(Dispatchers.IO) { client.bill() } }
                    .getOrNull() ?: continue
                val first = bills.firstOrNull() ?: continue
                val key = first.key
                if (lastBillKey == null) {
                    lastBillKey = key
                    continue
                }
                if (key != lastBillKey) {
                    lastBillKey = key
                    onConsumed(first)
                }
            }
        }
    }

    private fun onConsumed(item: EcardApi.Bill) {
        loadAccount()   // 立即刷新余额与预警
        when (EcardSettings.action(requireContext())) {
            EcardSettings.Action.IGNORE -> Unit

            EcardSettings.Action.NOTIFY -> {
                val suggest = EcardSettings.suggest(requireContext(), item.amountValue, balanceValue)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.ecard_pay_title)
                    .setMessage(
                        getString(
                            R.string.ecard_pay_msg,
                            item.type, item.amount,
                            "%.2f".format(balanceValue),
                            "%.2f".format(suggest),
                        )
                    )
                    .setPositiveButton(R.string.ecard_recharge) { _, _ -> openRecharge(suggest) }
                    .setNegativeButton(R.string.ecard_ignore, null)
                    .show()
            }

            EcardSettings.Action.RECHARGE -> {
                val suggest = EcardSettings.suggest(requireContext(), item.amountValue, balanceValue)
                if (suggest > 0) openRecharge(suggest)
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    /** 提示行 = 错误优先，否则显示刷新周期 */
    private fun refreshTip() {
        tipView.text = errorText ?: getString(
            if (kind == EcardApi.QrKind.CAMPUS) R.string.ecard_tip_campus
            else R.string.ecard_tip_identity,
            (kind.refreshMs / 1000).toInt(),
        )
    }

    private fun renderBills(bills: List<EcardApi.Bill>) {
        billBox.removeAllViews()
        if (bills.isEmpty()) {
            billBox.addView(TextView(requireContext()).apply {
                text = getString(R.string.ecard_no_bill)
                setPadding(48, 24, 48, 24)
                setTextColor(resources.getColor(R.color.ink_muted, null))
            })
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        bills.forEach { b ->
            val row = inflater.inflate(R.layout.item_ecard_bill, billBox, false)
            row.findViewById<TextView>(R.id.row_type).text = b.type
            row.findViewById<TextView>(R.id.row_amount).text = b.amount
            row.findViewById<TextView>(R.id.row_time).text = b.time
            row.findViewById<TextView>(R.id.row_status).text = b.status
            billBox.addView(row)
        }
    }

    private fun renderQr(text: String, size: Int, level: ErrorCorrectionLevel) =
        com.google.zxing.qrcode.QRCodeWriter().let { _ ->
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to level,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                pixels[y * w + x] = if (matrix[x, y]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        }

    // ---------------------------------------------------------------- 交互

    private fun switchKind(k: EcardApi.QrKind) {
        if (kind == k) return
        kind = k
        loadQr()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(kind.refreshMs)
                if (hiddenSince == 0L) loadQr()
            }
        }
    }

    /**
     * 打开 App 内的充值页（WebView + 金额预填）。
     *
     * 打开**之前**先确认会话有效：WebView 用的是自己的一份 cookie 快照，
     * 如果带着已过期的 JSESSIONID 打开，页面会自己报"登录已过期"，
     * 而 WebView 内部没有任何自动重登能力。所以在这里先把会话续上。
     */
    private fun openRecharge(amount: Double) {
        // 手动点「充值」时没有「刚消费了多少」这个上下文（amount 传 0），
        // 此时按设置里的策略取金额；`SAME_AS_PAY` 在这一场景下无从取值，
        // 回退到固定金额（默认 50，可在设置里改）。
        // 不这样做的话链接里不会带 pay_money，微信里打开也不会预填金额。
        val resolved = if (amount > 0) amount else when (EcardSettings.strategy(requireContext())) {
            EcardSettings.Strategy.TOP_UP_TO ->
                (EcardSettings.targetBalance(requireContext()) - balanceValue).coerceAtLeast(0.0)
            else -> EcardSettings.fixedAmount(requireContext()).toDouble()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { App.ecard.isSignedIn() }.getOrDefault(false) ||
                    Session.reloginCard()
            }
            if (ok) {
                RechargeActivity.start(requireContext(), resolved)
            } else {
                errorText = getString(R.string.session_expired)
                refreshTip()
            }
        }
    }

    // ------------------------------------------------------------ 生命周期

    private fun setVisible(visible: Boolean) {
        if (visible) {
            val now = SystemClock.elapsedRealtime()
            // 首次进入（hiddenSince == 0）或离开超过一个刷新周期 → 重新取码
            if (hiddenSince == 0L || now - hiddenSince > kind.refreshMs) loadQr()
            hiddenSince = 0
            loadAccount()
            loadBill()
            startAutoRefresh()
            startPolling()
            applyDisplayMode(true)
        } else {
            if (hiddenSince == 0L) hiddenSince = SystemClock.elapsedRealtime()
            refreshJob?.cancel()
            pollJob?.cancel()
            applyDisplayMode(false)
        }
    }

    private fun applyDisplayMode(on: Boolean) {
        val act = activity ?: return
        val lp = act.window.attributes
        lp.screenBrightness =
            if (on) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        act.window.attributes = lp
        if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (::swipe.isInitialized) setVisible(!isHidden)
    }

    override fun onPause() {
        if (::swipe.isInitialized) setVisible(false)
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (::swipe.isInitialized) setVisible(!hidden)
    }
}
