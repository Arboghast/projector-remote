package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether Claude is driving the projector. Claude says so with start and end; any tool call also
 * counts as a start, and silence for [IDLE_END_MS] ends it, for when Claude was interrupted and
 * never got to say end. A person's Stop holds until a new task begins; Claude can't lift it.
 */
class Agent(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private val _driving = MutableStateFlow(false)
    val driving: StateFlow<Boolean> = _driving.asStateFlow()

    @Volatile private var stopped = false
    @Volatile private var lastCall = 0L
    private var watchdog: Job? = null

    /** A person asked for something new: an earlier Stop no longer applies. */
    fun newTask() {
        stopped = false
    }

    /** Every tool call except end goes through here first, so looking also keeps the glow up. */
    fun beforeAction() {
        if (stopped) throw ToolFailure("Stopped: someone pressed Stop on the remote. Don't continue; tell them what you'd got done.")
        mark()
    }

    @Synchronized
    fun end() {
        watchdog?.cancel()
        watchdog = null
        _driving.value = false
    }

    /** A person pressed Stop: Claude's next call fails, and the glow goes now. */
    fun stop() {
        stopped = true
        end()
    }

    @Synchronized
    private fun mark() {
        lastCall = now()
        if (watchdog?.isActive == true) return
        _driving.value = true
        watchdog = scope.launch {
            while (now() - lastCall < IDLE_END_MS) delay(CHECK_MS)
            _driving.value = false
        }
    }

    companion object {
        const val IDLE_END_MS = 120_000L
        private const val CHECK_MS = 5_000L
    }
}
