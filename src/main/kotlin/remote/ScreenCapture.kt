package remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

/**
 * Grabs the projector's screen. A PNG screencap takes ~3 s on this SoC; raw RGBA piped through
 * gzip takes ~1.7 s, so we fetch raw pixels and encode on the PC.
 */
class ScreenCapture(private val shell: AdbShell) {
    private val lock = Mutex()
    private var cached: Pair<Long, BufferedImage>? = null

    /** A frame no older than [maxAgeMs]; concurrent callers share one capture. */
    suspend fun frame(maxAgeMs: Long = 800): BufferedImage = lock.withLock {
        cached?.let { (at, image) -> if (System.currentTimeMillis() - at <= maxAgeMs) return@withLock image }
        val image = decode(shell.exec("screencap | gzip -1", timeoutSeconds = 10))
        cached = System.currentTimeMillis() to image
        image
    }

    suspend fun jpeg(width: Int = PREVIEW_WIDTH, maxAgeMs: Long = 800): ByteArray {
        val full = frame(maxAgeMs)
        return withContext(Dispatchers.Default) {
            val height = full.height * width / full.width
            val small = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            small.createGraphics().run {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(full, 0, 0, width, height, null)
                dispose()
            }
            ByteArrayOutputStream().also { ImageIO.write(small, "jpg", it) }.toByteArray()
        }
    }

    private suspend fun decode(gzipped: ByteArray): BufferedImage = withContext(Dispatchers.Default) {
        val raw = GZIPInputStream(gzipped.inputStream()).readAllBytes()
        // The vendor build prints log lines to stdout around the image (before or after, depending on
        // buffering), so find the header by its known width/height rather than assuming byte 0.
        val start = indexOf(raw, HEADER_MARKER)
        if (start < 0) throw ProjectorUnavailable("screen capture came back unreadable")
        val pixels = SCREEN_W * SCREEN_H * 4
        // Android 11 adds a colour-space word, making the header 16 bytes instead of 12.
        val headerSize = if (raw.size - start >= 16 + pixels) 16 else 12
        if (raw.size - start < headerSize + pixels) throw ProjectorUnavailable("screen capture came back short")
        val buffer = ByteBuffer.wrap(raw, start + headerSize, pixels).order(ByteOrder.BIG_ENDIAN)
        val image = BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(SCREEN_W)
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val rgba = buffer.int
                row[x] = rgba ushr 8 // RGBA → 0RGB
            }
            image.setRGB(0, y, SCREEN_W, 1, row, 0, SCREEN_W)
        }
        image
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val SCREEN_W = Wall.WIDTH
        const val SCREEN_H = Wall.HEIGHT
        const val PREVIEW_WIDTH = 960

        /** Little-endian width (1920) then height (1080), as the raw screencap header starts. */
        val HEADER_MARKER = byteArrayOf(0x80.toByte(), 0x07, 0, 0, 0x38, 0x04, 0, 0)
    }
}
