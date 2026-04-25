package com.SmsRasti

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PrefsHelper(context)
            if (prefs.autoStart()) {
                ContextCompat.startForegroundService(context, Intent(context, SmsService::class.java).setAction(SmsService.ACTION_START))
            }
        }
    }
}
