package io.github.jackwener.dsh.feature.voice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jackwener.dsh.feature.conversation.ComposerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speech → composer text: the system recognizer streams partial results into the draft, and the
 * final text is sent after a short countdown ([VoiceMachine]) unless the user edits or cancels.
 *
 * Recognizer choice: the on-device one (API 31+) when the system has it, else a recognition
 * service other than DSH's own stub (when DSH is the default assistant, the system default
 * recognizer *is* that stub), else the RECOGNIZE_SPEECH activity of some app, else the keyboard.
 */
@Stable
class VoiceInput internal constructor(private val context: Context, private val composer: ComposerState, private val scope: CoroutineScope) {
    private val machine = VoiceMachine()
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set
    val active get() = state is VoiceState.Listening || state is VoiceState.Countdown

    internal var onSend: () -> Unit = {}
    internal var requestPermission: () -> Unit = {}
    internal var launchActivity: (Intent) -> Boolean = { false }

    private var recognizer: SpeechRecognizer? = null
    /** The bundled-model capture, when 离线听写 drove this turn. */
    private var offline: io.github.jackwener.dsh.wake.OfflineDictation? = null
    private var onDevice = false
    private var draft = ""
    private var timer: Job? = null

    fun toggle() = if (state is VoiceState.Listening) stop() else start()

    fun start() {
        cancel()
        draft = composer.text
        // 离线听写: the bundled model instead of the system recogniser, so this works in airplane
        // mode. It drives the same VoiceMachine states, so the composer UI is unchanged.
        if (offlineDictation()) { startOffline(); return }
        val service = serviceRecognizer()
        val canOnDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        if (!canOnDevice && service == null) {
            // No in-app recognizer: a recognizer activity needs no microphone permission of ours.
            if (launchActivity(recognizeIntent())) dispatch(VoiceEvent.Start) else dispatch(VoiceEvent.Start).also { dispatch(VoiceEvent.Error(VoiceError.NoRecognizer)) }
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermission(); return }
        dispatch(VoiceEvent.Start)
        listen(onDevice = canOnDevice, service)
    }

    /** Stops taking audio: the recognizer then delivers the final result (nothing heard yet: just cancels). */
    fun stop() {
        offline?.let { dictation -> if (dictation.isCapturing) { dictation.stop(); return } }
        val s = state as? VoiceState.Listening ?: return
        val r = recognizer
        if (r != null && s.text.isNotEmpty()) r.stopListening() else cancel()
    }

    /** Keeps whatever is in the composer and sends nothing. */
    fun cancel() {
        offline?.let { if (it.isCapturing) it.cancel() }
        if (state != VoiceState.Idle) dispatch(VoiceEvent.Cancel)
    }

    /** The user typed in the composer while we were writing to it. */
    fun onUserEdit() = cancel()

    internal fun onPermission(granted: Boolean) {
        if (granted) start() else { dispatch(VoiceEvent.Start); dispatch(VoiceEvent.Error(VoiceError.Permission)) }
    }

    internal fun onActivityResult(resultCode: Int, data: Intent?) {
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        // The recognizer activity covering the panel stopped it (and so cancelled): listen again to take the result.
        if (resultCode == Activity.RESULT_OK && text.isNotBlank()) { dispatch(VoiceEvent.Start); dispatch(VoiceEvent.Final(text)) } else cancel()
    }

    internal fun release() {
        cancel()
        offline?.release()
        offline = null
        destroyRecognizer()
    }

