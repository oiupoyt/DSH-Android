package io.github.jackwener.dsh.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.platform.ActivityStarts
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.feature.quick.QuickActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Live state of the wake word, as the 免提语音 settings row shows it.
 *
 * Only [Listening] means audio is actually running: the service reports [Starting] while it loads
 * the model, waits for the microphone or the panel after a handoff, [BlockedMic] when RECORD_AUDIO
 * is missing, and [Unavailable] when the phrase or the model cannot work on this device. `Off`
 * means no listening service is alive (a stopped service, or settings that say it must not run).
 */
sealed interface WakeStatus {
    data object Off : WakeStatus
    data object Starting : WakeStatus
    data object Listening : WakeStatus

    /** Mic permission missing/denied. */
    data object BlockedMic : WakeStatus

    /** Model files missing/corrupt, or the sherpa runtime could not load on this ABI. */
    data class Unavailable(val reason: String) : WakeStatus
}

/** The result of the settings screen's bounded phrase test. [Idle] means "no test has run". */
sealed interface WakeTestResult {
    data object Idle : WakeTestResult
    data object Listening : WakeTestResult
    data object Detected : WakeTestResult
    data object NotDetected : WakeTestResult
    data class Failed(val reason: String) : WakeTestResult
}

/**
 * The always-listening wake word: a microphone foreground service that feeds 16 kHz frames to
 * sherpa-onnx's keyword spotter and hands a detection to the quick panel.
 *
 * The microphone is the scarce resource here, so the service owns it in exactly one place at a
 * time: [runEngine] builds the spotter, [listenForWakeWord] holds the recorder, and every path
 * out of it — a detection, a test, a stop — releases the recorder before anything else touches
 * the mic. A detection releases it *before* the panel is opened, because the panel records too.
 *
 * Status is push, not pull: [status], [phrase] and the test result are exposed as StateFlows so
 * the settings screen never has to guess whether audio is running.
 */
@AndroidEntryPoint
class WakeWordService : Service() {
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = WakeGate(SystemClock::elapsedRealtime)
    private val backoff = WakeBackoff()
    private val audioLock = Any()
    private var audio: AudioRecord? = null
    private var engine: Job? = null
    private var watcher: Job? = null
    private var destroyed = false

    /** A test was requested but the engine has not picked it up yet. */
    @Volatile private var testPending = false

    /** DSH itself is in front; from a handoff on, the panel's recorder owns the mic. */
    @Volatile private var appInFront = false

    /** When the last handoff opened the panel (monotonic clock); 0 before the first one. */
    @Volatile private var handoffAt = 0L

