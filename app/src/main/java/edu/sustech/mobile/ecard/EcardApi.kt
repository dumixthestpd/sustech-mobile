package edu.sustech.mobile.ecard

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.sso.CasLogin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * 校园卡（一卡通）服务。
 *
 * 全部走原生 HTTP，不用 WebView —— 页面里的二维码本来就是前端用
 * `jquery.qrcode` 把一段文本渲染出来的，所以拿到文本即可本地生成。
 *
 * 接口（均由实际登录抓取确认）：
 *  * `POST /epay/vcard/getqrcode` → `{"qrcode":"SWH5_…"}` **校园码**，需 CSRF 头，每次调用都变
 *  * `GET  /epay/h5/v5qrcode?codetype=O5` → 身份码文本渲染在 `<input id="myText" value="…">`
 *  * `GET  /epay/h5/accountinfo` → `weui-cell__bd`/`__ft` 键值对
 *  * `GET  /epay/h5/bill` → `weui-media-box` 卡片列表
 *
 * 会话由 [edu.sustech.mobile.core.CookieStore] 统一持有；[App.http] 不跟随重定向，
 * 所以「302 到 CAS」就是会话失效的明确信号（`signInRequired`），由
 * [edu.sustech.mobile.sso.Session.reloginCard] 静默重登。
 */
class EcardApi(private val http: OkHttpClient) {

    /** 账户信息。余额单独给出数值形式，便于做低余额预警。 */
    data class Account(
        val name: String = "",
        val sid: String = "",
        val balance: String = "",
        val school: String = "",
        val major: String = "",
        val className: String = "",
    ) {
        /** 余额（元）；解析失败时为 0 */
        val balanceValue: Double
            get() = Regex("""[0-9]+(?:\.[0-9]+)?""").find(balance)?.value?.toDoubleOrNull() ?: 0.0
    }

    /** 一条消费流水 */
    data class Bill(
        val type: String,
        val amount: String,
        val time: String,
        val status: String,
    ) {
        val amountValue: Double
            get() = Regex("""[0-9]+(?:\.[0-9]+)?""").find(amount)?.value?.toDoubleOrNull() ?: 0.0

        /** 用于识别「是不是新的一笔」 */
        val key: String get() = "$time|$amount|$type"
    }

    /**
     * 两种码。
     *
     * CAMPUS 校园码：消费 + 门禁扫码，页面每 30s 刷新。
     * IDENTITY 身份码：身份核验，页面每 60s 刷新。
     */
    enum class QrKind(val codeType: String, val refreshMs: Long) {
        CAMPUS("H5", 30_000L),
        IDENTITY("O5", 60_000L),
    }

    // ---------------------------------------------------------------- 读取

    /** 账密是否已被 CAS 接受（Account 页的会话探针） */
    fun isSignedIn(): Boolean = runCatching { accountInfo(); true }.getOrDefault(false)

    fun accountInfo(): Account {
        val html = getText("/epay/h5/accountinfo")
        val re = Regex(
            """<div class="weui-cell__bd">\s*<p>([^<]*)</p>\s*</div>\s*""" +
                """<div class="weui-cell__ft">([^<]*)</div>"""
        )
        val map = re.findAll(html).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
        return Account(
            name = map["姓名"].orEmpty(),
            sid = map["学工号"].orEmpty(),
            balance = map["账户余额"].orEmpty(),
            school = map["学校"].orEmpty(),
            major = map["专业"].orEmpty(),
            className = map["班级"].orEmpty(),
        )
    }

    /** 消费流水，最新在前 */
    fun bill(): List<Bill> {
        val html = getText("/epay/h5/bill")
        return html.split("weui-media-box weui-media-box_text")
            .drop(1)
            .mapNotNull { block ->
                val title = Regex("""<h4 class="weui-media-box__title"[^>]*>([\s\S]*?)</h4>""")
                    .find(block)?.groupValues?.get(1).orEmpty()
                val divs = Regex("""<div[^>]*>([^<]*)</div>""")
                    .findAll(title).map { it.groupValues[1].trim() }.toList()
                val time = Regex("""color:#999;flex:\s*1">([^<]*)</div>""")
                    .find(block)?.groupValues?.get(1)?.trim().orEmpty()
                val status = Regex("""<span class="label label-[^"]*"[^>]*>([^<]*)</span>""")
                    .find(block)?.groupValues?.get(1)?.trim().orEmpty()
                val type = divs.getOrNull(0).orEmpty()
                val amount = divs.getOrNull(1).orEmpty()
                if (type.isBlank() && amount.isBlank()) null
                else Bill(type = type, amount = amount, time = time, status = status)
            }
    }

    // ------------------------------------------------------------ 二维码

    /** 校园码文本（需 CSRF 头） */
    fun campusQrText(): String {
        val (header, token) = csrf()
        val request = Request.Builder()
            .url(Hosts.CAMPUS_CARD + "/epay/vcard/getqrcode")
            .post(ByteArray(0).toRequestBody(null))
            .header("User-Agent", CasLogin.UA)
            .apply { if (header.isNotBlank() && token.isNotBlank()) header(header, token) }
            .build()
        val body = execute(request, "/epay/vcard/getqrcode").trim()
        return runCatching { JSONObject(body).optString("qrcode") }.getOrDefault("")
    }

