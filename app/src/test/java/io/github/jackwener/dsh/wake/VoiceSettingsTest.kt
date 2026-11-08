package io.github.jackwener.dsh.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Voice section's shared contract: defaults, clamping and the sensitivity-to-threshold mapping. */
class VoiceSettingsTest {
    @Test fun `hands-free is off by default and nothing else pretends to be on`() {
        val s = VoiceSettings()
        assertFalse("master toggle must ship off", s.enabled)
        assertFalse("wake must ship off", s.wakeEnabled)
        assertFalse("talkback must ship off", s.talkbackEnabled)
        assertFalse("screen-reader override must ship off", s.talkbackWithScreenReader)
    }

    @Test fun `the default wake phrase is Hey DSH`() {
        assertEquals("Hey DSH", VoiceSettings().wakePhrase)
    }

    @Test fun `saved values are clamped to the ranges the UI offers`() {
        val s = VoiceSettings(enabled = true, talkbackRate = 9f, talkbackPitch = 0.1f, sensitivity = 4f)
        assertEquals(VoiceSettings.RATE_MAX, s.clamped().talkbackRate, 0.0001f)
        assertEquals(VoiceSettings.PITCH_MIN, s.clamped().talkbackPitch, 0.0001f)
        assertEquals(1f, s.clamped().sensitivity, 0.0001f)
    }

    @Test fun `a blank or whitespace-only wake phrase falls back to the default`() {
        assertEquals("Hey DSH", VoiceSettings(wakePhrase = "   ").effectivePhrase)
        assertEquals("Computer", VoiceSettings(wakePhrase = "  Computer ").effectivePhrase)
    }

    @Test fun `higher sensitivity means a lower trigger threshold`() {
        // sherpa triggers when the decoded probability exceeds keywords_threshold, so a more
        // sensitive setting must lower it.
        val low = VoiceSettings(sensitivity = 0f).keywordsThreshold()
        val high = VoiceSettings(sensitivity = 1f).keywordsThreshold()
        assertTrue("sensitivity 1.0 ($high) must be below 0.0 ($low)", high < low)
        assertEquals(VoiceSettings.THRESHOLD_MIN, high, 0.0001f)
        assertEquals(VoiceSettings.THRESHOLD_MAX, low, 0.0001f)
    }

    @Test fun `the threshold stays inside the range sherpa accepts`() {
        for (step in 0..100) {
            val t = VoiceSettings(sensitivity = step / 100f).keywordsThreshold()
            assertTrue("threshold $t out of range at $step", t in 0.005f..0.995f)
        }
    }

    @Test fun `the wake service runs only when the master toggle and the wake toggle are both on`() {
        assertFalse(VoiceSettings(enabled = false, wakeEnabled = true).shouldRunWakeService)
        assertFalse(VoiceSettings(enabled = true, wakeEnabled = false).shouldRunWakeService)
        assertTrue(VoiceSettings(enabled = true, wakeEnabled = true).shouldRunWakeService)
    }

    @Test fun `talkback speaks only when the master toggle and the talkback toggle are both on`() {
        assertFalse(VoiceSettings(enabled = false, talkbackEnabled = true).shouldSpeak)
        assertTrue(VoiceSettings(enabled = true, talkbackEnabled = true).shouldSpeak)
        assertFalse(VoiceSettings(enabled = true, talkbackEnabled = false).shouldSpeak)
    }

    @Test fun `a system screen reader suppresses talkback unless explicitly overridden`() {
        val s = VoiceSettings(enabled = true, talkbackEnabled = true)
        assertFalse("screen reader active must suppress by default", s.willSpeak(screenReaderActive = true))
        assertTrue("explicit override lets it speak", s.copy(talkbackWithScreenReader = true).willSpeak(screenReaderActive = true))
        assertTrue("no screen reader: speaks", s.willSpeak(screenReaderActive = false))
    }

    @Test fun `round-trips through its stored form`() {
        val original = VoiceSettings(
            enabled = true, wakeEnabled = false, wakePhrase = "Okay DSH", sensitivity = 0.42f,
            talkbackEnabled = true, talkbackVoice = "en-us-x-iom-local", talkbackRate = 1.25f,
            talkbackPitch = 0.8f, talkbackWithScreenReader = true,
        )
        assertEquals(original, VoiceSettings.from(original.toStored()).clamped())
    }

    @Test fun `a hostile stored blob yields defaults rather than throwing`() {
        assertEquals(VoiceSettings(), VoiceSettings.from(emptyMap()).clamped())
        assertEquals(VoiceSettings(), VoiceSettings.from(mapOf("rate" to "not-a-number")).clamped())
        assertEquals(VoiceSettings(), VoiceSettings.from(mapOf("enabled" to "maybe")).clamped())
    }
}
