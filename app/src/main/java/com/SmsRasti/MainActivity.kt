package com.SmsRasti

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : Activity() {
    private lateinit var prefs: PrefsHelper
    private lateinit var txtStatus: TextView
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PrefsHelper(this)
        txtStatus = findViewById(R.id.txtStatus)
        requestPerms()

        val etServer = findViewById<EditText>(R.id.etServer)
        val etApi = findViewById<EditText>(R.id.etApi)
        val etWs = findViewById<EditText>(R.id.etWs)
        val etToken = findViewById<EditText>(R.id.etToken)
        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        val swSending = findViewById<CheckBox>(R.id.swSending)
        val swReceiving = findViewById<CheckBox>(R.id.swReceiving)
        val swAutoStart = findViewById<CheckBox>(R.id.swAutoStart)

        etServer.setText(prefs.server())
        etApi.setText(prefs.api())
        etWs.setText(prefs.ws())
        etToken.setText(prefs.token())
        etUser.setText(prefs.user())
        etPass.setText(prefs.pass())
        etInterval.setText(prefs.interval().toString())
        swSending.isChecked = prefs.sending()
        swReceiving.isChecked = prefs.receiving()
        swAutoStart.isChecked = prefs.autoStart()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val interval = etInterval.text.toString().toIntOrNull() ?: 7
            prefs.save(etServer.text.toString(), etApi.text.toString(), etWs.text.toString(), etToken.text.toString(), etUser.text.toString(), etPass.text.toString(), interval, swSending.isChecked, swReceiving.isChecked, swAutoStart.isChecked)
            status("✅ تنظیمات ذخیره شد")
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            saveFromUi(etServer, etApi, etWs, etToken, etUser, etPass, etInterval, swSending, swReceiving, swAutoStart)
            testConnection()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            saveFromUi(etServer, etApi, etWs, etToken, etUser, etPass, etInterval, swSending, swReceiving, swAutoStart)
            ContextCompat.startForegroundService(this, Intent(this, SmsService::class.java).setAction(SmsService.ACTION_START))
            status("✅ سرویس روشن شد")
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, SmsService::class.java).setAction(SmsService.ACTION_STOP))
            status("⛔ سرویس خاموش شد")
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { openAppSettings() }
    }

    private fun saveFromUi(a: EditText,b: EditText,c: EditText,d: EditText,e: EditText,f: EditText,g: EditText,h: CheckBox,i: CheckBox,j: CheckBox) {
        prefs.save(a.text.toString(), b.text.toString(), c.text.toString(), d.text.toString(), e.text.toString(), f.text.toString(), g.text.toString().toIntOrNull() ?: 7, h.isChecked, i.isChecked, j.isChecked)
    }

    private fun testConnection() {
        status("⏳ در حال تست اتصال...")
        uiScope.launch(Dispatchers.IO) {
            try {
                val url = SmsService.buildPollUrl(prefs)
                val res = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
                withContext(Dispatchers.Main) { status("✅ اتصال موفق: HTTP ${res.code}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status("❌ خطای اتصال: ${e.message}") }
            }
        }
    }

    private fun requestPerms() {
        val list = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 200)
    }

    private fun openBatterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
            } else openAppSettings()
        } catch (_: Exception) { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
    }

    private fun status(t: String) { txtStatus.text = t; Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }
}
