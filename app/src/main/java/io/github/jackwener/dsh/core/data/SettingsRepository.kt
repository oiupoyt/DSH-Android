package io.github.jackwener.dsh.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.extensions.McpServer
import io.github.jackwener.dsh.core.extensions.RegistryMode
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.SessionMode
import io.github.jackwener.dsh.core.model.SessionModes
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.provider.ModelChoice
import io.github.jackwener.dsh.core.provider.ModelReasoning
import io.github.jackwener.dsh.core.provider.ReasoningLevels
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.resolveChoice
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("settings")

enum class ThemeMode { System, Light, Dark }

/** dsh "工作步骤展示": how much process detail completed turns keep open. */
enum class WorkDetails { Compact, Standard, Detailed }

/** dsh "繁忙时的发送行为". */
enum class BusySend { Queue, Steer }

/**
 * Reasoning effort ids accepted by the DeepSeek adapter (`packages/llm/llm-deepseek/src/model-info.ts`).
 * DeepSeek only; third-party models offer the levels the engine reports for them (ModelReasoning.kt).
 */
enum class Effort(val id: String, val label: String) {
    Off("off", "Off"), Low("low", "Low"), High("high", "High"), Max("max", "Max");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: High }
}

data class Settings(
    /** Selected route: "" = DeepSeek (key or account), else a third-party provider's route id ([providers]). */
    val provider: String = "",
    val model: String = DEFAULT_MODEL,
    val effort: Effort = Effort.High,
    /** Device tool groups the user switched off (everything else is offered to the agent). */
    val disabledGroups: Set<ToolGroup> = emptySet(),
    /** Apps whose notifications DSH must not read (default: none — every app is readable). */
    val notificationBlocked: Set<String> = emptySet(),
    val floatingBall: Boolean = false,
    /** 操作时显示状态: the ball's status capsule, and the border while the agent operates the user's screen. */
    val ballStatus: Boolean = true,
    val dailyTokenLimit: Long = 0,
    val sessionTokenLimit: Long = 0,
    val theme: ThemeMode = ThemeMode.System,
    val workDetails: WorkDetails = WorkDetails.Standard,
    val busySend: BusySend = BusySend.Queue,
    val defaultPreset: PermissionPreset = PermissionPreset.Full,
    /** 允许其他 App 调用: whether the exported RUN_TASK entry accepts tasks from other apps. Off by default. */
    val allowExternalApps: Boolean = false,
    /** 唤起助手时直接语音输入: the assistant gesture opens the quick panel already listening. */
    val voiceOnAssist: Boolean = true,
    /**
     * 免提语音 (Voice): wake word + spoken replies. Stored as a flat string map in the same
     * preferences blob as [floatingBall] / [theme]; see [io.github.jackwener.dsh.wake.VoiceSettings].
     */
    val voice: Map<String, String> = emptyMap(),
    /** 在后台屏操作 App: with Shizuku, apps the agent opens run on its own virtual screen (AgentScreen). */
    val agentScreen: Boolean = true,
    /** 任务结束后自动总结经验: after a turn that worked in an app, one short model call distills App 经验 notes. */
    val reflectExperience: Boolean = true,
    /** Third-party OpenAI-compatible providers (keys live in SecretStore, never here). */
    val providers: List<ProviderConfig> = emptyList(),
    /** Third-party 推理强度 per route + model ([ReasoningLevels.key] → level id); absent = the model's default. */
    val reasoningChoices: Map<String, String> = emptyMap(),
    /** Each third-party model's levels as the engine last reported them (refreshed at every engine start). */
    val reasoningCatalog: Map<String, ModelReasoning> = emptyMap(),
    /** The user's own session modes (设置 → 会话模式); the built-ins live in [SessionModes]. */
    val modes: List<SessionMode> = emptyList(),
    /** 插件下载源 (设置 → 扩展): read by the engine's plugin-manager row at start. */
    val pluginRegistry: RegistryMode = RegistryMode.Auto,
    val pluginRegistryCustom: String = "",
    /** MCP servers (设置 → 扩展 → MCP 服务); header values live in SecretStore. */
    val mcpServers: List<McpServer> = emptyList(),
    /** Plugin updates found by the last check: the 扩展 entry's badge. */
    val extUpdateBadge: Int = 0,
    /** 自动检查更新: a background check for a new DSH release at most once a day (core/update/AppUpdater). */
    val autoUpdateCheck: Boolean = true,
    /** 引擎常驻 (#52): the engine stays up (foreground service) instead of stopping when idle. Off by default. */
    val engineKeepAlive: Boolean = false,
    /** A plugin ran a background session (heartbeat, bot) at some point: 设置 → 扩展 may suggest [engineKeepAlive]. */
    val pluginBackgroundSeen: Boolean = false,
) {
    /** Built-in then user modes, in display order. */
    val allModes: List<SessionMode> get() = SessionModes.all(modes)
    fun mode(id: String?): SessionMode = SessionModes.resolve(id, modes)

    val enabledGroups: Set<ToolGroup> get() = ToolGroup.entries.toSet() - disabledGroups
    /** The effective selection (falls back to DeepSeek when the provider/model is gone or disabled). */
    val choice: ModelChoice get() = resolveChoice(provider, model, providers)

    /** The selected third-party model's reasoning selector (null for DeepSeek, or while unknown). */
    val reasoning: ModelReasoning? get() = choice.provider?.let { ReasoningLevels.of(it, choice.model, reasoningCatalog) }
    /** The saved 推理强度 of the selected third-party model, null = its default. */
    val reasoningChoice: String? get() = choice.provider?.let { reasoningChoices[ReasoningLevels.key(it.id, choice.model)] }
    /** The level the selected third-party route sends: the saved one while the model offers it, else none. */
    val reasoningEffort: String? get() = ReasoningLevels.effective(reasoning, reasoningChoice)

    companion object { const val DEFAULT_MODEL = "deepseek-flash" }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val providerList = ListSerializer(ProviderConfig.serializer())
private val modeList = ListSerializer(SessionMode.serializer())
private val mcpList = ListSerializer(McpServer.serializer())
private val choiceMap = MapSerializer(String.serializer(), String.serializer())
private val catalogMap = MapSerializer(String.serializer(), ModelReasoning.serializer())
private val voiceBlobMap = MapSerializer(String.serializer(), String.serializer())

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val provider = stringPreferencesKey("provider")
        val providers = stringPreferencesKey("providers_json")
        val modes = stringPreferencesKey("session_modes_json")
        val model = stringPreferencesKey("model")
        val effort = stringPreferencesKey("effort")
        val disabledGroups = stringSetPreferencesKey("disabled_groups")
        val notifBlocked = stringSetPreferencesKey("notif_blocked")
        val ball = booleanPreferencesKey("floating_ball")
        val ballStatus = booleanPreferencesKey("ball_status")
        val dailyTokens = longPreferencesKey("daily_tokens")
        val sessionTokens = longPreferencesKey("session_tokens")
        val theme = stringPreferencesKey("theme")
        val workDetails = stringPreferencesKey("work_details")
        val busySend = stringPreferencesKey("busy_send")
        val preset = stringPreferencesKey("default_preset")
        val externalApps = booleanPreferencesKey("allow_external_apps")
        val agentScreen = booleanPreferencesKey("agent_screen")
        val reflect = booleanPreferencesKey("reflect_experience")
        val voiceOnAssist = booleanPreferencesKey("voice_on_assist")
        val voiceBlob = stringPreferencesKey("voice_json")
        val pluginRegistry = stringPreferencesKey("plugin_registry_mode")
        val pluginRegistryCustom = stringPreferencesKey("plugin_registry_custom")
        val mcpServers = stringPreferencesKey("mcp_servers_json")
        val extUpdateBadge = intPreferencesKey("ext_update_badge")
        val autoUpdateCheck = booleanPreferencesKey("auto_update_check")
        val engineKeepAlive = booleanPreferencesKey("engine_keep_alive")
        val pluginBackgroundSeen = booleanPreferencesKey("plugin_background_seen")
        val reasoningChoices = stringPreferencesKey("reasoning_choices_json")
        val reasoningCatalog = stringPreferencesKey("reasoning_catalog_json")
    }

    val settings: Flow<Settings> = context.store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        provider = this[K.provider] ?: "",
        providers = this[K.providers]?.let { runCatching { json.decodeFromString(providerList, it) }.getOrNull() } ?: emptyList(),
        modes = decodeModes(this[K.modes]),
        model = this[K.model] ?: Settings.DEFAULT_MODEL,
        effort = Effort.of(this[K.effort]),
        reasoningChoices = decodeChoices(this[K.reasoningChoices]),
        reasoningCatalog = this[K.reasoningCatalog]?.let { runCatching { json.decodeFromString(catalogMap, it) }.getOrNull() } ?: emptyMap(),
        disabledGroups = this[K.disabledGroups]?.mapNotNull(ToolGroup::of)?.toSet() ?: emptySet(),
        notificationBlocked = this[K.notifBlocked] ?: emptySet(),
        floatingBall = this[K.ball] ?: false,
        ballStatus = this[K.ballStatus] ?: true,
        dailyTokenLimit = this[K.dailyTokens] ?: 0,
        sessionTokenLimit = this[K.sessionTokens] ?: 0,
        theme = this[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.System,
        workDetails = this[K.workDetails]?.let { v -> WorkDetails.entries.firstOrNull { it.name == v } } ?: WorkDetails.Standard,
        busySend = this[K.busySend]?.let { v -> BusySend.entries.firstOrNull { it.name == v } } ?: BusySend.Queue,
        defaultPreset = PermissionPreset.of(this[K.preset]),
        allowExternalApps = this[K.externalApps] ?: false,
        agentScreen = this[K.agentScreen] ?: true,
        reflectExperience = this[K.reflect] ?: true,
        voiceOnAssist = this[K.voiceOnAssist] ?: true,
        voice = decodeVoice(this[K.voiceBlob]),
        pluginRegistry = RegistryMode.of(this[K.pluginRegistry]),
        pluginRegistryCustom = this[K.pluginRegistryCustom] ?: "",
        mcpServers = decodeMcp(this[K.mcpServers]),
        extUpdateBadge = this[K.extUpdateBadge] ?: 0,
        autoUpdateCheck = this[K.autoUpdateCheck] ?: true,
        engineKeepAlive = this[K.engineKeepAlive] ?: false,
        pluginBackgroundSeen = this[K.pluginBackgroundSeen] ?: false,
    )

    /** [provider] "" = DeepSeek. */
    suspend fun setModel(provider: String, model: String) = context.store.edit { it[K.provider] = provider; it[K.model] = model }
    suspend fun saveProvider(p: ProviderConfig) = context.store.edit {
        val cur = it[K.providers]?.let { s -> runCatching { json.decodeFromString(providerList, s) }.getOrNull() } ?: emptyList()
        val next = if (cur.any { c -> c.id == p.id }) cur.map { c -> if (c.id == p.id) p else c } else cur + p
        it[K.providers] = json.encodeToString(providerList, next)
    }
    /** Read-modify-write of one stored provider (no-op when it is gone), e.g. recording a test result. */
    suspend fun updateProvider(id: String, transform: (ProviderConfig) -> ProviderConfig) = context.store.edit {
        val cur = it[K.providers]?.let { s -> runCatching { json.decodeFromString(providerList, s) }.getOrNull() } ?: return@edit
        it[K.providers] = json.encodeToString(providerList, cur.map { c -> if (c.id == id) transform(c) else c })
    }
    suspend fun deleteProvider(id: String) = context.store.edit {
        val cur = it[K.providers]?.let { s -> runCatching { json.decodeFromString(providerList, s) }.getOrNull() } ?: emptyList()
        it[K.providers] = json.encodeToString(providerList, cur.filter { c -> c.id != id })
        if (it[K.provider] == id) { it[K.provider] = ""; it[K.model] = Settings.DEFAULT_MODEL }
        // A later provider may get the same route id: it starts from its models' defaults.
        it[K.reasoningChoices] = json.encodeToString(choiceMap, decodeChoices(it[K.reasoningChoices]).filterKeys { k -> !k.startsWith("$id/") })
    }
    /** Adds or replaces a user mode (built-in ids are refused: built-ins are duplicated, not edited). */
    suspend fun saveMode(m: SessionMode) {
        require(SessionModes.builtIns.none { it.id == m.id }) { "built-in mode ${m.id} cannot be edited" }
        context.store.edit {
            val cur = decodeModes(it[K.modes])
            val mode = m.copy(builtIn = false, toolsVersion = SessionModes.TOOLS_VERSION)
            val next = if (cur.any { c -> c.id == m.id }) cur.map { c -> if (c.id == m.id) mode else c } else cur + mode
            it[K.modes] = json.encodeToString(modeList, next)
        }
    }
    suspend fun deleteMode(id: String) = context.store.edit {
        it[K.modes] = json.encodeToString(modeList, decodeModes(it[K.modes]).filter { c -> c.id != id })
    }
    suspend fun setEffort(effort: Effort) = context.store.edit { it[K.effort] = effort.id }
    /** 推理强度 of one third-party model; [level] null = the model's default. */
    suspend fun setReasoningChoice(provider: String, model: String, level: String?) = context.store.edit {
        it[K.reasoningChoices] = json.encodeToString(choiceMap, withChoice(decodeChoices(it[K.reasoningChoices]), provider, model, level))
    }
    suspend fun setReasoningCatalog(catalog: Map<String, ModelReasoning>) = context.store.edit { it[K.reasoningCatalog] = json.encodeToString(catalogMap, catalog) }
    suspend fun setGroupEnabled(group: ToolGroup, enabled: Boolean) = context.store.edit {
        val cur = it[K.disabledGroups] ?: emptySet()
        it[K.disabledGroups] = if (enabled) cur - group.id else cur + group.id
    }
    suspend fun setNotificationBlocked(apps: Set<String>) = context.store.edit { it[K.notifBlocked] = apps }
    suspend fun setFloatingBall(on: Boolean) = context.store.edit { it[K.ball] = on }
    suspend fun setBallStatus(on: Boolean) = context.store.edit { it[K.ballStatus] = on }
    suspend fun setLimits(daily: Long, session: Long) = context.store.edit {
        it[K.dailyTokens] = daily; it[K.sessionTokens] = session
    }
    suspend fun setTheme(mode: ThemeMode) = context.store.edit { it[K.theme] = mode.name }
    suspend fun setWorkDetails(v: WorkDetails) = context.store.edit { it[K.workDetails] = v.name }
    suspend fun setBusySend(v: BusySend) = context.store.edit { it[K.busySend] = v.name }
    suspend fun setDefaultPreset(p: PermissionPreset) = context.store.edit { it[K.preset] = p.id }
    suspend fun setAllowExternalApps(on: Boolean) = context.store.edit { it[K.externalApps] = on }
    suspend fun setAgentScreen(on: Boolean) = context.store.edit { it[K.agentScreen] = on }
    suspend fun setReflectExperience(on: Boolean) = context.store.edit { it[K.reflect] = on }
    suspend fun setVoiceOnAssist(on: Boolean) = context.store.edit { it[K.voiceOnAssist] = on }

    /** 免提语音: replaces the stored blob (the Voice section's whole state, one write). */
    suspend fun setVoice(values: Map<String, String>) = context.store.edit {
        it[K.voiceBlob] = json.encodeToString(voiceBlobMap, values)
    }
    suspend fun setAutoUpdateCheck(on: Boolean) = context.store.edit { it[K.autoUpdateCheck] = on }
    suspend fun setEngineKeepAlive(on: Boolean) = context.store.edit { it[K.engineKeepAlive] = on }
    suspend fun setPluginBackgroundSeen() = context.store.edit { it[K.pluginBackgroundSeen] = true }

    // ── 扩展 ──
    suspend fun setPluginRegistry(mode: RegistryMode, custom: String) = context.store.edit {
        it[K.pluginRegistry] = mode.id; it[K.pluginRegistryCustom] = custom.trim()
    }
    suspend fun setExtUpdateBadge(n: Int) = context.store.edit { it[K.extUpdateBadge] = n }
    /** Adds or replaces the MCP server with [s]'s id. */
    suspend fun saveMcpServer(s: McpServer) = context.store.edit {
        val cur = decodeMcp(it[K.mcpServers])
        val next = if (cur.any { c -> c.id == s.id }) cur.map { c -> if (c.id == s.id) s else c } else cur + s
        it[K.mcpServers] = json.encodeToString(mcpList, next)
    }
    suspend fun updateMcpServer(id: String, transform: (McpServer) -> McpServer) = context.store.edit {
        it[K.mcpServers] = json.encodeToString(mcpList, decodeMcp(it[K.mcpServers]).map { c -> if (c.id == id) transform(c) else c })
    }
    suspend fun deleteMcpServer(id: String) = context.store.edit {
        it[K.mcpServers] = json.encodeToString(mcpList, decodeMcp(it[K.mcpServers]).filter { c -> c.id != id })
    }
}

