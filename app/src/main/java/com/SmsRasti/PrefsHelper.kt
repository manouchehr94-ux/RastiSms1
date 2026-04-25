package com.SmsRasti

import android.content.Context

class PrefsHelper(context: Context) {
    private val prefs = context.getSharedPreferences("rastisms_prefs", Context.MODE_PRIVATE)

    fun save(server: String, api: String, ws: String, token: String, user: String, pass: String, interval: Int, sending: Boolean, receiving: Boolean, autoStart: Boolean) {
        prefs.edit()
            .putString("server", server.trimEnd('/'))
            .putString("api", api)
            .putString("ws", ws)
            .putString("token", token)
            .putString("user", user)
            .putString("pass", pass)
            .putInt("interval", interval.coerceAtLeast(7))
            .putBoolean("sending", sending)
            .putBoolean("receiving", receiving)
            .putBoolean("autoStart", autoStart)
            .apply()
    }

    fun server() = prefs.getString("server", "") ?: ""
    fun api() = prefs.getString("api", "/api/sms/gateway/poll/") ?: "/api/sms/gateway/poll/"
    fun ws() = prefs.getString("ws", "/ws/sms/gateway/") ?: "/ws/sms/gateway/"
    fun token() = prefs.getString("token", "") ?: ""
    fun user() = prefs.getString("user", "") ?: ""
    fun pass() = prefs.getString("pass", "") ?: ""
    fun interval() = prefs.getInt("interval", 7).coerceAtLeast(7)
    fun sending() = prefs.getBoolean("sending", true)
    fun receiving() = prefs.getBoolean("receiving", true)
    fun autoStart() = prefs.getBoolean("autoStart", true)
}