    /** 身份码文本（服务端渲染进 <input id="myText">） */
    fun identityQrText(codeType: String = "O5"): String {
        val html = getText("/epay/h5/v5qrcode?codetype=$codeType")
        return Regex("""id="myText"[^>]*value="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""value="([^"]*)"[^>]*id="myText"""").find(html)?.groupValues?.get(1)
            ?: ""
    }

    fun qrText(kind: QrKind): String = when (kind) {
        QrKind.CAMPUS -> campusQrText()
        QrKind.IDENTITY -> identityQrText(kind.codeType)
    }

    // ------------------------------------------------------------ 传输

    private fun csrf(): Pair<String, String> {
        val html = getText("/epay/vcard/index")
        val token = Regex("""<meta\s+name="_csrf"\s+content="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        val header = Regex("""<meta\s+name="_csrf_header"\s+content="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        return header to token
    }

    private fun getText(path: String): String = execute(
        Request.Builder()
            .url(Hosts.CAMPUS_CARD + path)
            .get()
            .header("User-Agent", CasLogin.UA)
            .build(),
        path,
    )

    /**
     * 发一次请求并判定会话。
     *
     * `App.http` 不跟随重定向，所以 302 就是「被弹回 CAS」= 需要重新登录。
     */
    private fun execute(request: Request, what: String): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        response.use {
            return when {
                it.code == 302 || it.code == 301 -> {
                    val location = it.header("Location").orEmpty()
                    if (location.contains(Hosts.CAS)) {
                        throw ApiException("校园卡会话已失效", signInRequired = true)
                    }
                    throw ApiException("校园卡重定向到 $location")
                }
                it.code == 403 -> throw ApiException("校园卡需要校园网")
                it.code >= 400 -> throw ApiException("校园卡回答 HTTP ${it.code}（$what）")
                else -> it.body?.string().orEmpty()
            }
        }
    }

    companion object {

        /** 校园卡新版 SPA（微信内）的基址 */
        const val SPA_BASE = "https://campuscard.xh.sustech.edu.cn/"

        /** 微信静默授权跳板 */
        private const val WECHAT_GATEWAY = "https://wechat.xh.sustech.edu.cn/redirect"

        /**
         * 构造「在微信里充值 N 元」的链接。
         *
         * 链路（2026-10-01 实测确认）：
         *   wechat.xh.sustech.edu.cn/redirect
         *     → 302 open.weixin.qq.com（scope=snsapi_base，静默授权，无需用户确认）
         *     → 带 ?user=&access_token= 跳回 SPA
         *     → #/PayMoney 金额已由 pay_money 预填
         *     → 支付提交到 https://payment.sustech.edu.cn/zhifu/payAccept.aspx
         *
         * `pay_money` 能穿过 OAuth 跳转（redirect_uri 里双重编码保留），
         * 所以金额可以预填 —— 这点比旧的 epay 充值页好（那页不认 URL 参数）。
         *
         * ⚠️ 硬约束：`open.weixin.qq.com` 这一步**只能在微信客户端内完成**，
         * 所以该链接必须发到微信里再点开。
         */
        fun wechatRechargeUrl(amount: Double): String {
            val business = SPA_BASE + if (amount > 0) "?pay_money=${trimAmount(amount)}" else ""
            return WECHAT_GATEWAY + "?businessUrl=" +
                java.net.URLEncoder.encode(business, "UTF-8")
        }

        private fun trimAmount(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        /**
         * 网关认的「微信系客户端」UA。**必须带 `MicroMessenger`**，否则
         * [WECHAT_GATEWAY] 不会 302，而是直接返回一个「请在微信客户端打开链接」
         * 的错误页。
         */
        private const val WECHAT_UA =
            "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 " +
                "MicroMessenger/8.0.47.2560(0x28002F3B) WeChat/arm64 " +
                "NetType/WIFI Language/zh_CN ABI/arm64"
    }

    /**
     * 取出网关那一跳的落地 URL —— 也就是真正需要「在微信系客户端里打开」的那个
     * `open.weixin.qq.com/connect/oauth2/authorize` 地址。
     *
     * 网关自己只做 302，把浏览器交给微信。因为 [App.http] 不跟随重定向，这里
     * 直接读 `Location` 即可，不用重新实现一遍 URL 编码规则。
     *
     * 实测该地址**在微信外是死循环**（301 跳自己，加 `connect_redirect=1` 后
     * 仍然跳自己），只有微信/企业微信客户端会原生接管，所以它同时也是「能不能
     * 直接交给客户端打开」这个问题的测试对象。
     *
     * @return `Location`；网关没有按预期 302 时返回 null（链路变了）。
     */
    fun wechatOauthUrl(amount: Double): String? {
        val request = Request.Builder()
            .url(wechatRechargeUrl(amount))
            .get()
            .header("User-Agent", WECHAT_UA)
            .build()
        return try {
            http.newCall(request).execute().use { it.header("Location") }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }
}
