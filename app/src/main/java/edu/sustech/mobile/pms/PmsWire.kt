package edu.sustech.mobile.pms

/**
 * The print host's non-JSON answers, told apart before anything is parsed.
 *
 * Everything the print API serves is JSON, so an HTML answer is always a
 * refusal of some kind — but three different kinds, and each needs its own
 * response:
 *
 *  - the campus-only gateway page, which `pms.sustech.edu.cn` serves with
 *    **HTTP 200** (not 403) to every address outside the campus network: a
 *    location fact, which a sign-in cannot fix;
 *  - a CAS sign-in page: the session is gone;
 *  - any other page: a fault worth reporting as what it is.
 *
 * Reading all three as "session expired" is what made an off-campus print tab
 * tell users to sign in again, which never helps. The classification lives here
 * — outside OkHttp, on purpose — so the wire question can be tested without a
 * device.
 */
internal object PmsWire {

    /** Phrases that only the campus-only gateway page carries. */
    private val ACCESS_NOTICE = listOf(
        "仅限校内访问",
        "联创打印访问提示",
        "available only from the campus network",
        "campus network",
    )

    /** Phrases that only a CAS sign-in page carries. */
    private val SIGN_IN_PAGE = listOf(
        "name=\"execution\"",
        "/cas/login",
        "name=\"username\"",
    )

    /** Envelope messages that mean "the session is gone", not "the request was wrong". */
    private val STALE = listOf("请用户重新登录", "重新登录", "无效会话", "未登录", "请登录", "用户未登录")

    enum class Html {
        /** The campus-only gateway page: the request never reached this account. */
        ACCESS_NOTICE,

        /** A sign-in page: the session is gone. */
        SIGN_IN_PAGE,

        /** Some other page — a fault, but not a claim about the session. */
        OTHER,
    }

    /** What an HTML body from the print host is. */
    fun html(text: String): Html = when {
        containsAny(text, ACCESS_NOTICE) -> Html.ACCESS_NOTICE
        containsAny(text, SIGN_IN_PAGE) -> Html.SIGN_IN_PAGE
        else -> Html.OTHER
    }

    /** True when an envelope's own message means "sign in again". */
    fun isStale(message: String): Boolean = containsAny(message, STALE)

    private fun containsAny(text: String, needles: List<String>) =
        needles.any { text.contains(it, ignoreCase = true) }
}
