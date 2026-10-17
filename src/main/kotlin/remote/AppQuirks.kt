package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Workarounds for how particular apps on the projector behave. Multi-step sequences run in the
 * background, so the phone's next command isn't held up behind them; starting a new one cancels
 * the one in progress.
 */
class AppQuirks(
    private val sender: Sender,
    private val shell: AdbShell,
    private val state: () -> ProjectorState,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(AppQuirks::class.java)
    private var macro: Job? = null

    /** Back while a page is showing in TV Bro, which answers Back with its menu bar instead. */
    fun handlesBack(): Boolean = state().let { it.app == Packages.TV_BRO && !it.keyboard }

    /** Opening TV Bro's bar, pressing its ← and closing the bar again goes back a page. */
    fun browserBack() = run {
        sender.press(RemoteKey.Back)
        delay(MENU_OPEN_MS)
        sender.deliver(ProjectorCommand.Tap(TV_BRO_BACK_X, TV_BRO_BACK_Y))
        delay(PAGE_BACK_MS)
        sender.press(RemoteKey.Back)
    }

    /**
     * YouTube has no key for its settings panel. Menu shows the player controls with the highlight
     * on play/pause; the gear is the last control on that row, so stepping right until the row ends
     * lands on it whatever buttons the video has. Elsewhere, Menu is the closest thing.
     */
    fun playerSettings() = run {
        sender.press(RemoteKey.Menu)
        if (state().app != Packages.YOUTUBE) return@run
        delay(CONTROLS_SHOW_MS)
        repeat(GEAR_STEPS) {
            sender.press(RemoteKey.Right)
            delay(GEAR_STEP_MS)
        }
        sender.press(RemoteKey.Ok)
    }

    /** YouTube treats a seek key as "start scrubbing" and waits there, paused; a play commits it. */
    fun afterSeek(app: String) {
        if (app != Packages.YOUTUBE) return
        scope.launch {
            delay(SEEK_COMMIT_MS)
            runCatching { shell.send("cmd media_session dispatch play") }
        }
    }

    /**
     * This projector's firmware relaunches apps that Android killed for memory, in the background.
     * TV Bro relaunched that way can't rebuild its web engine and later shows "Can not link to
     * WebView component" when brought forward. If it isn't on screen, start it fresh instead.
     */
    suspend fun beforeOpening(app: String?) {
        if (app == Packages.TV_BRO && state().app != Packages.TV_BRO) shell.send("am force-stop ${Packages.TV_BRO}")
    }

    private fun run(steps: suspend () -> Unit) {
        macro?.cancel()
        macro = scope.launch {
            try {
                steps()
            } catch (e: IOException) {
                log.warn("app shortcut failed: {}", e.message)
            }
        }
    }

    private companion object {
        // The ← in TV Bro's bottom bar, as a fraction of the screen.
        const val TV_BRO_BACK_X = 0.403
        const val TV_BRO_BACK_Y = 0.954
        const val MENU_OPEN_MS = 900L
        const val PAGE_BACK_MS = 500L
        const val CONTROLS_SHOW_MS = 700L
        const val GEAR_STEPS = 10
        const val GEAR_STEP_MS = 90L
        const val SEEK_COMMIT_MS = 600L
    }
}
