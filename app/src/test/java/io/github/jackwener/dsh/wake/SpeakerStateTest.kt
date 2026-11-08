package io.github.jackwener.dsh.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for turning a streamed reply into speech are one state machine, tested here without
 * Android: who may speak at all, what is skipped, and how much is said per piece so speech starts
 * before the reply ends and nothing is said twice.
 */
class SpeakerStateTest {
    private fun speaking() = VoiceSettings(enabled = true, talkbackEnabled = true)

    private fun state(
        settings: VoiceSettings = speaking(),
        screenReaderActive: Boolean = false,
    ) = SpeakerState(settings, screenReaderActive)

    private fun enqueued(action: SpeechAction): List<String> {
        assertTrue("expected speech, got $action", action is SpeechAction.Enqueue)
        return (action as SpeechAction.Enqueue).chunks
    }

    // ---- who may speak ------------------------------------------------------------------------

    @Test fun `a screen reader suppresses talkback`() {
        val s = state(screenReaderActive = true)
        assertFalse(s.enabled)
        assertEquals(SpeechAction.SkipReason.ScreenReader, s.muted)
        assertEquals(
            SpeechAction.Skip(SpeechAction.SkipReason.ScreenReader),
            s.feed("Hello there."),
        )
    }

    @Test fun `the override lets talkback speak over a screen reader`() {
        val s = state(speaking().copy(talkbackWithScreenReader = true), screenReaderActive = true)
        assertTrue(s.enabled)
        assertEquals(listOf("Hello there."), enqueued(s.feed("Hello there.")))
    }

    @Test fun `the master switch suppresses everything, and so does the talkback switch`() {
        val masterOff = state(speaking().copy(enabled = false))
        assertEquals(SpeechAction.SkipReason.MasterOff, masterOff.muted)
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.MasterOff), masterOff.feed("Hello."))

        val talkbackOff = state(speaking().copy(talkbackEnabled = false))
        assertEquals(SpeechAction.SkipReason.TalkbackOff, talkbackOff.muted)
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.TalkbackOff), talkbackOff.feed("Hello."))
    }

    @Test fun `turning replies off mid-reply forgets the half buffer`() {
        val s = state()
        assertEquals(SpeechAction.Buffer, s.feed("The answer is"))
        s.update(speaking().copy(enabled = false))
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.MasterOff), s.feed(" still 42."))
        s.update(speaking())
        // After re-enabling, the old fragment must not be spoken with the new text.
        assertEquals(listOf("A fresh reply."), enqueued(s.feed("A fresh reply.")))
    }

    // ---- what is skipped ----------------------------------------------------------------------

    @Test fun `a code fence is never spoken`() {
        val s = state()
        assertEquals(SpeechAction.Buffer, s.feed("```kotlin\nfun main() = 1\n```"))
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Empty), s.complete())
    }

    @Test fun `a JSON reply is skipped, even while it streams`() {
        val s = state()
        val action = s.feed("{\"answer\": \"Hello. This is inside a string.\"}")
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Data), action)
        assertTrue(s.isData)
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Data), s.complete())
        // Nothing was ever queued: the sentence inside the JSON string never reached the engine.
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Data), s.feed(" more"))
    }

    @Test fun `a table of numbers is skipped`() {
        val s = state()
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Data), s.complete("1 2 3 4 5 6"))
    }

    @Test fun `an empty reply says nothing`() {
        val s = state()
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Empty), s.complete())
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Empty), s.complete("   "))
    }

    // ---- streaming ----------------------------------------------------------------------------

    @Test fun `a sentence is emitted once and never re-spoken`() {
        val s = state()
        assertEquals(listOf("First sentence."), enqueued(s.feed("First sentence.")))
        assertEquals(listOf("Second one."), enqueued(s.feed(" Second one.")))
        // The buffer now holds both; feeding nothing new must not repeat the first.
        assertEquals(SpeechAction.Buffer, s.feed(""))
    }

    @Test fun `an unfinished tail is retained and spoken with the rest of its sentence`() {
        val s = state()
        assertEquals(SpeechAction.Buffer, s.feed("The reply is still"))
        assertEquals(listOf("The reply is still streaming."), enqueued(s.feed(" streaming.")))
    }

    @Test fun `completing flushes the retained tail exactly once`() {
        val s = state()
        assertEquals(SpeechAction.Buffer, s.feed("A final fragment"))
        assertEquals(listOf("A final fragment"), enqueued(s.complete()))
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Finished), s.complete())
    }

    @Test fun `markdown split across pieces is still stripped`() {
        val s = state()
        assertEquals(SpeechAction.Buffer, s.feed("**Hel"))
        assertEquals(listOf("Hello."), enqueued(s.feed("lo.**")))
    }

    @Test fun `a reply that streams a code block after prose speaks only the prose`() {
        val s = state()
        assertEquals(listOf("Look at this."), enqueued(s.feed("Look at this.\n```\n")))
        assertEquals(SpeechAction.Buffer, s.feed("fun x() = 1\n```"))
        assertEquals(SpeechAction.Skip(SpeechAction.SkipReason.Empty), s.complete())
    }

    @Test fun `completing with the authoritative text does not repeat what already streamed`() {
        val s = state()
        assertEquals(listOf("One."), enqueued(s.feed("One.")))
        // Only the not-yet-streamed part is said; chunks are trimmed, hence "Two." not " Two.".
        assertEquals(listOf("Two."), enqueued(s.complete("One. Two.")))
    }
}
