package com.mcpcontroller.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mcpcontroller.server.MCPServerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MCPServerService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
