package io.github.jackwener.dsh.wake

/**
 * What the assistant wrote → what a human wants read aloud.
 *
 * A reply arrives as markdown (headings, code fences, tables, links) and, while it streams,
 * in arbitrary pieces. Two jobs live here, both pure Kotlin so they are unit-tested without an
 * emulator:
 *
 * - [toProse] strips everything that is not spoken prose. Code is never read out: a fenced
 *   block is dropped whole, and a reply that is only JSON or a table is not spoken at all
 *   ([looksLikeData]).
 * - [sentences] turns the growing buffer into speakable chunks as soon as a sentence ends, so
 *   speech starts before the reply is finished, and keeps the incomplete tail for the next
 *   piece. Chunks are capped at [MAX_CHUNK] so a wall of text without punctuation still reaches
 *   the engine in sane pieces.
 *
 * Markdown is stripped from the whole accumulated buffer on every piece (a fence or an emphasis
 * marker can be split across pieces), which is why the buffer, not the piece, is the input.
 */
object SpeechText {
    /** Longest piece handed to the TTS queue. Longer sentences are split on word boundaries. */
    const val MAX_CHUNK = 200

    /** A line at least this long ends at its newline even without punctuation (a soft boundary). */
    const val SOFT_LINE = 160

    /** An unpunctuated run longer than this is flushed mid-sentence so speech keeps up. */
    const val TAIL_FLUSH = 400

    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val HTML_TAG = Regex("</?[A-Za-z][^>]*>")
    private val BARE_URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s*")
    private val QUOTE = Regex("^\\s{0,3}>\\s?")
    private val BULLET = Regex("^\\s*[-*+]\\s+")
    private val NUMBERED = Regex("^\\s*\\d{1,2}[.)]\\s+")
    private val RULE = Regex("^\\s*(?:-\\s*){3,}$|^\\s*(?:\\*\\s*){3,}$|^\\s*(?:_\\s*){3,}$")
    private val INLINE_CODE = Regex("`([^`\\n]*)`")
    private val DOUBLE_EMPHASIS = Regex("\\*\\*|__|~~")
    private val ASTERISK = Regex("\\*")
    private val UNDERSCORE = Regex("(?<![\\p{L}\\p{N}_])_+|_+(?![\\p{L}\\p{N}_])")
    private val SPACE_RUN = Regex("[ \\t\\u00a0]+")
    private val BLANK_LINE_RUN = Regex("\\n{2,}")
    private val LINE_EDGE = Regex(" ?\\n ?")

    /**
     * The speakable prose of [markdown]: code blocks, tables, links' targets, HTML and markdown
     * syntax removed, whitespace collapsed. Paragraph breaks survive as single newlines so the
     * engine pauses there; everything else collapses to one space.
     */
    fun toProse(markdown: String): String {
        if (markdown.isBlank()) return ""
        val unfenced = stripFences(markdown)
        if (unfenced.isBlank()) return ""

        val kept = ArrayList<String>()
        for (raw in unfenced.split('\n')) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (isTableSeparator(trimmed) || RULE.matches(trimmed)) continue

            var s = line
            s = HEADING.replace(s, "")
            s = QUOTE.replace(s, "")
            s = BULLET.replace(s, "")
            s = NUMBERED.replace(s, "")
            if ('|' in s) s = s.replace('|', ' ')
            kept += s.trim()
        }
        if (kept.isEmpty()) return ""
        var text = kept.joinToString("\n")
        text = SPACE_RUN.replace(text, " ")
        text = LINE_EDGE.replace(text, "\n")
        text = BLANK_LINE_RUN.replace(text, "\n").trim()

        text = IMAGE.replace(text, "")
        text = LINK.replace(text, "$1")
        text = HTML_COMMENT.replace(text, "")
        text = BARE_URL.replace(text, "")
        text = HTML_TAG.replace(text, "")
        text = INLINE_CODE.replace(text, "$1")
        text = text.replace("`", "")
        text = DOUBLE_EMPHASIS.replace(text, "")
        text = ASTERISK.replace(text, "")
        text = UNDERSCORE.replace(text, "")

