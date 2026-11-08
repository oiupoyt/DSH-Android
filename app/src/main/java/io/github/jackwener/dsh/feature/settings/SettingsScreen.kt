package io.github.jackwener.dsh.feature.settings

import android.content.Intent
import io.github.jackwener.dsh.core.platform.AppLanguage
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.BuildConfig
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.session.cacheHitRate
import io.github.jackwener.dsh.wake.VoiceSettings
import io.github.jackwener.dsh.core.data.BusySend
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.data.WorkDetails
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.findActivity
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.CodeCard
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.DTopBar
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.FilterPill
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.feature.conversation.PresetUi
import io.github.jackwener.dsh.nav.AppNotesRoute
import io.github.jackwener.dsh.nav.ModeRoute
import io.github.jackwener.dsh.nav.NotifAppsRoute
import io.github.jackwener.dsh.nav.ProviderRoute
import io.github.jackwener.dsh.nav.Route
import io.github.jackwener.dsh.nav.SectionRoute
import kotlinx.coroutines.launch

private fun openKeyPage(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun isZh(): Boolean = LocalConfiguration.current.locales[0].language == "zh"

enum class SettingsSection(val label: Int, val icon: Int) {
    Account(R.string.acct_nav, R.drawable.dsw_user_outline), General(R.string.set_general, R.drawable.dsw_settings_outline),
    Model(R.string.set_model, R.drawable.dsw_data_outline), Agent(R.string.set_agent, R.drawable.dsw_apps_add_outline),
    Extensions(R.string.set_extensions, R.drawable.dsw_cordis_plugin_outline),
    Modes(R.string.set_modes, R.drawable.dsw_agent_preset_outline),
    Usage(R.string.set_usage, R.drawable.dsw_gauge_outline), Phone(R.string.set_phone, R.drawable.dsw_shield_outline),
    About(R.string.set_about, R.drawable.dsw_info_outline);
    companion object {
        /** null (blank / unknown) = the section index. 网页搜索 was folded into 能力. */
        fun of(name: String?): SettingsSection? =
            if (name.equals("WebSearch", true)) Agent else entries.firstOrNull { it.name.equals(name, true) }
    }
}

/**
 * A settings page: top bar (Back = up one level, the same as system Back) over one scrolling
 * column. Every settings page is its own destination (nav.Routes), so there is no in-page back logic.
 */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Dsh.colors.bgBase).statusBarsPadding()) {
        DTopBar(title, onBack)
        // IME insets first so focused fields and their buttons stay above the keyboard.
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            content()
        }
    }
}

/** dsh settings on a phone: a section index (one card of rows with chevrons); each section is its own page. */
@Composable
fun SettingsIndexScreen(onBack: () -> Unit, open: (Route) -> Unit, vm: SettingsViewModel = hiltViewModel()) =
    SettingsPage(stringResource(R.string.settings), onBack) {
        SettingsIndex(vm.settings.collectAsStateWithLifecycle().value.extUpdateBadge) { open(SectionRoute(it)) }
    }

/** One settings section page; its sub-pages are opened with [open]. [onTop]: the current page (sheets show on one page only). */
@Composable
fun SettingsSectionScreen(section: SettingsSection, onBack: () -> Unit, open: (Route) -> Unit, onTop: Boolean = true, vm: SettingsViewModel = hiltViewModel()) =
    SettingsPage(stringResource(section.label), onBack) {
        when (section) {
            SettingsSection.Account -> {
                val hasKey by vm.hasKeyFlow.collectAsStateWithLifecycle()
                Spacer(Modifier.height(8.dp))
                io.github.jackwener.dsh.feature.account.AccountSettingsSection(vm.account, usingApiKey = hasKey)
            }
            SettingsSection.General -> GeneralSection(vm)
            SettingsSection.Model -> ModelSection(vm) { open(ProviderRoute(it.takeUnless { id -> id == NEW_PROVIDER })) }
            SettingsSection.Agent -> AgentSection(vm) { open(AppNotesRoute) }
            SettingsSection.Extensions -> io.github.jackwener.dsh.feature.extensions.ExtensionsSection(open, onTop)
            SettingsSection.Modes -> ModesSection(vm) { open(ModeRoute(it.takeUnless { id -> id == NEW_MODE })) }
            SettingsSection.Usage -> UsageSection(vm)
            SettingsSection.Phone -> PhoneSection(vm) { open(NotifAppsRoute) }
            SettingsSection.About -> AboutSection(vm)
        }
    }