internal fun decodeChoices(raw: String?): Map<String, String> =
    raw?.let { runCatching { json.decodeFromString(choiceMap, it) }.getOrNull() } ?: emptyMap()

/** [choices] with [provider] + [model] set to [level] (null removes it: the model's default). */
internal fun withChoice(choices: Map<String, String>, provider: String, model: String, level: String?): Map<String, String> {
    val k = ReasoningLevels.key(provider, model)
    return if (level == null) choices - k else choices + (k to level)
}

/** The Voice blob; an unreadable value (a future format) reads as defaults, never as an error. */
internal fun decodeVoice(raw: String?): Map<String, String> =
    raw?.let { runCatching { json.decodeFromString(voiceBlobMap, it) }.getOrNull() } ?: emptyMap()

internal fun encodeVoice(values: Map<String, String>): String = json.encodeToString(voiceBlobMap, values)

internal fun decodeMcp(raw: String?): List<McpServer> =
    raw?.let { runCatching { json.decodeFromString(mcpList, it) }.getOrNull() } ?: emptyList()

/** Stored user modes; an unreadable value (a future format) reads as none rather than failing settings. */
internal fun decodeModes(raw: String?): List<SessionMode> =
    raw?.let { runCatching { json.decodeFromString(modeList, it) }.getOrNull() }?.map(SessionModes::migrate) ?: emptyList()

internal fun encodeModes(modes: List<SessionMode>): String = json.encodeToString(modeList, modes)
