package com.music.bitchord.playback

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.music.bitchord.data.settings.AppSettings

/**
 * Decides whether speculative network work may run in the background.
 *
 * The app already lowers stream quality on a metered connection, but reading
 * ahead a whole track and auditioning a lossless replacement are both guesses
 * about what the listener will want next. On battery, with the screen off and
 * on mobile data, those guesses are the listener's data and charge spent with
 * nobody watching, so they wait until one of those conditions changes.
 */
internal object PlaybackNetworkPolicy {

    fun isCharging(context: Context): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java)
        if (battery != null) {
            runCatching { battery.isCharging }.getOrNull()?.let { return it }
        }
        // Some devices fail to answer the manager; the sticky battery
        // broadcast describes the same state.
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isScreenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    /**
     * False for the case this exists to catch: screen off, off the charger, and
     * a metered connection. No active network is false too — there is no heavy
     * work it could complete, and letting it start would only spend the retries.
     */
    fun allowHeavyBackgroundWork(context: Context): Boolean {
        val metered = AppSettings.meteredConnection.value ?: return false
        return isScreenOn(context) || isCharging(context) || !metered
    }
}
