package com.SmsRasti

import android.content.Context

class PrefsHelper(context: Context) {

    private val prefs = context.getSharedPreferences("rastisms_prefs", Context.MODE_PRIVATE)

    fun save(
        server: String,
        api: String,
        ws: String,
        token: String,
        user: String,
        pass: String,
        interval: Int,
        sending: Boolean,
        receiving: Boolean,
        autoStart: Boolean
    ) {
        prefs.edit()
            .putString("server", server.trim().trimEnd('/'))
            .putString("api", api.trim())
            .putString("ws", ws.trim())
            .putString("token", token.trim())
            .putString("user", user.trim())
            .putString("pass", pass.trim())
            .putInt("interval", interval.coerceAtLeast(7))
            .putBoolean("sending", sending)
            .putBoolean("receiving", receiving)
            .putBoolean("autoStart", autoStart)
            .apply()
    }

    fun server(): String {
        return prefs.getString("server", "") ?: ""
    }

    fun api(): String {
        return prefs.getString("api", "/api/sms/gateway/poll/") ?: "/api/sms/gateway/poll/"
    }

    fun ws(): String {
        return prefs.getString("ws", "/ws/sms/gateway/") ?: "/ws/sms/gateway/"
    }

    fun token(): String {
        return prefs.getString("token", "") ?: ""
    }

    fun user(): String {
        return prefs.getString("user", "") ?: ""
    }

    fun pass(): String {
        return prefs.getString("pass", "") ?: ""
    }

    fun interval(): Int {
        return prefs.getInt("interval", 7).coerceAtLeast(7)
    }

    fun sending(): Boolean {
        return prefs.getBoolean("sending", true)
    }

    fun receiving(): Boolean {
        return prefs.getBoolean("receiving", true)
    }

    fun autoStart(): Boolean {
        return prefs.getBoolean("autoStart", true)
    }
}
