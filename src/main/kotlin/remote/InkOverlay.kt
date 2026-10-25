package remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Our small overlay app on the projector (overlay-app/): live drawing over any app, and Claude's
 * glow and cursor while it's in control. It's installed and granted "draw over other apps" on first
 * use, then fed JSON messages, one per line. While either layer is on, a dropped connection is
 * re-made on the next message; when both are off the app is stopped so its memory is freed.
 */
class InkOverlay(private val shell: AdbShell, private val apk: File) {
    private val log = LoggerFactory.getLogger(InkOverlay::class.java)
    private val lock = Mutex()
    private var socket: Socket? = null
    private var writer: Writer? = null

    // Separate from the socket, which can drop and come back.
    @Volatile private var drawing = false
    @Volatile private var agentShown = false

    suspend fun start() = lock.withLock {
        drawing = true
        sendLocked(ON)
        sendLocked(CLEAR)
    }

    /** Hides the drawing layer; the app is stopped too unless Claude's layer is still up. */
    suspend fun stop() = lock.withLock {
        if (!drawing) return@withLock
        drawing = false
        runCatching { sendLocked(OFF) }
        if (!agentShown) release()
    }

    suspend fun send(json: String) = lock.withLock {
        if (!drawing) throw InvalidCommand("drawing isn't on")
        sendLocked(json)
    }

    /** Claude's glow on or off; off lets the glow fade before the app is stopped. */
    suspend fun agent(on: Boolean) {
        lock.withLock {
            if (on == agentShown) return
            agentShown = on
            if (on) return sendLocked(AGENT_ON)
            runCatching { sendLocked(AGENT_OFF) }
        }
        // Outside the lock, so drawing isn't held up while the glow fades.
        delay(AGENT_FADE_MS)
        lock.withLock { if (!agentShown && !drawing) release() }
    }

    /** Installs or updates the app ahead of time, so the first glow or stroke doesn't wait a minute for it. */
    suspend fun prepare() = lock.withLock { withContext(Dispatchers.IO) { installIfOld() } }

    /** A cursor move, tap ripple or caption on Claude's layer; dropped if the layer isn't up. */
    suspend fun agentShow(json: String) = lock.withLock {
        if (agentShown) sendLocked(json)
    }

    private suspend fun release() {
        close()
        // Force-stop rather than just stopping the service, or the idle process lingers in memory.
        runCatching { shell.send("am force-stop $PACKAGE") }
    }

    private suspend fun sendLocked(json: String) = withContext(Dispatchers.IO) {
        val w = writer ?: connect().also { restore(it) }
        try {
            w.line(json)
        } catch (e: IOException) {
            close()
            throw ProjectorUnavailable("lost the drawing overlay: ${e.message}")
        }
    }

    /** A new connection starts with nothing shown; put back whatever should be up. */
    private fun restore(w: Writer) {
        if (drawing) w.line(ON)
        if (agentShown) w.line(AGENT_ON)
    }

    private suspend fun connect(): Writer {
        if (!shell.connected.value) throw ProjectorUnavailable("projector offline")
        installIfOld()
        shell.send("appops set $PACKAGE SYSTEM_ALERT_WINDOW allow; am start-foreground-service -n $PACKAGE/.InkService")
        shell.forward(LOCAL_PORT, DEVICE_PORT)
        repeat(START_ATTEMPTS) {
            delay(START_POLL_MS)
            val s = Socket().apply { tcpNoDelay = true }
            try {
                s.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), CONNECT_TIMEOUT_MS)
                val w = s.getOutputStream().bufferedWriter()
                socket = s
                writer = w
                log.info("drawing overlay connected")
                return w
            } catch (e: IOException) {
                runCatching { s.close() }
            }
        }
        throw ProjectorUnavailable("the drawing overlay didn't start")
    }

    private suspend fun installIfOld() {
        val installed = installedVersion(shell.exec("pm list packages --show-versioncode $PACKAGE").decodeToString())
        if (installed != null && installed >= VERSION) return
        log.info("installing the drawing overlay v$VERSION (projector has ${installed ?: "none"})")
        shell.install(apk)
        // Android restarts a just-updated app's process shortly after install, which drops the
        // first connection (and the first strokes with it) unless the service starts after that.
        delay(AFTER_INSTALL_MS)
    }

    private fun Writer.line(json: String) {
        write(json)
        write("\n")
        flush()
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
        writer = null
    }

    companion object {
        /** versionCode in overlay-app/AndroidManifest.xml; raise both together so projectors update. */
        const val VERSION = 4

        /** The versionCode from `pm list packages --show-versioncode`, or null when it isn't installed. */
        fun installedVersion(listing: String): Int? =
            Regex("""package:${Regex.escape(PACKAGE)} versionCode:(\d+)""").find(listing)?.groupValues?.get(1)?.toIntOrNull()

        private const val PACKAGE = "com.projectorremote.ink"
        private const val DEVICE_PORT = 4790
        private const val LOCAL_PORT = 14790
        private const val START_ATTEMPTS = 10
        private const val START_POLL_MS = 300L
        private const val AFTER_INSTALL_MS = 2500L
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val ON = """{"type":"overlay-on"}"""
        private const val OFF = """{"type":"overlay-off"}"""
        private const val CLEAR = """{"type":"ink-clear"}"""
        private const val AGENT_ON = """{"type":"agent-on"}"""
        private const val AGENT_OFF = """{"type":"agent-off"}"""
        private const val AGENT_FADE_MS = 700L
    }
}

/**
 * Claude's glow, cursor, ripples and captions, sent from a queue in the background: a slow or
 * broken overlay never delays Claude's actions, and after a failure the decorations pause for a
 * while instead of retrying on every call. Only the glow itself is always attempted.
 */
class AgentOverlay(private val overlay: InkOverlay, scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) : AgentShow {
    private val log = LoggerFactory.getLogger(AgentOverlay::class.java)
    private val queue = Channel<Pair<Boolean, suspend () -> Unit>>(QUEUE, BufferOverflow.DROP_OLDEST)
    @Volatile private var failedAt = Long.MIN_VALUE / 2

    init {
        scope.launch { for ((essential, job) in queue) attempt(essential, job) }
    }

    fun glow(on: Boolean) {
        queue.trySend(true to { overlay.agent(on) })
    }

    override fun point(x: Double, y: Double) = post("agent-point") { put("x", x); put("y", y) }
    override fun tap(x: Double, y: Double) = post("agent-tap") { put("x", x); put("y", y) }
    override fun say(text: String) = post("agent-say") { put("text", text) }
    override fun quiet() = post("agent-quiet") {}
    override fun url(url: String) = post("agent-url") { put("url", url) }
    override fun scroll(direction: String) = post("agent-scroll") { put("direction", direction) }

    private fun post(type: String, fields: JsonObjectBuilder.() -> Unit) {
        val json = buildJsonObject { put("type", type); fields() }.toString()
        queue.trySend(false to { overlay.agentShow(json) })
    }

    private suspend fun attempt(essential: Boolean, job: suspend () -> Unit) {
        if (!essential && now() - failedAt < BACKOFF_MS) return
        try {
            job()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt = now()
            log.debug("couldn't show Claude's action: {}", e.message)
        }
    }

    private companion object {
        const val QUEUE = 64
        const val BACKOFF_MS = 30_000L
    }
}
