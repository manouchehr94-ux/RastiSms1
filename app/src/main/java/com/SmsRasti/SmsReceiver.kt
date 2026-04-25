package com.SmsRasti

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = PrefsHelper(context)
        if (!prefs.receiving()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val phone = messages.firstOrNull()?.originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }

        ContextCompat.startForegroundService(context, Intent(context, SmsService::class.java).setAction(SmsService.ACTION_START))

        Thread {
            try {
                val url = prefs.server() + "/api/sms/gateway/inbox/" +
                    "?pass=${URLEncoder.encode(prefs.token(), "UTF-8")}" +
                    "&user=${URLEncoder.encode(prefs.user(), "UTF-8")}" +
                    "&password=${URLEncoder.encode(prefs.pass(), "UTF-8")}" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}" +
                    "&message=${URLEncoder.encode(body, "UTF-8")}"
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().close()
            } catch (_: Exception) {}
        }.start()
    }
}
