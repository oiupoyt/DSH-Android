package io.github.jackwener.dsh.wake

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import io.github.jackwener.dsh.R
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/** PCM16 (little-endian) to the [-1, 1] floats sherpa expects. Pure, so the JVM tests cover it. */
internal object Pcm16 {
    /** Two bytes per sample, low byte first; an odd trailing byte is ignored, never shifted in. */
    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val low = bytes[i * 2].toInt() and 0xFF
            // High byte stays signed, so << 8 sign-extends: 0x8000 becomes -32768.
            val high = bytes[i * 2 + 1].toInt()
            out[i] = ((high shl 8) or low) / 32768f
        }
        return out
    }

    /** The same scale for the shorts AudioRecord hands out directly. */
    fun shortsToFloats(shorts: ShortArray, count: Int): FloatArray {
        val n = count.coerceIn(0, shorts.size)
        return FloatArray(n) { shorts[it] / 32768f }
    }
}

/** Tracks what was already published, so partials update without repeating and finals are trimmed. */
internal class DictationState {
    private var last = ""

    /** The text to publish when it changed, else null; blanks are never published. */
    fun partial(text: String): String? {
        val value = text.trim()
        if (value.isEmpty() || value == last) return null
        last = value
        return value
    }

    /** The finished utterance, trimmed, or null when nothing was heard. Resets for the next one. */
    fun endpoint(text: String): String? {
        val value = text.trim()
        reset()
        return value.ifEmpty { null }
    }

    fun reset() {
        last = ""
    }
}

/**
 * Dictation with the bundled offline speech model: microphone -> streaming ASR -> partial text,
 * ending in one final transcript on a pause or on [stop].
 *
 * The microphone is shared with [WakeWordService], and Android hands it to one recorder at a time.
 * This class does not coordinate with the wake service: only one session may run here
 * ([isCapturing]), and callers start dictation from the visible UI, where the wake service has
 * already yielded the mic after its handoff pause. Holding [isCapturing] is how the UI knows a
 * session owns the recorder.
 *
 * Callbacks run on the dictation worker thread — hop to the main thread before touching UI.
 * [start] can report an error synchronously (a second session while one runs).
 */
class OfflineDictation(private val context: Context) {
    private val lock = Any()
    private var recognizer: OfflineSpeechRecognizer? = null
    private var worker: Thread? = null

    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false

    /** True from the moment a session starts until its recorder is released. */
    @Volatile var isCapturing: Boolean = false
        private set

    /** Starts capturing. Callbacks: partial text, the final transcript, and a failure reason. */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        val thread = Thread({ runSession(onPartial, onFinal, onError) }, "offline-dictation")
        synchronized(lock) {
            if (isCapturing) {
                onError(context.getString(R.string.stt_already_running))
                return
            }
            cancelled = false
            stopRequested = false
            isCapturing = true
            worker = thread
        }
        thread.start()
    }

    /** Ends the utterance: the tail is decoded and [onFinal] fires (possibly with an empty string). */
    fun stop() {
        stopRequested = true
    }

    /** Drops the session without any callback and releases the microphone. */
    fun cancel() {
        cancelled = true
        stopRequested = true
    }

    /** Cancels, waits for the worker so the mic is free, then frees the ~71 MB model. */
    fun release() {
        cancelled = true
        stopRequested = true
        val thread = synchronized(lock) { worker }
        thread?.join(RELEASE_JOIN_MS)
        if (thread?.isAlive == true) {
            // Freeing the model under a live decoder would be a native use-after-free; keep it and
            // let the caller release again after the session ends.
            Log.w(TAG, "dictation worker still running; model kept")
            return
        }
        synchronized(lock) {
            runCatching { recognizer?.close() }
            recognizer = null
            worker = null
            isCapturing = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSession(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        var recorder: AudioRecord? = null
        var recognizer: OfflineSpeechRecognizer? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onError(context.getString(R.string.stt_needs_mic))
                return
            }
            val loaded = synchronized(lock) { this.recognizer }
            recognizer = loaded ?: when (val started = OfflineSpeechRecognizer.create(context)) {
                is AsrStart.Unavailable -> {
                    onError(started.reason)
                    return
                }
                is AsrStart.Ready -> started.recognizer.also { synchronized(lock) { this.recognizer = it } }
            }
            recorder = openRecorder()
            if (recorder == null) {
                onError(context.getString(R.string.stt_open_failed))
                return
            }
            recognizer.reset()
            val frames = ShortArray(FRAME_SAMPLES)
            val samples = FloatArray(FRAME_SAMPLES)
            val state = DictationState()
            while (!stopRequested && !cancelled) {
                val read = try {
                    recorder.read(frames, 0, frames.size, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    -1
                }
                if (read <= 0) {
                    if (!cancelled) onError(context.getString(R.string.stt_interrupted))
                    return
                }
                for (i in 0 until read) samples[i] = frames[i] / 32768f
                recognizer.accept(if (read == frames.size) samples else samples.copyOf(read))
                recognizer.transcript()?.let { text -> state.partial(text)?.let(onPartial) }
                if (recognizer.endpoint()) {
                    recognizer.finish()
                    onFinal(state.endpoint(recognizer.text()) ?: "")
                    return
                }
            }
            if (stopRequested && !cancelled) {
                recognizer.finish()
                onFinal(state.endpoint(recognizer.text()) ?: "")
            }
        } catch (e: Exception) {
            if (!cancelled) {
                Log.w(TAG, "dictation failed", e)
                onError(e.message ?: context.getString(R.string.stt_failed))
            }
        } finally {
            closeRecorder(recorder)
            runCatching { recognizer?.reset() }
            synchronized(lock) {
                isCapturing = false
                stopRequested = false
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(ASR_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val recorder = try {
            AudioRecord(
                // The source built for this: no AGC or noise processing fights the model.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                ASR_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot open the microphone", e)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return try {
            recorder.startRecording()
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder
            } else {
                recorder.release()
                null
            }
        } catch (e: SecurityException) {
            recorder.release()
            null
        }
    }

    private fun closeRecorder(recorder: AudioRecord?) {
        if (recorder == null) return
        runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
        runCatching { recorder.release() }
    }

    companion object {
        private const val TAG = "OfflineDictation"

        /** ~0.1 s of 16 kHz audio per read: low enough latency for partials, large enough to decode. */
        private const val FRAME_SAMPLES = ASR_SAMPLE_RATE / 10
        private const val RELEASE_JOIN_MS = 2_000L
    }
}