        text = SPACE_RUN.replace(text, " ")
        text = LINE_EDGE.replace(text, "\n")
        return BLANK_LINE_RUN.replace(text, "\n").trim()
    }

    /**
     * True when [text] is something the user is looking at rather than listening to: a JSON
     * object/array, or mostly digits, punctuation and symbols. Such replies stay silent.
     */
    fun looksLikeData(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) return true
        val visible = t.count { !it.isWhitespace() }
        if (visible < 3) return false
        return t.count { it.isLetter() }.toDouble() / visible < 0.4
    }

    /**
     * Splits completed sentences out of a streaming [buffer], returning the sentences and the
     * unfinished tail.
     *
     * A sentence ends at `.`, `!` or `?` followed by whitespace, the end of the buffer, or
     * closing punctuation. Abbreviations (`Mr.`, `e.g.`, `vs.`, a single initial) and decimals
     * are not boundaries. A line already longer than [SOFT_LINE] ends at its newline, and a run
     * with no terminator at all is flushed at a word boundary once it passes [TAIL_FLUSH].
     *
     * The tail is returned exactly as it appears in [buffer] (leading spaces included): the
     * streaming caller advances its cursor by `buffer.length - tail.length`.
     */
    fun sentences(buffer: String): Pair<List<String>, String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c != '.' && c != '!' && c != '?') {
                i++
                continue
            }
            var end = i
            while (end + 1 < buffer.length && buffer[end + 1].let { it == '.' || it == '!' || it == '?' }) end++
            var after = end + 1
            while (after < buffer.length && buffer[after] in CLOSERS) after++
            val boundary = after >= buffer.length || buffer[after].isWhitespace()
            if (boundary && !isAbbreviation(buffer, i, end == buffer.lastIndex)) {
                emit(out, buffer.substring(start, after))
                start = after
            }
            i = end + 1
        }

        var tail = buffer.substring(start)
        // A line that already ended and is long is speakable without punctuation.
        while (true) {
            val newline = tail.indexOf('\n')
            if (newline < SOFT_LINE) break
            emit(out, tail.substring(0, newline))
            tail = tail.substring(newline + 1)
        }
        // A very long run with no terminator must not silence the reply until it ends; flush all
        // of it, not one chunk, or a single big piece would leave the rest queued behind it.
        while (tail.length > TAIL_FLUSH) {
            val cut = wordCut(tail, MAX_CHUNK)
            emit(out, tail.substring(0, cut))
            tail = tail.substring(cut)
        }
        return out to tail
    }

    /** [text] split into pieces of at most [max] characters on word boundaries, empty pieces dropped. */
    fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= max) return listOf(t)
        val out = ArrayList<String>()
        var rest = t
        while (rest.length > max) {
            val cut = wordCut(rest, max)
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            rest = rest.substring(cut).trimStart()
            if (rest.isEmpty()) break
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    // ---- internals ---------------------------------------------------------------------------

    private val CLOSERS = charArrayOf('"', '\'', '”', '’', ')', ']', '}', '»')

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "approx", "dept", "est",
        "fig", "no", "vol", "inc", "ltd", "co", "pl", "rev", "ed", "eds", "cf", "al", "pp",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    /**
     * Removes fenced code blocks (``` … ```). An unterminated fence is dropped to the end of the
     * buffer: while a reply streams, that half-arrived block is code, not prose, and speaking it
     * would read source code aloud.
     */
    private fun stripFences(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        var inFence = false
        while (i < s.length) {
            val fence = s.indexOf("```", i)
            if (fence < 0) {
                if (!inFence) out.append(s, i, s.length)
                break
            }
            if (inFence) {
                inFence = false
                i = fence + 3
            } else {
                out.append(s, i, fence)
                inFence = true
                // The info string (```kotlin) lives on the fence's own line and is not prose.
                val newline = s.indexOf('\n', fence + 3)
                if (newline < 0) break
                i = newline + 1
            }
        }
        return out.toString()
    }

    /** `|---|:--:|` / `--- | ---`: a table's alignment row, which carries no text. */
    private fun isTableSeparator(line: String): Boolean {
        if (line.count { it == '-' } < 2) return false
        return line.all { it == '-' || it == '|' || it == ':' || it.isWhitespace() }
    }

    /** True when the terminator at [dot] belongs to a word, not to the end of a sentence. */
    private fun isAbbreviation(buffer: String, dot: Int, atEnd: Boolean): Boolean {
        if (buffer[dot] != '.') return false
        if (dot > 0 && buffer[dot - 1].isDigit()) {
            // A fraction: "3.5" is never a sentence break.
            if (dot + 1 < buffer.length && buffer[dot + 1].isDigit()) return true
            // A bare number at the end of a piece may be "3." of "3.5", so wait for the next
            // character. A token with letters in it ("word60.") is a word and ends the sentence.
            if (atEnd && isBareNumber(buffer, dot)) return true
        }
        var start = dot
        while (start > 0 && buffer[start - 1].isLetter()) start--
        val word = buffer.substring(start, dot)
        if (word.isEmpty()) return false
        if (word.length == 1) return true // an initial, or the `g` of `e.g.`
        return word.lowercase() in ABBREVIATIONS
    }

    /** The token before [dot] is only digits ("3"): a number that may still become "3.5". */
    private fun isBareNumber(buffer: String, dot: Int): Boolean {
        var start = dot
        while (start > 0 && (buffer[start - 1].isLetterOrDigit() || buffer[start - 1] == '.')) start--
        val token = buffer.substring(start, dot)
        return token.isNotEmpty() && token.all { it.isDigit() }
    }

    private fun emit(out: MutableList<String>, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        out += chunks(t)
    }

    /** The index to cut [text] at so the piece stays under [limit], preferring a space. */
    private fun wordCut(text: String, limit: Int): Int {
        if (text.length <= limit) return text.length
        for (i in limit downTo 1) if (text[i - 1].isWhitespace()) return i
        return limit
    }
}

