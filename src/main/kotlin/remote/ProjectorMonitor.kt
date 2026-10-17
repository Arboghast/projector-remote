package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps [state] in step with the projector: what app is in front, what's playing, whether its
 * keyboard is up, and the last link we opened. Polls only while someone is watching, since each poll costs the
 * projector ~300 ms of CPU.
 */
class ProjectorMonitor(private val shell: AdbShell, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(ProjectorMonitor::class.java)
    private val _state = MutableStateFlow(ProjectorState())
    val state: StateFlow<ProjectorState> = _state.asStateFlow()

    fun watch(hasViewers: () -> Boolean) {
        scope.launch {
            while (isActive) {
                if (hasViewers() && shell.connected.value) poll()
                delay(POLL_INTERVAL)
            }
        }
        // The phone's text box should rise as soon as a field on the wall is picked, so the keyboard
        // gets its own quick, cheap check.
        scope.launch {
            while (isActive) {
                if (hasViewers() && shell.connected.value) pollKeyboard()
                delay(KEYBOARD_INTERVAL)
            }
        }
    }

    fun opened(url: String) = _state.update { it.copy(lastOpened = url) }

    private suspend fun poll() {
        try {
            val output = shell.exec(StateParser.POLL_SCRIPT).decodeToString()
            val (app, nowPlaying) = StateParser.parse(output)
            _state.update { it.copy(app = app, nowPlaying = nowPlaying) }
        } catch (e: IOException) {
            log.debug("state poll failed: {}", e.message)
        }
    }

    private suspend fun pollKeyboard() {
        try {
            val shown = StateParser.inputShown(shell.exec(StateParser.KEYBOARD_SCRIPT).decodeToString())
            if (shown != _state.value.keyboard) _state.update { it.copy(keyboard = shown) }
        } catch (e: IOException) {
            log.debug("keyboard poll failed: {}", e.message)
        }
    }

    private companion object {
        val POLL_INTERVAL = 2.seconds
        val KEYBOARD_INTERVAL = 0.4.seconds
    }
}
