package io.github.jackwener.dsh.feature.quick

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshTheme
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Whale
import io.github.jackwener.dsh.core.designsystem.elevated
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.core.session.PanelSession
import io.github.jackwener.dsh.core.session.RowStatus
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.feature.conversation.ChatStream
import io.github.jackwener.dsh.feature.conversation.ComposerSlot
import io.github.jackwener.dsh.feature.conversation.QuickViewModel
import io.github.jackwener.dsh.feature.conversation.addFile
import io.github.jackwener.dsh.feature.conversation.addBitmap
import io.github.jackwener.dsh.feature.conversation.addImages
import io.github.jackwener.dsh.feature.conversation.draftFor
import io.github.jackwener.dsh.feature.conversation.rememberComposerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The summon-anywhere panel (floating ball, QS tile, assistant gesture, share sheet, RUN_TASK):
 * a compact dsh card with the current run's progress and the same composer slot (approval /
 * permission / question cards replace the composer exactly as on the home screen).
 *
 * singleInstance: a summon while the panel is alive arrives in [onNewIntent]. A plain summon
 * keeps (or, after a dismiss, reopens) the recent panel session, so the run started here stays
 * visible; a share or RUN_TASK starts a fresh one. Intents are handled once — never again on
 * rotation (the ViewModel survives).
 */
