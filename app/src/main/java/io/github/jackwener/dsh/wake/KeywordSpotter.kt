package io.github.jackwener.dsh.wake

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaKeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.jackwener.dsh.BuildConfig
import java.io.File
import java.io.IOException

/** 16 kHz is what the bundled wake model was trained at, and what sherpa needs on every frame. */
const val WAKE_SAMPLE_RATE = 16000

/**
 * The keyword spotter for one phrase, or the reason it cannot be built.
 *
 * sherpa's `KeywordSpotter` in this AAR has two construction paths: with an `AssetManager` and with
 * `null`, which reads everything from the filesystem (`newFromFile`). Only the filesystem path can
 * work here. The asset path reads the *keywords file* through the AssetManager too
 * (`InitKeywords(mgr)` in sherpa's `keyword-spotter-transducer-impl.h`), and that file is generated
 * from the user's phrase when listening starts, while the assets inside an APK are read-only.
 *
 * Every phrase goes through [TextToBpe] and is checked against `tokens.txt` before sherpa is even
 * constructed: a keyword containing an unknown piece makes sherpa call `SHERPA_ONNX_EXIT` inside
 * `EncodeKeywords`, which kills the process and cannot be caught in Java.
 */
class WakeKeywordSpotter private constructor(
    private val spotter: SherpaKeywordSpotter,
    private val stream: OnlineStream,
) : AutoCloseable {

    /**
     * Feeds one frame of 16 kHz audio with samples in [-1, 1] and returns the phrase when this frame
     * completed it, otherwise null. A hit resets the stream, so the next utterance matches at once.
     */
    fun accept(samples: FloatArray): String? {
        stream.acceptWaveform(samples, WAKE_SAMPLE_RATE)
        while (spotter.isReady(stream)) spotter.decode(stream)
        val keyword = spotter.getResult(stream).keyword
        if (keyword.isEmpty()) return null
        spotter.reset(stream)
        return keyword
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { spotter.release() }
    }

    companion object {
        private const val TAG = "WakeKeywordSpotter"

        /** Assets folder holding the model; `tools/gen-wake-vocab.py` produced `vocab.tsv`. */
        private const val ASSET_DIR = "wake"
        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.int8.onnx"
        private const val JOINER = "joiner.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val VOCAB = "vocab.tsv"
        private const val MARKER = ".complete"

        /** Model version, bumped by hand if `assets/wake` changes without a versionCode change. */
        private const val MODEL_VERSION = 1

        private val MODEL_ASSETS = listOf(ENCODER, DECODER, JOINER, TOKENS, VOCAB)

        private const val KEYWORDS_SCORE = 1.0f
        private const val MAX_ACTIVE_PATHS = 4
        private const val NUM_TRAILING_BLANKS = 2

        /**
         * Builds the spotter for [voice]'s phrase and threshold. Blocking: the first call copies the
         * model out of the APK and every call loads ~5 MB of ONNX, so run it off the main thread.
         */
        fun create(context: Context, voice: VoiceSettings): SpotterStart {
            val dir: File
            val tokens: Set<String>
            val vocabulary: TextToBpe.Vocabulary
            try {
                dir = modelFiles(context)
                tokens = TextToBpe.tokensFrom(File(dir, TOKENS).readLines().asSequence())
                vocabulary = TextToBpe.Vocabulary.fromTsvFile(File(dir, VOCAB))
            } catch (e: Exception) {
                return refused(e.message ?: e.javaClass.simpleName)
            }
            val encoded = when (val r = TextToBpe.encode(voice.effectivePhrase, vocabulary, tokens, threshold = voice.keywordsThreshold())) {
                is TextToBpe.Result.UnknownPieces -> return SpotterStart.Refused(SpotterProblem.PhraseNotExpressible(r.phrase, r.pieces))
                is TextToBpe.Result.Ok -> r
            }
            val keywords = try {
                keywordsFile(context, encoded.line)
            } catch (e: Exception) {
                return refused(e.message ?: e.javaClass.simpleName)
            }
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(WAKE_SAMPLE_RATE, 80, 0.0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, ENCODER).absolutePath,
                        decoder = File(dir, DECODER).absolutePath,
                        joiner = File(dir, JOINER).absolutePath,
                    ),
                    tokens = File(dir, TOKENS).absolutePath,
                    numThreads = 1,
                    modelType = "zipformer2",
                ),
                maxActivePaths = MAX_ACTIVE_PATHS,
                keywordsFile = keywords.absolutePath,
                keywordsScore = KEYWORDS_SCORE,
                keywordsThreshold = voice.keywordsThreshold(),
                numTrailingBlanks = NUM_TRAILING_BLANKS,
            )
            return try {
                val spotter = SherpaKeywordSpotter(null, config)
                val stream = spotter.createStream()
                if (stream.ptr == 0L) {
                    spotter.release()
                    refused("sherpa returned an empty stream")
                } else {
                    SpotterStart.Ready(WakeKeywordSpotter(spotter, stream))
                }
            } catch (e: UnsatisfiedLinkError) {
                // Only arm64-v8a is vendored: x86 / 32-bit devices have no sherpa at all.
                SpotterStart.Refused(SpotterProblem.NativeMissing)
            } catch (e: IllegalArgumentException) {
                // sherpa's Kotlin wrapper throws this when it could not create the native spotter.
                refused(e.message ?: "sherpa rejected the configuration")
            }
        }

        private fun refused(detail: String) = SpotterStart.Refused(SpotterProblem.ModelUnreadable(detail))

        /**
         * The model files as real files, copied out of the APK once per build.
         *
         * Versioned by [BuildConfig.VERSION_CODE]: an update invalidates the copy. The marker is
         * written last, so an interrupted copy is redone instead of being trusted.
         */
        @Synchronized
        private fun modelFiles(context: Context): File {
            val root = File(context.cacheDir, "wake-models")
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
            Log.i(TAG, "wake model copied to ${dir.absolutePath}")
            root.listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
            return dir
        }

        /** sherpa opens the keyword file by path, so the validated line is written next to the model. */
        private fun keywordsFile(context: Context, line: String): File {
            val dir = File(context.cacheDir, "wake-keywords")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
            return File(dir, "keywords.txt").apply { writeText(line + "\n") }
        }
    }
}

/** The spotter, or why it could not be built. */
sealed interface SpotterStart {
    data class Ready(val spotter: WakeKeywordSpotter) : SpotterStart
    data class Refused(val problem: SpotterProblem) : SpotterStart
}

/** Why listening cannot start. Every case is reported to the user instead of being silent. */
sealed interface SpotterProblem {
    /**
     * The model's `tokens.txt` cannot express the phrase. Refused here, before the native library
     * is touched, because sherpa's `EncodeKeywords` exits the process on an unknown piece.
     */
    data class PhraseNotExpressible(val phrase: String, val pieces: List<String>) : SpotterProblem

    /** The APK carries no sherpa native library for this device's CPU (only arm64-v8a is vendored). */
    data object NativeMissing : SpotterProblem

    /** The model assets could not be copied out of the APK, or sherpa rejected the configuration. */
    data class ModelUnreadable(val detail: String) : SpotterProblem
}

/** One line of English for the notification and the status row; the settings UI may use its own strings. */
fun SpotterProblem.describe(): String = when (this) {
    is SpotterProblem.PhraseNotExpressible -> "The voice model cannot pronounce \u201c$phrase\u201d"
    SpotterProblem.NativeMissing -> "This device's CPU is not supported by the voice engine"
    is SpotterProblem.ModelUnreadable -> "The wake-word model could not be loaded: $detail"
}
