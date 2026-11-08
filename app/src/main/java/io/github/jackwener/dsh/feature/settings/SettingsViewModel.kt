package io.github.jackwener.dsh.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.data.BusySend
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.data.ThemeMode
import io.github.jackwener.dsh.core.data.WorkDetails
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.data.Settings as DshSettings
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.UsageDao
import io.github.jackwener.dsh.core.data.UsageEntity
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.diagnostics.DiagnosticsInput
import io.github.jackwener.dsh.core.diagnostics.DiagnosticsReport
import io.github.jackwener.dsh.core.diagnostics.ProviderFacts
import io.github.jackwener.dsh.core.provider.HeaderRow
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.appRequestHeaders
import io.github.jackwener.dsh.core.provider.appUserAgent
import io.github.jackwener.dsh.core.provider.headerRowsOf
import io.github.jackwener.dsh.core.provider.planHeaders
import io.github.jackwener.dsh.core.provider.resolveHeaderRows
import io.github.jackwener.dsh.core.provider.VerifyFailure
import io.github.jackwener.dsh.core.provider.VerifyOutcome
import io.github.jackwener.dsh.core.provider.API_CHAT
import io.github.jackwener.dsh.core.provider.probeModel
import io.github.jackwener.dsh.core.provider.verifyCandidates
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.FloatingBallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class DoctorCheck(val title: String, val ok: Boolean?, val detail: String, val fix: Intent? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val engine: EngineManager,
    /** Engine restarts go through the store, so the runs they kill end as stopped (not spinning forever). */
    private val sessions: io.github.jackwener.dsh.core.session.SessionStore,
    val shizuku: ShizukuShell,
    private val shizukuInstaller: io.github.jackwener.dsh.core.platform.ShizukuInstaller,
    val account: io.github.jackwener.dsh.core.account.AccountRepository,
    usage: UsageDao,
    private val sessionDao: io.github.jackwener.dsh.core.data.SessionDao,
    private val agentScreen: io.github.jackwener.dsh.core.capability.AgentScreen,
    val updater: io.github.jackwener.dsh.core.update.AppUpdater,
) : ViewModel() {
    val settings: StateFlow<DshSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, DshSettings())
    val usage: StateFlow<List<UsageEntity>> = usage.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val engineState: StateFlow<EngineState> = engine.state
    val doctor = MutableStateFlow<List<DoctorCheck>>(emptyList())
    val keyStatus = MutableStateFlow<String?>(null)

    val hasKey get() = secrets.read() != null
    val hasKeyFlow = MutableStateFlow(secrets.read() != null)
    fun clearKey() = viewModelScope.launch { secrets.clear(); hasKeyFlow.value = false; sessions.restartEngine() }
    val maskedKey get() = secrets.read()?.let { it.take(5) + "…" + it.takeLast(4) }

    fun setGroup(g: ToolGroup, on: Boolean) = viewModelScope.launch { repo.setGroupEnabled(g, on) }
    suspend fun saveMode(m: io.github.jackwener.dsh.core.model.SessionMode) = repo.saveMode(m)
    suspend fun deleteMode(id: String) = repo.deleteMode(id)
    /** [provider] "" = DeepSeek. */
    fun setModel(provider: String, m: String) = viewModelScope.launch { repo.setModel(provider, m) }

    // ── third-party providers ──
    // Saving changes the engine's patch layer / key environment: the engine picks it up on its next
    // idle restart (EngineManager.profileFingerprint), so nothing running is interrupted here.
    /**
     * Saves [p]; a non-blank [key] replaces the stored key (Keystore-encrypted, never in settings).
     * [headerRows] (the editor's 额外请求头) replace [p]'s headers: secret-looking values go to
     * SecretStore like the key (planHeaders); null keeps [p.headers] as they are.
     */
    suspend fun saveProvider(p: ProviderConfig, key: String?, headerRows: List<HeaderRow>? = null) {
        val k = key?.trim().orEmpty()
        val previous = repo.current().providers.firstOrNull { it.id == p.id }
        val plan = headerRows?.let { planHeaders(p.id, it, previous) }
        withContext(Dispatchers.IO) {
            if (k.isNotEmpty()) secrets.write(p.secretName, k)
            plan?.write?.forEach { (name, value) -> secrets.write(name, value) }
            plan?.clear?.forEach { secrets.clear(it) }
        }
        repo.saveProvider(p.copy(hasKey = p.hasKey || k.isNotEmpty(), headers = plan?.headers ?: p.headers))
    }
    fun deleteProvider(p: ProviderConfig) = viewModelScope.launch {
        withContext(Dispatchers.IO) { secrets.clear(p.secretName); p.headers.filter { it.secret }.forEach { secrets.clear(p.headerSecretName(it.name)) } }
        repo.deleteProvider(p.id)
    }
    /** Headers of the app's own requests to a provider (listing, connection test): the editor's rows or [p]'s stored ones. */
    private fun requestHeaders(existing: ProviderConfig?, rows: List<HeaderRow>?, sessionHeader: String?): Map<String, String> =
        appRequestHeaders(resolveHeaderRows(rows ?: headerRowsOf(existing), existing) { secrets.read(it) }, sessionHeader, appUserAgent(io.github.jackwener.dsh.BuildConfig.VERSION_NAME))
    fun setProviderEnabled(p: ProviderConfig, on: Boolean) = viewModelScope.launch { repo.saveProvider(p.copy(enabled = on)) }
    /** `GET {baseUrl}/models` with [key] (or the stored key of [existing]); also the connection test. */
    suspend fun fetchModels(baseUrl: String, key: String?, existing: ProviderConfig?, headerRows: List<HeaderRow>? = null, sessionHeader: String? = existing?.sessionHeader): Result<io.github.jackwener.dsh.core.provider.FetchedModels<io.github.jackwener.dsh.core.provider.RemoteModel>> = withContext(Dispatchers.IO) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.takeIf { it.hasKey }?.let { secrets.read(it.secretName) }
        val headers = requestHeaders(existing, headerRows, sessionHeader)
        io.github.jackwener.dsh.core.provider.fetchModelsResolving(baseUrl, k) { u, kk -> io.github.jackwener.dsh.core.provider.fetchModels(u, kk, headers) }
    }
    private fun keyFor(existing: ProviderConfig?, typed: String?): String? =
        typed?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.takeIf { it.hasKey }?.let { secrets.read(it.secretName) }

    /** 测试: real chat requests with a tool on protocol [api], [candidates] in order (ModelVerify.kt). [onTesting] runs on an IO thread. */
    suspend fun verifyModels(baseUrl: String, key: String?, existing: ProviderConfig?, candidates: List<String>, onTesting: (String) -> Unit,
        headerRows: List<HeaderRow>? = null, sessionHeader: String? = existing?.sessionHeader, api: String = existing?.api ?: API_CHAT): VerifyOutcome =
        withContext(Dispatchers.IO) {
            val k = keyFor(existing, key)
            // A fresh session id per probe: each test call stands alone (appRequestHeaders).
            verifyCandidates(candidates, onTesting = onTesting) { probeModel(baseUrl, k, it, requestHeaders(existing, headerRows, sessionHeader), api) }
        }

    /** Saves the editor's provider and, when [main] is set (a model that passed 测试), makes that model the main model. */
    suspend fun saveEdited(p: ProviderConfig, key: String?, main: String?, headerRows: List<HeaderRow>? = null) {
        saveProvider(p, key, headerRows)
        if (main != null) repo.setModel(p.id, main)
    }

    /** A single-model test from the provider list (重新测试) or the default-model picker; null when idle. */
    data class ModelCheck(val provider: String, val model: String, val busy: Boolean, val ok: Boolean = false, val message: String? = null)
    val modelCheck = MutableStateFlow<ModelCheck?>(null)

    /**
     * Tests [model] of [p] the way the editor's 测试 does and records the result. [select]: the user picked
     * it as the main model; a verified model switches at once, an untested one only after it
     * passes (or when the provider is unreachable right now: switched, flagged as untested).
     */
    fun checkModel(p: ProviderConfig, model: String, select: Boolean) = viewModelScope.launch {
        if (modelCheck.value?.busy == true) return@launch
        if (select && model in p.verified) { modelCheck.value = null; repo.setModel(p.id, model); return@launch }
        modelCheck.value = ModelCheck(p.id, model, busy = true)
        val f = withContext(Dispatchers.IO) { probeModel(p.baseUrl, keyFor(p, null), model, requestHeaders(p, null, p.sessionHeader), p.api) }
        repo.updateProvider(p.id) { cur -> cur.copy(verified = if (f == null) (cur.verified + model).distinct() else cur.verified - model) }
        modelCheck.value = when {
            f == null -> {
                if (select) repo.setModel(p.id, model)
                ModelCheck(p.id, model, busy = false, ok = true, message = context.getString(R.string.prov_test_ok, model))
            }
            select && f is VerifyFailure.Network -> {
                repo.setModel(p.id, model)
                ModelCheck(p.id, model, busy = false, message = context.getString(R.string.model_pick_offline, model, describeFailure(context, f)))
            }
            select -> ModelCheck(p.id, model, busy = false, message = context.getString(R.string.model_pick_failed, model, describeFailure(context, f)))
            else -> ModelCheck(p.id, model, busy = false, message = describeFailure(context, f))
        }
    }

    fun setEffort(e: Effort) = viewModelScope.launch { repo.setEffort(e) }
    /** 推理强度 of a third-party model as the default for new conversations (#46/#53; [level] null = the model's default). */
    fun setReasoningChoice(provider: String, model: String, level: String?) = viewModelScope.launch { repo.setReasoningChoice(provider, model, level) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { repo.setTheme(t) }
    fun setWorkDetails(w: WorkDetails) = viewModelScope.launch { repo.setWorkDetails(w) }
    fun setBusySend(b: BusySend) = viewModelScope.launch { repo.setBusySend(b) }
    fun setDefaultPreset(p: PermissionPreset) = viewModelScope.launch { repo.setDefaultPreset(p) }
    fun setAllowExternalApps(on: Boolean) = viewModelScope.launch { repo.setAllowExternalApps(on) }
    fun setVoiceOnAssist(on: Boolean) = viewModelScope.launch { repo.setVoiceOnAssist(on) }
    fun setAutoUpdateCheck(on: Boolean) = viewModelScope.launch { repo.setAutoUpdateCheck(on) }
    fun setEngineKeepAlive(on: Boolean) = viewModelScope.launch { engine.setKeepAlive(on) }
    fun setAgentScreen(on: Boolean) { viewModelScope.launch { repo.setAgentScreen(on) } }
    /** Only the degraded (untrusted) agent screen works on this phone (#60); the setting says what that means. */
    val agentScreenDegraded: Boolean get() = agentScreen.degradedReason != null
    fun restartEngine() = viewModelScope.launch { sessions.restartEngine() }
    val engineLog: String? get() = io.github.jackwener.dsh.core.engine.EngineDiagnostics.logTail(context, 4000)
    val lastEngineExit: String? get() = ReliabilityDoctor.lastExitText(context)
    /** Bumped on resume so permission rows re-check their status. */
    val permissionTick = MutableStateFlow(0)
    fun setBall(on: Boolean) = viewModelScope.launch { repo.setFloatingBall(on); FloatingBallService.setEnabled(context, on) }
    fun setBallStatus(on: Boolean) = viewModelScope.launch { repo.setBallStatus(on) }

    // ── 免提语音 (Voice) ──
    /**
     * Applies one Voice change: persists the whole blob in a single write, then starts or stops
     * the services so the switch takes effect immediately rather than on the next launch.
     */
    fun updateVoice(transform: (io.github.jackwener.dsh.wake.VoiceSettings) -> io.github.jackwener.dsh.wake.VoiceSettings) =
        viewModelScope.launch {
            val next = transform(io.github.jackwener.dsh.wake.VoiceSettings.from(repo.current().voice)).clamped()
            repo.setVoice(next.toStored())
            // The master switch gates both: off releases the mic and the TTS engine right away.
            io.github.jackwener.dsh.wake.WakeWordService.setEnabled(context, next.shouldRunWakeService)
            io.github.jackwener.dsh.wake.TalkbackService.setEnabled(context, next.shouldSpeak)
        }
    fun setLimits(daily: Long, session: Long) = viewModelScope.launch { repo.setLimits(daily, session) }
    fun toggleNotificationApp(pkg: String) = viewModelScope.launch {
        val cur = settings.value.notificationBlocked
        repo.setNotificationBlocked(if (pkg in cur) cur - pkg else cur + pkg)
    }

    // ── 复制诊断信息 ──
    /** The report shown in the diagnostics sheet; null = closed. Built on request, never sent anywhere by the app. */
    val diagnostics = MutableStateFlow<String?>(null)
    fun openDiagnostics() = viewModelScope.launch { diagnostics.value = buildDiagnostics() }
    fun closeDiagnostics() { diagnostics.value = null }

    /** Collects the facts for [DiagnosticsReport]: versions, device, access states, routes (hosts only), recent run ends, engine errors. */
    suspend fun buildDiagnostics(): String {
        val st = settings.value
        val shell = shizuku.access.refresh()
        val screenWhy = runCatching { agentScreen.unavailableReason() }.getOrElse { it.message }
        return withContext(Dispatchers.IO) {
            fun granted(id: String) = runCatching { PermissionCatalog.byId(id)?.check?.invoke(context) == true }.getOrDefault(false)
            val choice = st.choice
            val roles = mutableListOf<Pair<String, String>>()
            roles += "main model" to (choice.provider?.let { p -> "${p.id} / ${choice.model}" + if (choice.model in p.verified) " (passed test)" else " (not tested)" }
                ?.let { it + ", effort ${st.reasoningEffort ?: "default"}" }
                ?: "DeepSeek / ${choice.model}, effort ${st.effort.id}")
            if (st.provider.isNotEmpty() && choice.isDeepSeek) roles += "saved selection" to "${st.provider} / ${st.model} (unavailable, DeepSeek used instead)"
            val runs = runCatching { sessionDao.recentRunEnds(DiagnosticsReport.RUNS) }.getOrDefault(emptyList())
                .mapNotNull { DiagnosticsReport.outcomeOf(it.type, it.time, it.json) }
            DiagnosticsReport.build(DiagnosticsInput(
                appVersion = io.github.jackwener.dsh.BuildConfig.VERSION_NAME,
                versionCode = io.github.jackwener.dsh.BuildConfig.VERSION_CODE.toLong(),
                buildType = io.github.jackwener.dsh.BuildConfig.BUILD_TYPE,
                engineVersion = runCatching { context.assets.open("engine-version.txt").bufferedReader().use { it.readText().trim() } }.getOrNull(),
                device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidRelease = android.os.Build.VERSION.RELEASE,
                sdk = android.os.Build.VERSION.SDK_INT,
                abis = android.os.Build.SUPPORTED_ABIS.toList(),
                shizuku = shizuku.access.describe(shell),
                accessibility = granted("accessibility"),
                overlay = granted("overlay"),
                batteryExempt = granted("battery"),
                notifications = granted("notifications"),
                agentScreenUnavailable = screenWhy,
                agentScreenSetting = st.agentScreen,
                agentScreenError = agentScreen.lastCreateError,
                agentScreenDegraded = agentScreen.degradedReason,
                deepseekCredential = when { hasKey -> "api key"; account.signedIn -> "account"; else -> "none" },
                roles = roles,
                providers = st.providers.map { ProviderFacts(it.id, it.preset, it.enabled, it.baseUrl, it.models, it.verified, it.interfaceType) },
                runs = runs,
                engineState = when (val e = engine.state.value) { is EngineState.Failed -> "failed: " + e.message; else -> e.javaClass.simpleName.lowercase() },
                engineErrors = DiagnosticsReport.errorLines(io.github.jackwener.dsh.core.engine.EngineDiagnostics.logTail(context, 20_000)),
                lastEngineExit = io.github.jackwener.dsh.core.engine.EngineDiagnostics.exitRecords(context).firstOrNull()?.summary,
                generatedAt = System.currentTimeMillis(),
            ))
        }
    }

    /** Validates against DeepSeek's /models before saving. Returns null on success, else the error. */
    suspend fun saveKey(key: String): String? {
        keyStatus.value = "checking"
        val err = validateKey(key.trim())
        keyStatus.value = err
        if (err == null) { secrets.write(key.trim()); hasKeyFlow.value = true; sessions.restartEngine() }
        return err
    }

    /** (label, package) of user apps for the notification whitelist; null while loading. */
    val installedApps = MutableStateFlow<List<Pair<String, String>>?>(null)
    fun loadInstalledApps() = viewModelScope.launch {
        if (installedApps.value != null) return@launch
        installedApps.value = withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("messaging") || it.packageName.contains("mms") }
                .map { context.packageManager.getApplicationLabel(it).toString() to it.packageName }
                .sortedBy { it.first.lowercase() }
        }
    }

    fun runDoctor() = viewModelScope.launch {
        val zh = context.resources.configuration.locales[0].language == "zh"
        val checks = mutableListOf<DoctorCheck>()
        PermissionCatalog.forGroups(settings.value.enabledGroups).forEach {
            checks += DoctorCheck(it.title(zh), it.check(context), it.why(zh), it.intent(context))
        }
        val shell = shizuku.access.refresh()
        checks += DoctorCheck("Shizuku", if (shell == io.github.jackwener.dsh.core.platform.ShellState.NotInstalled) null else shell.ready,
            listOfNotNull(shellStateLabel(context, shell), shellStateHint(context, shell)).joinToString(" · "), shellFixIntent(shizuku.access, shell, shizukuInstaller.bundled))
        agentScreen.lastCreateError?.let { checks += DoctorCheck(context.getString(R.string.agent_screen_doctor), false, context.getString(R.string.agent_screen_create_failed, it)) }
            ?: agentScreen.degradedReason?.let { checks += DoctorCheck(context.getString(R.string.agent_screen_doctor), true, context.getString(R.string.agent_screen_degraded)) }
        val signedIn = account.signedIn
        val third = settings.value.choice.provider
        checks += DoctorCheck(if (zh) "模型凭据" else "Model credential", hasKey || signedIn || third != null, when {
            third != null -> third.name + " / " + settings.value.choice.model
            hasKey -> "API Key " + (maskedKey ?: "")
            signedIn -> if (zh) "DeepSeek 账号" else "DeepSeek account"
            else -> if (zh) "未登录，也未设置 API Key" else "Not signed in, no API key"
        })
        val engineDetail = when (val s = engine.state.value) {
            EngineState.Ready -> (if (zh) "运行中，启动用时 " else "Running, started in ") + "${engine.startupMillis} ms"
            is EngineState.Failed -> s.message
            else -> if (zh) "空闲（按需启动）" else "Idle (starts on demand)"
        }
        checks += DoctorCheck(if (zh) "Agent 引擎" else "Agent engine", engine.state.value !is EngineState.Failed, engineDetail)
        checks += DoctorCheck(if (zh) "存储空间" else "Storage", (context.filesDir.usableSpace > 500_000_000L),
            "${context.filesDir.usableSpace / 1_000_000} MB free")
        checks += withContext(Dispatchers.IO) { ReliabilityDoctor.checks(context, zh) }
        doctor.value = checks
    }

    private suspend fun validateKey(key: String): String? = withContext(Dispatchers.IO) {
        if (key.length < 20) return@withContext "too short"
        runCatching {
            val c = URL("https://api.deepseek.com/models").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $key"); c.connectTimeout = 10_000; c.readTimeout = 10_000
            val code = c.responseCode
            c.disconnect()
            if (code == 200) null else "HTTP $code"
        }.getOrElse { it.message ?: "network error" }
    }

    /** DSH holds the assistant role (默认数字助理). */
    fun isDefaultAssistant(): Boolean = runCatching {
        context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }.getOrDefault(false)

    /** The default-apps page (where the assistant picker lives on most ROMs), then voice input, then Settings. */
    fun openAssistantSettings(from: Context) {
        listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_SETTINGS).firstOrNull { action ->
            runCatching { from.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