    private val appLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appInFront = true }
        override fun onStop(owner: LifecycleOwner) { appInFront = false }
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        createChannel()
        if (!startForegroundSafely()) {
            stopSelf()
            return
        }
        appInFront = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycle)
        watchSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                turnOffFromNotification()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                testCancel = false
                testState.value = WakeTestResult.Idle
                testPending = true
                // A running engine picks the request up between frames and reuses its own spotter;
                // only a stopped engine (off, or never started) has to be brought up for the test.
                if (engine?.isActive != true) restart()
                return START_NOT_STICKY
            }
        }
        restart()
        // Not sticky: Android 14+ refuses to restart a microphone foreground service from the
        // background, and a wake word that silently is not listening is worse than one that is off.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycle)
        watcher?.cancel()
        engine?.cancel()
        scope.cancel()
        closeAudio()
        phraseState.value = null
        // An Unavailable reason is a diagnosis the user has to act on, so it outlives the service.
        if (state.value !is WakeStatus.Unavailable) state.value = WakeStatus.Off
        super.onDestroy()
    }

    /**
     * Follows the stored settings, not just the call that started the service: a different phrase
     * or sensitivity rebuilds the spotter, and switching listening off releases the microphone
     * without waiting for anyone to call [setEnabled] again. The first emission is what [runEngine]
     * reads for itself.
     */
    private fun watchSettings() {
        watcher = scope.launch {
            settings.settings
                .map { stored -> VoiceSettings.from(stored.voice).let { VoiceKey(it.enabled, it.wakeEnabled, it.effectivePhrase, it.sensitivity) } }
                .distinctUntilChanged()
                .drop(1)
                .collect { restart() }
        }
    }

    /** The stored settings that change what the engine does; a change in any of them rebuilds it. */
    private data class VoiceKey(val enabled: Boolean, val listening: Boolean, val phrase: String, val sensitivity: Float)

    private fun restart() {
        engine?.cancel()
        engine = scope.launch { runEngine() }
    }

    /**
     * One engine run: read the settings, build the spotter, run a pending test, and then listen
     * until a detection, a settings change or a stop ends it. Cancelling this job always releases
     * the microphone and the spotter, whatever ended it.
     */
    private suspend fun runEngine() {
        val voice = readVoice() ?: return
        val listen = WakeGate.shouldRun(voice)
        val testing = testPending
        testPending = false
        if (!listen && !testing) {
            // Settings say the service must not run: nothing is held, and nothing pretends to listen.
            state.value = WakeStatus.Off
            stopSelf()
            return
        }
        if (!micGranted()) {
            setStatus(WakeStatus.BlockedMic)
            if (testing) publishTest(WakeTestResult.Failed(getString(R.string.wake_test_denied)))
            return
        }
        setStatus(WakeStatus.Starting)
        // NonCancellable: a cancelled run must still get the spotter back so it can be released.
        val started = withContext(NonCancellable + Dispatchers.IO) { WakeKeywordSpotter.create(this@WakeWordService, voice) }
        val spotter = when (started) {
            is SpotterStart.Refused -> {
                val reason = started.problem.describe()
                setStatus(WakeStatus.Unavailable(reason))
                if (testing) publishTest(WakeTestResult.Failed(reason))
                return
            }
            is SpotterStart.Ready -> started.spotter
        }
        phraseState.value = voice.effectivePhrase
        try {
            if (testing) runTest(spotter)
            if (listen && currentCoroutineContext().isActive) listenForWakeWord(spotter)
            if (!listen) stopSelf()
        } finally {
            closeAudio()
            spotter.close()
            phraseState.value = null
            if (testState.value == WakeTestResult.Listening) testState.value = WakeTestResult.Idle
        }
    }

    /**
     * Holds one microphone recorder, feeds the spotter, and releases the recorder on every way out.
     *
     * A recorder that cannot be opened or that stops mid-stream is retried with a climbing backoff
     * ([WakeBackoff]) instead of giving up: another app holding the mic is usually temporary. The
     * status is [WakeStatus.Listening] only while a recorder is actually running.
     */
    private suspend fun listenForWakeWord(spotter: WakeKeywordSpotter) = withContext(Dispatchers.IO) {
        val frames = ShortArray(FRAME_SAMPLES)
        val samples = FloatArray(FRAME_SAMPLES)
        while (currentCoroutineContext().isActive) {
            if (testPending) {
                // [testPhrase] asked for a bounded listen: release the always-on recorder (the inner
                // loop broke out) and run the test through this same spotter, then listen again.
                testPending = false
                runTest(spotter)
                continue
            }
            if (pausedAfterHandoff()) {
                closeAudio()
                setStatus(WakeStatus.Starting)
                delay(PAUSE_POLL_MS)
                continue
            }
            val recorder = openRecorder()
            if (recorder == null) {
                setStatus(WakeStatus.Starting)
                delay(backoff.next())
                continue
            }
            backoff.reset()
            setStatus(WakeStatus.Listening)
            try {
                while (currentCoroutineContext().isActive && !pausedAfterHandoff() && !testPending) {
                    val read = try {
                        recorder.read(frames, 0, frames.size, AudioRecord.READ_BLOCKING)
                    } catch (e: IllegalStateException) {
                        throw IOException("the recorder was closed underneath us", e)
                    }
                    if (read <= 0) throw IOException("AudioRecord.read returned $read")
                    for (i in 0 until read) samples[i] = frames[i] * SHORT_TO_FLOAT
                    val keyword = spotter.accept(if (read == frames.size) samples else samples.copyOf(read))
                    // A detection inside the debounce window is a second hit of the same utterance.
                    if (keyword != null && gate.acceptsDetection()) {
                        handoff()
                        return@withContext
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "microphone lost", e)
                if (currentCoroutineContext().isActive && !pausedAfterHandoff()) {
                    setStatus(WakeStatus.Starting)
                    delay(backoff.next())
                }
            } finally {
                closeAudio()
            }
        }
    }

    /**
     * A detection: release the recorder first (the panel opens its own and two recorders fight for
     * one microphone), stop the assistant talking over the user, then open the quick panel already
     * listening. Listening pauses while DSH is in front so the panel keeps the mic.
     */
    private suspend fun handoff() {
        closeAudio()
        handoffAt = SystemClock.elapsedRealtime()
        setStatus(WakeStatus.Starting)
        withContext(Dispatchers.Main) {
            TalkbackService.stopSpeaking(this@WakeWordService)
            ActivityStarts.start(this@WakeWordService, Intent(this@WakeWordService, QuickActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(QuickActivity.EXTRA_WAKE, true))
        }
    }

    /**
     * After a handoff the quick panel records, so the wake word yields the microphone while DSH is
     * in front. The deadline keeps a refused activity start — nothing comes to the front — from
     * leaving the wake word dead: after it, listening resumes either way.
     */
    private fun pausedAfterHandoff(): Boolean {
        val at = handoffAt
        if (at == 0L) return false
        if (SystemClock.elapsedRealtime() - at > HANDOFF_MAX_SUSPEND_MS) return false
        return appInFront
    }

    /**
     * The bounded listen behind [testPhrase]: a recorder of its own (the always-on one is closed by
     * the restart that brought the engine here), the same spotter, five seconds at most. The result
     * is always one of Detected / NotDetected / Failed — never a silent nothing.
     */
    private suspend fun runTest(spotter: WakeKeywordSpotter) = withContext(Dispatchers.IO) {
        testState.value = WakeTestResult.Listening
        setStatus(WakeStatus.Starting)
        val recorder = openRecorder()
        if (recorder == null) {
            publishTest(WakeTestResult.Failed(getString(R.string.wake_test_open_failed)))
            return@withContext
        }
        val frames = ShortArray(FRAME_SAMPLES)
        val samples = FloatArray(FRAME_SAMPLES)
        val deadline = SystemClock.elapsedRealtime() + TEST_WINDOW_MS
        try {
            while (isActive && !testCancel && SystemClock.elapsedRealtime() < deadline) {
                val read = try {
                    recorder.read(frames, 0, frames.size, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    -1
                }
                if (read <= 0) {
                    publishTest(WakeTestResult.Failed(getString(R.string.wake_test_interrupted)))
                    return@withContext
                }
                for (i in 0 until read) samples[i] = frames[i] * SHORT_TO_FLOAT
                if (spotter.accept(if (read == frames.size) samples else samples.copyOf(read)) != null) {
                    publishTest(WakeTestResult.Detected)
                    return@withContext
                }
            }
            publishTest(if (testCancel) WakeTestResult.Idle else WakeTestResult.NotDetected)
        } finally {
            closeAudio()
        }
    }

    private suspend fun readVoice(): VoiceSettings? = try {
        VoiceSettings.from(settings.current().voice)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "cannot read the voice settings", e)
        setStatus(WakeStatus.Unavailable(getString(R.string.wake_settings_unreadable, e.message ?: "")))
        null
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** A new recorder each time, so a recorder that failed is never reused. Null when unavailable. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? = synchronized(audioLock) {
        if (destroyed) return null
        val minBuffer = AudioRecord.getMinBufferSize(WAKE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val recorder = try {
            AudioRecord(
                // The source built for this: no AGC or noise processing fights the model.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WAKE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot open the microphone", e)
            null
        } ?: return null
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        try {
            recorder.startRecording()
        } catch (e: SecurityException) {
            recorder.release()
            return null
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            return null
        }
        audio = recorder
        recorder
    }

    /** Releases the microphone synchronously; safe to call when nothing is open. */
    private fun closeAudio() {
        synchronized(audioLock) {
            val recorder = audio ?: return
            audio = null
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private fun setStatus(value: WakeStatus) {
        if (destroyed) return
        state.value = value
        updateNotification()
    }

    private fun publishTest(result: WakeTestResult) {
        Log.i(TAG, "wake phrase test: $result")
        testState.value = result
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, getString(R.string.voice_wake_group), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.wake_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** True when the microphone foreground service is allowed; false already reported as Unavailable. */
    private fun startForegroundSafely(): Boolean = try {
        startForeground(ID_WAKE, buildNotification(notificationText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: SecurityException) {
        // Without FOREGROUND_SERVICE_MICROPHONE (or the manifest <service> entry) Android throws
        // here. Say so once instead of crash-looping or claiming to listen.
        Log.e(TAG, "not allowed to run the microphone foreground service", e)
        state.value = WakeStatus.Unavailable(e.message ?: getString(R.string.voice_status_unavailable))
        false
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(getString(R.string.wake_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(Notifications.openRunning(this))
            .addAction(0, getString(R.string.stop), stopIntent())
            .build()

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        STOP_REQUEST,
        Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationText(): String = when {
        testState.value == WakeTestResult.Listening -> getString(R.string.wake_notif_testing)
        pausedAfterHandoff() -> getString(R.string.wake_notif_paused)
        else -> when (val value = state.value) {
            // The phrase itself is user text and is spoken/listened for verbatim.
            WakeStatus.Listening -> getString(R.string.wake_notif_listening, phraseState.value ?: VoiceSettings.DEFAULT_PHRASE)
            WakeStatus.Off -> getString(R.string.wake_notif_off)
            WakeStatus.BlockedMic -> getString(R.string.wake_notif_needs_mic)
            WakeStatus.Starting -> getString(R.string.wake_notif_starting)
            is WakeStatus.Unavailable -> value.reason
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (destroyed) return
        runCatching { NotificationManagerCompat.from(this).notify(ID_WAKE, buildNotification(notificationText())) }
    }

    /** The notification's Stop action: listening is off in the settings too, so the switch follows. */
    private fun turnOffFromNotification() {
        scope.launch {
            runCatching {
                val voice = VoiceSettings.from(settings.current().voice)
                settings.setVoice(voice.copy(wakeEnabled = false).toStored())
            }.onFailure { Log.w(TAG, "cannot turn the wake word off", it) }
            state.value = WakeStatus.Off
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "WakeWordService"

        /** The listening notice's channel: low importance, silent, never an alert. */
        const val CHANNEL_WAKE = "wake"

        private const val ID_WAKE = 6
        private const val STOP_REQUEST = 7
        private const val ACTION_TEST = "io.github.jackwener.dsh.wake.TEST"
        private const val ACTION_STOP = "io.github.jackwener.dsh.wake.STOP"

        /** Contract with [QuickActivity]: the quick panel opens already listening to a wake detection. */
        const val EXTRA_WAKE = "wake"

        /** ~0.1 s of 16 kHz mono audio: small enough for low latency, large enough for the decoder. */
        private const val FRAME_SAMPLES = WAKE_SAMPLE_RATE / 10
        private const val TEST_WINDOW_MS = 5_000L
        private const val HANDOFF_MAX_SUSPEND_MS = 30_000L
        private const val PAUSE_POLL_MS = 250L
        private const val SHORT_TO_FLOAT = 1f / 32768f

        private val state = MutableStateFlow<WakeStatus>(WakeStatus.Off)

        /** Truthful live status; the settings row renders this and never invents "Active". */
        val status: StateFlow<WakeStatus> = state.asStateFlow()

        private val phraseState = MutableStateFlow<String?>(null)

        /** The phrase the running spotter was built for; null when nothing is loaded. */
        val phrase: StateFlow<String?> = phraseState.asStateFlow()

        private val testState = MutableStateFlow<WakeTestResult>(WakeTestResult.Idle)

        /** The phrase test's live result, the same flow [testPhrase] returns; the settings row collects this. */
        val test: StateFlow<WakeTestResult> = testState.asStateFlow()

        @Volatile private var testCancel = false

        /** Start (foreground) or stop the always-listening service. */
        fun setEnabled(context: Context, on: Boolean) {
            val intent = Intent(context, WakeWordService::class.java)
            if (!on) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 14+ refuses a microphone foreground service started from the background
                // (a boot restore, for example). That is not something the user can act on, and
                // DshApp's next launch restores the service anyway: stay silent. A refusal while
                // the app is actually in front is real, so it is reported.
                Log.w(TAG, "cannot start the wake word service", e)
                val inFront = runCatching {
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }.getOrDefault(false)
                state.value = if (inFront) {
                    WakeStatus.Unavailable(e.message ?: context.getString(R.string.voice_status_unavailable))
                } else {
                    WakeStatus.Off
                }
            }
        }

        /**
         * Starts a bounded 5-second listen that reports whether the phrase was heard. The result
         * arrives on the returned flow: [WakeTestResult.Listening] while capturing, then exactly one
         * of Detected / NotDetected / Failed (or Idle when cancelled via [cancelTest]).
         */
        fun testPhrase(context: Context): StateFlow<WakeTestResult> {
            testCancel = false
            testState.value = WakeTestResult.Idle
            return try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java).setAction(ACTION_TEST))
                testState
            } catch (e: Exception) {
                Log.w(TAG, "cannot start the phrase test", e)
                testState.value = WakeTestResult.Failed(e.message ?: context.getString(R.string.voice_status_unavailable))
                testState
            }
        }

        /** Abandons a test in progress; the engine goes back to listening and the result is [WakeTestResult.Idle]. */
        fun cancelTest(context: Context) {
            testCancel = true
            testState.value = WakeTestResult.Idle
        }
    }
}

/**
 * The wake service's decisions that need no Android, so [WakeGateTest] runs them on the JVM:
 * whether a detection is inside the debounce window, how long to wait after a failure, and whether
 * the service may run at all.
 */
internal class WakeGate(private val clock: () -> Long, private val debounceMs: Long = DEBOUNCE_MS) {
    private var lastAcceptedAt: Long? = null

    /** True when this detection may act; one inside [DEBOUNCE_MS] of the last accepted one is dropped. */
    fun acceptsDetection(): Boolean {
        val now = clock()
        val last = lastAcceptedAt
        if (last != null && now - last < debounceMs) return false
        lastAcceptedAt = now
        return true
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L

        /** The microphone is wanted only when the master switch and the listening switch are both on. */
        fun shouldRun(settings: VoiceSettings): Boolean = settings.shouldRunWakeService
    }
}

/** 1 s, 2 s, 5 s, 10 s, then 10 s: the delay before retrying a microphone that could not be opened. */
internal class WakeBackoff(private val steps: LongArray = DEFAULT_STEPS) {
    private var index = 0

    fun next(): Long {
        val wait = steps[minOf(index, steps.lastIndex)]
        if (index < steps.lastIndex) index++
        return wait
    }

    fun reset() { index = 0 }

    companion object { val DEFAULT_STEPS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L) }
}
