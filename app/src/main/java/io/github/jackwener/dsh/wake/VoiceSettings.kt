package io.github.jackwener.dsh.wake

/**
 * The whole hands-free feature's state, as the Voice settings section stores it.
 *
 * This is a plain value type with no Android dependencies so the persisted surface and its
 * rules are unit-testable. [SettingsRepository][io.github.jackwener.dsh.core.data.SettingsRepository]
 * owns the storage — the same preferences datastore that already holds `floating_ball` and
 * `theme` — and converts through [toStored] / [from]. Nothing here keeps state in memory
 * across a process death: the services re-read their settings on every start.
 */
data class VoiceSettings(
    /** Master switch. Off means: service stopped, mic released, TTS shut down, no boot restart. */
    val enabled: Boolean = false,
    /** Wake word listening. Gated by [enabled]. */
    val wakeEnabled: Boolean = false,
    /** The phrase the user chose. Free text; sherpa's model decides whether it is expressible. */
    val wakePhrase: String = DEFAULT_PHRASE,
    /** 0 = least sensitive (fewest false triggers), 1 = most sensitive. Maps to [keywordsThreshold]. */
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    /** Spoken replies. Gated by [enabled]. */
    val talkbackEnabled: Boolean = false,
    /** TTS voice name, or "" for the system default. */
    val talkbackVoice: String = "",
    val talkbackRate: Float = DEFAULT_RATE,
    val talkbackPitch: Float = DEFAULT_PITCH,
    /** Speak even while a screen reader is active; off by default so nothing double-speaks. */
    val talkbackWithScreenReader: Boolean = false,
    /**
     * Transcribe with the bundled model instead of the system recogniser, so dictation works in
     * airplane mode. Off by default: it is what makes the install ~70 MB heavier.
     */
    val offlineDictation: Boolean = false,
) {
    /** The phrase to actually listen for: never blank, so the spotter always has a target. */
    val effectivePhrase: String get() = wakePhrase.trim().ifEmpty { DEFAULT_PHRASE }

    /** The foreground service is wanted only when the master switch and the wake switch are both on. */
    val shouldRunWakeService: Boolean get() = enabled && wakeEnabled

    /** Spoken replies are configured. Whether they are allowed right now also needs [willSpeak]. */
    val shouldSpeak: Boolean get() = enabled && talkbackEnabled

    /**
     * Whether a reply may be spoken now.
     *
     * A screen reader already reads the screen aloud; speaking over it is the double-speech
     * failure this feature must not have, so it suppresses talkback unless the user opted in.
     */
    fun willSpeak(screenReaderActive: Boolean): Boolean = shouldSpeak && (!screenReaderActive || talkbackWithScreenReader)

    /**
     * The `keywords_threshold` to give the keyword spotter.
     *
     * sherpa triggers when a decoded keyword's probability exceeds the threshold, so higher
     * sensitivity must lower it. The range stays clear of 0 (fires on anything) and 1 (never).
     */
    fun keywordsThreshold(): Float =
        THRESHOLD_MAX - clampedSensitivity() * (THRESHOLD_MAX - THRESHOLD_MIN)

    /** This value with every numeric field inside the range the settings UI offers. */
    fun clamped(): VoiceSettings = copy(
        sensitivity = clampedSensitivity(),
        talkbackRate = talkbackRate.coerceIn(RATE_MIN, RATE_MAX),
        talkbackPitch = talkbackPitch.coerceIn(PITCH_MIN, PITCH_MAX),
    )

    private fun clampedSensitivity() = sensitivity.coerceIn(0f, 1f).takeUnless { it.isNaN() } ?: DEFAULT_SENSITIVITY

    /** Flat string map, matching how the datastore stores primitive preferences. */
    fun toStored(): Map<String, String> = mapOf(
        KEY_ENABLED to enabled.toString(),
        KEY_WAKE to wakeEnabled.toString(),
        KEY_PHRASE to wakePhrase,
        KEY_SENSITIVITY to sensitivity.toString(),
        KEY_TALKBACK to talkbackEnabled.toString(),
        KEY_VOICE to talkbackVoice,
        KEY_RATE to talkbackRate.toString(),
        KEY_PITCH to talkbackPitch.toString(),
        KEY_WITH_SCREEN_READER to talkbackWithScreenReader.toString(),
        KEY_OFFLINE_DICTATION to offlineDictation.toString(),
    )

    companion object {
        const val DEFAULT_PHRASE = "Hey DSH"
        const val DEFAULT_SENSITIVITY = 0.5f
        const val DEFAULT_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val RATE_MIN = 0.5f
        const val RATE_MAX = 2.0f
        const val PITCH_MIN = 0.5f
        const val PITCH_MAX = 2.0f
        /** Least and most sensitive ends of the threshold slider. */
        const val THRESHOLD_MIN = 0.02f
        const val THRESHOLD_MAX = 0.50f

        // Persisted keys, in the same preferences blob as floating_ball / theme.
        const val KEY_ENABLED = "wake_enabled"
        const val KEY_WAKE = "wake_listening"
        const val KEY_PHRASE = "wake_phrase"
        const val KEY_SENSITIVITY = "wake_sensitivity"
        const val KEY_TALKBACK = "talkback_enabled"
        const val KEY_VOICE = "talkback_voice"
        const val KEY_RATE = "talkback_rate"
        const val KEY_PITCH = "talkback_pitch"
        const val KEY_WITH_SCREEN_READER = "talkback_with_screen_reader"
        const val KEY_OFFLINE_DICTATION = "offline_dictation"

        /** A stored blob back into a value; anything unreadable falls back to the default field. */
        fun from(stored: Map<String, String?>): VoiceSettings {
            val d = VoiceSettings()
            return VoiceSettings(
                enabled = stored.bool(KEY_ENABLED) ?: d.enabled,
                wakeEnabled = stored.bool(KEY_WAKE) ?: d.wakeEnabled,
                wakePhrase = stored[KEY_PHRASE] ?: d.wakePhrase,
                sensitivity = stored.float(KEY_SENSITIVITY) ?: d.sensitivity,
                talkbackEnabled = stored.bool(KEY_TALKBACK) ?: d.talkbackEnabled,
                talkbackVoice = stored[KEY_VOICE] ?: d.talkbackVoice,
                talkbackRate = stored.float(KEY_RATE) ?: d.talkbackRate,
                talkbackPitch = stored.float(KEY_PITCH) ?: d.talkbackPitch,
                talkbackWithScreenReader = stored.bool(KEY_WITH_SCREEN_READER) ?: d.talkbackWithScreenReader,
                offlineDictation = stored.bool(KEY_OFFLINE_DICTATION) ?: d.offlineDictation,
            )
        }

        /** Only the stored spellings `true` / `false` count; anything else keeps the default. */
        private fun Map<String, String?>.bool(key: String): Boolean? = when (get(key)) {
            "true" -> true
            "false" -> false
            else -> null
        }

        private fun Map<String, String?>.float(key: String): Float? = get(key)?.toFloatOrNull()
    }
}