/** 模型服务 → a provider: the preset picker (a preset opens the editor as the next level), or the editor; [onDone] returns to 模型服务. */
@Composable
fun ProviderScreen(route: ProviderRoute, onBack: () -> Unit, open: (Route) -> Unit, onDone: () -> Unit, vm: SettingsViewModel = hiltViewModel()) =
    SettingsPage(stringResource(R.string.prov_title), onBack) {
        ProviderPage(vm, route.id ?: NEW_PROVIDER, route.preset, onPreset = { open(ProviderRoute(preset = it)) }, onDone = onDone)
    }

/** 会话模式 → a mode (view / edit / new); [onReplace] swaps this page for another mode (复制). */
@Composable
fun ModeScreen(route: ModeRoute, onBack: () -> Unit, onReplace: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val id = route.id ?: NEW_MODE
    val title = if (id == NEW_MODE) stringResource(R.string.mode_new)
        else if (io.github.jackwener.dsh.core.model.SessionModes.builtIns.any { it.id == id }) stringResource(R.string.mode_view_title) else stringResource(R.string.mode_edit_title)
    SettingsPage(title, onBack) { ModePage(vm, id, onDone = onBack, onOpen = onReplace) }
}

@Composable
private fun SettingsIndex(extUpdates: Int, onOpen: (SettingsSection) -> Unit) {
    val c = Dsh.colors
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsSection.entries.forEachIndexed { i, s ->
            SettingsRow(stringResource(s.label), divider = i > 0, onClick = { onOpen(s) },
                leading = { DIcon(s.icon, size = 18.dp, tint = c.labelSecondary) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 扩展: "N 个可更新" (updates are never automatic, §7.3).
                        if (s == SettingsSection.Extensions && extUpdates > 0) Tag(stringResource(R.string.ext_updates_badge, extUpdates), Tone.Info)
                        DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary)
                    }
                })
        }
    }
}

