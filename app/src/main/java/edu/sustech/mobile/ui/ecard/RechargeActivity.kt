package edu.sustech.mobile.ui.ecard

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ecard.EcardApi
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * App 内的充值页。
 *
 * 为什么最后一步必须落到微信里：充值页的支付是微信 **JSAPI**
 * （`WeixinJSBridge.invoke('getBrandWCPayRequest')`），而后端返回的 `config`
 * 是一份 **绑定当前页面 URL 的微信 JS-SDK 签名**。这两样东西都只存在于微信系
 * 内置浏览器里，第三方原生代码无法唤起 —— 不是"没找到办法"，是微信的服务端把
 * 门槛设在了那里。校园卡余额只能在微信内补，所以 App 能做的是
 * 「把金额选好、把出路铺到只剩一步」。
 *
 * 因此底部两排按钮：
 *  * 「企业微信」「微信」→ [directLaunch]，尝试用 `ACTION_VIEW` + `setPackage`
 *    把那串 `open.weixin.qq.com` 授权链接**直接丢给客户端**。这是本测试版要
 *    验证的假设（预期失败：微信系内置浏览器 Activity 不导出），失败会自动退化
 *    为分享，不会卡住。
 *  * 「分享到微信」→ [shareToWechat]，当前已知可靠的路径：把带金额的链接作为
 *    一条消息发给微信，用户在微信里点开即可（`snsapi_base` 静默授权）。
 *  * 「复制链接」→ 复制同一条链接，作为最后的手工兜底。
 *
 * 诊断面板是测试期的主角：它把每个候选 Intent 的解析结果、拉起是否抛异常都
 * 显示出来，点一下可整段复制。拿到真实手机的这份文本就能判定假设成立与否。
 *
 * 注意 cookie 同步：OkHttp 与 WebView 的 cookie 存储互相独立，不同步会被弹回
 * CAS 登录页，细节见 [syncCookiesThenLoad]。
 */
class RechargeActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var amount: Double = 0.0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)   // 与其他 Activity 一致：初始化 App 单例
        setContentView(R.layout.activity_recharge)
        title = getString(R.string.ecard_recharge)

        amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
        web = findViewById(R.id.recharge_web)
        findViewById<TextView>(R.id.recharge_hint).text =
            if (amount > 0) getString(R.string.ecard_recharge_hint, "%.2f".format(amount))
            else getString(R.string.ecard_recharge_hint_bare)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (amount > 0) fillAmount(view, amount)
            }
        }
        syncCookiesThenLoad(Hosts.CAMPUS_CARD + "/epay/h5/chargeindex")

        findViewById<Button>(R.id.recharge_refill).setOnClickListener {
            if (amount > 0) fillAmount(web, amount) else web.reload()
        }
        findViewById<Button>(R.id.recharge_reload).setOnClickListener { web.reload() }

        // 实验性：直接把授权链接交给客户端，失败则自动退化为分享。
        findViewById<Button>(R.id.recharge_launch_wework).setOnClickListener {
            directLaunch(PKG_WEWORK, getString(R.string.ecard_direct_wework))
        }
        findViewById<Button>(R.id.recharge_launch_wx).setOnClickListener {
            directLaunch(PKG_WECHAT, getString(R.string.ecard_direct_wx))
        }

        findViewById<Button>(R.id.recharge_share).setOnClickListener { shareToWechat() }

        // 复制的是「能真正完成充值」的那条链接，而不是这个 WebView 的地址 ——
        // 后者在微信里打开也走不通。
        findViewById<Button>(R.id.recharge_copy).setOnClickListener {
            copyText(EcardApi.wechatRechargeUrl(amount))
            Toast.makeText(this, getString(R.string.ecard_copied), Toast.LENGTH_LONG).show()
        }

        // 点诊断面板 = 整段复制，方便直接粘贴反馈。
        findViewById<TextView>(R.id.recharge_diag).setOnClickListener {
            copyText((it as TextView).text.toString())
            Toast.makeText(this, getString(R.string.ecard_diag_copied), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 直达拉起

    /**
     * 尝试把充值链接直接交给 [pkg] 打开。
     *
     * 先在 IO 线程把两条候选链接准备好（网关链接固定；OAuth 链接要问网关要
     * 302 的 `Location`，避免自己重写一遍 URL 编码规则），再回主线程做
     * resolve / startActivity。
     */
    private fun directLaunch(pkg: String, label: String) {
        runIo(
            block = {
                val gateway = EcardApi.wechatRechargeUrl(amount)
                val oauth = App.ecard.wechatOauthUrl(amount)
                gateway to oauth
            },
            onOk = { (gateway, oauth) -> ladder(pkg, label, gateway, oauth) },
            onErr = { showDiag(listOf(getString(R.string.ecard_diag_header, label), "", getString(R.string.ecard_diag_prepare_failed, it.friendly(this)))) },
        )
    }

    /**
     * 依次试「OAuth 链接 → 网关链接 → 另一个客户端」，每一步都记录结果。
     *
     * 之所以两条链接都要试：网关链接是给浏览器看的 302 跳板；OAuth 链接才是
     * 微信客户端真正认得的那一串。如果客户端注册了后者的 intent-filter，
     * 就能一步直达，连跳板都不需要。
     */
    private fun ladder(pkg: String, label: String, gateway: String, oauth: String?) {
        val lines = mutableListOf<String>()
        lines += getString(R.string.ecard_diag_header, label)
        lines += buildString {
            append(getString(R.string.ecard_diag_device, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            append(" · ${packageName} ${appVersion()}")
        }
        lines += getString(R.string.ecard_diag_installed, nameOf(PKG_WEWORK), installed(PKG_WEWORK), nameOf(PKG_WECHAT), installed(PKG_WECHAT))
        lines += ""
        lines += getString(R.string.ecard_diag_links)
        lines += "  ${getString(R.string.ecard_diag_gateway)}  $gateway"
        lines += "  OAuth ${oauth ?: getString(R.string.ecard_diag_no_redirect)}"
        lines += ""
        lines += getString(R.string.ecard_diag_resolution)

        val urls = listOfNotNull(oauth?.let { "OAuth" to it }, getString(R.string.ecard_diag_gateway) to gateway)
        for ((uname, url) in urls) {
            for ((p, pname) in listOf(PKG_WEWORK to nameOf(PKG_WEWORK), PKG_WECHAT to nameOf(PKG_WECHAT))) {
                lines += "  $pname · $uname → ${resolve(p, url)}"
            }
        }

        lines += ""
        lines += getString(R.string.ecard_diag_attempts)
        val candidates = mutableListOf<Pair<String, String>>()
        urls.forEach { (_, url) -> candidates += pkg to url }
        // 另一个客户端兜底，仍走网关链接（它对两种 UA 都会 302）
        candidates += (if (pkg == PKG_WECHAT) PKG_WEWORK else PKG_WECHAT) to gateway

        for ((p, url) in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(p)
            val resolved = intent.resolveActivity(packageManager)
            if (resolved == null) {
                lines += getString(R.string.ecard_diag_no_activity, nameOf(p), shorten(url))
                continue
            }
            lines += "  → ${nameOf(p)} · ${shorten(url)} = ${resolved.flattenToShortString()}"
            try {
                startActivity(intent)
                lines += getString(R.string.ecard_diag_launched, nameOf(p))
                showDiag(lines)
                return
            } catch (e: ActivityNotFoundException) {
                lines += "  ✗ ActivityNotFoundException"
            } catch (e: SecurityException) {
                lines += "  ✗ SecurityException: ${e.message}"
            }
        }

        lines += ""
        lines += getString(R.string.ecard_diag_fallback)
        showDiag(lines)
        shareToWechat()
    }

    /** 已知可靠的路径：把链接作为一条消息发给微信，用户在微信里点开。 */
    private fun shareToWechat() {
        val link = EcardApi.wechatRechargeUrl(amount)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        // 优先直接唤起微信；没有则退化为系统分享面板。
        // （resolveActivity 能看见微信，靠的是 manifest 里的 <queries>。）
        val wechat = Intent(send).setPackage(PKG_WECHAT)
        val target = if (wechat.resolveActivity(packageManager) != null) wechat else send
        runCatching { startActivity(Intent.createChooser(target, getString(R.string.ecard_share_wechat))) }
        Toast.makeText(this, getString(R.string.ecard_shared_hint), Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ 小工具

    /** 某个包能不能处理这个链接；返回可读的 Activity 名或「不可解析」。 */
    private fun resolve(pkg: String, url: String): String =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(pkg)
            .resolveActivity(packageManager)
            ?.flattenToShortString()
            ?: getString(R.string.ecard_diag_no_match)

    private fun installed(pkg: String): String = try {
        "✅ ${packageManager.getPackageInfo(pkg, 0).versionName ?: "?"}"
    } catch (e: Exception) {
        getString(R.string.ecard_diag_not_installed)
    }

    private fun appVersion(): String = try {
        "v${packageManager.getPackageInfo(packageName, 0).versionName}"
    } catch (e: Exception) {
        ""
    }

    private fun nameOf(pkg: String) = if (pkg == PKG_WEWORK) getString(R.string.ecard_direct_wework) else getString(R.string.ecard_direct_wx)

    /** 把长 URL 压成 `host/path…`，方便在小面板里读。 */
    private fun shorten(url: String): String {
        val u = Uri.parse(url)
        val path = (u.path ?: "").take(24)
        return "${u.host}$path${if ((u.path ?: "").length > 24) "…" else ""}"
    }

    private fun showDiag(lines: List<String>) {
        val text = lines.joinToString("\n")
        Log.i(TAG, "ecard diag\n$text")
        findViewById<View>(R.id.recharge_diag_scroll).visibility = View.VISIBLE
        findViewById<TextView>(R.id.recharge_diag).text = text
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ecard", text))
    }

    // ------------------------------------------------------------------ cookie

    /**
     * 把 OkHttp 的会话 cookie 灌进 WebView，然后才加载页面。
     *
     * 两个必须注意的点：
     *  1. **探针 URL 要带真实路径前缀**。服务器下发的是 `JSESSIONID=…; Path=/epay`，
     *     而 OkHttp 的 `Cookie.matches()` 要求 path 前缀匹配 —— 若用裸域名（path 为 "/"），
     *     所有 cookie 都会被过滤掉，看起来像"一条都没有"。
     *  2. **灌之前先清空 WebView 自己的 cookie**。否则新旧同名 `JSESSIONID` 会并存
     *     （实测读回是 `JSESSIONID=旧; JSESSIONID=新`），服务器取到失效的那个，
     *     页面被弹回 CAS 登录页 —— 表现就是"同步了却还是登录页"。
     */
    private fun syncCookiesThenLoad(url: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true)
        }

        val probes = listOf(
            Hosts.CAMPUS_CARD + "/epay/",
            "https://" + Hosts.CAS + "/cas/",
        )

        val flushThenLoad = {
            for (probe in probes) {
                val httpUrl = probe.toHttpUrl()
                App.cookies.loadForRequest(httpUrl).forEach { c ->
                    // 交给 WebView 时放宽到根路径，省去逐路径匹配
                    cm.setCookie(probe, "${c.name}=${c.value}; path=/")
                }
            }
            cm.flush()
            web.postDelayed({ web.loadUrl(url) }, 200)
        }

        // 先清空，避免旧会话残留
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.removeAllCookies { flushThenLoad() }
        } else {
            @Suppress("DEPRECATION")
            cm.removeAllCookie()
            flushThenLoad()
        }
    }

    /** 充值页不认 URL 参数，只能注入 JS 填金额并触发 input 事件 */
    private fun fillAmount(view: WebView, amount: Double) {
        val js = """
            (function(){
              var el = document.getElementById('amount');
              if (!el) return 'no-field';
              el.value = '${String.format(Locale.US, "%.2f", amount)}';
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return 'ok';
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    companion object {
        private const val TAG = "EcardRecharge"
        private const val EXTRA_AMOUNT = "amount"

        /** 微信 */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** 企业微信 */
        private const val PKG_WEWORK = "com.tencent.wework"

        fun start(ctx: Context, amount: Double) {
            ctx.startActivity(
                Intent(ctx, RechargeActivity::class.java)
                    .putExtra(EXTRA_AMOUNT, amount)
            )
        }
    }
}
