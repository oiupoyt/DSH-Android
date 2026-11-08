package io.github.jackwener.dsh.wake

import io.github.jackwener.dsh.core.data.decodeVoice
import io.github.jackwener.dsh.core.data.encodeVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Voice section persists into the same datastore blob as `floating_ball` / `theme`.
 * These tests pin the stored representation so a future format change cannot silently
 * reset a user's wake phrase, voice or toggles.
 */
class VoicePersistenceTest {
    @Test fun `an empty or absent blob means every default, feature off`() {
        assertEquals(VoiceSettings(), VoiceSettings.from(decodeVoice(null)).clamped())
        assertEquals(VoiceSettings(), VoiceSettings.from(decodeVoice("")).clamped())
    }

    @Test fun `a corrupt blob falls back to defaults instead of failing`() {
        assertEquals(VoiceSettings(), VoiceSettings.from(decodeVoice("{not json")).clamped())
        assertEquals(VoiceSettings(), VoiceSettings.from(decodeVoice("[1,2,3]")).clamped())
    }

    @Test fun `a full round trip survives the stored encoding`() {
        val saved = VoiceSettings(
            enabled = true, wakeEnabled = true, wakePhrase = "Okay DSH", sensitivity = 0.8f,
            talkbackEnabled = true, talkbackVoice = "en-us-x-iom-local", talkbackRate = 1.5f,
            talkbackPitch = 0.75f, talkbackWithScreenReader = true,
        )
        val reloaded = VoiceSettings.from(decodeVoice(encodeVoice(saved.toStored()))).clamped()
        assertEquals(saved, reloaded)
        assertEquals("Okay DSH", reloaded.effectivePhrase)
    }

    @Test fun `the stored keys are the documented ones`() {
        // Changing a key string silently resets users who upgrade; pin them.
        assertEquals(
            setOf(
                "wake_enabled", "wake_listening", "wake_phrase", "wake_sensitivity",
                "talkback_enabled", "talkback_voice", "talkback_rate", "talkback_pitch",
                "talkback_with_screen_reader", "offline_dictation",
            ),
            VoiceSettings().toStored().keys,
        )
    }

    @Test fun `a stored blob written by an older version keeps its values`() {
        // Only some keys present: the rest keep their defaults, the present ones are honoured.
        val partial = encodeVoice(mapOf("wake_enabled" to "true", "wake_phrase" to "Computer"))
        val s = VoiceSettings.from(decodeVoice(partial))
        assertTrue("master toggle from a partial blob", s.enabled)
        assertEquals("Computer", s.effectivePhrase)
        assertFalse("absent keys keep defaults", s.talkbackEnabled)
    }
}