/** Re-evaluates on every ON_RESUME (coming back from a system settings page). */
@Composable
private fun resumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
private fun GeneralSection(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tick = resumeTick()
    val canOverlay = remember(tick) { Settings.canDrawOverlays(context) }
    val isAssistant = remember(tick) { vm.isDefaultAssistant() }
    // The floating ball needs "display over other apps" first; it is only switched on once granted.
    var wantBall by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canOverlay) { if (wantBall && canOverlay) { wantBall = false; vm.setBall(true) } }
    val openOverlay = {
        wantBall = true
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            .onFailure { runCatching { context.startActivity(PermissionCatalog.appDetails(context)) } }
        Unit
    }
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsRow(stringResource(R.string.set_permission), stringResource(R.string.set_permission_desc), divider = false, stacked = true) {
            Segmented(PermissionPreset.entries.map { stringResource(PresetUi.label(it)) }, s.defaultPreset.ordinal, { vm.setDefaultPreset(PermissionPreset.entries[it]) })
        }
        SettingsRow(stringResource(R.string.set_appearance), stacked = true) {
            Segmented(listOf(stringResource(R.string.theme_light), stringResource(R.string.theme_dark), stringResource(R.string.theme_system)),
                when (s.theme) { ThemeMode.Light -> 0; ThemeMode.Dark -> 1; ThemeMode.System -> 2 },
                { vm.setTheme(listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.System)[it]) })
        }
        // In-app language picker (#40): 跟随系统 + the languages in locales_config (AppLanguage).
        val languages = remember { AppLanguage.supported(context) }
        val language = remember(tick) { AppLanguage.current(context) }
        SettingsRow(stringResource(R.string.set_language), stringResource(R.string.set_language_desc), stacked = true) {
            Segmented(listOf(stringResource(R.string.language_system)) + languages.map(AppLanguage::displayName), AppLanguage.selectedIndex(language, languages), { i ->
                context.findActivity()?.let { AppLanguage.set(it, if (i == 0) null else languages[i - 1]) }
            })
        }
        SettingsRow(stringResource(R.string.set_work_details), stringResource(R.string.set_work_details_desc), stacked = true) {
            Segmented(listOf(stringResource(R.string.details_compact), stringResource(R.string.details_standard), stringResource(R.string.details_detailed)),
                s.workDetails.ordinal, { vm.setWorkDetails(WorkDetails.entries[it]) })
        }
        SettingsRow(stringResource(R.string.set_busy_send), stringResource(R.string.set_busy_send_desc), stacked = true) {
            Segmented(listOf(stringResource(R.string.busy_queue), stringResource(R.string.busy_steer)), s.busySend.ordinal, { vm.setBusySend(BusySend.entries[it]) })
        }
    }
    SettingsGroupTitle(stringResource(R.string.set_entry_points))
    SettingsCard {
        val ballBlocked = (s.floatingBall || wantBall) && !canOverlay
        SettingsRow(stringResource(R.string.floating_ball), stringResource(if (ballBlocked) R.string.overlay_needed else R.string.floating_ball_desc), divider = false,
            onClick = if (ballBlocked) openOverlay else null,
            trailing = { DSwitch(s.floatingBall && canOverlay, { on -> if (on && !canOverlay) openOverlay() else { wantBall = false; vm.setBall(on) } }) })
        if (s.floatingBall && canOverlay) SettingsRow(stringResource(R.string.ball_status_setting), stringResource(R.string.ball_status_setting_desc),
            trailing = { DSwitch(s.ballStatus, { vm.setBallStatus(it) }) })
        SettingsRow(stringResource(R.string.default_assistant), stringResource(R.string.default_assistant_desc), onClick = { vm.openAssistantSettings(context) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isAssistant) Tag(stringResource(R.string.assistant_is_default), Tone.Success)
                    DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary)
                }
            })
        SettingsRow(stringResource(R.string.voice_on_assist), stringResource(R.string.voice_on_assist_desc),
            trailing = { DSwitch(s.voiceOnAssist, { vm.setVoiceOnAssist(it) }) })
        SettingsRow(stringResource(R.string.allow_external_apps), stringResource(R.string.allow_external_apps_desc),
            trailing = { DSwitch(s.allowExternalApps, { vm.setAllowExternalApps(it) }) })
    }
}

@Composable
private fun ModelSection(vm: SettingsViewModel, onProvider: (String) -> Unit) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.hasKeyFlow.collectAsStateWithLifecycle()
    val status by vm.keyStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    val masked = remember(hasKey) { vm.maskedKey }
    Spacer(Modifier.height(8.dp))
    // dsh provider card: grey fill, API key field, Cancel / Apply.
    Column(Modifier.fillMaxWidth().clip(Radius.lg).background(c.bgModule).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.github.jackwener.dsh.core.designsystem.Whale(width = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("DeepSeek", Modifier.weight(1f), style = DshType.s14m, color = c.labelPrimary)
            Tag(stringResource(if (hasKey) R.string.key_connected else R.string.key_missing), if (hasKey) Tone.Success else Tone.Warn)
        }
        Text(stringResource(R.string.api_key_label), style = DshType.xs13m, color = c.labelSecondary)
        DInput(key, { key = it }, placeholder = masked ?: "sk-…", secret = true, keyboardType = KeyboardType.Password)
        when (status) {
            null -> {}
            "checking" -> Text(stringResource(R.string.key_checking), style = DshType.xxs12, color = c.labelTertiary)
            else -> Text(stringResource(R.string.key_invalid, status!!), style = DshType.xxs12, color = c.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.key_get), Modifier.weight(1f).clip(Radius.sm).clickable { openKeyPage(context) }.padding(vertical = 4.dp),
                style = DshType.xs13, color = c.link)
            if (hasKey) DButton(stringResource(R.string.key_clear), { vm.clearKey() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            if (key.isNotEmpty()) DButton(stringResource(R.string.cancel), { key = "" }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.apply), { scope.launch { if (vm.saveKey(key) == null) key = "" } }, size = BtnSize.Sm, enabled = key.isNotBlank())
        }
    }
    ProvidersCard(vm, onProvider)
    DefaultModelCard(vm)
}

