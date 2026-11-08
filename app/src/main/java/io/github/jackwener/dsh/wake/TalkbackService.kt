package io.github.jackwener.dsh.wake

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One on-device voice offered by the speech engine. [name] is what [VoiceSettings.talkbackVoice] stores. */
data class VoiceOption(val name: String, val label: String, val locale: String)

/** What the 测试声音 button is doing, so the settings screen can show truth instead of a guess. */
sealed interface VoiceTestResult {
    data object Idle : VoiceTestResult
    data object Speaking : VoiceTestResult
    data object Done : VoiceTestResult

    /** No installed voice for the chosen language: speak nothing rather than gibberish. */
    data class NoVoice(val locale: String) : VoiceTestResult

    /** The engine could not speak at all; [reason] is shown to the user. */
    data class Failed(val reason: String) : VoiceTestResult
}

/**
 * True while a screen reader belonging to another app is reading the screen.
 *
 * Only services that ask for [AccessibilityServiceInfo.FEEDBACK_SPOKEN] count, and DSH's own
 * accessibility service never does: it is a generic feedback service the app asks users to enable
 * for screen operations, and counting it would silently turn spoken replies off for them. Touch
 * exploration alone is not enough either — a non-spoken service can use it.
 */
internal fun Context.isScreenReaderActive(): Boolean = runCatching {
    val manager = getSystemService(AccessibilityManager::class.java) ?: return@runCatching false
    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
        .any { it != packageName }
}.getOrDefault(false)

/**
 * Speaks the assistant's replies with the on-device TextToSpeech engine (免提语音 · 朗读回复).
 *
 * The reply itself is streamed, so speech starts before the model has finished: the live
 * `session.event`s are fed to [SpeakerState], which strips markdown, skips code/JSON, and hands
 * back whole sentences; this service only owns the engine, the audio focus and the queue.
 *
 * On-device only: the normal `TextToSpeech(context, listener)` constructor is used, i.e. whatever
 * engine the user installed. There is no cloud TTS and no audio is ever written to disk.
 *
 * Lifecycle: [setEnabled] starts the service (foreground, as the manifest declares) while the
 * feature is on; [stopSpeaking] stops speech immediately and works without a live instance, so
 * the wake-word service can use it for barge-in. The engine is created on the first spoken
 * turn and released after [IDLE_RELEASE_MS] without speech or when the feature is turned off.
 */
@AndroidEntryPoint
class TalkbackService : Service() {

    @Inject lateinit var settings: SettingsRepository

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speaker = SpeakerState(VoiceSettings(), false)
    private var voice = VoiceSettings()

    private var engine: TextToSpeech? = null
    private var engineReady = false
    private var engineBroken = false
    private var utteranceSeq = 0
    private var lastUtterance: String? = null
    private var languageBlocked: String? = null
    private val pending = ArrayList<String>()
    private var focus: AudioFocusRequest? = null
    private val idleRelease = Runnable { releaseEngine() }

