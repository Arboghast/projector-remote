package remote

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val config = Config.fromEnv()
    val shell = AdbShell(config.adb, config.device)

    embeddedServer(CIO, host = config.host, port = config.port) {
        val projector = ProjectorMonitor(shell, this)
        val scrcpy = ScrcpyServer(shell, File(config.scrcpyServer))
        val relay = Relay(
            shell = shell,
            input = InputServer(scrcpy, shell, this),
            commands = ShellCommands(config.browser),
            projector = projector,
            shelf = ShelfStore(File(config.shelfFile)),
            screen = ScreenCapture(shell),
            stream = ScreenStream(scrcpy, shell, this),
            overlay = InkOverlay(shell, File(config.inkOverlayApk)),
            agent = Agent(this),
            config = config,
            webDir = File(config.webDir),
        )
        relay.install(this)
        shell.watch(this)
        projector.watch { relay.hasRemotes }
        monitor.subscribe(ApplicationStopped) { shell.close() }
        LoggerFactory.getLogger("remote").info("relay on http://${config.host}:${config.port} -> projector ${config.device}")
    }.start(wait = true)
}
