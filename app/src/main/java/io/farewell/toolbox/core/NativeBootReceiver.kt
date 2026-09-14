package io.farewell.toolbox.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Applies the native property set right after boot when the ROM daemon is not
 * installed (stock ROM mode).
 */
class NativeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NativeService.applyStock(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
