package edu.sustech.mobile.weather

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Hosts
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Campus weather + air quality — both public, both work off campus.
 *
 * Weather comes from SUSTech CRA's own API, which returns a Chinese sentence
 * with the numbers embedded in it; the sentence is parsed for values and the
 * one closed-set fact the app cares about (rain in the next two hours) is
 * mapped to an English phrase, so no Chinese reaches the UI.
 * Air quality comes from Open-Meteo, the same source the Python client uses.
 */
class WeatherClient(private val http: OkHttpClient) {

    data class Weather(val tempC: Int?, val feelsLike: Int?, val rainExpected: Boolean)

    data class AirQuality(val aqi: Int?, val category: String, val observedAt: String = "", val pm25: Double? = null)

    fun weather(): Weather {
        val body = get(Hosts.CAMPUS_WEATHER)
        val message = JSONObject(body).optString("msg", "")
        return Weather(
            tempC = Regex("气温([0-9.]+)℃").find(message)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt(),
            feelsLike = Regex("体感([0-9.]+)℃").find(message)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt(),
            rainExpected = message.contains("降雨") && !message.contains("无降雨"),
        )
    }

    fun airQuality(): AirQuality {
        val url = Hosts.AIR_QUALITY +
            "?latitude=${Hosts.CAMPUS_LAT}&longitude=${Hosts.CAMPUS_LON}" +
            "&current=us_aqi,pm2_5,pm10,ozone&timezone=Asia/Shanghai"
        val current = JSONObject(get(url)).optJSONObject("current") ?: JSONObject()
        val stamp = current.optString("time")
        val aqi = AirReading.valid((current.opt("us_aqi") as? Number)?.toDouble(), stamp)
        return AirQuality(aqi, categoryOf(aqi), stamp,
            (current.opt("pm2_5") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 })
    }

    /** US AQI bands. The Python client stubs this out; the app renders it. */
    private fun categoryOf(aqi: Int?): String = when {
        aqi == null -> ""
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for sensitive groups"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "SUSTechMobile/0.2")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code >= 400 || text.isBlank()) {
                    throw ApiException("HTTP ${response.code}")
                }
                text
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }
}
