package com.SmsRasti

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat

class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PrefsHelper(context)
        if (!prefs.autoStart()) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(net) ?: return
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            ContextCompat.startForegroundService(context, Intent(context, SmsService::class.java).setAction(SmsService.ACTION_START))
        }
    }
}
