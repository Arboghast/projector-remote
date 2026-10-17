package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * All input to the projector through one resident process: scrcpy's server with only its control
 * channel. It injects keys, text, touches and a virtual mouse (UHID) in a few milliseconds each.
 *
 * This replaces three helpers (monkey, a separate mouse server, `input`) that together held
 * ~160 MB on a 1.7 GB device and pushed Android into killing apps. It's started as soon as a phone
 * opens the remote ([warm]) and shut down after [IDLE_STOP_MS] with no phone and no input, so
 * nothing stays resident while nobody is using it.
 */
class InputServer(private val scrcpy: ScrcpyServer, private val shell: AdbShell, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(InputServer::class.java)
    private val lock = Mutex()
    private val connecting = Mutex()
    private var out: DataOutputStream? = null
    private var socket: Socket? = null
    private var mouseCreated = false
    private var retryAfter = 0L
    @Volatile private var lastUse = 0L
    private var idleJob: Job? = null

    /** Set by the relay: a phone has the remote open, so keep the helper up. */
    @Volatile var inUse: () -> Boolean = { false }

    // Drag scrolling: phone moves are pooled and fed out at a steady 60 Hz as one continuous touch.
    private var finger: Pair<Int, Int>? = null
    private val pending = doubleArrayOf(0.0, 0.0)
    @Volatile private var lastScrollAt = 0L
    private var pacer: Job? = null

    /** True if handled here; false means the caller should use the slow shell path. */
    suspend fun run(command: ProjectorCommand): Boolean = when (command) {
        is ProjectorCommand.Key -> key(command.key.code)
        is ProjectorCommand.Tap -> tap(screenX(command.x), screenY(command.y))
        is ProjectorCommand.Text -> text(command.text)
        is ProjectorCommand.Scroll -> drag(command.dx, command.dy)
        is ProjectorCommand.Open, is ProjectorCommand.Media -> false
    }

    /**
     * Starts the helper and registers the mouse ahead of the first touch. Cold, the first key took
     * ~1.7 s (server start) and the first cursor move as long again (Android adding the new mouse).
     */
    suspend fun warm() = write { ensureMouse() }

    suspend fun key(code: Int) = write {
        keyEvent(ACTION_DOWN, code)
        keyEvent(ACTION_UP, code)
    }

    suspend fun mouseMove(dx: Int, dy: Int) = write { ensureMouse(); mouseMoveReports(dx, dy) }

    suspend fun mouseClick() = write {
        ensureMouse()
        mouseReport(BUTTON_LEFT, 0, 0, 0)
        mouseReport(0, 0, 0, 0)
    }

    private suspend fun text(raw: String): Boolean {
        if (raw.isBlank()) throw InvalidCommand("nothing to type")
        // The server types via the virtual keyboard map, so most Latin text works; unmappable
        // characters are skipped on the projector rather than failing the whole message.
        return raw.chunked(TEXT_CHUNK).all { chunk ->
            write {
                val bytes = chunk.toByteArray(Charsets.UTF_8)
                writeByte(TYPE_INJECT_TEXT)
                writeInt(bytes.size)
                write(bytes)
            }
        }
    }

    private suspend fun tap(x: Int, y: Int) = write {
        touch(ACTION_DOWN, x, y)
        touch(ACTION_UP, x, y)
    }

    private suspend fun drag(dx: Int, dy: Int): Boolean {
        if (out == null && !write { }) return false
        synchronized(pending) {
            pending[0] += dx.toDouble()
            pending[1] += dy.toDouble()
            lastScrollAt = System.currentTimeMillis()
        }
        if (pacer?.isActive != true) pacer = scope.launch { pace() }
        return true
    }

    private suspend fun pace() {
        while (true) {
            val (sx, sy, idle) = synchronized(pending) {
                // Ease out: most of what's pooled each frame, the rest once it's small. Network bursts
                // become a smooth glide instead of jumps.
                val take = { v: Double -> if (abs(v) < 3) v else v * EASE }
                val x = take(pending[0])
                val y = take(pending[1])
                pending[0] -= x
                pending[1] -= y
                Triple(x.toInt(), y.toInt(), System.currentTimeMillis() - lastScrollAt > LIFT_AFTER_MS)
            }
            write {
                if (sx != 0 || sy != 0) dragStep(sx, sy)
                if (idle && sx == 0 && sy == 0) {
                    finger?.let { (fx, fy) -> touch(ACTION_UP, fx, fy) }
                    finger = null
                }
            }
            if (idle && sx == 0 && sy == 0) return
            delay(FRAME_MS)
        }
    }

    private fun DataOutputStream.dragStep(dx: Int, dy: Int) {
        val (x, y) = finger ?: (CENTER_X to CENTER_Y).also { touch(ACTION_DOWN, it.first, it.second) }
        // The finger moves against the scroll direction: dragging up scrolls the page down.
        var nx = x - dx
        var ny = y - dy
        if (nx !in EDGE..SCREEN_W - EDGE || ny !in EDGE..SCREEN_H - EDGE) {
            // Ran off the screen: lift and put the finger back in the middle, like re-gripping.
            touch(ACTION_UP, x, y)
            touch(ACTION_DOWN, CENTER_X, CENTER_Y)
            nx = (CENTER_X - dx).coerceIn(EDGE, SCREEN_W - EDGE)
            ny = (CENTER_Y - dy).coerceIn(EDGE, SCREEN_H - EDGE)
        }
        touch(ACTION_MOVE, nx, ny)
        finger = nx to ny
    }

    // ── Wire format (scrcpy control protocol, v5.0.1) ──

    private fun DataOutputStream.keyEvent(action: Int, code: Int) {
        writeByte(TYPE_INJECT_KEYCODE)
        writeByte(action)
        writeInt(code)
        writeInt(0) // repeat
        writeInt(0) // meta state
    }

    private fun DataOutputStream.touch(action: Int, x: Int, y: Int) {
        writeByte(TYPE_INJECT_TOUCH)
        writeByte(action)
        writeLong(FINGER_ID)
        writeInt(x)
        writeInt(y)
        writeShort(SCREEN_W)
        writeShort(SCREEN_H)
        writeShort(if (action == ACTION_UP) 0 else 0xFFFF) // pressure
        writeInt(0) // action button
        writeInt(0) // buttons
    }

    private fun DataOutputStream.ensureMouse() {
        if (mouseCreated) return
        writeByte(TYPE_UHID_CREATE)
        writeShort(MOUSE_ID)
        writeShort(0)
        writeShort(0)
        val name = "Projector remote mouse".toByteArray()
        writeByte(name.size)
        write(name)
        writeShort(MOUSE_REPORT_DESCRIPTOR.size)
        write(MOUSE_REPORT_DESCRIPTOR)
        mouseCreated = true
    }

    /** HID reports carry signed bytes, so big moves are split into ±127 steps. */
    private fun DataOutputStream.mouseMoveReports(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        do {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            mouseReport(0, sx, sy, 0)
            x -= sx; y -= sy
        } while (x != 0 || y != 0)
    }

    private fun DataOutputStream.mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        writeByte(TYPE_UHID_INPUT)
        writeShort(MOUSE_ID)
        writeShort(4)
        write(byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
    }

    // ── Connection ──

    private suspend fun write(block: DataOutputStream.() -> Unit): Boolean {
        // Starting the helper takes seconds, so it happens outside the write lock.
        if (out == null && connecting.withLock { out ?: connect() } == null) return false
        return lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val o = out ?: return@withContext false
                    o.block()
                    o.flush()
                    lastUse = System.currentTimeMillis()
                    true
                } catch (e: IOException) {
                    log.warn("input server failed: {}", e.message)
                    close()
                    false
                }
            }
        }
    }

    private suspend fun connect(): DataOutputStream? {
        if (System.currentTimeMillis() < retryAfter || !shell.connected.value) return null
        retryAfter = System.currentTimeMillis() + RETRY_MS
        return try {
            scrcpy.launch(SCID, "tunnel_forward=true video=false audio=false control=true raw_stream=true")
            // The server sends nothing in raw mode, so a short read that times out means "alive".
            val (s, _) = scrcpy.connect(SCID, LOCAL_PORT) { s ->
                s.soTimeout = PROBE_MS
                val alive = try {
                    s.getInputStream().read() >= 0
                } catch (e: SocketTimeoutException) {
                    true
                }
                if (alive) s.also { it.soTimeout = 0 } else null
            }
            // The server can send messages back (clipboard, UHID output); nothing here needs them, but
            // they must be read so the server never blocks on a full socket.
            thread(isDaemon = true, name = "scrcpy-drain") { runCatching { s.getInputStream().copyTo(OutputStream.nullOutputStream()) } }
            lock.withLock {
                socket = s
                mouseCreated = false
                out = DataOutputStream(s.getOutputStream().buffered())
            }
            log.info("input server up")
            if (idleJob?.isActive != true) idleJob = scope.launch { stopWhenIdle() }
            out
        } catch (e: IOException) {
            log.warn("input server didn't start: {}", e.message)
            scrcpy.forgetPush()
            null
        }
    }

    private suspend fun stopWhenIdle() {
        while (true) {
            delay(IDLE_CHECK_MS)
            if (inUse() || System.currentTimeMillis() - lastUse < IDLE_STOP_MS) continue
            lock.withLock {
                if (out != null) {
                    log.info("input server idle, stopping it to free the projector's memory")
                    close()
                    scrcpy.kill(SCID)
                }
            }
            return
        }
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
        out = null
        finger = null
        mouseCreated = false
    }

    private fun screenX(fraction: Double) = (onScreen(fraction) * (Wall.WIDTH - 1)).toInt()
    private fun screenY(fraction: Double) = (onScreen(fraction) * (Wall.HEIGHT - 1)).toInt()

    private fun onScreen(fraction: Double) =
        fraction.takeIf { it in 0.0..1.0 } ?: throw InvalidCommand("tap is outside the screen")

    private companion object {
        const val SCID = "10" // hex; keeps this server apart from the screen stream's
        const val LOCAL_PORT = 27183

        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH = 2
        const val TYPE_UHID_CREATE = 12
        const val TYPE_UHID_INPUT = 13
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val FINGER_ID = -2L // scrcpy's "generic finger"
        const val MOUSE_ID = 2
        const val BUTTON_LEFT = 1
        const val TEXT_CHUNK = 250

        const val SCREEN_W = Wall.WIDTH
        const val SCREEN_H = Wall.HEIGHT
        const val CENTER_X = SCREEN_W / 2
        const val CENTER_Y = SCREEN_H / 2
        const val EDGE = 40
        const val FRAME_MS = 16L
        // Share of the pooled movement sent each frame. 0.5 lagged ~55 ms behind the finger; 0.8 still
        // smooths network bursts but delivers 90% within ~25 ms.
        const val EASE = 0.8
        const val LIFT_AFTER_MS = 120L

        const val PROBE_MS = 300
        const val RETRY_MS = 5_000L
        const val IDLE_CHECK_MS = 30_000L
        const val IDLE_STOP_MS = 5 * 60_000L

        /** A plain 3-button mouse with relative X/Y and a wheel: [buttons, dx, dy, wheel]. */
        val MOUSE_REPORT_DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}
