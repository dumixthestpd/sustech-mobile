package edu.sustech.mobile.core

/**
 * Every SUSTech host the app talks to, plus the couple of public APIs that
 * need no sign-in.
 *
 * Each service is CAS-fronted on its own subdomain, so a session is a set of
 * cookies per host — never one global session.
 */
object Hosts {

    const val PMS = "https://pms.sustech.edu.cn"
    const val TIS = "https://tis.sustech.edu.cn"
    const val BLACKBOARD = "https://bb.sustech.edu.cn"
    const val CAMPUS_CARD = "https://campuscard.sustech.edu.cn"
    const val CAS = "cas.sustech.edu.cn"

    /** SUSTech CRA's public campus weather API — no auth. */
    const val CAMPUS_WEATHER = "https://api.sustech.online/weather"

    /** Open-Meteo air quality — no auth, same source the Python client uses. */
    const val AIR_QUALITY = "https://air-quality-api.open-meteo.com/v1/air-quality"

    const val CAMPUS_LAT = 22.6029
    const val CAMPUS_LON = 113.9283

    /** Bare hostname of a URL — no scheme, no path, no port (Cookie.domain rules). */
    fun host(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
}
