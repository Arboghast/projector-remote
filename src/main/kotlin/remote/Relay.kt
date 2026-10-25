package remote

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.server.request.host
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** Turns what phones send into actions on the projector, and keeps every phone up to date. */
class Relay(
    private val shell: AdbShell,
    private val input: InputServer,
    private val commands: ShellCommands,
    private val projector: ProjectorMonitor,
    private val shelf: ShelfStore,
    private val screen: ScreenCapture,
    private val stream: ScreenStream,
    private val overlay: InkOverlay,
    private val push: PushNotifier,
    private val agent: Agent,
    private val config: Config,
    private val webDir: File,
) {
    private val log = LoggerFactory.getLogger(Relay::class.java)
    private val suggestions = Suggestions()
    private val sender = Sender(input, shell, commands)
    private val focus = FocusScroller()
    private val remotes = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private lateinit var quirks: AppQuirks
    private lateinit var runner: ClaudeRunner
    private lateinit var agentTools: AgentTools

    val hasRemotes: Boolean get() = remotes.isNotEmpty()

    /** The relay is going away: a request Claude is still on mustn't outlive it. */
    fun shutdown() {
        if (::runner.isInitialized) runner.stop()
    }

    private fun status() = RelayEvent.Status(shell.connected.value, shell.device)

    /** Runs a command, reporting failure as an event rather than throwing. */
    suspend fun execute(command: RemoteCommand): RelayEvent.Error? = try {
        when (command) {
            is ProjectorCommand -> projectorCommand(command)
            is RelayCommand.Draw -> overlay.start()
            is RelayCommand.DrawEnd -> overlay.stop()
            is RelayCommand.Ink, is RelayCommand.InkClear, is RelayCommand.InkUndo ->
                overlay.send(protocolJson.encodeToString(RemoteCommand.serializer(), command))
            is RelayCommand.PlayerSettings -> quirks.playerSettings()
            is RelayCommand.Pointer -> cursor(input.mouseMove(command.dx, command.dy))
            is RelayCommand.Click -> cursor(input.mouseClick())
            is RelayCommand.ShelfAdd -> addToShelf(command.url, command.from)
            is RelayCommand.ShelfRemove -> shelf.remove(command.id)
            is RelayCommand.ShelfProject -> project(command.id)
            is RelayCommand.Ask -> runner.ask(command.text)
            is RelayCommand.AskStop -> runner.stop()
        }
        null
    } catch (e: InvalidCommand) {
        RelayEvent.Error(e.message ?: "invalid command")
    } catch (e: IOException) {
        RelayEvent.Error(e.message ?: "projector unavailable")
    }

    private suspend fun projectorCommand(command: ProjectorCommand) = when {
        command is ProjectorCommand.Media -> media(command.action)
        command is ProjectorCommand.Key && command.key == RemoteKey.Back && quirks.handlesBack() -> quirks.browserBack()
        command is ProjectorCommand.Scroll && projector.state.value.app !in Packages.BROWSERS ->
            focus.steps(command.dx, command.dy, System.currentTimeMillis()).forEach { sender.press(it) }
        command is ProjectorCommand.Open -> {
            quirks.beforeOpening(runCatching { commands.appFor(command.url) }.getOrNull())
            open(command)
            projector.opened(command.url)
        }
        else -> sender.deliver(command)
    }

    private suspend fun media(action: MediaAction) {
        val playing = projector.state.value.nowPlaying
        if (playing == null) {
            // With no live media session, a dispatched key wakes the last media app (YouTube Music)
            // instead; a key event goes to whatever is on screen.
            if (action != MediaAction.PlayPause) throw InvalidCommand("nothing is playing")
            return sender.press(RemoteKey.PlayPause)
        }
        shell.send(commands.lineFor(ProjectorCommand.Media(action)))
        if (action == MediaAction.Rewind || action == MediaAction.FastForward) quirks.afterSeek(playing.app)
    }

    /** Runs `am start` and reads its reply, so a link nothing opened is reported instead of silently lost. */
    private suspend fun open(command: ProjectorCommand.Open) {
        val failure = startFailure(command.url, commands.lineFor(command)) ?: return
        val fallback = commands.openInBrowser(command.url)
        if (!isUnresolved(failure) || fallback == null) throw InvalidCommand("couldn't open the link: $failure")
        log.info("no app took {}; opening it in the browser", command.url)
        startFailure(command.url, fallback)?.let { throw InvalidCommand("couldn't open the link: $it") }
    }

    private suspend fun startFailure(url: String, line: String): String? =
        amStartFailure(shell.exec(line).decodeToString())?.also { log.warn("opening {} failed: {}", url, it) }

    private fun cursor(ok: Boolean) {
        if (!ok) throw ProjectorUnavailable("the cursor isn't available right now")
    }

    private suspend fun addToShelf(raw: String, from: String) {
        val url = extractUrl(raw) ?: throw InvalidCommand("no link found")
        shelf.add(url, commands.appFor(url) ?: "browser", from.take(MAX_FROM))
    }

    private suspend fun project(id: String) {
        val item = shelf.find(id) ?: throw InvalidCommand("that link is no longer on the shelf")
        // Moved only once it's on the wall, so a failed open leaves the link where it was.
        execute(ProjectorCommand.Open(item.url))?.let { error -> throw ProjectorUnavailable(error.message) }
        shelf.shown(item)
    }

    fun install(app: Application) = with(app) {
        runner = ClaudeRunner(config, agent, app, ::broadcast)
        quirks = AppQuirks(sender, shell, { projector.state.value }, app)
        input.inUse = { hasRemotes || agent.driving.value }
        install(WebSockets) { pingPeriod = 15.seconds }
        install(ContentNegotiation) { json(protocolJson) }

        launch { shell.connected.drop(1).collect { broadcast(status()) } }
        launch { projector.state.drop(1).collect { broadcast(RelayEvent.State(it)) } }
        launch { combine(shelf.items, shelf.recent, RelayEvent::Shelf).drop(1).collect { broadcast(it) } }
        val shown = AgentOverlay(overlay, app)
        agentTools = AgentTools(agent, ::execute, screen, { projector.state.value }, shell, shown)
        launch { agent.driving.drop(1).collect { shown.glow(it) } }
        // Ready the overlay app whenever the projector appears, rather than inside Claude's first action.
        launch { shell.connected.filter { it }.collect { runCatching { overlay.prepare() } } }

        routing {
            get("/health") { call.respond<RelayEvent>(status()) }
            webSocket("/ws") { if (fromOurPage()) remoteSession() }
            webSocket("/screen-stream") { if (fromOurPage()) screenSession() }
            shareRoutes(::execute, suggestions, shelf, push)
            screenRoutes()
            mcpRoutes(agentTools.all)
            if (webDir.isDirectory) staticFiles("/", webDir) else log.warn("no remote page at {}; build remote-web first", webDir.absolutePath)
        }
    }

    /**
     * Browsers let any web page open a WebSocket to any address, so a page elsewhere could drive the
     * projector (and ask Claude, which has a shell). Sockets are only taken from the remote's own page.
     */
    private suspend fun DefaultWebSocketServerSession.fromOurPage(): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin]
        if (trustedOrigin(origin, call.request.host())) return true
        log.warn("refused a socket from {}", origin)
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not from the remote's page"))
        return false
    }

    private suspend fun DefaultWebSocketServerSession.remoteSession() {
        remotes += this
        launch { input.warm() }
        try {
            sendEvent(status())
            sendEvent(RelayEvent.State(projector.state.value))
            sendEvent(RelayEvent.Shelf(shelf.items.value, shelf.recent.value))
            runner.current?.let { sendEvent(it) }
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val command = try {
                    protocolJson.decodeFromString<RemoteCommand>(frame.readText())
                } catch (e: IllegalArgumentException) { // includes SerializationException
                    sendEvent(RelayEvent.Error("unreadable command: ${e.message?.lineSequence()?.first()}"))
                    continue
                }
                execute(command)?.let { sendEvent(it) }
            }
        } finally {
            remotes -= this
            // A phone that closed mid-drawing never sends draw-end; the overlay would hold ~90 MB.
            if (remotes.isEmpty()) withContext(NonCancellable) { overlay.stop() }
        }
    }

    /**
     * The live picture: binary messages of [flags, H.264 Annex-B bytes] (see [VideoPacket.wire]).
     * What's needed to decode the current picture goes first, so it shows at once.
     */
    private suspend fun DefaultWebSocketServerSession.screenSession() {
        stream.watch()
        val forward = launch {
            // Subscribe before replaying so nothing emitted in between is lost, then skip what the
            // replay already sent: a repeated frame would corrupt the decoder.
            var replayedUpTo = -1L
            val live = stream.packets.onSubscription {
                for (p in stream.replay()) {
                    send(Frame.Binary(true, p.wire))
                    replayedUpTo = p.seq
                }
            }
            live.filter { it.seq > replayedUpTo }.collect { send(Frame.Binary(true, it.wire)) }
        }
        try {
            // Wait on the phone's side of the socket: a still screen sends no packets, so watching
            // only the outgoing side would never notice the phone leaving and the encoder would
            // keep running on the projector.
            for (frame in incoming) { /* the phone only listens */ }
        } finally {
            forward.cancel()
            stream.unwatch()
        }
    }

    private fun Route.screenRoutes() {
        get("/screen.jpg") {
            try {
                call.response.cacheControl(CacheControl.NoStore(null))
                call.respondBytes(screen.jpeg(), ContentType.Image.JPEG)
            } catch (e: IOException) {
                call.respondText(e.message ?: "no screen", status = HttpStatusCode.ServiceUnavailable)
            }
        }
    }

    private suspend fun broadcast(event: RelayEvent) {
        for (remote in remotes) runCatching { remote.sendEvent(event) }
    }

    private suspend fun DefaultWebSocketServerSession.sendEvent(event: RelayEvent) =
        send(Frame.Text(protocolJson.encodeToString(RelayEvent.serializer(), event)))

    private companion object {
        const val MAX_FROM = 24
    }
}


/**
 * Whether a socket's Origin is the remote's own page: no Origin (not a browser), the address the
 * request came to, or how the remote is reached at home: the tailnet, this machine, or a private address.
 */
fun trustedOrigin(origin: String?, requestHost: String): Boolean {
    if (origin == null) return true
    val host = runCatching { java.net.URI(origin).host }.getOrNull()?.lowercase()?.removeSurrounding("[", "]") ?: return false
    if (host == requestHost.lowercase() || host.endsWith(".ts.net") || host == "localhost" || host.endsWith(".localhost")) return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || host.count { it == '.' } != 3) return host == "::1"
    val (a, b) = parts
    return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
}
