package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/**
 * One encoded video packet: SPS/PPS config, or a frame (key or not), H.264 Annex-B. [wire] is the
 * message phones receive, [flags, bytes] (bit 0 config, bit 1 key frame), built once for all of them.
 */
class VideoPacket(val seq: Long, val config: Boolean, val key: Boolean, data: ByteArray) {
    val wire: ByteArray = ByteArray(data.size + 1).also {
        it[0] = ((if (config) 1 else 0) or (if (key) 2 else 0)).toByte()
        data.copyInto(it, 1)
    }
}

/**
 * A live H.264 picture of the projector, ~100–200 ms behind the wall instead of a ~2 s screenshot.
 * scrcpy's server encodes the screen on the projector's hardware encoder; this reads its packets
 * and fans them out to every phone watching. It only runs while someone is watching (plus a while
 * after, so reopening is instant).
 */
class ScreenStream(private val scrcpy: ScrcpyServer, private val shell: AdbShell, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(ScreenStream::class.java)
    private val lock = Mutex()
    private val _packets = MutableSharedFlow<VideoPacket>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val packets: SharedFlow<VideoPacket> = _packets.asSharedFlow()

    /**
     * The latest SPS/PPS and every packet since the last key frame. A still screen sends no new
     * frames, so a phone joining mid-stream gets this replayed and shows the wall at once instead
     * of staying black until the picture next changes.
     */
    private var lastConfig: VideoPacket? = null
    private val sinceKey = ArrayDeque<VideoPacket>()
    private var sinceKeyComplete = false
    private var seq = 0L

    fun replay(): List<VideoPacket> = synchronized(sinceKey) {
        listOfNotNull(lastConfig) + if (sinceKeyComplete) sinceKey else emptyList()
    }

    private var watchers = 0
    private var job: Job? = null
    private var linger: Job? = null
    @Volatile private var socket: Socket? = null

    suspend fun watch() = lock.withLock {
        watchers++
        linger?.cancel()
        if (job?.isActive != true) job = scope.launch(Dispatchers.IO) { runWithRetry() }
    }

    suspend fun unwatch() = lock.withLock {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) linger = scope.launch {
            delay(LINGER_MS)
            lock.withLock { if (watchers == 0) stop() }
        }
    }

    private suspend fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        synchronized(sinceKey) {
            lastConfig = null
            sinceKey.clear()
            sinceKeyComplete = false
        }
        scrcpy.kill(SCID)
    }

    private suspend fun CoroutineScope.runWithRetry() {
        while (isActive) {
            try {
                stream()
            } catch (e: IOException) {
                log.warn("screen stream stopped: {}", e.message)
                scrcpy.forgetPush()
            }
            delay(RETRY_MS)
        }
    }

    private suspend fun CoroutineScope.stream() {
        if (!shell.connected.value) throw IOException("projector offline")
        scrcpy.launch(
            SCID,
            "tunnel_forward=true audio=false control=false video_codec=h264 max_size=$MAX_SIZE " +
                "max_fps=$MAX_FPS video_bit_rate=$BIT_RATE send_device_meta=false send_dummy_byte=false " +
                "send_stream_meta=false send_frame_meta=true",
        )
        // The socket only counts once the first byte (the config packet) has arrived.
        val (s, input) = scrcpy.connect(SCID, LOCAL_PORT) { s ->
            s.soTimeout = FIRST_PACKET_MS
            val buffered = s.getInputStream().buffered()
            buffered.mark(1)
            if (buffered.read() < 0) null else buffered.also { it.reset(); s.soTimeout = 0 }
        }
        socket = s
        if (!isActive) return s.close() // stopped while connecting
        log.info("screen stream up")
        val data = DataInputStream(input)
        while (true) {
            val ptsAndFlags = data.readLong()
            val size = data.readInt()
            if (size !in 1..MAX_PACKET) throw IOException("bad packet size $size")
            val bytes = ByteArray(size).also { data.readFully(it) }
            val packet = VideoPacket(seq++, ptsAndFlags and FLAG_CONFIG != 0L, ptsAndFlags and FLAG_KEY_FRAME != 0L, bytes)
            remember(packet)
            _packets.tryEmit(packet)
        }
    }

    private fun remember(packet: VideoPacket) = synchronized(sinceKey) {
        when {
            packet.config -> lastConfig = packet
            packet.key -> {
                sinceKey.clear()
                sinceKey.addLast(packet)
                sinceKeyComplete = true
            }
            // Past the cap the run would have a gap that corrupts a joining decoder, so replay only
            // the config and let the newcomer wait for the next key frame.
            sinceKey.size >= MAX_REPLAY -> {
                sinceKey.clear()
                sinceKeyComplete = false
            }
            sinceKeyComplete -> sinceKey.addLast(packet)
        }
    }

    private companion object {
        const val SCID = "11" // hex; keeps this server apart from the input helper's
        const val LOCAL_PORT = 27184
        const val MAX_SIZE = 960
        const val MAX_FPS = 30
        const val BIT_RATE = 2_000_000
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val MAX_PACKET = 8 * 1024 * 1024
        const val FIRST_PACKET_MS = 3000
        const val RETRY_MS = 3000L
        // Reopening the picture within this window is instant; a cold start takes ~3.6 s.
        const val LINGER_MS = 180_000L
        const val MAX_REPLAY = 300
    }
}