@Composable
private fun AgentSection(vm: SettingsViewModel, onAppNotes: () -> Unit) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsGroupTitle(stringResource(R.string.set_capabilities))
    Text(stringResource(R.string.set_capabilities_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        ToolGroup.entries.forEachIndexed { i, g ->
            val (title, desc) = groupLabel(g)
            SettingsRow(stringResource(title), stringResource(desc), divider = i > 0, trailing = { DSwitch(g in s.enabledGroups, { vm.setGroup(g, it) }) })
        }
    }
    SettingsCard(Modifier.padding(top = 8.dp)) {
        SettingsRow(stringResource(R.string.app_notes), stringResource(R.string.app_notes_desc), divider = false, onClick = onAppNotes,
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
    }
    SettingsGroupTitle(stringResource(R.string.set_agent_loop))
    SettingsCard {
        var daily by remember(s.dailyTokenLimit) { mutableStateOf(if (s.dailyTokenLimit > 0) s.dailyTokenLimit.toString() else "") }
        var session by remember(s.sessionTokenLimit) { mutableStateOf(if (s.sessionTokenLimit > 0) s.sessionTokenLimit.toString() else "") }
        SettingsRow(stringResource(R.string.session_limit), stringResource(R.string.limit_hint), divider = false, stacked = true) {
            DInput(session, { session = it.filter(Char::isDigit) }, placeholder = "0", keyboardType = KeyboardType.Number)
        }
        SettingsRow(stringResource(R.string.daily_limit), stringResource(R.string.limit_hint), stacked = true) {
            DInput(daily, { daily = it.filter(Char::isDigit) }, placeholder = "0", keyboardType = KeyboardType.Number)
        }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
            DButton(stringResource(R.string.save), {
                vm.setLimits(daily.toLongOrNull() ?: 0, session.toLongOrNull() ?: 0)
                android.widget.Toast.makeText(context, R.string.saved, android.widget.Toast.LENGTH_SHORT).show()
            }, size = BtnSize.Sm)
        }
    }
    WebSection(vm)
}

internal fun groupLabel(g: ToolGroup) = when (g) {
    ToolGroup.Files -> R.string.grp_files to R.string.grp_files_desc
    ToolGroup.Apps -> R.string.grp_apps to R.string.grp_apps_desc
    ToolGroup.Screen -> R.string.grp_screen to R.string.grp_screen_desc
    ToolGroup.System -> R.string.grp_system to R.string.grp_system_desc
    ToolGroup.Notifications -> R.string.grp_notif to R.string.grp_notif_desc
    ToolGroup.Comms -> R.string.grp_comms to R.string.grp_comms_desc
    ToolGroup.Media -> R.string.grp_media to R.string.grp_media_desc
    ToolGroup.Clipboard -> R.string.grp_clip to R.string.grp_clip_desc
    ToolGroup.Browser -> R.string.grp_browser to R.string.grp_browser_desc
    ToolGroup.Rules -> R.string.grp_rules to R.string.grp_rules_desc
}

@Composable
private fun WebSection(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.hasKeyFlow.collectAsStateWithLifecycle()
    // Mirrors EngineManager.webSearch: DeepSeek search needs the API key, or the account while a DeepSeek model is in use.
    val search = hasKey || s.choice.isDeepSeek
    SettingsGroupTitle(stringResource(R.string.set_web_group))
    SettingsCard {
        SettingsRow(stringResource(R.string.web_search_title), stringResource(if (search) R.string.web_search_desc else R.string.web_search_off_desc), divider = false,
            trailing = { if (search) Tag(stringResource(R.string.enabled), Tone.Success) else Tag(stringResource(R.string.web_search_needs_key), Tone.Warn) })
        SettingsRow(stringResource(R.string.web_fetch_title), stringResource(R.string.web_fetch_desc), trailing = { Tag(stringResource(R.string.enabled), Tone.Success) })
    }
}

