package io.github.jackwener.dsh.feature.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.jackwener.dsh.core.capability.AgentScreen
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.session.ApprovalOutcome
import io.github.jackwener.dsh.core.session.Conversation
import io.github.jackwener.dsh.core.session.PendingApproval
import io.github.jackwener.dsh.core.session.PendingQuestion
import io.github.jackwener.dsh.core.session.Projector
import io.github.jackwener.dsh.core.session.ResolvedModel
import io.github.jackwener.dsh.core.session.SessionModels
import io.github.jackwener.dsh.core.session.RowStatus
import io.github.jackwener.dsh.core.session.SessionLive
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.core.session.TakeoverAnswer
import io.github.jackwener.dsh.core.session.Trajectory
import io.github.jackwener.dsh.core.session.TurnError
import io.github.jackwener.dsh.wake.TalkbackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which representation of the session the top-bar toggle shows. */
enum class SessionView { Chat, Trajectory }

/**
 * One conversation: live state, its projections (conversation + trajectory, both derived from
 * the same event log), and every action the composer slot can take. Shared by the home screen
 * and the quick panel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class ConversationController(
    protected val store: SessionStore,
    private val engine: EngineManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val sessionId = MutableStateFlow(store.newSessionId())
    val id: String get() = sessionId.value
    val currentId: StateFlow<String> = sessionId

    val engineState: StateFlow<EngineState> = engine.state
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val live: StateFlow<SessionLive> = sessionId.flatMapLatest { id -> flow { emitAll(store.state(id)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionLive(sessionId.value))

    /**
     * The conversation's model (#53): its own, or the settings default (a new conversation, rows from
     * before #53); [ResolvedModel.fellBack] when its own is no longer available. The composer's chip
     * shows and changes this, never the default.
     */
    val model: StateFlow<ResolvedModel> = combine(live, settings) { l, st -> SessionModels.resolve(l.model, st) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionModels.resolve(null, Settings()))

    /**
     * A model / effort change of a running conversation reaches its next model request (the engine
     * switches the route between steps); the composer says so while it runs.
     */
    val modelChangePending: StateFlow<Boolean> = live.map { it.running }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val conversation: StateFlow<Conversation> = live
        .distinctUntilChangedBy { Triple(it.sessionId, it.events.size, it.running) }
        .conflate()
        .map { Projector.conversation(it.events, it.running) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Conversation())

    val view = MutableStateFlow(SessionView.Chat)
    /** Trajectory record to scroll to and inspect (set by a tool row's 查看 pill). */
    val focusRecord = MutableStateFlow<String?>(null)

    val trajectory: StateFlow<Trajectory?> = combine(view, live.distinctUntilChangedBy { Triple(it.sessionId, it.events.size, it.running) }) { v, l -> v to l }
        .conflate()
        .flatMapLatest { (v, l) ->
            if (v != SessionView.Trajectory) flowOf<Trajectory?>(null)
            else flow<Trajectory?> { emit(Projector.trajectory(l.events, store.childEvents(l.sessionId), l.running)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        store.setViewing(this, id)
        // The conversation shown here was deleted (here, in the app's drawer while the quick panel shows it, …):
        // a new one, never a message under the deleted id (#57; the engine still has that session).
        viewModelScope.launch { store.deleted.collect { if (it == sessionId.value) newSession() } }
    }

    open fun open(id: String) {
        sessionId.value = id
        view.value = SessionView.Chat
        store.setViewing(this, id)
        // 免提语音: replies are spoken for the conversation the user is actually looking at, so a
        // typed prompt in the app speaks too (not only one started by the wake word). Never
        // cleared here: a reply still being read must not be cut off when a screen closes.
        TalkbackService.setActiveSession(id)
    }

    open fun newSession() {
        sessionId.value = store.newSessionId()
        view.value = SessionView.Chat
        store.setViewing(this, sessionId.value)
        TalkbackService.setActiveSession(sessionId.value)
    }

    /** The session's preset is the store's own; [live] can still be the previous session's right after open/newSession. */
    fun send(text: String, images: List<Pair<String, String>> = emptyList(), now: Boolean = false) = viewModelScope.launch {
        store.send(sessionId.value, text, images, now = now)
    }

    /** The composer took [SessionLive.restoredDraft] (queued messages handed back after a stop or failure). */
    fun consumeRestoredDraft() = store.consumeRestoredDraft(sessionId.value)

    fun stop() = viewModelScope.launch { store.stop() }
    fun retry(e: TurnError) = viewModelScope.launch { store.retry(sessionId.value, e) }
    /** 继续 on a turn's end card (work remains). */
    fun continueTask() = viewModelScope.launch { store.continueTask(sessionId.value) }
    fun approve(a: PendingApproval, outcome: ApprovalOutcome) = store.answerApproval(a.requestKey, outcome)
    fun answer(q: PendingQuestion, answers: Map<String, Pair<List<String>, String?>>) = store.answerQuestion(q.requestKey, answers)
    fun skipQuestion(q: PendingQuestion) = store.answerQuestion(q.requestKey, q.questions.associate { it.id to (emptyList<String>() to null) })
    fun answerPermission(requestKey: String, granted: Boolean) = store.answerPermission(requestKey, granted)
    fun answerTakeover(requestKey: String, answer: TakeoverAnswer) = store.answerTakeover(requestKey, answer)
    fun removeQueued(id: String) = store.removeQueued(sessionId.value, id)
    fun sendQueuedNow(id: String) = viewModelScope.launch { store.sendQueuedNow(sessionId.value, id) }
    fun setPreset(p: PermissionPreset) = viewModelScope.launch { store.setPreset(sessionId.value, p) }
    /** Only before the conversation's first message; the store refuses it afterwards. */
    fun setMode(modeId: String) = viewModelScope.launch { store.setMode(sessionId.value, modeId) }
    /** This conversation's model ([provider] "" = DeepSeek); the default (设置 → 模型服务) stays as it is. */
    fun setModel(provider: String, m: String) = viewModelScope.launch {
        store.setModel(sessionId.value, SessionModels.pick(model.value.selection, provider, m, settings.value))
    }
    /** This conversation's DeepSeek 推理等级. */
    fun setEffort(e: Effort) = viewModelScope.launch {
        val cur = model.value.selection
        if (cur.isDeepSeek) store.setModel(sessionId.value, cur.copy(effort = e.id))
    }
    /** 推理强度 of this conversation's third-party model ([level] null = its default). */
    fun setReasoning(level: String?) = viewModelScope.launch {
        val cur = model.value.selection
        if (!cur.isDeepSeek) store.setModel(sessionId.value, cur.copy(effort = level))
    }

    /** The screen is in front again: its session counts as seen (unread dot, notification). */
    fun markSeen() = store.setViewing(this, id)

    fun inspect(callId: String) { view.value = SessionView.Trajectory; focusRecord.value = "k$callId" }

    override fun onCleared() { store.setViewing(this, null) }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    store: SessionStore,
    engine: EngineManager,
    settings: SettingsRepository,
    private val agentScreen: AgentScreen,
) : ConversationController(store, engine, settings) {
    /** The open conversation's agent display (live preview), or null. */
    val agentDisplay: StateFlow<Int?> = combine(currentId, agentScreen.displays) { id, m -> m[id] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One preview frame of the agent screen, [maxWidth] px wide; null when there is none. */
    suspend fun agentFrame(displayId: Int, maxWidth: Int): ImageBitmap? = agentScreen.frame(displayId, maxWidth)?.let { jpeg ->
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
    }

    /** 我来接管: the agent screen's app moves to the user's screen; the agent stops acting there until the user writes. */
    fun takeOverAgentScreen(onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(agentScreen.handToUser(id, byUser = true) != null) }

    val sessions: StateFlow<List<SessionEntity>> = store.conversations.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rowStatus: StateFlow<Map<String, RowStatus>> = store.status

    /** Text to drop into the composer (e.g. "新建" on the automation screen). */
    val prefill = MutableStateFlow<String?>(null)

    fun newSession(prefillText: String?) { newSession(); prefill.value = prefillText }

    fun rename(id: String, title: String) = viewModelScope.launch { store.rename(id, title) }
    fun pin(id: String, pinned: Boolean) = viewModelScope.launch { store.setPinned(id, pinned) }
    fun mute(id: String, muted: Boolean) = viewModelScope.launch { store.setMuted(id, muted) }
    fun archive(id: String, archived: Boolean) = viewModelScope.launch {
        store.setArchived(id, archived)
        if (archived && id == this@HomeViewModel.id) newSession()
    }
    fun delete(id: String) = viewModelScope.launch {
        store.delete(id)
        if (id == this@HomeViewModel.id) newSession()
    }
}

@HiltViewModel
class QuickViewModel @Inject constructor(
    store: SessionStore,
    engine: EngineManager,
    settings: SettingsRepository,
    @ApplicationContext val context: Context,
) : ConversationController(store, engine, settings)
