package io.github.jackwener.dsh.core.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.core.engine.EngineKeepAlive
import io.github.jackwener.dsh.core.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Time alarms and proximity alerts (internal, not exported). */
@AndroidEntryPoint
class TriggerReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub

    override fun onReceive(context: Context, intent: Intent) {
        val jobs = runCatching {
            when (intent.action) {
                ACTION_TIME -> listOf(hub.onEvent(DeviceEvent.Time(intent.getLongExtra("ruleId", -1))))
                ACTION_GEOFENCE -> listOf(hub.onEvent(DeviceEvent.Geofence(intent.getLongExtra("ruleId", -1),
                    intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true))))
                ACTION_GEOFENCE_GMS -> {
                    val ev = GeofencingEvent.fromIntent(intent)
                    if (ev == null || ev.hasError()) emptyList() else {
                        val entering = ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
                        ev.triggeringGeofences.orEmpty().mapNotNull { g -> g.requestId.toLongOrNull()?.let { hub.onEvent(DeviceEvent.Geofence(it, entering)) } }
                    }
                }
                else -> emptyList()
            }
        }.getOrElse { android.util.Log.e("TriggerReceiver", "dispatch failed", it); emptyList() }
        if (jobs.isEmpty()) return
        // Keep the (possibly alarm-cold-started) process alive until the next alarm is scheduled and
        // the runs are launched; aggressive ROMs kill a bare cached process right after onReceive.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { withTimeoutOrNull(9_000) { jobs.joinAll() } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TIME = "io.github.jackwener.dsh.TRIGGER_TIME"
        const val ACTION_GEOFENCE = "io.github.jackwener.dsh.TRIGGER_GEOFENCE"
        const val ACTION_GEOFENCE_GMS = "io.github.jackwener.dsh.TRIGGER_GEOFENCE_GMS"
    }
}

/**
 * Re-registers all automations after boot or app update. Also wakes `:main` when Android re-created the
 * engine's 引擎常驻 service ([EngineKeepAlive.ACTION_RESTORE]); after boot / update DshApp's start restores it.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub
    @Inject lateinit var engine: dagger.Lazy<EngineManager>
    @Inject lateinit var settings: dagger.Lazy<io.github.jackwener.dsh.core.data.SettingsRepository>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == EngineKeepAlive.ACTION_RESTORE) { engine.get().keepAlive(restored = true); return }
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { hub.sync() }.onFailure { android.util.Log.e("BootReceiver", "sync failed", it) }
            // 免提语音: restore the wake-word service only if the user left it on. Off stays off
            // (nothing here can start listening that the user did not ask for).
            try {
                val voice = io.github.jackwener.dsh.wake.VoiceSettings.from(settings.get().current().voice)
                io.github.jackwener.dsh.wake.WakeWordService.setEnabled(context, voice.shouldRunWakeService)
            } catch (e: Exception) { android.util.Log.e("BootReceiver", "wake restore failed", e) }
            finally { pending.finish() }
        }
    }

    private companion object {
        // Exact-alarm access granted: re-sync so time rules become exact (a revoke kills the app; its next start re-syncs).
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
    }
}