    override fun onCreate() {
        super.onCreate()
        live = this
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            settings.settings.collect { stored ->
                val next = VoiceSettings.from(stored.voice)
                voice = next
                speaker.update(next, isScreenReaderActive())
                if (!next.shouldSpeak) {
                    // Turning the feature off releases the engine and the queue immediately.
                    firmStop()
                } else {
                    applyVoiceToEngine(next)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (live === this) live = null
        main.removeCallbacks(idleRelease)
        firmStop()
        releaseEngine()
        scope.cancel()
        super.onDestroy()
    }

    // ---- reply pipeline ----------------------------------------------------------------------

    /** A streamed piece of the assistant's markdown reply (from a live `assistant/message`). */
    private fun handleEvent(type: String, data: JsonObject) {
        when (type) {
            "assistant/message" -> {
                speaker.update(voice, isScreenReaderActive())
                val message = data["message"] as? JsonObject
                val text = streamText(data["stream"]).ifBlank { contentText(message?.get("content")) }
                if (text.isNotBlank()) deliver(speaker.feed(text))
            }
            // The turn is over: say the unfinished tail once, then forget the reply.
            "turn/end" -> deliver(speaker.complete())
        }
    }

    private fun deliver(action: SpeechAction) {
        when (action) {
            is SpeechAction.Enqueue -> enqueue(action.chunks)
            is SpeechAction.Skip, SpeechAction.Buffer -> Unit
        }
    }

    private fun enqueue(chunks: List<String>) {
        if (chunks.isEmpty()) return
        main.removeCallbacks(idleRelease)
        if (languageBlocked != null) return
        val tts = engine
        if (tts == null || !engineReady) {
            pending += chunks
            ensureEngine()
            return
        }
        acquireFocus()
        for (chunk in chunks) {
            val id = "talkback-${++utteranceSeq}"
            lastUtterance = id
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) {
                SPEAKING.value = true
            }
        }
    }

    // ---- engine ------------------------------------------------------------------------------

    private fun ensureEngine() {
        if (engine != null || engineBroken) return
        val listener = TextToSpeech.OnInitListener { status -> main.post { onEngineInit(status) } }
        val created = try {
            TextToSpeech(applicationContext, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "no on-device TextToSpeech engine", t)
            engineBroken = true
            pending.clear()
            TEST.value = VoiceTestResult.Failed(getString(R.string.voice_tts_unavailable))
            null
        } ?: return
        created.setOnUtteranceProgressListener(progress)
        engine = created
    }

    private fun onEngineInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            engineBroken = true
            pending.clear()
            releaseEngine()
            TEST.value = VoiceTestResult.Failed(getString(R.string.voice_tts_failed))
            return
        }
        engineReady = true
        cachedVoices = voiceOptions(tts)
        applyVoiceToEngine(voice)
        val queued = pending.toList()
        pending.clear()
        if (queued.isNotEmpty()) enqueue(queued)
    }

    /**
     * Applies rate, pitch and the chosen voice, and refuses a language the engine cannot speak.
     * Refusing is the point: an unsupported locale read anyway is gibberish, so nothing is
     * queued and the settings screen is told which language is missing.
     */
    private fun applyVoiceToEngine(next: VoiceSettings) {
        val tts = engine ?: return
        val clamped = next.clamped()
        runCatching {
            tts.setSpeechRate(clamped.talkbackRate)
            tts.setPitch(clamped.talkbackPitch)
        }
        val wanted = pickVoice(tts, clamped.talkbackVoice)
        if (wanted != null) runCatching { tts.setVoice(wanted) }
        val locale = wanted?.locale ?: Locale.getDefault()
        languageBlocked = if (runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) < TextToSpeech.LANG_AVAILABLE) {
            locale.toLanguageTag()
        } else {
            null
        }
        if (languageBlocked != null) {
            pending.clear()
            runCatching { tts.stop() }
            SPEAKING.value = false
            abandonFocus()
            TEST.value = VoiceTestResult.NoVoice(locale.toLanguageTag())
        }
    }

    private fun pickVoice(tts: TextToSpeech, name: String) =
        if (name.isBlank()) null else runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()

    private fun releaseEngine() {
        main.removeCallbacks(idleRelease)
        runCatching { engine?.shutdown() }
        engine = null
        engineReady = false
        engineBroken = false
        languageBlocked = null
    }

    private fun scheduleIdleRelease() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    // ---- speech lifecycle --------------------------------------------------------------------

    private fun stopSpeechNow() {
        speaker.reset()
        pending.clear()
        lastUtterance = null
        runCatching { engine?.stop() }
        SPEAKING.value = false
        abandonFocus()
        if (engine != null) scheduleIdleRelease()
    }

    /** Barge-in and toggle-off: speech and queued text are dropped now, the engine goes later. */
    private fun firmStop() {
        stopSpeechNow()
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, 0)
    }

    private fun finishRun() {
        SPEAKING.value = false
        abandonFocus()
        if (pending.isEmpty()) scheduleIdleRelease()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post { if (utteranceId == lastUtterance) finishRun() }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onError(utteranceId: String?) {
            main.post { if (utteranceId == lastUtterance) finishRun() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { if (utteranceId == lastUtterance) finishRun() }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            main.post { if (utteranceId == lastUtterance) finishRun() }
        }
    }

    // ---- audio focus -------------------------------------------------------------------------

    private fun acquireFocus() {
        if (focus != null) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .setWillPauseWhenDucked(false)
            .build()
        runCatching { manager.requestAudioFocus(request) }
        focus = request
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    // ---- notification ------------------------------------------------------------------------

    private fun notification(): Notification =
        NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(getString(R.string.voice_speak_replies))
            .setContentText(getString(R.string.voice_speak_replies_desc))
            .setContentIntent(Notifications.openRunning(this))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val TAG = "TalkbackService"

        /** Distinct from the other ongoing notifications (engine 1, monitor 2, ball 3). */
        private const val NOTIFICATION_ID = 6

        /** Release the engine after this long without speech; the next reply creates it again. */
        private const val IDLE_RELEASE_MS = 60_000L

        /** The sample 测试声音 speaks; the selected locale names itself in its own words. */
        private const val SAMPLE_RES = R.string.voice_test_sample

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Application context kept for strings/engine creation inside the companion object. */
        @Volatile private var appContext: android.content.Context? = null

        /** The running service, for barge-in; null when it was never started or is gone. */
        @Volatile private var live: TalkbackService? = null

        /** Which session's replies may be spoken; null = none (background plugin sessions stay silent). */
        @Volatile private var activeSession: String? = null

        private val SPEAKING = MutableStateFlow(false)

        /** True while audio is actually being spoken. */
        val isSpeaking: StateFlow<Boolean> = SPEAKING.asStateFlow()

        private val TEST = MutableStateFlow<VoiceTestResult>(VoiceTestResult.Idle)

        /** The 测试声音 result, observed by the settings screen. */
        val testResult: StateFlow<VoiceTestResult> = TEST.asStateFlow()

        @Volatile private var cachedVoices: List<VoiceOption>? = null

        @Volatile private var warming = false
        private val warmWaiters = ArrayList<() -> Unit>()

        @Volatile private var testEngine: TextToSpeech? = null
        private var testReady = false
        private var testUtterance: String? = null

        /** Starts or stops spoken replies. Disabling stops speech and releases the engine at once. */
        fun setEnabled(context: Context, on: Boolean) {
            val intent = Intent(context, TalkbackService::class.java)
            if (on) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { runCatching { context.startService(intent) } }
                return
            }
            stopSpeaking(context)
            runCatching { context.stopService(intent) }
        }

        /**
         * Barge-in: stop speaking now. Called on a new wake detection, a new user turn, a screen
         * tap or the toggle going off. Safe from any thread and without a running service.
         */
        fun stopSpeaking(context: Context) {
            mainHandler.post {
                if (TEST.value == VoiceTestResult.Speaking) {
                    TEST.value = VoiceTestResult.Idle
                    releaseTestEngine()
                }
                SPEAKING.value = false
            }
            live?.let { service -> service.main.post { service.stopSpeechNow() } }
        }

        /** Which conversation may be heard; anything else (a plugin's background session) is ignored. */
        fun setActiveSession(sessionId: String?) {
            val previous = activeSession
            activeSession = sessionId
            if (previous != sessionId) live?.let { service -> service.main.post { service.stopSpeechNow() } }
        }

        /**
         * One live `session.event` whose `data` still carries the model `stream`
         * ([io.github.jackwener.dsh.core.session.SessionStore] slims it before persisting).
         * Only assistant prose is spoken; reasoning and tool-call chunks are never read out.
         */
        fun onEngineEvent(sessionId: String, type: String, data: JsonObject) {
            if (sessionId != activeSession) return
            if (type == "user/message") {
                onUserMessage(sessionId)
                return
            }
            if (type != "assistant/message" && type != "turn/end") return
            val service = live ?: return
            // Re-check on the main thread: the equality above runs on SessionStore's IO thread,
            // so a conversation switch can post its stop between that check and this lambda. Doing
            // the check again where the work actually runs makes "check" and "switch" mutually
            // ordered on one thread — otherwise an old conversation's text could be read out (or
            // merged into the new conversation's first sentence) after the user moved on.
            service.main.post { if (sessionId == activeSession) service.handleEvent(type, data) }
        }

        /** A new user turn: the reply being read is no longer what the user is waiting for. */
        fun onUserMessage(sessionId: String) {
            if (sessionId != activeSession) return
            live?.let { service -> service.main.post { service.stopSpeechNow() } }
        }

        /** Feeds one assistant text delta directly (for a caller that already owns the stream). */
        fun onAssistantText(sessionId: String, delta: String) {
            if (sessionId != activeSession) return
            val service = live ?: return
            service.main.post { if (sessionId == activeSession) service.deliver(service.speaker.feed(delta)) }
        }

        /**
         * The on-device voices, for the picker. The engine enumerates them asynchronously, so the
         * first call may find nothing:
         *  - off the main thread it waits up to 3 s for the warm-up and returns the real list;
         *  - on the main thread it starts the warm-up and returns what is cached (never blocks:
         *    TTS reports init on the main thread, so waiting there would deadlock).
         */
        fun voices(context: Context): List<VoiceOption> {
            cachedVoices?.let { return it }
            val app = context.applicationContext
            if (Looper.myLooper() == Looper.getMainLooper()) {
                warmVoices(app)
                return emptyList()
            }
            val latch = CountDownLatch(1)
            warmVoices(app) { latch.countDown() }
            runCatching { latch.await(WAIT_FOR_VOICES_MS, TimeUnit.MILLISECONDS) }
            return cachedVoices ?: emptyList()
        }

        /** Starts the one-off engine warm-up that fills [voices]; [onReady] runs when it settles. */
        @JvmOverloads
        fun warmVoices(context: Context, onReady: (() -> Unit)? = null) {
            if (cachedVoices != null) {
                if (onReady != null) mainHandler.post { onReady() }
                return
            }
            val app = context.applicationContext
            mainHandler.post {
                if (cachedVoices != null) {
                    onReady?.invoke()
                    return@post
                }
                if (warming) {
                    if (onReady != null) warmWaiters += onReady
                    return@post
                }
                warming = true
                if (onReady != null) warmWaiters += onReady
                var created: TextToSpeech? = null
                created = try {
                    TextToSpeech(app) { status -> mainHandler.post { finishWarm(created, status) } }
                } catch (t: Throwable) {
                    Log.w(TAG, "no on-device TextToSpeech engine", t)
                    null
                }
                if (created == null) finishWarm(null, TextToSpeech.ERROR)
            }
        }

        /**
         * Speaks one sample sentence so the user hears the chosen voice before trusting it, and
         * reports the outcome in [testResult]. Never a silent no-op: no engine, no voice for the
         * language and a failed utterance all come back as a failure.
         */
        fun testVoice(context: Context, settings: VoiceSettings): StateFlow<VoiceTestResult> {
            val app = context.applicationContext
            appContext = app
            val clamped = settings.clamped()
            mainHandler.post {
                TEST.value = VoiceTestResult.Speaking
                speakSample(app, clamped)
            }
            return testResult
        }

        // ---- warm-up and test engine (companion-owned: they must work without the service) ----

        private fun finishWarm(tts: TextToSpeech?, status: Int) {
            warming = false
            if (tts != null && status == TextToSpeech.SUCCESS) cachedVoices = voiceOptions(tts)
            runCatching { tts?.shutdown() }
            val waiters = warmWaiters.toList()
            warmWaiters.clear()
            waiters.forEach { runCatching { it() } }
        }

        private fun speakSample(context: Context, settings: VoiceSettings) {
            val existing = testEngine
            if (existing == null) {
                var created: TextToSpeech? = null
                created = try {
                    TextToSpeech(context) { status -> mainHandler.post { onTestInit(created, status, settings) } }
                } catch (t: Throwable) {
                    Log.w(TAG, "no on-device TextToSpeech engine", t)
                    null
                }
                if (created == null) {
                    failTest(context.getString(R.string.voice_tts_unavailable))
                    return
                }
                created.setOnUtteranceProgressListener(testProgress)
                testEngine = created
                return
            }
            if (!testReady) return // init still running; its callback speaks
            saySample(existing, settings)
        }

        private fun onTestInit(tts: TextToSpeech?, status: Int, settings: VoiceSettings) {
            if (tts == null || tts !== testEngine) return
            if (status != TextToSpeech.SUCCESS) {
                failTest(appContext?.getString(R.string.voice_tts_failed) ?: "TextToSpeech failed")
                return
            }
            testReady = true
            cachedVoices = voiceOptions(tts)
            saySample(tts, settings)
        }

        private fun saySample(tts: TextToSpeech, settings: VoiceSettings) {
            runCatching {
                tts.setSpeechRate(settings.talkbackRate)
                tts.setPitch(settings.talkbackPitch)
            }
            val wanted = if (settings.talkbackVoice.isBlank()) null else runCatching { tts.voices?.firstOrNull { it.name == settings.talkbackVoice } }.getOrNull()
            if (wanted != null) runCatching { tts.setVoice(wanted) }
            val locale = wanted?.locale ?: Locale.getDefault()
            val available = runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (available < TextToSpeech.LANG_AVAILABLE) {
                TEST.value = VoiceTestResult.NoVoice(locale.toLanguageTag())
                SPEAKING.value = false
                releaseTestEngine()
                return
            }
            val id = "voice-test-${System.nanoTime()}"
            testUtterance = id
            val sample = contextSample(locale)
            if (tts.speak(sample, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                failTest(appContext?.getString(R.string.voice_tts_unavailable) ?: "TextToSpeech unavailable")
                return
            }
            SPEAKING.value = true
        }

        /** A sample in the voice's own language: the locale's name is always understood by it. */
        private fun contextSample(locale: Locale): String =
            appContext?.getString(SAMPLE_RES) ?: locale.getDisplayName(locale)

        @Suppress("DEPRECATION")
        private val testProgress = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId != testUtterance) return@post
                    TEST.value = VoiceTestResult.Done
                    SPEAKING.value = false
                    releaseTestEngine()
                }
            }

            override fun onError(utteranceId: String?) = testError(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = testError(utteranceId)
        }

        private fun testError(utteranceId: String?) {
            mainHandler.post {
                if (utteranceId != testUtterance) return@post
                failTest(appContext?.getString(R.string.voice_tts_failed) ?: "TextToSpeech failed")
            }
        }

        private fun failTest(reason: String) {
            mainHandler.post {
                TEST.value = VoiceTestResult.Failed(reason)
                SPEAKING.value = false
                releaseTestEngine()
            }
        }

        private fun releaseTestEngine() {
            runCatching { testEngine?.shutdown() }
            testEngine = null
            testReady = false
            testUtterance = null
        }

        /** On-device voices only (a network voice would need a cloud service); one per locale. */
        private fun voiceOptions(tts: TextToSpeech): List<VoiceOption> =
            runCatching { tts.voices }.getOrNull()
                ?.filterNot { it.isNetworkConnectionRequired }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.locale.toLanguageTag() })
                ?.distinctBy { it.locale }
                ?.map { v ->
                    val locale = v.locale
                    VoiceOption(name = v.name, label = locale.getDisplayName(locale), locale = locale.toLanguageTag())
                }
                ?: emptyList()

        private const val WAIT_FOR_VOICES_MS = 3_000L
    }

    // ---- JSON of a live session event --------------------------------------------------------

    /** The streamed prose of an `assistant/message`: `text-chunks.texts` (and a lone text `chunk`). */
    private fun streamText(stream: JsonElement?): String {
        val records = stream as? JsonArray ?: return ""
        val out = StringBuilder()
        for (record in records) {
            val r = record as? JsonObject ?: continue
            when (r.str("type")) {
                "text-chunks" -> (r["texts"] as? JsonArray)?.forEach { piece ->
                    out.append((piece as? JsonPrimitive)?.contentOrNull ?: "")
                }
                "chunk" -> {
                    val chunk = r["chunk"] as? JsonObject ?: continue
                    if (chunk.str("type") == "text" || chunk.str("type") == "text-delta") out.append(chunk.str("text") ?: "")
                }
                // reasoning-chunks and tool-call-chunks are never spoken.
            }
        }
        return out.toString()
    }

    /** The final text of a message's content blocks, used when no stream came with the event. */
    private fun contentText(content: JsonElement?): String {
        val array = content as? JsonArray
            ?: return (content as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
        return array.mapNotNull { block ->
            val o = block as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "text") o.str("text") else null
        }.joinToString("\n").trim()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
