package remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Live drawing over any app, through our small overlay app on the projector (overlay-app/). It's
 * installed and granted "draw over other apps" on first use, then fed ink messages, one JSON object
 * per line. While drawing is on, a dropped connection is re-made on the next stroke.
 */
class InkOverlay(private val shell: AdbShell, private val apk: File) {
    private val log = LoggerFactory.getLogger(InkOverlay::class.java)
    private val lock = Mutex()
    private var socket: Socket? = null
    private var writer: Writer? = null

    /** Drawing is on; separate from the socket, which can drop and come back. */
    @Volatile private var wanted = false

    suspend fun start() = lock.withLock {
        wanted = true
        sendLocked(CLEAR)
    }

    /** Hides the layer and stops the app's service, so its ~90 MB is freed while you're not drawing. */
    suspend fun stop() = lock.withLock {
        if (!wanted) return@withLock
        wanted = false
        runCatching { sendLocked(OFF) }
        close()
        // Force-stop rather than just stopping the service, or the idle process lingers in memory.
        runCatching { shell.send("am force-stop $PACKAGE") }
    }

    suspend fun send(json: String) = lock.withLock {
        if (!wanted) throw InvalidCommand("drawing isn't on")
        sendLocked(json)
    }

    private suspend fun sendLocked(json: String) = withContext(Dispatchers.IO) {
        val w = writer ?: connect().also { it.line(ON) }
        try {
            w.line(json)
        } catch (e: IOException) {
            close()
            throw ProjectorUnavailable("lost the drawing overlay: ${e.message}")
        }
    }

    private suspend fun connect(): Writer {
        if (!shell.connected.value) throw ProjectorUnavailable("projector offline")
        val installed = shell.exec("pm list packages $PACKAGE").decodeToString().contains(PACKAGE)
        if (!installed) {
            log.info("installing the drawing overlay")
            shell.install(apk)
        }
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

    private companion object {
        const val PACKAGE = "com.projectorremote.ink"
        const val DEVICE_PORT = 4790
        const val LOCAL_PORT = 14790
        const val START_ATTEMPTS = 10
        const val START_POLL_MS = 300L
        const val CONNECT_TIMEOUT_MS = 2000
        const val ON = """{"type":"overlay-on"}"""
        const val OFF = """{"type":"overlay-off"}"""
        const val CLEAR = """{"type":"ink-clear"}"""
    }
}