/** What [SpeakerState] wants done with the text it was just handed. */
sealed interface SpeechAction {
    /** Nothing to say yet (or nothing new): keep buffering. */
    data object Buffer : SpeechAction

    /** Say these chunks now, in order (TTS `QUEUE_ADD`). */
    data class Enqueue(val chunks: List<String>) : SpeechAction

    /** This reply must not be spoken. */
    data class Skip(val reason: SkipReason) : SpeechAction

    /** Why a reply stays silent. */
    enum class SkipReason { MasterOff, TalkbackOff, ScreenReader, Data, Empty, Finished }
}

/**
 * The talkback state machine: settings + screen-reader state in, streamed reply text in,
 * "buffer some more / enqueue these sentences / skip this reply" out.
 *
 * Android-free on purpose, so the rules the user actually hears are tested in `SpeakerStateTest`
 * without an emulator. The service owns one instance, feeds it live assistant text and plays
 * whatever comes back; all methods must be called from the same thread (the service's main
 * thread).
 */
class SpeakerState(settings: VoiceSettings, screenReaderActive: Boolean) {
    /** The settings this machine runs with; change through [update] so a mid-reply toggle resets. */
    var settings: VoiceSettings = settings
        private set

    /** Whether a screen reader is reading the screen right now. */
    var screenReaderActive: Boolean = screenReaderActive
        private set

    private val raw = StringBuilder()
    private var emitted = 0
    private var data = false
    private var finished = false

    /** Whether a reply fed now would be spoken at all. */
    val enabled: Boolean get() = settings.willSpeak(screenReaderActive)

    /** Why the reply stays silent, or null when it may speak. */
    val muted: SpeechAction.SkipReason?
        get() = when {
            !settings.enabled -> SpeechAction.SkipReason.MasterOff
            !settings.talkbackEnabled -> SpeechAction.SkipReason.TalkbackOff
            screenReaderActive && !settings.talkbackWithScreenReader -> SpeechAction.SkipReason.ScreenReader
            else -> null
        }

