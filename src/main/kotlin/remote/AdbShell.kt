package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class ProjectorUnavailable(message: String) : IOException(message)

/**
 * One long-lived `adb shell` on the projector. Commands go to its stdin, which avoids spawning
 * adb per key press (~100 ms each) and keeps D-pad input feeling immediate.
 */
class AdbShell(private val adb: String, val device: String) {
    private val log = LoggerFactory.getLogger(AdbShell::class.java)
    private val writeLock = Mutex()
    @Volatile private var process: Process? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Fails immediately while the projector is offline. Only [watch] reconnects, and it never takes
     * [writeLock], so a slow `adb connect` to a sleeping projector can't stall key presses.
     */
    suspend fun send(line: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val shell = process?.takeIf { it.isAlive }
                ?: throw ProjectorUnavailable("projector $device is offline, reconnecting")
            try {
                write(shell, line)
            } catch (e: IOException) {
                shell.destroy()
                throw ProjectorUnavailable("lost the projector mid-command (${e.message}), reconnecting")
            }
        }
    }

    /**
     * Runs [command] in its own `adb exec-out`, apart from the input shell, so slow reads (dumpsys,
     * screencap) never hold up key presses. Returns raw stdout.
     */
    suspend fun exec(command: String, timeoutSeconds: Long = EXEC_TIMEOUT_S): ByteArray = coroutineScope {
        if (!connected.value) throw ProjectorUnavailable("projector $device is offline, reconnecting")
        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(adb, "-s", device, "exec-out", command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        // Drain stdout while waiting; a full pipe would otherwise stall the process until the timeout.
        val output = async(Dispatchers.IO) { proc.inputStream.readAllBytes() }
        val finished = withContext(Dispatchers.IO) { proc.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
        if (!finished) {
            proc.destroyForcibly()
            output.cancel()
            throw ProjectorUnavailable("projector took too long to answer")
        }
        output.await()
    }

    /** Notices a dropped shell (projector rebooted, Wi-Fi blip) and reconnects in the background. */
    fun watch(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            if (process?.isAlive != true) {
                _connected.value = false
                runCatching { open() }.onFailure { log.debug("reconnect failed: {}", it.message) }
            }
            delay(RECONNECT_INTERVAL)
        }
    }

    fun close() {
        process?.destroy()
        _connected.value = false
    }

    private fun open(): Process {
        runAdb("connect", device)
        val state = runAdb("-s", device, "get-state").trim()
        if (state != "device") {
            _connected.value = false
            throw ProjectorUnavailable("projector $device is not reachable over adb ($state)")
        }
        val shell = ProcessBuilder(adb, "-s", device, "shell").redirectErrorStream(true).start()
        thread(isDaemon = true, name = "adb-shell-output") {
            shell.inputStream.bufferedReader().forEachLine { log.info("projector: {}", it) }
            if (process === shell) _connected.value = false
            log.warn("adb shell to {} closed", device)
        }
        process = shell
        _connected.value = true
        log.info("adb shell open on {}", device)
        return shell
    }

    /** Forwards a PC port to a port on the projector (`adb forward`). */
    suspend fun forward(localPort: Int, remotePort: Int) = withContext(Dispatchers.IO) {
        runAdb("-s", device, "forward", "tcp:$localPort", "tcp:$remotePort")
    }

    /** Forwards a PC port to a named unix socket on the projector. */
    suspend fun forwardAbstract(localPort: Int, socketName: String) = withContext(Dispatchers.IO) {
        runAdb("-s", device, "forward", "tcp:$localPort", "localabstract:$socketName")
    }

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        if (!apk.isFile) throw ProjectorUnavailable("missing ${apk.path}")
        val out = runAdbLong("-s", device, "install", "-r", apk.absolutePath)
        if (!out.contains("Success")) throw ProjectorUnavailable("install failed: ${out.trim().lines().lastOrNull()}")
    }

    suspend fun push(local: File, remote: String) = withContext(Dispatchers.IO) {
        if (!local.isFile) throw ProjectorUnavailable("missing ${local.path}")
        runAdb("-s", device, "push", local.absolutePath, remote)
    }

    private fun write(shell: Process, line: String) {
        shell.outputStream.write("$line\n".toByteArray())
        shell.outputStream.flush()
    }

    /** For slow operations like installs, which outlast the normal adb timeout. */
    private fun runAdbLong(vararg args: String): String {
        val proc = ProcessBuilder(adb, *args).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor(60, TimeUnit.SECONDS)
        return output
    }

    private fun runAdb(vararg args: String): String {
        val proc = ProcessBuilder(adb, *args).redirectErrorStream(true).start()
        if (!proc.waitFor(ADB_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw ProjectorUnavailable("adb ${args.joinToString(" ")} timed out")
        }
        return proc.inputStream.bufferedReader().readText()
    }

    private companion object {
        val RECONNECT_INTERVAL = 5.seconds
        const val ADB_TIMEOUT_S = 5L
        const val EXEC_TIMEOUT_S = 8L
    }
}
