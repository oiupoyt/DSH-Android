package io.github.jackwener.dsh.wake

import android.content.Context
import io.github.jackwener.dsh.R
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer as SherpaOnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.jackwener.dsh.BuildConfig
import java.io.File
import java.io.IOException

/** 16 kHz mono is what the streaming ASR model was trained on. */
const val ASR_SAMPLE_RATE = 16_000

/**
 * On-device streaming speech recognition for dictation, or the reason it cannot run.
 *
 * Same construction rule as the wake word (see [WakeKeywordSpotter]): an AssetManager would make
 * sherpa read through `AAssetManager_open`, and a failed asset read there calls
 * `SHERPA_ONNX_EXIT` — process death, uncatchable. With a null AssetManager sherpa uses
 * `newFromFile`, which validates every path first and returns a catchable
 * `IllegalArgumentException` instead. So the model is copied out of the APK once per build and
 * referenced by absolute path.
 *
 * Unlike the wake model, this one is loaded once and kept: it is ~71 MB and takes long enough that
 * rebuilding it for every utterance would be felt. [close] is the only thing that frees it.
 */
class OfflineSpeechRecognizer private constructor(
    private val recognizer: SherpaOnlineRecognizer,
    private val stream: OnlineStream,
) : AutoCloseable {
    private var lastText = ""

    /** Feeds one frame of 16 kHz audio with samples in [-1, 1]. */
    fun accept(samples: FloatArray) {
        stream.acceptWaveform(samples, ASR_SAMPLE_RATE)
    }

    /**
     * Decodes whatever is ready and returns the transcript when it changed, else null: this is the
     * partial result the composer shows while the user is still speaking.
     */
    fun transcript(): String? {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (text == lastText) return null
        lastText = text
        return text
    }

    /** True once the decoder heard a pause: the utterance is finished. */
    fun endpoint(): Boolean = recognizer.isEndpoint(stream)

    /** The transcript as it stands; used for the final text after [finish] or an endpoint. */
    fun text(): String = recognizer.getResult(stream).text.trim()

    /** Marks the audio as ended and decodes the tail, so [text] is complete. */
    fun finish() {
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    /** Prepares the stream for another utterance; the model stays loaded. */
    fun reset() {
        lastText = ""
        recognizer.reset(stream)
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }

    companion object {
        private const val TAG = "OfflineSpeechRecognizer"

        private const val ASSET_DIR = "asr"
        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.int8.onnx"
        private const val JOINER = "joiner.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val MARKER = ".complete"

        /** Model version, bumped by hand if `assets/asr` changes without a versionCode change. */
        private const val MODEL_VERSION = 1

        private val MODEL_ASSETS = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** Builds the recognizer for the bundled English streaming zipformer. Blocking: call off the main thread. */
        fun create(context: Context): AsrStart {
            val dir = try {
                modelFiles(context)
            } catch (e: Exception) {
                return AsrStart.Unavailable(context.getString(R.string.voice_stt_missing))
            }
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(ASR_SAMPLE_RATE, 80, 0.0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, ENCODER).absolutePath,
                        decoder = File(dir, DECODER).absolutePath,
                        joiner = File(dir, JOINER).absolutePath,
                    ),
                    tokens = File(dir, TOKENS).absolutePath,
                    numThreads = 1,
                    modelType = "zipformer",
                ),
                // Pause lengths are seconds. A short pause after speech finalises a sentence, a long
                // pause always finalises, and a monologue is cut at 20 s instead of never ending.
                endpointConfig = EndpointConfig(
                    EndpointRule(false, 2.4f, 0.0f),
                    EndpointRule(true, 1.2f, 0.0f),
                    EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
            return try {
                val recognizer = SherpaOnlineRecognizer(null, config)
                val stream = recognizer.createStream()
                if (stream.ptr == 0L) {
                    recognizer.release()
                    AsrStart.Unavailable(context.getString(R.string.stt_start_failed))
                } else {
                    AsrStart.Ready(OfflineSpeechRecognizer(recognizer, stream))
                }
            } catch (e: UnsatisfiedLinkError) {
                // Only arm64-v8a is vendored: x86 / 32-bit devices have no sherpa at all.
                AsrStart.Unavailable(context.getString(R.string.stt_cpu_unsupported))
            } catch (e: OutOfMemoryError) {
                // A ~71 MB model on a small-RAM phone: report it rather than take the process down.
                Log.e(TAG, "not enough memory for the speech model", e)
                AsrStart.Unavailable(context.getString(R.string.stt_no_memory))
            } catch (e: IllegalArgumentException) {
                // sherpa's Kotlin wrapper throws this when Validate() rejected the configuration.
                AsrStart.Unavailable(e.message ?: context.getString(R.string.stt_config_rejected))
            }
        }

        /**
         * The model files as real files, copied out of the APK once per build.
         *
         * Versioned by [BuildConfig.VERSION_CODE] so an update replaces the copy; the marker is
         * written last, so an interrupted copy is redone rather than trusted.
         */
        @Synchronized
        private fun modelFiles(context: Context): File {
            val root = File(context.cacheDir, "asr-models")
            val dir = File(root, "${BuildConfig.VERSION_CODE}-$MODEL_VERSION")
            val marker = File(dir, MARKER)
            if (marker.isFile && MODEL_ASSETS.all { File(dir, it).length() > 0L }) return dir
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
            for (name in MODEL_ASSETS) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            marker.writeText("$MODEL_VERSION")
            Log.i(TAG, "speech model copied to ${dir.absolutePath}")
            root.listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
            return dir
        }
    }
}

/** The recognizer, or why on-device dictation cannot run on this device. */
sealed interface AsrStart {
    data class Ready(val recognizer: OfflineSpeechRecognizer) : AsrStart
    data class Unavailable(val reason: String) : AsrStart
}
