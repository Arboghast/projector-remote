package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether Claude is driving the projector. A spoken request holds it from the moment it's asked
 * until Claude finishes, whatever Claude does meanwhile (thinking, or working through adb). Without
 * one, as for another MCP client, Claude says so with start and end; any tool call counts as a
 * start, and silence for [IDLE_END_MS] ends it, for when Claude was interrupted. A person's Stop
 * holds until a new task begins; Claude can't lift it.
 */
class Agent(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private val _driving = MutableStateFlow(false)
    val driving: StateFlow<Boolean> = _driving.asStateFlow()

    @Volatile private var stopped = false
    @Volatile private var holding = false
    @Volatile private var lastCall = 0L
    private var watchdog: Job? = null

    /** A person asked for something: the wall glows at once, and an earlier Stop no longer applies. */
    @Synchronized
    fun newTask() {
        stopped = false
        holding = true
        mark()
    }

    /** The request is over, however it went. */
    @Synchronized
    fun finishTask() {
        holding = false
        release()
    }

    /** Every tool call except end goes through here first, so looking also keeps the glow up. */
    fun beforeAction() {
        if (stopped) throw ToolFailure("Stopped: someone pressed Stop on the remote. Don't continue; tell them what you'd got done.")
        mark()
    }

    /** Claude says it's done; during a request the glow stays until the request itself ends. */
    @Synchronized
    fun end() {
        if (!holding) release()
    }

    /** A person pressed Stop: Claude's next call fails, and the glow goes now. */
    @Synchronized
    fun stop() {
        stopped = true
        holding = false
        release()
    }

    private fun release() {
        watchdog?.cancel()
        watchdog = null
        _driving.value = false
    }

    @Synchronized
    private fun mark() {
        lastCall = now()
        if (watchdog?.isActive == true) return
        _driving.value = true
        watchdog = scope.launch {
            while (holding || now() - lastCall < IDLE_END_MS) delay(CHECK_MS)
            _driving.value = false
        }
    }

    companion object {
        const val IDLE_END_MS = 120_000L
        private const val CHECK_MS = 5_000L
    }
}