    private fun listen(onDevice: Boolean, service: ComponentName?) {
        destroyRecognizer()
        this.onDevice = onDevice
        val r = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context, service)
        }.getOrNull() ?: return dispatch(VoiceEvent.Error(VoiceError.NoRecognizer))
        recognizer = r
        r.setRecognitionListener(Listener(r, service))
        runCatching { r.startListening(recognizeIntent()) }.onFailure { dispatch(VoiceEvent.Error(VoiceError.Other)) }
    }

    private inner class Listener(private val r: SpeechRecognizer, private val service: ComponentName?) : RecognitionListener {
        private val current get() = recognizer === r
        override fun onReadyForSpeech(params: Bundle?) { if (current) dispatch(VoiceEvent.Ready) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (current) dispatch(VoiceEvent.Level(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { if (current) dispatch(VoiceEvent.Partial(best(partialResults))) }
        override fun onResults(results: Bundle?) { if (current) dispatch(VoiceEvent.Final(best(results))) }
        override fun onError(error: Int) {
            if (!current) return
            val heard = (state as? VoiceState.Listening)?.text.orEmpty()
            // The on-device model may lack the language (or fail outright): retry once with the service recognizer.
            if (onDevice && heard.isEmpty() && service != null && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return listen(onDevice = false, service)
            dispatch(VoiceEvent.Error(VoiceMachine.errorOf(error)))
        }
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /**
     * Whether the bundled model should transcribe. Kept current by the caller from live settings,
     * so toggling 离线听写 takes effect on the next tap rather than after a restart.
     */
    internal var offlineDictation: () -> Boolean = { false }

    /** Capture through [OfflineDictation]; partials and the final transcript feed the same machine. */
    private fun startOffline() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            return
        }
        dispatch(VoiceEvent.Start)
        dispatch(VoiceEvent.Ready)
        offline?.release()
        val dictation = io.github.jackwener.dsh.wake.OfflineDictation(context)
        offline = dictation
        dictation.start(
            onPartial = { text -> if (text.isNotEmpty()) dispatch(VoiceEvent.Partial(text)) },
            onFinal = { text -> if (text.isNotBlank()) dispatch(VoiceEvent.Final(text)) else cancel() },
            onError = { message ->
                // Typed failure is shown, never a silent no-op; the composer keeps whatever was said.
                dispatch(VoiceEvent.Error(VoiceError.Other))
                android.util.Log.w("VoiceInput", "offline dictation: $message")
            },
        )
    }

    private fun dispatch(e: VoiceEvent) {
        val effect = machine.on(e)
        state = machine.state
        when (val s = state) {
            is VoiceState.Listening -> if (s.text.isNotEmpty()) composer.text = VoiceMachine.join(draft, s.text)
            is VoiceState.Countdown -> if (e !is VoiceEvent.Tick) { composer.text = VoiceMachine.join(draft, s.text); runTimer() }
            is VoiceState.Failed -> if (e !is VoiceEvent.Tick) runTimer()
            VoiceState.Idle -> {}
        }
        when (effect) {
            is VoiceEffect.Send -> onSend()
            VoiceEffect.Release -> destroyRecognizer()
            null -> {}
        }
        if (!active && state !is VoiceState.Failed) { timer?.cancel(); timer = null }
    }

    /** Countdown ticks, or clearing an error after a few seconds. */
    private fun runTimer() {
        timer?.cancel()
        timer = scope.launch {
            if (state is VoiceState.Failed) { delay(ERROR_SHOWN_MS); if (state is VoiceState.Failed) { machine.on(VoiceEvent.Cancel); state = machine.state }; return@launch }
            var last = SystemClock.uptimeMillis()
            while (state is VoiceState.Countdown) {
                delay(TICK_MS)
                val now = SystemClock.uptimeMillis()
                dispatch(VoiceEvent.Tick(now - last))
                last = now
            }
        }
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel(); r.destroy() }
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    /** The system default recognition service unless it is our own stub, else a known good one, else any. */
    private fun serviceRecognizer(): ComponentName? {
        val pm = context.packageManager
        val all = runCatching { pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0) }.getOrDefault(emptyList())
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }.filter { it.packageName != context.packageName }
        if (all.isEmpty()) return null
        val default = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.let(ComponentName::unflattenFromString)
        return all.firstOrNull { it == default } ?: PREFERRED.firstNotNullOfOrNull { p -> all.firstOrNull { it.packageName == p } } ?: all.first()
    }

    companion object {
        private const val TICK_MS = 50L
        private const val ERROR_SHOWN_MS = 4000L
        private val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
    }
}

/** A [VoiceInput] writing into [composer]; [onSend] sends the composer as the Send button would. */
@Composable
fun rememberVoiceInput(composer: ComposerState, onSend: () -> Unit, offlineDictation: Boolean = false): VoiceInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember(composer) { VoiceInput(context, composer, scope) }
    val send by rememberUpdatedState(onSend)
    val offline by rememberUpdatedState(offlineDictation)
    voice.offlineDictation = { offline }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voice.onPermission(it) }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { voice.onActivityResult(it.resultCode, it.data) }
    voice.onSend = { send() }
    voice.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    voice.launchActivity = { i -> i.resolveActivity(context.packageManager) != null && runCatching { activity.launch(i) }.isSuccess }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        // Leaving the screen stops listening and any pending auto-send; the text stays in the draft.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) voice.cancel() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); voice.release() }
    }
    return voice
}
