package io.github.jackwener.dsh.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the assistant wrote must become prose a person would want read aloud.
 *
 * These are the rules the user hears: no code, no markdown syntax, no URLs, no table borders —
 * and sentences handed to the engine as soon as they are complete, while a half-arrived reply
 * keeps its unfinished tail.
 */
class SpeechTextTest {

    // ---- toProse ------------------------------------------------------------------------------

    @Test fun `a fenced code block is dropped whole, including its info string`() {
        val markdown = "Here is how:\n```kotlin\nfun main() = println(\"hi\")\n```\nThat is all."
        assertEquals("Here is how:\nThat is all.", SpeechText.toProse(markdown))
        assertEquals("", SpeechText.toProse("```\nsecret code\n```"))
    }

    @Test fun `an unterminated fence is code to the end of the buffer, not prose`() {
        // While a reply streams, the closing fence has not arrived yet; reading the code out is
        // the failure this prevents.
        assertEquals("Let me show you:", SpeechText.toProse("Let me show you:\n```python\nprint(1)"))
    }

    @Test fun `inline code keeps its text without the backticks`() {
        assertEquals("Use TextToSpeech for this.", SpeechText.toProse("Use `TextToSpeech` for this."))
    }

    @Test fun `links keep their text, images and bare URLs are dropped`() {
        assertEquals("See the docs for more.", SpeechText.toProse("See [the docs](https://example.com/a) for more."))
        assertEquals("A picture then prose.", SpeechText.toProse("![a chart](https://example.com/c.png) A picture then prose."))
        val bare = SpeechText.toProse("Read https://example.com/x now")
        assertFalse("a URL is not spoken", bare.contains("http"))
        assertFalse(bare.contains("example.com"))
    }

    @Test fun `headings, quotes, bullets and numbered items lose their markers`() {
        assertEquals("Title\nBody", SpeechText.toProse("## Title\nBody"))
        assertEquals("Quoted line", SpeechText.toProse("> Quoted line"))
        assertEquals("one\ntwo", SpeechText.toProse("- one\n- two"))
        assertEquals("first\nsecond", SpeechText.toProse("1. first\n2. second"))
        // A year that starts a line is not an ordered list.
        assertEquals("2024. was a year.", SpeechText.toProse("2024. was a year."))
    }

    @Test fun `a table's alignment row and pipes are not spoken`() {
        val table = "| Name | Count |\n|---|---|\n| alpha | 3 |"
        assertEquals("Name Count\nalpha 3", SpeechText.toProse(table))
    }

    @Test fun `emphasis markers are stripped but snake_case survives`() {
        assertEquals("bold and italic and gone and also", SpeechText.toProse("**bold** and *italic* and ~~gone~~ and __also__"))
        assertEquals("my_var_name stays", SpeechText.toProse("my_var_name stays"))
    }

    @Test fun `html tags are stripped`() {
        assertEquals("bold", SpeechText.toProse("<b>bold</b>"))
        assertEquals("kept", SpeechText.toProse("<!-- hidden -->kept"))
    }

    @Test fun `whitespace collapses, and paragraphs stay separate lines`() {
        assertEquals("a b", SpeechText.toProse("a    b"))
        assertEquals("one\ntwo", SpeechText.toProse("one\n\n\ntwo"))
        assertEquals("", SpeechText.toProse("   \n\t "))
    }

    @Test fun `a horizontal rule is not spoken`() {
        assertEquals("above\nbelow", SpeechText.toProse("above\n---\nbelow"))
    }

    // ---- looksLikeData ------------------------------------------------------------------------

    @Test fun `JSON replies are data`() {
        assertTrue(SpeechText.looksLikeData("{\"ok\":true}"))
        assertTrue(SpeechText.looksLikeData("[\n  1,\n  2\n]"))
    }

    @Test fun `a wall of digits and symbols is data, prose is not`() {
        assertTrue(SpeechText.looksLikeData("|---|---|"))
        assertTrue(SpeechText.looksLikeData("1 2 3 4 5"))
        assertFalse(SpeechText.looksLikeData("Hello there, here is the answer."))
        assertFalse(SpeechText.looksLikeData("你好，这就是答案。"))
    }

    // ---- sentences ----------------------------------------------------------------------------

    @Test fun `complete sentences are split off and the tail is kept`() {
        val (done, tail) = SpeechText.sentences("Hello world. Next one! And")
        assertEquals(listOf("Hello world.", "Next one!"), done)
        assertEquals(" And", tail)
    }

