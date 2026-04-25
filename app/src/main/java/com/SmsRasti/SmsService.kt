package com.SmsRasti

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SmsService : Service() {

    companion object {
        const val ACTION_START = "com.SmsRasti.START"
        const val ACTION_STOP = "com.SmsRasti.STOP"

        private const val CHANNEL_ID = "rastisms_gateway"
        private const val NOTIFY_ID = 1001

        private fun encode(value: String): String {
            return URLEncoder.encode(value, "UTF-8")
        }

        fun buildPollUrl(prefs: PrefsHelper): String {
            val server = prefs.server().trimEnd('/')
            val api = if (prefs.api().startsWith("/")) prefs.api() else "/${prefs.api()}"

            return server + api +
                "?pass=${encode(prefs.token())}" +
                "&user=${encode(prefs.user())}" +
                "&password=${encode(prefs.pass())}" +
                "&type=receive"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var prefs: PrefsHelper
    private lateinit var client: OkHttpClient

    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()

        prefs = PrefsHelper(this)

        client = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFY_ID,
            notification("روشن است", "سرویس فعال - در حال اتصال")
        )

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

        updateNotify("فعال", "سرویس RastiSMS فعال شد")
    }

    private fun stopEverything() {
        running = false

        try {
            webSocket?.close(1000, "stopped")
        } catch (_: Exception) {
        }

        webSocket = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }

        wakeLock = null

        AlarmReceiver.cancel(this)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "RastiSMS:GatewayWakeLock"
                )

                wakeLock?.setReferenceCounted(false)

                // WakeLock محدود؛ هر بار در حلقه تمدید می‌شود
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("RastiSMS", "WakeLock error: ${e.message}")
        }
    }

    private fun startPollLoop() {
        scope.launch {
            while (running) {
                try {
                    acquireWakeLock()
                    pollOnce()
                    AlarmReceiver.schedule(this@SmsService)
                } catch (e: Exception) {
                    Log.e("RastiSMS", "Poll error: ${e.message}")
                    updateNotify("خطا در اتصال", e.message ?: "poll error")
                }

                delay((prefs.interval().coerceAtLeast(7) * 1000).toLong())
            }
        }
    }

    private fun pollOnce() {
        if (!prefs.sending()) {
            updateNotify("ارسال خاموش است", "Enable Sending خاموش است")
            return
        }

        val pollUrl = buildPollUrl(prefs)

        val request = Request.Builder()
            .url(pollUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""

            updateNotify(
                "فعال",
                "آخرین اتصال: HTTP ${response.code}"
            )

            handleServerMessage(body)
        }
    }

    private fun connectWebSocket() {
        val server = prefs.server()

        if (server.isBlank()) {
            updateNotify("تنظیمات ناقص", "Server URL خالی است")
            return
        }

        val wsPath = if (prefs.ws().startsWith("/")) prefs.ws() else "/${prefs.ws()}"

        val wsBase = when {
            server.startsWith("https://") -> server.replaceFirst("https://", "wss://")
            server.startsWith("http://") -> server.replaceFirst("http://", "ws://")
            else -> "ws://$server"
        }

        val wsUrl = wsBase.trimEnd('/') +
            wsPath +
            "?pass=${encode(prefs.token())}" +
            "&user=${encode(prefs.user())}" +
            "&password=${encode(prefs.pass())}"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    updateNotify("WebSocket وصل", "اتصال زنده برقرار شد")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    updateNotify("WebSocket قطع شد", "Polling فعال است")

                    if (running) {
                        scope.launch {
                            delay(15000)
                            connectWebSocket()
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    updateNotify("WebSocket بسته شد", reason)

                    if (running) {
                        scope.launch {
                            delay(15000)
                            connectWebSocket()
                        }
                    }
                }
            }
        )
    }

    private fun handleServerMessage(body: String) {
        try {
            if (body.isBlank()) return

            val json = JsonParser.parseString(body).asJsonObject
            val status = json.get("status")?.asString ?: ""

            if (status != "ok") {
                return
            }

            val smsId = json.get("id")?.asString ?: ""
            val phone = json.get("phone")?.asString ?: ""
            val message = json.get("message")?.asString ?: ""

            if (smsId.isBlank()) {
                updateNotify("خطا", "SMS ID از سرور دریافت نشد")
                return
            }

            if (phone.isBlank()) {
                updateNotify("خطا", "شماره مقصد خالی است")
                ack(smsId, "failed", "phone is empty")
                return
            }

            if (message.isBlank()) {
                updateNotify("خطا", "متن پیام خالی است")
                ack(smsId, "failed", "message is empty")
                return
            }

            sendSms(smsId, phone, message)

        } catch (e: Exception) {
            Log.e("RastiSMS", "Handle message error: ${e.message}")
            updateNotify("خطا در پردازش پاسخ", e.message ?: "json error")
        }
    }

    private fun sendSms(smsId: String, phone: String, message: String) {
        if (!prefs.sending()) {
            updateNotify("ارسال خاموش است", "پیام ارسال نشد")
            ack(smsId, "failed", "sending disabled")
            return
        }

        try {
            val smsManager: SmsManager =
                if (Build.VERSION.SDK_INT >= 31) {
                    getSystemService(SmsManager::class.java)
                } else {
                    SmsManager.getDefault()
                }

            val parts = smsManager.divideMessage(message)

            smsManager.sendMultipartTextMessage(
                phone,
                null,
                parts,
                null,
                null
            )

            updateNotify("SMS ارسال شد", "ID=$smsId → $phone")

            // همین ID دریافتی از poll به Django گزارش می‌شود
            ack(smsId, "sent", "")

        } catch (e: Exception) {
            val errorMessage = e.message ?: "send failed"

            updateNotify("خطای ارسال SMS", errorMessage)

            // در صورت خطا همان ID به failed تغییر می‌کند
            ack(smsId, "failed", errorMessage)
        }
    }

    private fun ack(smsId: String, status: String, error: String) {
        if (smsId.isBlank()) {
            Log.e("RastiSMS_ACK", "ACK skipped: empty smsId")
            return
        }

        scope.launch {
            try {
                val server = prefs.server().trimEnd('/')

                val ackUrl =
                    server +
                        "/api/sms/gateway/ack/" +
                        "?pass=${encode(prefs.token())}" +
                        "&id=${encode(smsId)}" +
                        "&status=${encode(status)}" +
                        if (error.isNotBlank()) {
                            "&error=${encode(error)}"
                        } else {
                            ""
                        }

                val request = Request.Builder()
                    .url(ackUrl)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    Log.d(
                        "RastiSMS_ACK",
                        "ACK id=$smsId status=$status HTTP=${response.code} body=$responseBody"
                    )

                    if (response.isSuccessful) {
                        updateNotify("ACK ارسال شد", "ID=$smsId وضعیت=$status")
                    } else {
                        updateNotify("خطای ACK", "HTTP ${response.code}")
                    }
                }

            } catch (e: Exception) {
                Log.e("RastiSMS_ACK", "ACK failed: ${e.message}")
                updateNotify("ACK ناموفق", e.message ?: "ack failed")
            }
        }
    }

    fun reportIncoming(phone: String, message: String) {
        if (!prefs.receiving()) {
            updateNotify("دریافت خاموش است", "Enable Receiving خاموش است")
            return
        }

        scope.launch {
            try {
                val server = prefs.server().trimEnd('/')

                val inboxUrl =
                    server +
                        "/api/sms/gateway/inbox/" +
                        "?pass=${encode(prefs.token())}" +
                        "&phone=${encode(phone)}" +
                        "&message=${encode(message)}"

                val request = Request.Builder()
                    .url(inboxUrl)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    updateNotify(
                        "پیام دریافتی ثبت شد",
                        "HTTP ${response.code} - $phone"
                    )
                }

            } catch (e: Exception) {
                updateNotify("خطای ثبت پیام دریافتی", e.message ?: "inbox error")
            }
        }
    }

    private fun updateNotify(title: String, text: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFY_ID, notification(title, text))
        } catch (e: Exception) {
            Log.e("RastiSMS", "Notify error: ${e.message}")
        }
    }

    private fun notification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RastiSMS Gateway - $title")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RastiSMS Gateway",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (running) {
            try {
                val restartIntent = Intent(this, SmsService::class.java)
                    .setAction(ACTION_START)

                ContextCompat.startForegroundService(this, restartIntent)
            } catch (e: Exception) {
                Log.e("RastiSMS", "Restart onDestroy error: ${e.message}")
            }
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