@AndroidEntryPoint
class QuickActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var store: SessionStore
    private val vm: QuickViewModel by viewModels()
    private var useContext by mutableStateOf(false)
    /** Bumped when a summon asks to start listening (not saved: a rotation does not listen again). */
    private var listenRequest by mutableIntStateOf(0)

    /** Android 10–12: the in-app language (AppLanguage); 13+ does it itself. */
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(io.github.jackwener.dsh.core.platform.AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        val fresh = savedInstanceState == null
        // RUN_TASK comes from another app: never attach what is on screen to its task.
        if (fresh) captureContext(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (fresh) {
            useContext = ScreenContext.text != null
            val reopened = !isShareOrTask(intent) && (intent.hasExtra(EXTRA_SESSION) || reopenRecent())
            handleIntent(intent, freshPanel = true, reopened)
        } else useContext = ScreenContext.text != null && savedInstanceState.getBoolean(STATE_USE_CONTEXT, false)
        setContent {
            val loaded by settingsRepo.settings.collectAsStateWithLifecycle(null)
            // Wait for the stored theme: no one-frame flash of the default theme.
            val settings = loaded ?: return@setContent
            DshTheme(settings.theme) {
                val c = Dsh.colors
                val live by vm.live.collectAsStateWithLifecycle()
                val conv by vm.conversation.collectAsStateWithLifecycle()
                val composer = rememberComposerState(live.sessionId)
                val list = rememberLazyListState()
                // A tap on the dim area dismisses, unless that would drop a draft or hide a run in progress.
                val dismissable = composer.isEmpty && !live.running
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { if (dismissable) finish() }
                    .statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(8.dp)
                            .elevated(Radius.panel, c.borderL2, 12.dp).clip(Radius.panel).background(c.bgBase)
                            .clickable(remember { MutableInteractionSource() }, null) {}.padding(bottom = 8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Whale(width = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(live.title.ifBlank { stringResource(R.string.app_name) }, Modifier.weight(1f), style = DshType.s14m, color = c.labelPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconBtn(R.drawable.dsw_right_up_outline, { open(vm.id) }, size = 40.dp, contentDescription = stringResource(R.string.open_in_app))
                            IconBtn(R.drawable.dsw_close_outline, { finish() }, size = 40.dp, contentDescription = stringResource(R.string.close))
                        }
                        if (useContext && ScreenContext.packageName != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).clip(Radius.sm).background(c.selector)
                            .pressable({ useContext = false }, Radius.sm).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DIcon(R.drawable.dsw_fullscreen_outline, size = 12.dp, tint = c.labelSecondary)
                            Text(stringResource(R.string.current_page, appLabel(ScreenContext.packageName!!)), style = DshType.xxs12, color = c.labelSecondary)
                            DIcon(R.drawable.dsw_close_outline, size = 10.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.remove_attachment))
                        }
                        if (!conv.isEmpty || live.running) {
                            // weight(fill = false): the stream gives way first, so the composer / approval buttons stay on screen.
                            ChatStream(conv, live, settings.workDetails, list, onInspect = { open(vm.id) }, onRetry = vm::retry, onContinue = { vm.continueTask() }, onOpenSettings = { openSettings(it) },
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp), compact = true)
                        }
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            ComposerSlot(vm, composer, settings, live, conv, hero = false, prepare = { text ->
                                PanelSession.remember(this@QuickActivity, vm.id)
                                val ctx = if (useContext && ScreenContext.text != null)
                                    "[The user summoned you while looking at ${ScreenContext.packageName}. Screen content:]\n${ScreenContext.text}\n" else ""
                                useContext = false
                                if (ctx.isBlank()) text else "$ctx\n$text"
                            }, listenRequest = listenRequest)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureContext(intent)
        useContext = ScreenContext.text != null
        handleIntent(intent, freshPanel = false, reopened = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_USE_CONTEXT, useContext)
    }

    override fun onStop() {
        super.onStop()
        PanelSession.touch(this, vm.id)
    }

    private fun isShareOrTask(i: Intent) = i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE || i.action == ACTION_RUN_TASK

    private fun captureContext(i: Intent) {
        // A double tap on the floating ball captured the screen itself, before the ball was shown again.
        if (!i.getBooleanExtra(EXTRA_FROM_ASSIST, false) && !i.getBooleanExtra(EXTRA_SCREEN, false) && i.action != ACTION_RUN_TASK) ScreenContext.captureFromAccessibility()
    }

    /**
     * Re-summoning after a dismiss shows the panel's last session if it is still running or was used in the
     * last 10 minutes ([PanelSession]; a deleted one is forgotten).
     */
    private fun reopenRecent(): Boolean {
        val id = PanelSession.reopen(this, System.currentTimeMillis()) { store.status.value[it].let { s -> s != null && s != RowStatus.Idle } } ?: return false
        vm.open(id)
        return true
    }

    /**
     * Floating-ball double tap: the screenshot goes into the composer of the most recent conversation
     * (the panel's own, else one used in the last 10 minutes, else a new one); the screen text rides
     * along as the 当前页面 chip.
     */
    private fun attachScreen(reopened: Boolean) {
        val shot = ScreenContext.takeShot()
        lifecycleScope.launch {
            if (!reopened && draftFor(vm.id).isEmpty) store.conversations.first().filter { !it.archived }.maxByOrNull { it.updatedAt }
                ?.takeIf { System.currentTimeMillis() - it.updatedAt < PanelSession.RECENT_MS }?.let { vm.open(it.id) }
            shot?.let { addBitmap(draftFor(vm.id), it) }
        }
    }

    /** A share or RUN_TASK goes to a fresh session unless the current one is still untouched. */
    private fun freshSessionIfUsed() {
        if (vm.live.value.running || vm.live.value.events.isNotEmpty() || !draftFor(vm.id).isEmpty) vm.newSession()
    }

    private fun handleIntent(i: Intent, freshPanel: Boolean, reopened: Boolean) {
        // The floating ball / capsule during a run started here: exactly that conversation (#39).
        i.getStringExtra(EXTRA_SESSION)?.let { id ->
            // The conversation this panel now shows is where a spoken reply belongs, even when it
            // is already open (vm.open covers the switch case, this covers the same-session case).
            if (id != vm.id) vm.open(id) else io.github.jackwener.dsh.wake.TalkbackService.setActiveSession(id)
            return
        }
        if (i.getBooleanExtra(EXTRA_WAKE, false)) {
            // Wake capture: same "start listening" request as the ball, plus the spoken-reply target.
            listenRequest++
            lifecycleScope.launch { io.github.jackwener.dsh.wake.TalkbackService.setActiveSession(vm.id) }
        } else if (i.getBooleanExtra(EXTRA_VOICE, false)) listenRequest++
        else if (i.getBooleanExtra(EXTRA_FROM_ASSIST, false)) lifecycleScope.launch { if (settingsRepo.current().voiceOnAssist) listenRequest++ }
        if (i.getBooleanExtra(EXTRA_SCREEN, false)) return attachScreen(reopened)
        if (i.action == ACTION_RUN_TASK) {
            val task = i.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() } ?: return
            lifecycleScope.launch {
                // 允许其他 App 调用 (Settings → General → entry points) is off by default.
                if (settingsRepo.current().allowExternalApps) { freshSessionIfUsed(); vm.send(task) }
                else {
                    Toast.makeText(applicationContext, getString(R.string.external_refused, callerLabel()), Toast.LENGTH_LONG).show()
                    if (freshPanel) finish()
                }
            }
            return
        }
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return
        // Shared content goes into the composer, where the user sees it and can edit or remove it.
        freshSessionIfUsed()
        val draft = draftFor(vm.id)
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).distinct().joinToString("\n")
        if (text.isNotBlank()) draft.text = (draft.text + (if (draft.text.isBlank()) "" else "\n") + text).trim()
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM))
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        if (uris.isEmpty()) return
        val (images, files) = uris.partition { (contentResolver.getType(it) ?: i.type ?: "").startsWith("image/") }
        // Content URIs are only readable while we hold the grant: decode / copy them now.
        lifecycleScope.launch {
            if (images.isNotEmpty()) addImages(this@QuickActivity, draft, images)
            files.forEach { addFile(this@QuickActivity, draft, it) }
        }
    }

    private fun open(sessionId: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply { sessionId?.let { putExtra(MainActivity.EXTRA_SESSION, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Error-row actions (登录 / 添加 API Key / 打开自检) open the matching Settings page in the app. */
    private fun openSettings(section: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun callerLabel(): String = referrer?.host?.let(::appLabel) ?: getString(R.string.external_unknown_app)

    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    override fun onDestroy() { if (isFinishing) ScreenContext.clear(); super.onDestroy() }

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
        /** Set by the floating ball's double tap: ScreenContext holds a fresh screenshot and screen text. */
        const val EXTRA_SCREEN = "screen"
        /** Open listening (floating ball long-press). */
        const val EXTRA_VOICE = "voice"
        /**
         * 免提语音 唤醒词: the wake-word service saw the phrase. The panel starts capture as soon as
         * the engine is ready instead of waiting for a tap; a cold engine keeps the same loading
         * surface the panel already shows.
         */
        const val EXTRA_WAKE = "wake"
        const val ACTION_RUN_TASK = "io.github.jackwener.dsh.RUN_TASK"
        const val EXTRA_TASK = "task"
        /** Open this panel conversation (the floating ball while a run started here goes on). */
        const val EXTRA_SESSION = "session"
        private const val STATE_USE_CONTEXT = "use_context"

        /** The conversation last used in the panel (a run started there reopens in the panel, not the app). */
        fun panelSession(context: Context): String? = PanelSession.last(context)
    }
}
