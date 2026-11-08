package io.github.jackwener.dsh.wake

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.Manifest
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import kotlin.math.roundToInt

/** True when the user granted RECORD_AUDIO. Checked at render time, not from stored state. */
private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** True when the app is already exempt from battery optimisation. */
private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    runCatching { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)

/**
 * 设置 → 免提语音: the single Voice section for the wake word and spoken replies.
 *
 * Everything the feature offers lives here — no second screen and no hidden flags. Rows stay
 * visible but disabled while the master switch is off, so the cost is visible before the user
 * opts in. Every state shown comes from the running services; nothing is inferred from a toggle.
 */
@Composable
fun VoiceSettingsSection(settings: VoiceSettings, onChange: ((VoiceSettings) -> VoiceSettings) -> Unit) {
    val context = LocalContext.current
    val c = Dsh.colors
    val on = settings.enabled

    // Status straight from the services: the panel never claims to listen on its own.
    val wakeStatus by WakeWordService.status.collectAsState()
    val wakeTest by WakeWordService.test.collectAsState()
    val voiceTest by TalkbackService.testResult.collectAsState()

    var micGranted by remember { mutableStateOf(context.hasMicPermission()) }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    var batteryExempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    val screenReader = remember { context.isScreenReaderActive() }
    var explainer by rememberSaveable { mutableStateOf(false) }
    var editingPhrase by rememberSaveable { mutableStateOf(false) }
    var phraseDraft by rememberSaveable { mutableStateOf("") }

    // First enable: explain the microphone, the battery cost and the privacy promise once.
    if (explainer) AlertDialog(
        onDismissRequest = { explainer = false },
        title = { Text(stringResource(R.string.voice_explainer_title)) },
        text = { Text(stringResource(R.string.voice_explainer_body)) },
        confirmButton = {
            TextButton({ explainer = false; onChange { it.copy(enabled = true) } }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton({ explainer = false; openBatterySettings(context) }) { Text(stringResource(R.string.voice_explainer_open_battery)) }
        },
    )

    if (editingPhrase) AlertDialog(
        onDismissRequest = { editingPhrase = false },
        title = { Text(stringResource(R.string.voice_phrase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DInput(phraseDraft, { phraseDraft = it }, placeholder = VoiceSettings.DEFAULT_PHRASE, singleLine = true)
                Text(stringResource(R.string.voice_phrase_desc), style = DshType.xs13, color = c.labelTertiary)
            }
        },
        confirmButton = {
            TextButton({
                editingPhrase = false
                val phrase = phraseDraft.trim().ifEmpty { VoiceSettings.DEFAULT_PHRASE }
                onChange { it.copy(wakePhrase = phrase) }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton({ phraseDraft = VoiceSettings.DEFAULT_PHRASE }) { Text(stringResource(R.string.voice_phrase_reset)) }
                TextButton({ editingPhrase = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )

    SettingsGroupTitle(stringResource(R.string.set_voice))
    SettingsCard {
        SettingsRow(
            stringResource(R.string.voice_hands_free),
            stringResource(R.string.voice_hands_free_desc),
            divider = false,
            trailing = {
                DSwitch(on, { want ->
                    // Turning on asks for the microphone first, then shows the one-time explainer.
                    if (want && !micGranted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    if (want) explainer = true else onChange { it.copy(enabled = false) }
                })
            },
        )
    }
    if (on && !micGranted) SettingsCard(Modifier.padding(top = 8.dp)) {
        SettingsRow(
            stringResource(R.string.voice_status_blocked),
            stringResource(R.string.voice_mic_denied_fix),
            divider = false,
            leading = { StateDot(DotKind.Warn) },
            trailing = {
                DButton(stringResource(R.string.voice_grant_mic), { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    variant = BtnVariant.Outline, size = BtnSize.Sm)
            },
        )
    }

    // ── wake word ──
    SettingsGroupTitle(stringResource(R.string.voice_wake_group))
    SettingsCard {
        SettingsRow(
            stringResource(R.string.voice_phrase), stringResource(R.string.voice_phrase_desc),
            divider = false,
            onClick = if (on) ({ phraseDraft = settings.effectivePhrase; editingPhrase = true }) else null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(settings.effectivePhrase, style = DshType.s14, color = if (on) c.labelSecondary else c.labelDimmed)
                    DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary)
                }
            },
        )
        SettingsRow(stringResource(R.string.voice_sensitivity), stringResource(R.string.voice_sensitivity_desc), stacked = true) {
            SettingsSlider(
                value = settings.sensitivity,
                range = 0f..1f,
                enabled = on,
                contentDescription = stringResource(R.string.voice_sensitivity),
                onChange = { v -> onChange { it.copy(sensitivity = v) } },
            )
        }
        SettingsRow(
            stringResource(R.string.voice_status),
            divider = true,
            // Never "Active" unless the service says it is actually recording.
            trailing = { WakeStatusTag(wakeStatus, micGranted) },
        )
        SettingsRow(
            stringResource(R.string.voice_test_wake), stringResource(R.string.voice_test_wake_desc),
            trailing = {
                DButton(
                    text = when (wakeTest) {
                        WakeTestResult.Listening -> stringResource(R.string.voice_test_running)
                        else -> stringResource(R.string.voice_test_wake)
                    },
                    onClick = { WakeWordService.testPhrase(context) },
                    enabled = on && micGranted && wakeTest != WakeTestResult.Listening,
                    variant = BtnVariant.Outline, size = BtnSize.Sm,
                )
            },
        )
        VoiceTestRow(wakeTest)
    }

    if (!on) Text(stringResource(R.string.voice_off_first), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xxs12, color = c.labelTertiary)

    // ── talkback ──
    SettingsGroupTitle(stringResource(R.string.voice_talkback_group))
    SettingsCard {
        SettingsRow(
            stringResource(R.string.voice_speak_replies), stringResource(R.string.voice_speak_replies_desc),
            divider = false,
            trailing = { DSwitch(settings.shouldSpeak, { want -> onChange { it.copy(talkbackEnabled = want) } }, enabled = on) },
        )
        SettingsRow(stringResource(R.string.voice_tts_voice), stacked = true) {
            val voices = remember(on) { if (on) TalkbackService.voices(context) else emptyList() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = voices.firstOrNull { it.name == settings.talkbackVoice }
                Text(current?.label ?: stringResource(R.string.voice_tts_default), style = DshType.s14, color = if (on) c.labelSecondary else c.labelDimmed)
                if (voices.isNotEmpty()) {
                    // A plain wrap of selectable labels keeps this inside the existing card style.
                    io.github.jackwener.dsh.core.designsystem.Segmented(
                        options = (listOf(stringResource(R.string.voice_tts_default)) + voices.take(3).map { it.label }),
                        selected = (listOf(settings.talkbackVoice) + voices.take(3).map { it.name }).indexOf(settings.talkbackVoice).coerceAtLeast(0),
                        onSelect = { i -> onChange { it.copy(talkbackVoice = if (i == 0) "" else voices.take(3)[i - 1].name) } },
                    )
                }
            }
        }
        SettingsRow(stringResource(R.string.voice_rate), stacked = true) {
            SettingsSlider(settings.talkbackRate, VoiceSettings.RATE_MIN..VoiceSettings.RATE_MAX, on,
                stringResource(R.string.voice_rate)) { v -> onChange { it.copy(talkbackRate = v) } }
        }
        SettingsRow(stringResource(R.string.voice_pitch), stacked = true) {
            SettingsSlider(settings.talkbackPitch, VoiceSettings.PITCH_MIN..VoiceSettings.PITCH_MAX, on,
                stringResource(R.string.voice_pitch)) { v -> onChange { it.copy(talkbackPitch = v) } }
        }
        SettingsRow(
            stringResource(R.string.voice_test_voice),
            trailing = {
                DButton(stringResource(R.string.voice_test_voice), { TalkbackService.testVoice(context, settings) },
                    enabled = on && settings.shouldSpeak && voiceTest != VoiceTestResult.Speaking,
                    variant = BtnVariant.Outline, size = BtnSize.Sm)
            },
        )
        when (val r = voiceTest) {
            is VoiceTestResult.NoVoice -> SettingsRow(
                stringResource(R.string.voice_voice_missing), null, divider = false, leading = { StateDot(DotKind.Warn) })
            is VoiceTestResult.Failed -> SettingsRow(r.reason, null, divider = false, leading = { StateDot(DotKind.Warn) })
            else -> Unit
        }
        if (screenReader) {
            SettingsRow(stringResource(R.string.voice_screen_reader_note), null, divider = false, leading = { StateDot(DotKind.Warn) })
            SettingsRow(
                stringResource(R.string.voice_screen_reader_override), divider = false,
                trailing = {
                    DSwitch(settings.talkbackWithScreenReader, { want -> onChange { it.copy(talkbackWithScreenReader = want) } }, enabled = on)
                },
            )
        }
    }

    // ── offline dictation ──
    SettingsGroupTitle(stringResource(R.string.voice_stt_group))
    SettingsCard {
        SettingsRow(
            stringResource(R.string.voice_stt_toggle), stringResource(R.string.voice_stt_desc),
            divider = false,
            trailing = { DSwitch(settings.offlineDictation, { want -> onChange { it.copy(offlineDictation = want) } }, enabled = on) },
        )
    }

    // ── battery ──
    SettingsGroupTitle(stringResource(R.string.voice_battery_group))
    SettingsCard {
        SettingsRow(
            stringResource(R.string.voice_battery), stringResource(R.string.voice_battery_desc),
            divider = false,
            onClick = if (!batteryExempt) ({ openBatterySettings(context); batteryExempt = context.isIgnoringBatteryOptimizations() }) else null,
            trailing = {
                if (batteryExempt) Text(stringResource(R.string.voice_battery_allowed), style = DshType.xs13, color = c.labelTertiary)
                else DButton(stringResource(R.string.voice_battery_allow), { openBatterySettings(context) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            },
        )
    }

    // ── privacy ──
    SettingsGroupTitle(stringResource(R.string.voice_privacy))
    Text(stringResource(R.string.voice_desc), Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
}

/** The honest status chip: Active only while the service is actually recording. */
@Composable
private fun WakeStatusTag(status: WakeStatus, micGranted: Boolean) {
    val c = Dsh.colors
    val (dot, label, tone) = when {
        !micGranted -> Triple(DotKind.Warn, R.string.voice_status_blocked, Tone.Warn)
        status is WakeStatus.Listening -> Triple(DotKind.Done, R.string.voice_status_active, Tone.Success)
        status is WakeStatus.Starting -> Triple(DotKind.Idle, R.string.voice_status_starting, Tone.Neutral)
        status is WakeStatus.BlockedMic -> Triple(DotKind.Warn, R.string.voice_status_blocked, Tone.Warn)
        status is WakeStatus.Unavailable -> Triple(DotKind.Warn, R.string.voice_status_unavailable, Tone.Warn)
        else -> Triple(DotKind.Idle, R.string.voice_status_off, Tone.Neutral)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateDot(dot)
        if (status is WakeStatus.Unavailable) {
            Text(status.reason, style = DshType.xs13, color = c.labelTertiary)
        } else {
            Tag(stringResource(label), tone)
        }
    }
}

/** The Test button's own outcome line: a real detected / not-detected answer, never a silent no-op. */
@Composable
private fun VoiceTestRow(result: WakeTestResult) {
    val c = Dsh.colors
    val text = when (result) {
        WakeTestResult.Detected -> stringResource(R.string.voice_test_detected)
        WakeTestResult.NotDetected -> stringResource(R.string.voice_test_not_detected)
        WakeTestResult.Listening -> stringResource(R.string.voice_test_running)
        is WakeTestResult.Failed -> result.reason
        WakeTestResult.Idle -> return
    }
    val warn = result !is WakeTestResult.Detected
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateDot(if (result == WakeTestResult.Detected) DotKind.Done else if (warn) DotKind.Warn else DotKind.Idle)
        Text(text, style = DshType.xs13, color = if (result == WakeTestResult.Detected) c.success else c.labelTertiary)
    }
}

/**
 * A slider in the settings style.
 *
 * The app's design system has no slider yet, so this follows its language rather than
 * importing Material's rounded one: a hairline track, a filled span and a square thumb.
 * It reports itself to accessibility as a progress bar carrying its own description, which is
 * what lets TalkBack announce and adjust it.
 */
@Composable
fun SettingsSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    contentDescription: String,
    onChange: (Float) -> Unit,
) {
    val c = Dsh.colors
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val steps = 20
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = range.start + fraction * span,
                    range = range,
                    steps = steps - 1,
                )
            }
            .pointerInput(range, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val snapped = (f * steps).roundToInt() / steps.toFloat()
                    onChange(range.start + snapped * span)
                }
            }
            .pointerInput(range, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val snapped = (f * steps).roundToInt() / steps.toFloat()
                    onChange(range.start + snapped * span)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.borderL2))
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(if (enabled) c.brandPrimary else c.labelDimmed))
        Box(
            Modifier.fillMaxWidth(fraction),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.size(10.dp).background(if (enabled) c.brandPrimary else c.labelDimmed, Radius.xs))
        }
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
