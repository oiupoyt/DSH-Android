package io.github.jackwener.dsh.wake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of offline dictation: PCM16 -> float conversion (sign, endianness, scale) and the
 * partial/final accumulator. No Android and no AudioRecord, so this runs on the JVM.
 */
class OfflineDictationTest {
    // ── PCM16 -> float ──────────────────────────────────────────────────────

    @Test fun `16-bit PCM is little-endian`() {
        // 0x0100 little-endian is 1, so 1/32768; 0x0001 big-endian would be 256/32768 (~0.0078).
        assertEquals(1f / 32768f, Pcm16.bytesToFloats(byteArrayOf(0x01, 0x00))[0], 0f)
        assertEquals(256f / 32768f, Pcm16.bytesToFloats(byteArrayOf(0x00, 0x01))[0], 0f)
    }

    @Test fun `the PCM16 extremes map to minus one and just under one`() {
        // -32768 is the only value that maps to exactly -1; +32767 stops just short of 1.
        assertEquals(-1f, Pcm16.bytesToFloats(byteArrayOf(0x00, 0x80.toByte()))[0], 0f)
        assertEquals(32767f / 32768f, Pcm16.bytesToFloats(byteArrayOf(0xFF.toByte(), 0x7F))[0], 0f)
        assertEquals(0f, Pcm16.bytesToFloats(byteArrayOf(0x00, 0x00))[0], 0f)
    }

    @Test fun `converted samples never leave the minus one to one range`() {
        val bytes = ByteArray(2048) { (it * 37).toByte() }
        assertTrue(Pcm16.bytesToFloats(bytes).all { it >= -1f && it <= 1f })
    }

    @Test fun `an odd trailing byte is ignored instead of shifting every sample`() {
        // Three bytes: one complete sample plus a stray byte that must not become a sample.
        assertArrayEquals(floatArrayOf(1f / 32768f), Pcm16.bytesToFloats(byteArrayOf(0x01, 0x00, 0x02)), 0f)
    }

    @Test fun `shorts convert through the same scale`() {
        assertArrayEquals(
            floatArrayOf(0f, 1f / 32768f, -1f, 32767f / 32768f),
            Pcm16.shortsToFloats(shortArrayOf(0, 1, -32768, 32767), 4),
            0f,
        )
    }

    @Test fun `shorts only convert the count that was read`() {
        assertArrayEquals(floatArrayOf(0f, -1f), Pcm16.shortsToFloats(shortArrayOf(0, -32768, 12345), 2), 0f)
    }

    // ── partial/final accumulator ───────────────────────────────────────────

    @Test fun `a partial is published only when the text changed`() {
        val state = DictationState()
        assertEquals("hello", state.partial("hello"))
        assertNull("same text must not be repeated", state.partial("hello"))
        assertEquals("hello world", state.partial("hello world"))
    }

    @Test fun `blank partials are not published`() {
        val state = DictationState()
        assertNull(state.partial(""))
        assertNull(state.partial("   "))
    }

    @Test fun `the endpoint result is the finished utterance, trimmed`() {
        val state = DictationState()
        state.partial("open the set")
        assertEquals("open the settings", state.endpoint("  open the settings  "))
    }

    @Test fun `an endpoint with nothing heard yields nothing`() {
        val state = DictationState()
        assertNull(state.endpoint(""))
        assertNull(state.endpoint("   "))
    }

    @Test fun `a new utterance publishes its first partial after an endpoint`() {
        val state = DictationState()
        assertEquals("first", state.endpoint("first"))
        assertEquals("second", state.partial("second"))
    }

    @Test fun `reset forgets the last partial`() {
        val state = DictationState()
        assertEquals("abc", state.partial("abc"))
        state.reset()
        assertEquals("abc", state.partial("abc"))
    }
}