@Composable
private fun UsageSection(vm: SettingsViewModel) {
    val c = Dsh.colors
    val usage by vm.usage.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val today = usage.firstOrNull { it.day == java.time.LocalDate.now().toString() }
    SettingsGroupTitle(stringResource(R.string.usage_today))
    SettingsCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat(stringResource(R.string.usage_input), Fmt.tokens(today?.inputTokens ?: 0))
            Stat(stringResource(R.string.usage_cached), Fmt.tokens(today?.cachedTokens ?: 0))
            Stat(stringResource(R.string.usage_output), Fmt.tokens(today?.outputTokens ?: 0))
        }
        today?.let { cacheHitRate(it.cachedTokens, it.inputTokens) }?.let { SettingsRow(stringResource(R.string.usage_cache_hit), Fmt.percent(it)) }
        if (s.dailyTokenLimit > 0) SettingsRow(stringResource(R.string.daily_limit), Fmt.tokens(s.dailyTokenLimit))
    }
    SettingsGroupTitle(stringResource(R.string.usage_30d))
    SettingsCard {
        val max = usage.maxOfOrNull { it.inputTokens + it.outputTokens }?.coerceAtLeast(1) ?: 1
        if (usage.isEmpty()) SettingsRow(stringResource(R.string.usage_none), divider = false)
        usage.forEachIndexed { i, u ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val day = remember(u.day) { runCatching { java.time.LocalDate.parse(u.day) }.getOrNull() }
                Row { Text(day?.let { stringResource(R.string.date_md, it.monthValue, it.dayOfMonth) } ?: u.day, Modifier.weight(1f), style = DshType.xs13, color = c.labelSecondary)
                    val hit = cacheHitRate(u.cachedTokens, u.inputTokens)?.let { " · " + stringResource(R.string.usage_cache_hit_short, Fmt.percent(it)) }.orEmpty()
                    Text("${Fmt.tokens(u.inputTokens + u.outputTokens)} tok$hit", style = DshType.mono12, color = c.labelTertiary) }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.borderL2)) {
                    Box(Modifier.fillMaxWidth((u.inputTokens + u.outputTokens).toFloat() / max).height(4.dp).clip(CircleShape).background(c.kindMessages))
                }
            }
            if (i < usage.lastIndex) io.github.jackwener.dsh.core.designsystem.HDivider()
        }
    }
}

@Composable
private fun Stat(label: String, value: String) = Column {
    Text(value, style = DshType.l20, color = Dsh.colors.labelPrimary)
    Text(label, style = DshType.xxs12, color = Dsh.colors.labelTertiary)
}