    /**
     * Applies new settings. Flipping whether replies may be spoken drops whatever was buffered:
     * half a reply read from the middle is worse than starting at the next sentence.
     */
    fun update(settings: VoiceSettings, screenReaderActive: Boolean = this.screenReaderActive) {
        val wasEnabled = enabled
        this.settings = settings
        this.screenReaderActive = screenReaderActive
        if (wasEnabled != enabled) reset()
    }

    /** Forgets the reply being streamed (barge-in, a new user turn, the toggle going off). */
    fun reset() {
        raw.setLength(0)
        emitted = 0
        data = false
        finished = false
    }

    /**
     * Feeds the next piece of the assistant's raw (markdown) text and returns the sentences that
     * are now complete. Feeding a new piece after [complete] starts the next reply — unless the
     * finished reply was data, which stays silent until [reset] (the next user turn) so a data
     * turn never starts talking halfway through its own output.
     */
    fun feed(delta: String): SpeechAction {
        muted?.let { return SpeechAction.Skip(it) }
        if (finished) {
            if (data) return SpeechAction.Skip(SpeechAction.SkipReason.Data)
            reset()
        }
        if (delta.isEmpty()) return SpeechAction.Buffer
        raw.append(delta)
        return pump(final = false)
    }

    /**
     * The reply is complete: [fullText] (when given) is authoritative and replaces what streamed,
     * and the unfinished tail is flushed. Called once per turn; a second call says why nothing
     * more will be said.
     */
    fun complete(fullText: String? = null): SpeechAction {
        muted?.let { return SpeechAction.Skip(it) }
        if (fullText != null) {
            raw.setLength(0)
            raw.append(fullText)
        }
        if (finished) {
            // Same outcome as the first call: a blank reply is Empty, an already-flushed one Finished.
            return if (SpeechText.toProse(raw.toString()).isBlank()) {
                SpeechAction.Skip(SpeechAction.SkipReason.Empty)
            } else {
                SpeechAction.Skip(SpeechAction.SkipReason.Finished)
            }
        }
        val action = pump(final = true)
        finished = true
        return action
    }

    /** Whether the reply was recognised as data and is therefore never spoken. */
    val isData: Boolean get() = data

    private fun pump(final: Boolean): SpeechAction {
        val prose = SpeechText.toProse(raw.toString())
        if (prose.isBlank()) {
            return if (final) SpeechAction.Skip(SpeechAction.SkipReason.Empty) else SpeechAction.Buffer
        }
        // A reply that opens with `{`/`[` is JSON, and may contain sentences inside its strings:
        // stop before any of it reaches the queue, not after.
        if (data || startsLikeJson(prose)) {
            data = true
            return SpeechAction.Skip(SpeechAction.SkipReason.Data)
        }
        if (final && SpeechText.looksLikeData(prose)) {
            data = true
            return SpeechAction.Skip(SpeechAction.SkipReason.Data)
        }

        if (emitted > prose.length) emitted = 0 // the whole-buffer rewrite got shorter; re-read it
        val fresh = prose.substring(emitted)
        val (done, tail) = SpeechText.sentences(fresh)
        // `emitted` must point at the same place in `prose`: `sentences` returns the tail with the
        // buffer's own leading characters, so this arithmetic is exact.
        emitted = prose.length - tail.length
        val chunks = if (final) done + SpeechText.chunks(tail) else done
        return when {
            chunks.isNotEmpty() -> SpeechAction.Enqueue(chunks)
            // A finished reply with nothing left to say is Empty, not "buffering".
            final -> SpeechAction.Skip(SpeechAction.SkipReason.Empty)
            else -> SpeechAction.Buffer
        }
    }

    private fun startsLikeJson(prose: String): Boolean {
        val first = prose.firstOrNull { !it.isWhitespace() } ?: return false
        return first == '{' || first == '['
    }
}
