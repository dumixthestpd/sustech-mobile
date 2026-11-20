package edu.sustech.mobile.weather
import java.time.*
object AirReading {
    fun valid(value: Double?, stamp: String, now: Instant = Instant.now()): Int? {
        if (value == null || !value.isFinite() || value < 0 || value > 500) return null
        val at = runCatching { OffsetDateTime.parse(stamp).toInstant() }.getOrElse {
            runCatching { LocalDateTime.parse(stamp).atZone(ZoneId.of("Asia/Shanghai")).toInstant() }.getOrNull()
        } ?: return null
        if (Duration.between(at, now).seconds !in -3600L..10800L) return null
        return kotlin.math.round(value).toInt()
    }
}