/** 手机权限: every Android permission the tools may need, with status and a jump to its system page. */
@Composable
private fun PhoneSection(vm: SettingsViewModel, onNotificationApps: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val zh = isZh()
    var bump by remember { mutableStateOf(0) }
    val tick = resumeTick() + bump
    // Runtime permissions denied with "don't ask again": 去授权 opens the app's settings page instead.
    var requested by remember { mutableStateOf<String?>(null) }
    var blocked by rememberSaveable { mutableStateOf(setOf<String>()) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        bump++
        val id = requested
        if (id != null && res.values.none { it } && PermissionCatalog.permanentlyDenied(context, res.keys)) blocked = blocked + id
    }
    Text(stringResource(R.string.set_phone_desc), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        PermissionCatalog.all.forEachIndexed { i, p ->
            val granted = remember(tick) { p.check(context) }
            SettingsRow(p.title(zh), p.why(zh), divider = i > 0,
                leading = { StateDot(if (granted) DotKind.Done else DotKind.Idle) },
                trailing = {
                    if (granted) Text(stringResource(R.string.granted), style = DshType.xs13, color = c.labelTertiary)
                    else DButton(stringResource(if (p.id in blocked) R.string.open_system_settings else R.string.go_grant), {
                        val rt = p.runtime
                        if (rt != null && p.id !in blocked) { requested = p.id; runtime.launch(rt.toTypedArray()) }
                        else if (rt != null) runCatching { context.startActivity(PermissionCatalog.appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        else runCatching { context.startActivity((p.intent(context) ?: PermissionCatalog.appDetails(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                })
        }
    }
    Text(stringResource(R.string.restricted_hint), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xxs12, color = c.labelTertiary)
    // 免提语音: wake word + spoken replies, in one section on this page (no separate screen).
    val voiceSettings = VoiceSettings.from(vm.settings.collectAsStateWithLifecycle().value.voice)
    io.github.jackwener.dsh.wake.VoiceSettingsSection(voiceSettings) { transform -> vm.updateVoice(transform) }
    SettingsGroupTitle(stringResource(R.string.set_more))
    SettingsCard {
        SettingsRow(stringResource(R.string.notification_apps), stringResource(R.string.notification_apps_desc), divider = false, onClick = onNotificationApps,
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
        val kind = remember { RomKeepAlive.kinds(KeepAliveLauncher.rom.vendor).first() }
        SettingsRow(stringResource(R.string.keep_alive), keepAliveHint(kind, zh), onClick = { KeepAliveLauncher.open(context, kind) },
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
    }
    SettingsGroupTitle(stringResource(R.string.shz_group))
    ShellAccessCard()
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsCard(Modifier.padding(top = 8.dp)) {
        val degraded = remember { vm.agentScreenDegraded }
        SettingsRow(stringResource(R.string.agent_screen_setting), stringResource(R.string.agent_screen_setting_desc) +
            (if (degraded) "\n" + stringResource(R.string.agent_screen_setting_desc_degraded) else ""), divider = false,
            trailing = { DSwitch(s.agentScreen, vm::setAgentScreen) })
    }
}

@Composable
private fun AboutSection(vm: SettingsViewModel) {
    val c = Dsh.colors
    val checks by vm.doctor.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.runDoctor() }
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsRow("DSH", stringResource(R.string.version, BuildConfig.VERSION_NAME), divider = false,
            leading = { io.github.jackwener.dsh.core.designsystem.Whale(width = 22.dp) })
        val s by vm.settings.collectAsStateWithLifecycle()
        io.github.jackwener.dsh.feature.update.UpdateSettingsRows(vm.updater, s.autoUpdateCheck, { vm.setAutoUpdateCheck(it) })
        SettingsRow(stringResource(R.string.engine), when (val e = engine) {
            EngineState.Ready -> stringResource(R.string.engine_ready); EngineState.Starting -> stringResource(R.string.starting_engine)
            EngineState.Stopped -> stringResource(R.string.engine_idle); is EngineState.Failed -> e.message.take(300)
        }, leading = { StateDot(when (engine) { EngineState.Ready -> DotKind.Done; is EngineState.Failed -> DotKind.Error; else -> DotKind.Idle }) },
            trailing = { DButton(stringResource(R.string.restart_engine), { vm.restartEngine(); vm.runDoctor() }, variant = BtnVariant.Outline, size = BtnSize.Sm) })
        // A plugin that keeps the engine from starting: start once with every third-party plugin paused (PLUGINS-DESIGN §7.6).
        val ext: io.github.jackwener.dsh.feature.extensions.ExtensionsViewModel = hiltViewModel()
        SettingsRow(stringResource(R.string.ext_safe_mode_start), stringResource(R.string.ext_safe_mode_start_desc),
            trailing = { DButton(stringResource(R.string.ext_safe_mode_start), { ext.startSafeMode(); vm.runDoctor() }, variant = BtnVariant.Outline, size = BtnSize.Sm) })
        SettingsRow(stringResource(R.string.about_disclaimer_title), stringResource(R.string.about_disclaimer), divider = true)
        // Attribution for the bundled official Shizuku APK (Apache-2.0); tap shows the license shipped with it.
        val shizukuLicense = remember { runCatching { vm.context.assets.open("shizuku/LICENSE").bufferedReader().use { it.readText() } }.getOrNull() }
        var showLicense by remember { mutableStateOf(false) }
        if (shizukuLicense != null) SettingsRow(stringResource(R.string.about_oss_title),
            stringResource(R.string.about_oss_shizuku, io.github.jackwener.dsh.BuildConfig.SHIZUKU_VERSION), divider = true,
            onClick = { showLicense = !showLicense },
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
        if (showLicense && shizukuLicense != null) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            CodeCard(shizukuLicense, label = "Shizuku · LICENSE (Apache-2.0)", maxHeight = 320.dp)
        }
    }
    SettingsGroupTitle(stringResource(R.string.doctor))
    SettingsCard {
        checks.forEachIndexed { i, ch ->
            SettingsRow(ch.title, ch.detail, divider = i > 0,
                leading = { StateDot(when (ch.ok) { true -> DotKind.Done; false -> DotKind.Error; null -> DotKind.Idle }) },
                onClick = ch.fix?.let { f -> { runCatching { vm.context.startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } })
        }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
            DButton(stringResource(R.string.doctor_rerun), { vm.runDoctor() }, variant = BtnVariant.Outline, size = BtnSize.Sm, icon = R.drawable.dsw_refresh_outline)
        }
    }
    // A report for bug reports (also the place 打开自检 from a failed turn leads to). Shown in full before anything leaves the page.
    val report by vm.diagnostics.collectAsStateWithLifecycle()
    SettingsCard(Modifier.padding(top = 12.dp)) {
        SettingsRow(stringResource(R.string.diag_entry), stringResource(R.string.diag_entry_desc), divider = false, onClick = { vm.openDiagnostics() },
            trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
    }
    report?.let { DiagnosticsSheet(it, vm::closeDiagnostics) }
    SettingsGroupTitle(stringResource(R.string.keep_alive))
    KeepAliveCard(vm)
    vm.engineLog?.let { log ->
        SettingsGroupTitle(stringResource(R.string.engine_log))
        CodeCard(log, label = "engine.log", maxHeight = 320.dp)
    }
    vm.lastEngineExit?.let { record ->
        SettingsGroupTitle(stringResource(R.string.engine_last_exit))
        CodeCard(record, label = "exit / tombstone", maxHeight = 320.dp)
    }
}

/** 引擎常驻, then vendor keep-alive: detected ROM, recents-lock tip and one button per setting worth changing. */
@Composable
private fun KeepAliveCard(vm: SettingsViewModel) {
    val c = Dsh.colors
    val context = LocalContext.current
    val zh = isZh()
    val tick = resumeTick()
    val rom = KeepAliveLauncher.rom
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsCard {
        SettingsRow(stringResource(R.string.engine_keep_alive), stringResource(R.string.engine_keep_alive_desc), divider = false,
            trailing = { DSwitch(s.engineKeepAlive, vm::setEngineKeepAlive) })
        SettingsRow(stringResource(R.string.keep_alive_device, rom.label(zh)),
            stringResource(R.string.keep_alive_desc) + "\n" + RomKeepAlive.recentsHint(rom.vendor).text(zh))
        RomKeepAlive.kinds(rom.vendor).forEach { kind ->
            // Re-read on every resume so coming back from system settings updates the battery row.
            val status = remember(tick, kind) { KeepAliveLauncher.status(context, kind) }
            val statusText = stringResource(when (status) {
                RomKeepAlive.RowStatus.Exempt -> R.string.keep_alive_exempt
                RomKeepAlive.RowStatus.NotExempt -> R.string.keep_alive_not_exempt
                RomKeepAlive.RowStatus.Unreadable -> R.string.keep_alive_unreadable
                RomKeepAlive.RowStatus.ManualCheck -> R.string.keep_alive_manual_check
            })
            // Vendor switches can't be read, so those rows get no dot: a grey dot read as "off". State is always spelled out in text.
            val dot = when (status) {
                RomKeepAlive.RowStatus.Exempt -> DotKind.Done
                RomKeepAlive.RowStatus.NotExempt -> DotKind.Error
                RomKeepAlive.RowStatus.Unreadable -> DotKind.Warn
                RomKeepAlive.RowStatus.ManualCheck -> null
            }
            SettingsRow(stringResource(when (kind) {
                RomKeepAlive.Kind.AutoStart -> R.string.keep_alive_autostart
                RomKeepAlive.Kind.Background -> R.string.keep_alive_background
                RomKeepAlive.Kind.Battery -> R.string.keep_alive_battery
            }), keepAliveHint(kind, zh) + if (status == RomKeepAlive.RowStatus.Exempt) "" else "\n" + statusText,
                leading = if (dot == null) null else ({ StateDot(dot) }),
                trailing = {
                    if (status == RomKeepAlive.RowStatus.Exempt) Text(statusText, style = DshType.xs13, color = c.labelTertiary)
                    else DButton(stringResource(R.string.keep_alive_open), { KeepAliveLauncher.open(context, kind) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                })
        }
    }
}

/** The vendor hint, prefixed with "App info" when no vendor screen resolves and App info is what opens. */
@Composable
private fun keepAliveHint(kind: RomKeepAlive.Kind, zh: Boolean): String {
    val context = LocalContext.current
    val vendor = KeepAliveLauncher.rom.vendor
    val hint = RomKeepAlive.hint(vendor, kind).text(zh)
    val direct = remember(kind) { kind == RomKeepAlive.Kind.Battery || KeepAliveLauncher.hasVendorScreen(context, kind) }
    return if (direct || RomKeepAlive.targets(vendor, kind).firstOrNull() == RomKeepAlive.APP_DETAILS) hint
    else stringResource(R.string.keep_alive_via_app_info, hint)
}

/** Notification whitelist: which apps' notifications the agent may read (default none). */
@Composable
fun NotificationAppsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val loaded by vm.installedApps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInstalledApps() }
    val apps = loaded.orEmpty()
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().imePadding()) {
        DTopBar(stringResource(R.string.notification_apps), onBack)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { DInput(q, { q = it }, placeholder = stringResource(R.string.search)) }
        if (loaded == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { io.github.jackwener.dsh.core.designsystem.Spinner(20.dp) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding()) {
            items(apps.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }, key = { it.second }) { (label, pkg) ->
                SettingsRow(label, pkg, trailing = { DSwitch(pkg !in s.notificationBlocked, { vm.toggleNotificationApp(pkg) }) })
            }
        }
    }
}

/**
 * First run without a credential (dsh onboarding's model step): whale + 探索未至之境, then the
 * choice — 登录 DeepSeek 账号 (primary; the sign-in dialog and the in-app sign-in screen take over) or 使用 API
 * Key (the key card). Straight into the conversation once either exists.
 */
@Composable
fun Onboarding(showKey: Boolean, onShowKey: (Boolean) -> Unit, onDone: () -> Unit, onThirdParty: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val scope = rememberCoroutineScope()
    val status by vm.keyStatus.collectAsStateWithLifecycle()
    val account = vm.account
    val accountState by account.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    LaunchedEffect(accountState.signedIn) { if (accountState.signedIn) onDone() }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.github.jackwener.dsh.core.designsystem.Whale(width = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.hero_title), style = DshType.xl24.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(500)), color = c.labelPrimary)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onb_tagline), style = DshType.s14, color = c.labelTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        SettingsCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.acct_login_title), style = DshType.base16m, color = c.labelPrimary)
                Text(stringResource(R.string.acct_login_desc), style = DshType.xs13, color = c.labelTertiary)
                DButton(stringResource(R.string.onb_sign_in), { account.startSignIn() }, Modifier.fillMaxWidth(), size = BtnSize.Lg,
                    enabled = !accountState.active)
                DButton(stringResource(R.string.onb_use_key), { onShowKey(!showKey) }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, size = BtnSize.Lg)
                if (showKey) {
                    HDividerSpaced()
                    Text(stringResource(R.string.onb_key_body), style = DshType.xs13, color = c.labelTertiary)
                    DInput(key, { key = it }, placeholder = "sk-…", secret = true, keyboardType = KeyboardType.Password)
                    when (status) {
                        null -> {}
                        "checking" -> Text(stringResource(R.string.key_checking), style = DshType.xxs12, color = c.labelTertiary)
                        else -> Text(stringResource(R.string.key_invalid, status!!), style = DshType.xxs12, color = c.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.key_get), Modifier.weight(1f).clip(Radius.sm).clickable { openKeyPage(context) }.padding(vertical = 6.dp),
                            style = DshType.xs13, color = c.link)
                        DButton(stringResource(R.string.continue_), { scope.launch { if (vm.saveKey(key) == null) onDone() } }, enabled = key.isNotBlank() && status != "checking")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onb_third_party), Modifier.clip(Radius.sm).clickable(onClick = onThirdParty).padding(8.dp), style = DshType.xs13, color = c.link)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.onb_disclaimer), style = DshType.xxs12, color = c.labelTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun HDividerSpaced() = io.github.jackwener.dsh.core.designsystem.HDivider(Modifier.padding(vertical = 4.dp))
