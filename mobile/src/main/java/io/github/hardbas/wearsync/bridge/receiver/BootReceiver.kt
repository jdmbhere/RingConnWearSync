package io.github.hardbas.wearsync.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hardbas.wearsync.bridge.service.RingConnForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            RingConnForegroundService.start(context)
        }
    }
}
