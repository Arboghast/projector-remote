package remote

import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * scrcpy's server on the projector, shared by the input helper and the video helper: each runs as
 * its own instance under a distinct scid, reached through an adb-forwarded local port.
 */
class ScrcpyServer(private val shell: AdbShell, private val jar: File) {
    @Volatile private var pushed = false

    suspend fun launch(scid: String, options: String) {
        if (!pushed) {
            shell.push(jar, DEVICE_JAR)
            pushed = true
        }
        // Started from the long-lived shell so it ends with the relay's connection.
        shell.send(
            "pkill -f scid=$scid; CLASSPATH=$DEVICE_JAR app_process / com.genymobile.scrcpy.Server $VERSION " +
                "scid=$scid $options cleanup=false log_level=warn >/dev/null 2>&1 &",
        )
    }

    suspend fun kill(scid: String) {
        runCatching { shell.send("pkill -f scid=$scid") }
    }

    /** After a failure the projector may have rebooted and lost the jar. */
    fun forgetPush() {
        pushed = false
    }

    /**
     * Connects once the server listens. adb accepts a forwarded connection even with nothing behind
     * it and then drops it, so each attempt is checked by [ready], which returns what the caller
     * reads from (or null when the socket isn't live yet).
     */
    suspend fun <T : Any> connect(scid: String, localPort: Int, ready: (Socket) -> T?): Pair<Socket, T> {
        shell.forwardAbstract(localPort, "scrcpy_" + scid.padStart(8, '0'))
        repeat(START_ATTEMPTS) {
            delay(START_POLL_MS)
            val s = Socket().apply { tcpNoDelay = true }
            try {
                s.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
                ready(s)?.let { return s to it }
            } catch (e: IOException) {
                // not listening yet
            }
            runCatching { s.close() }
        }
        throw IOException("scrcpy server $scid didn't start")
    }

    private companion object {
        const val VERSION = "5.0.1"
        const val DEVICE_JAR = "/data/local/tmp/scrcpy-server.jar"
        const val START_ATTEMPTS = 12
        const val START_POLL_MS = 400L
        const val CONNECT_TIMEOUT_MS = 2000
    }
}
