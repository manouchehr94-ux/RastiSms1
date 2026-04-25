package com.SmsRasti

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SmsService : Service() {
    companion object {
        const val ACTION_START = "com.SmsRasti.START"
        const val ACTION_STOP = "com.SmsRasti.STOP"
        private const val CHANNEL_ID = "rastisms_gateway"
        private const val NOTIFY_ID = 1001

        fun buildPollUrl(prefs: PrefsHelper): String {
            val api = if (prefs.api().startsWith("/")) prefs.api() else "/${prefs.api()}"
            return prefs.server() + api +
                "?pass=${url(prefs.token())}" +
                "&user=${url(prefs.user())}" +
                "&password=${url(prefs.pass())}" +
                "&type=receive"
        }

        private fun url(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PrefsHelper
    private lateinit var client: OkHttpClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var ws: WebSocket? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsHelper(this)
        client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, notification("روشن است", "سرویس فعال - در حال اتصال"))
        startEverything()
        return START_STICKY
    }

    private fun startEverything() {
        if (running) return
        running = true
        acquireWakeLock()
        AlarmReceiver.schedule(this)
        connectWebSocket()
        startPollLoop()
    }

    private fun stopEverything() {
        running = false
        ws?.close(1000, "stopped")
        ws = null
        wakeLock?.release()
        wakeLock = null
        AlarmReceiver.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RastiSMS:GatewayLock")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) { Log.e("RastiSMS", "wakelock ${e.message}") }
    }

    private fun startPollLoop() {
        scope.launch {
            while (running) {
                try {
                    pollOnce()
                    AlarmReceiver.schedule(this@SmsService)
                } catch (e: Exception) {
                    Log.e("RastiSMS", "poll ${e.message}")
                    updateNotify("خطا", e.message ?: "poll error")
                }
                delay((prefs.interval().coerceAtLeast(7) * 1000).toLong())
            }
        }
    }

    private fun pollOnce() {
        if (!prefs.sending()) return
        val req = Request.Builder().url(buildPollUrl(prefs)).build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: ""
            updateNotify("فعال", "آخرین اتصال: HTTP ${res.code}")
            handleServerMessage(body)
        }
    }

    private fun connectWebSocket() {
        val server = prefs.server()
        if (server.isBlank()) return
        val wsPath = if (prefs.ws().startsWith("/")) prefs.ws() else "/${prefs.ws()}"
        val wsBase = when {
            server.startsWith("https://") -> server.replaceFirst("https://", "wss://")
            server.startsWith("http://") -> server.replaceFirst("http://", "ws://")
            else -> "ws://$server"
        }
        val url = wsBase + wsPath + "?pass=${URLEncoder.encode(prefs.token(), "UTF-8")}&user=${URLEncoder.encode(prefs.user(), "UTF-8")}&password=${URLEncoder.encode(prefs.pass(), "UTF-8")}"
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { updateNotify("WebSocket وصل", "سرویس فعال") }
            override fun onMessage(webSocket: WebSocket, text: String) { handleServerMessage(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateNotify("WebSocket قطع", "Polling فعال است")
                if (running) scope.launch { delay(15000); connectWebSocket() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) scope.launch { delay(15000); connectWebSocket() }
            }
        })
    }

    private fun handleServerMessage(body: String) {
        try {
            val json = JsonParser.parseString(body).asJsonObject
            if ((json.get("status")?.asString ?: "") == "ok") {
                val id = json.get("id")?.asString ?: ""
                val phone = json.get("phone")?.asString ?: return
                val msg = json.get("message")?.asString ?: return
                sendSms(id, phone, msg)
            }
        } catch (e: Exception) { Log.e("RastiSMS", "handle ${e.message}") }
    }

    private fun sendSms(id: String, phone: String, msg: String) {
        if (!prefs.sending()) return
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = sms.divideMessage(msg)
            sms.sendMultipartTextMessage(phone, null, parts, null, null)
            updateNotify("ارسال شد", phone)
            ack(id, "sent", "")
        } catch (e: Exception) {
            updateNotify("خطای ارسال", e.message ?: "failed")
            ack(id, "failed", e.message ?: "send failed")
        }
    }

    private fun ack(id: String, status: String, error: String) {
        if (id.isBlank()) return
        scope.launch {
            try {
                val url = prefs.server() + "/api/sms/gateway/ack/?pass=${URLEncoder.encode(prefs.token(), "UTF-8")}&id=${URLEncoder.encode(id, "UTF-8")}&status=$status&error=${URLEncoder.encode(error, "UTF-8")}"
                client.newCall(Request.Builder().url(url).build()).execute().close()
            } catch (_: Exception) {}
        }
    }

    fun reportIncoming(phone: String, message: String) {
        if (!prefs.receiving()) return
        scope.launch {
            try {
                val url = prefs.server() + "/api/sms/gateway/inbox/?pass=${URLEncoder.encode(prefs.token(), "UTF-8")}&phone=${URLEncoder.encode(phone, "UTF-8")}&message=${URLEncoder.encode(message, "UTF-8")}"
                client.newCall(Request.Builder().url(url).build()).execute().close()
                updateNotify("پیام دریافتی ثبت شد", phone)
            } catch (e: Exception) { updateNotify("خطای دریافت", e.message ?: "inbox error") }
        }
    }

    private fun updateNotify(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, notification(title, text))
    }

    private fun notification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RastiSMS Gateway - $title")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "RastiSMS Gateway", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        if (running) ContextCompat.startForegroundService(this, Intent(this, SmsService::class.java).setAction(ACTION_START))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
