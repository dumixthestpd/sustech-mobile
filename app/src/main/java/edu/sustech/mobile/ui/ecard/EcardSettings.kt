package edu.sustech.mobile.ui.ecard

import android.content.Context

/**
 * 扫码支付联动的本地设置。
 *
 * 说明：「自动充值」只是**自动打开充值页并预填金额** —— 学校充值页只提供
 * 微信 JSAPI 支付（依赖 WeixinJSBridge，只存在于微信内置浏览器），
 * 第三方 App 无法唤起，最后一步必须用户在微信里手动确认。
 */
object EcardSettings {

    /**
     * 检测到扫码消费后的行为。
     *
     * 与 `sustech_survival` / `sustech-cli` 的凭据契约无关，纯本地偏好。
     */
    enum class Action { IGNORE, NOTIFY, RECHARGE }

    enum class Strategy { SAME_AS_PAY, FIXED, TOP_UP_TO }

    private const val PREFS = "sustech_mobile_ecard"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun action(c: Context): Action =
        runCatching { Action.valueOf(prefs(c).getString("action", Action.NOTIFY.name)!!) }
            .getOrDefault(Action.NOTIFY)

    fun setAction(c: Context, a: Action) = prefs(c).edit().putString("action", a.name).apply()

    fun strategy(c: Context): Strategy =
        runCatching { Strategy.valueOf(prefs(c).getString("strategy", Strategy.SAME_AS_PAY.name)!!) }
            .getOrDefault(Strategy.SAME_AS_PAY)

    fun setStrategy(c: Context, s: Strategy) = prefs(c).edit().putString("strategy", s.name).apply()

    /** 低余额预警阈值（元）：余额低于此值时提醒「可能支付失败」。 */
    fun lowBalance(c: Context): Double = prefs(c).getFloat("low_balance", 30f).toDouble()

    fun setLowBalance(c: Context, v: Double) = prefs(c).edit().putFloat("low_balance", v.toFloat()).apply()

    fun fixedAmount(c: Context): Int = prefs(c).getInt("fixed", 50)

    fun setFixedAmount(c: Context, v: Int) = prefs(c).edit().putInt("fixed", v).apply()

    fun targetBalance(c: Context): Int = prefs(c).getInt("target", 100)

    fun setTargetBalance(c: Context, v: Int) = prefs(c).edit().putInt("target", v).apply()

    /** 流水轮询间隔（毫秒） */
    fun pollMs(c: Context): Long = prefs(c).getLong("poll", 5_000L)

    fun setPollMs(c: Context, v: Long) = prefs(c).edit().putLong("poll", v.coerceAtLeast(2_000L)).apply()

    /** 本次消费后建议充值多少（元）；0 表示不必充 */
    fun suggest(c: Context, payAmount: Double, balance: Double): Double = when (strategy(c)) {
        Strategy.SAME_AS_PAY -> payAmount
        Strategy.FIXED -> fixedAmount(c).toDouble()
        Strategy.TOP_UP_TO -> (targetBalance(c) - balance).coerceAtLeast(0.0)
    }
}