    @Test fun `an unfinished sentence is never emitted`() {
        val (done, tail) = SpeechText.sentences("The reply is still")
        assertTrue(done.isEmpty())
        assertEquals("The reply is still", tail)
    }

    @Test fun `abbreviations do not end a sentence`() {
        assertTrue(SpeechText.sentences("I met Mr. Smith").first.isEmpty())
        assertTrue(SpeechText.sentences("Ask Dr. Jones about it").first.isEmpty())
        assertTrue(SpeechText.sentences("Use e.g. this one").first.isEmpty())
        assertTrue(SpeechText.sentences("i.e. that one").first.isEmpty())
        assertTrue(SpeechText.sentences("Cats vs. dogs").first.isEmpty())
    }

    @Test fun `decimals do not end a sentence`() {
        assertEquals(listOf("It costs 3.50 now."), SpeechText.sentences("It costs 3.50 now.").first)
        // A trailing "3." may be the start of "3.5" in the next piece: hold it.
        assertTrue(SpeechText.sentences("It costs 3.").first.isEmpty())
        // Once a space follows, it is a real sentence end.
        assertEquals(listOf("It costs 3."), SpeechText.sentences("It costs 3. Next").first)
    }

    @Test fun `a sentence ending at the end of the buffer is complete`() {
        assertEquals(listOf("Done."), SpeechText.sentences("Done.").first)
    }

    @Test fun `closing punctuation stays with its sentence`() {
        assertEquals(listOf("He said \"hi.\""), SpeechText.sentences("He said \"hi.\" Then").first)
    }

    @Test fun `no chunk is blank or whitespace only`() {
        val (done, tail) = SpeechText.sentences("One.  Two!   Three?")
        assertEquals(listOf("One.", "Two!", "Three?"), done)
        assertTrue(done.all { it.isNotBlank() })
        assertEquals("", tail)
    }

    @Test fun `long sentences are split into engine-sized chunks on word boundaries`() {
        val long = (1..60).joinToString(" ") { "word$it" } + "."
        val (done, _) = SpeechText.sentences(long)
        assertTrue("a long sentence must become several chunks", done.size >= 2)
        for (chunk in done) {
            assertTrue("chunk too long (${chunk.length}): $chunk", chunk.length <= SpeechText.MAX_CHUNK)
            assertFalse("no chunk starts with a space", chunk.startsWith(" "))
            assertFalse("no chunk ends with a space", chunk.endsWith(" "))
            assertFalse("no chunk cuts a word", chunk.split(' ').any { it.isEmpty() })
        }
        assertEquals("no text is lost between chunks", long, done.joinToString(" "))
    }

    @Test fun `a long line is a soft boundary at its newline`() {
        val line = (1..60).joinToString(" ") { "w$it" }
        assertTrue("line long enough to qualify", line.length >= SpeechText.SOFT_LINE)
        val (done, tail) = SpeechText.sentences("$line\nnext")
        assertEquals("no text is lost", line, done.joinToString(" "))
        assertEquals("next", tail)
    }

    @Test fun `a very long unpunctuated run is flushed instead of staying silent`() {
        val run = (1..120).joinToString(" ") { "word$it" }
        assertTrue("run long enough to flush", run.length > SpeechText.TAIL_FLUSH)
        val (done, tail) = SpeechText.sentences(run)
        assertTrue("a long run must not stay silent", done.isNotEmpty())
        // One flush per call would leave the rest silent for another whole piece.
        assertTrue("the whole over-long run is spoken, not just one piece", done.size >= 2)
        assertTrue("something is retained for the next piece", tail.isNotEmpty())
        for (chunk in done) assertTrue(chunk.length <= SpeechText.MAX_CHUNK)
        assertEquals("no text is lost", run, (done + tail.trim()).joinToString(" "))
    }

    // ---- chunks -------------------------------------------------------------------------------

    @Test fun `chunks never exceed the cap and never cut a word`() {
        val text = "alpha beta gamma delta epsilon zeta eta theta iota kappa"
        for (chunk in SpeechText.chunks(text, max = 12)) {
            assertTrue(chunk.length <= 12)
            assertFalse(chunk.startsWith(" "))
            assertFalse(chunk.endsWith(" "))
        }
        assertEquals(text, SpeechText.chunks(text, max = 12).joinToString(" "))
        assertEquals(emptyList<String>(), SpeechText.chunks("   "))
    }
}
