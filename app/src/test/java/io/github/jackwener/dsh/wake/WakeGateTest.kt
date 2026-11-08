package io.github.jackwener.dsh.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake service's decisions that need no Android: the debounce window, the retry backoff and
 * whether the service may run at all. [WakeGate] is a plain class so this runs on the JVM.
 */
class WakeGateTest {
    private var now = 0L
    private val gate = WakeGate(clock = { now })

    @Test fun `the first detection is accepted`() {
        now = 12_345
        assertTrue("nothing was accepted before", gate.acceptsDetection())
    }

    @Test fun `a detection 1499 ms after the last one is dropped`() {
        now = 10_000
        assertTrue(gate.acceptsDetection())
        now = 11_499
        assertFalse("inside the 1500 ms window", gate.acceptsDetection())
    }

    @Test fun `a detection 1500 ms after the last one is accepted`() {
        now = 10_000
        assertTrue(gate.acceptsDetection())
        now = 11_500
        assertTrue("1500 ms after the last accepted one", gate.acceptsDetection())
    }

    @Test fun `a dropped detection does not move the window`() {
        now = 1_000
        assertTrue(gate.acceptsDetection())
        now = 2_000
        assertFalse(gate.acceptsDetection())
        now = 2_500
        assertTrue("1500 ms after the last accepted one, not the last dropped one", gate.acceptsDetection())
    }

    @Test fun `the backoff climbs then stays capped`() {
        val backoff = WakeBackoff()
        assertEquals(1_000L, backoff.next())
        assertEquals(2_000L, backoff.next())
        assertEquals(5_000L, backoff.next())
        assertEquals(10_000L, backoff.next())
        assertEquals("capped, not unbounded", 10_000L, backoff.next())
    }

    @Test fun `the backoff starts over after a success`() {
        val backoff = WakeBackoff()
        backoff.next()
        backoff.next()
        assertEquals(5_000L, backoff.next())
        backoff.reset()
        assertEquals(1_000L, backoff.next())
    }

    @Test fun `the service must not run while the master switch is off`() {
        val settings = VoiceSettings(enabled = false, wakeEnabled = true)
        assertFalse(WakeGate.shouldRun(settings))
    }

    @Test fun `the service must not run while listening is off`() {
        val settings = VoiceSettings(enabled = true, wakeEnabled = false)
        assertFalse(WakeGate.shouldRun(settings))
    }

    @Test fun `the service runs with both switches on`() {
        val settings = VoiceSettings(enabled = true, wakeEnabled = true)
        assertTrue(WakeGate.shouldRun(settings))
    }
}
