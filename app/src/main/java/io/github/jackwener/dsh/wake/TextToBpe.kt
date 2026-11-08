package io.github.jackwener.dsh.wake

import java.io.File

/**
 * Turns a wake phrase into the keyword line sherpa-onnx's keyword spotter expects.
 *
 * The KWS model is trained with BPE modelling units, so each `keywords.txt` line is
 * space-separated *pieces*, not words. sherpa's loader (`EncodeBase` in utils.cc) only maps
 * pieces to ids through `tokens.txt`; it does not tokenize text, and an unknown piece is
 * fatal there — it logs and calls `SHERPA_ONNX_EXIT`, killing the process. So the phrase is
 * tokenized here, exactly as the model's `bpe.model` would, and every piece is validated
 * against `tokens.txt` before the spotter is constructed.
 *
 * `bpe.model` is a SentencePiece **unigram** model (unigram despite the BPE field names):
 * the correct segmentation is the highest-scoring path over the piece vocabulary.
 * `vocab.tsv` carries that vocabulary and its scores, extracted once by
 * `tools/gen-wake-vocab.py`, so no protobuf parser ships in the app. `TextToBpeGoldenTest`
 * holds encodings produced by real sentencepiece, and checks the table itself against
 * `bpe.model`.
 */
object TextToBpe {
    /** A phrase encoded for the spotter, or the reason it cannot be. */
    sealed interface Result {
        /** Ready to write to a keyword file. */
        data class Ok(val line: String, val pieces: List<String>) : Result

        /** The model cannot express this phrase; it must never reach sherpa. */
        data class UnknownPieces(val phrase: String, val pieces: List<String>) : Result
    }

    /**
     * The model's unigram piece table: piece text to log score.
     *
     * Only NORMAL pieces are decodable segments; control and unknown pieces never take part
     * in a segmentation, and `vocab.tsv` excludes them.
     */
    class Vocabulary(private val scores: Map<String, Float>) {
        val pieces: Set<String> get() = scores.keys
        private val maxPieceLength = scores.keys.maxOfOrNull { it.length } ?: 0

        /**
         * Highest-scoring segmentation of [text], or null when none exists.
         *
         * Mirrors the model's normalizer spec (`nmt_nfkc`, `add_dummy_prefix`,
         * `remove_extra_whitespaces`, `escape_whitespaces`): collapse whitespace runs, prefix the
         * dummy space that becomes the `▁` marker, then escape the remaining spaces. Without the
         * dummy prefix the first piece of every phrase differs from the model's own tokenizer.
         *
         * Text is uppercased first: the vocabulary is casing-sensitive and carries upper-case
         * pieces only, so a user typing "hey dsh" would otherwise encode to `<unk>`.
         */
        fun segment(text: String): List<String>? {
            val normalized = text.trim().uppercase().replace(WHITESPACE_RUN, " ")
            if (normalized.isEmpty()) return null
            val s = SPACE + normalized.replace(" ", SPACE)
            // best[i] = (log score, pieces) for the best segmentation of s[0, i).
            val best = arrayOfNulls<Pair<Float, List<String>>>(s.length + 1)
            best[0] = 0f to emptyList()
            for (i in s.indices) {
                val (score, path) = best[i] ?: continue
                for (j in i + 1..minOf(s.length, i + maxPieceLength)) {
                    val pieceScore = scores[s.substring(i, j)] ?: continue
                    val candidate = score + pieceScore
                    val current = best[j]
                    if (current == null || candidate > current.first) best[j] = candidate to (path + s.substring(i, j))
                }
            }
            return best[s.length]?.second
        }

        companion object {
            /** The SentencePiece space marker (U+2581). */
            const val SPACE = "\u2581"

            /** The model's normalizer collapses runs of whitespace before escaping. */
            private val WHITESPACE_RUN = Regex("\\s+")

            /** Reads the `vocab.tsv` written by `tools/gen-wake-vocab.py`: piece TAB score. */
            fun fromTsv(lines: Sequence<String>): Vocabulary {
                val scores = LinkedHashMap<String, Float>()
                for (line in lines) {
                    if (line.isBlank()) continue
                    val tab = line.lastIndexOf('\t')
                    if (tab <= 0) continue
                    val score = line.substring(tab + 1).trim().toFloatOrNull() ?: continue
                    scores[line.substring(0, tab)] = score
                }
                return Vocabulary(scores)
            }

            fun fromTsvFile(file: File): Vocabulary = fromTsv(file.readLines().asSequence())
        }
    }

    /** Parses `tokens.txt` (one `<piece> <id>` per line) into the set of ids the model knows. */
    fun tokensFrom(lines: Sequence<String>): Set<String> =
        lines.mapNotNull { line ->
            line.trim().takeIf(String::isNotEmpty)?.substringBefore(' ')
        }.toSet()

    /**
     * Encodes [phrase] as a keyword line, after checking every piece against [tokens].
     *
     * `boost` and `threshold` are the documented per-keyword `:score` and `#threshold`
     * suffixes; both are omitted when null so the spotter's own configured values apply.
     */
    fun encode(
        phrase: String,
        vocabulary: Vocabulary,
        tokens: Set<String>,
        boost: Float? = null,
        threshold: Float? = null,
    ): Result {
        val pieces = vocabulary.segment(phrase) ?: return Result.UnknownPieces(phrase, emptyList())
        val unknown = pieces.filterNot { it in tokens }
        if (unknown.isNotEmpty()) return Result.UnknownPieces(phrase, unknown)
        val line = buildString {
            append(pieces.joinToString(" "))
            // sherpa's parser accepts the suffixes in either order; "#" first matches its docs.
            if (threshold != null) append(" #").append(threshold)
            if (boost != null) append(" :").append(boost)
        }
        return Result.Ok(line, pieces)
    }
}
