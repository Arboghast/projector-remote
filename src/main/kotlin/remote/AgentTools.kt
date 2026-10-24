package remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What Claude is doing, shown on the wall (the overlay's cursor, ripples and captions). */
interface AgentShow {
    fun point(x: Double, y: Double)
    fun tap(x: Double, y: Double)
    fun say(text: String)

    /** Clears the cursor and caption, so a screenshot shows the wall rather than Claude's marks. */
    fun quiet()
}

/**
 * The projector as tools for Claude. Screenshots are [SHOT_W]×[SHOT_H] and every position Claude
 * gives or gets is in that picture's pixels, so it can point at what it sees.
 */
class AgentTools(
    private val agent: Agent,
    private val execute: suspend (RemoteCommand) -> RelayEvent.Error?,
    private val screen: ScreenCapture,
    private val state: () -> ProjectorState,
    private val shell: AdbShell,
    private val show: AgentShow,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var lastActionAt = 0L

    val all: List<McpTool> = listOf(
        tool("start", "Call before your first action: the wall starts glowing so people can see you're in control.") {
            agent.beforeAction()
            text("You're in control; the wall is glowing. Call end when you're done.")
        },
        tool("end", "Call when the task is done (or you give up): the glow fades and control goes back to people.") {
            agent.end()
            text("Done; the glow is fading.")
        },
        tool(
            "screenshot",
            "What's on the wall now, as a ${SHOT_W}×$SHOT_H picture; positions you pass to tap are in its pixels. " +
                "It waits a moment after your last action so the screen has settled. The soft orange border is your own glow, not part of the app.",
        ) {
            agent.beforeAction()
            show.quiet()
            settle()
            delay(QUIET_MS)
            listOf(McpContent.Image(screen.jpeg(SHOT_W, maxAgeMs = 0), "image/jpeg"))
        },
        tool("what_is_on", "Cheaper than a screenshot: the app in front, what's playing, the last link opened and whether a text field wants typing.") {
            agent.beforeAction()
            text(describe(state()))
        },
        tool(
            "locate",
            "Finds something on the wall, or whether it's there at all: a button, a field, a result, a section of an article, a prompt. " +
                "Answers where it is (to tap, or to move focus to), which way to go if it's off screen, or that it isn't there.",
            props = { str("target", "What to find, as you'd describe it: \"search box\", \"Episode 3\", \"the conclusion\", \"a sign-in prompt\"") },
            required = listOf("target"),
        ) { args ->
            agent.beforeAction()
            settle()
            text(locate(args.string("target")))
        },
        tool(
            "tap",
            "Taps a point on the wall, in screenshot pixels. Works for web pages and touch-friendly apps; TV apps usually want key instead.",
            props = { num("x", "0–$SHOT_W"); num("y", "0–$SHOT_H") },
            required = listOf("x", "y"),
        ) { args ->
            agent.beforeAction()
            val x = args.number("x")
            val y = args.number("y")
            if (x !in 0.0..SHOT_W.toDouble() || y !in 0.0..SHOT_H.toDouble()) throw ToolFailure("($x, $y) is outside the ${SHOT_W}×$SHOT_H screenshot")
            // The cursor glides there first, so people see where Claude is about to touch.
            show.point(x / SHOT_W, y / SHOT_H)
            delay(GLIDE_MS)
            act(ProjectorCommand.Tap(x / SHOT_W, y / SHOT_H))
            show.tap(x / SHOT_W, y / SHOT_H)
            text("Tapped (${x.roundToInt()}, ${y.roundToInt()}).")
        },
        tool(
            "key",
            "Presses a remote button. up/down/left/right move focus, ok selects, back goes back, home leaves the app, menu opens options. " +
                "count repeats it (e.g. down ×4 to move four rows); hold makes it a long press (often opens a context menu).",
            props = {
                enum("key", RemoteKey.entries.map { it.wire })
                int("count", "How many times, 1–20 (default 1)")
                bool("hold", "Long press instead of a tap (default false)")
            },
            required = listOf("key"),
        ) { args ->
            agent.beforeAction()
            val name = args.string("key")
            val key = RemoteKey.entries.firstOrNull { it.wire == name } ?: throw ToolFailure("no key named $name")
            val count = args.int("count")?.coerceIn(1, MAX_REPEAT) ?: 1
            val hold = args.flag("hold")
            show.say(keyCaption(key, count, hold))
            if (hold) {
                lastActionAt = now()
                shell.send("input keyevent --longpress KEYCODE_${key.keycode}")
            } else {
                repeat(count) {
                    act(ProjectorCommand.Key(key))
                    if (count > 1) delay(REPEAT_GAP_MS)
                }
            }
            text("Pressed $name${if (hold) " (held)" else if (count > 1) " ×$count" else ""}.")
        },
        tool(
            "media",
            "Controls whatever is playing, whichever app has focus: play-pause, next, previous, rewind or fast-forward (10 s).",
            props = { enum("action", MediaAction.entries.map { it.wire }) },
            required = listOf("action"),
        ) { args ->
            agent.beforeAction()
            val name = args.string("action")
            val action = MediaAction.entries.firstOrNull { it.wire == name } ?: throw ToolFailure("no media action named $name")
            show.say(name.replace('-', ' ').replaceFirstChar { it.uppercase() })
            act(ProjectorCommand.Media(action))
            text("Sent $name.")
        },
        tool(
            "type",
            "Types text into the field that has focus on the wall (what_is_on says whether one is waiting). enter presses Enter afterwards, e.g. to search.",
            props = {
                str("text", "What to type")
                bool("enter", "Press Enter afterwards (default false)")
            },
            required = listOf("text"),
        ) { args ->
            agent.beforeAction()
            // Only "Typing…": it may be a password or a private search, and the room can see the wall.
            show.say("Typing…")
            act(ProjectorCommand.Text(args.string("text")))
            if (args.flag("enter")) act(ProjectorCommand.Key(RemoteKey.Enter))
            text("Typed it.")
        },
        tool(
            "scroll",
            "Scrolls what's on the wall. In the browser the page glides by amount (fractions of a screen: 0.3 a nudge, 1 a screen). " +
                "In TV apps it moves focus instead, one item per 0.2 of amount; key with count is more precise there.",
            props = {
                enum("direction", listOf("up", "down", "left", "right"))
                num("amount", "How far, 0.1–3 (default 0.6)")
            },
            required = listOf("direction"),
        ) { args ->
            agent.beforeAction()
            val amount = args.number("amount", default = 0.6).coerceIn(0.1, 3.0)
            val direction = args.string("direction")
            val (dx, dy, key) = when (direction) {
                "up" -> Triple(0.0, -amount * Wall.HEIGHT, RemoteKey.Up)
                "down" -> Triple(0.0, amount * Wall.HEIGHT, RemoteKey.Down)
                "left" -> Triple(-amount * Wall.WIDTH, 0.0, RemoteKey.Left)
                "right" -> Triple(amount * Wall.WIDTH, 0.0, RemoteKey.Right)
                else -> throw ToolFailure("direction must be up, down, left or right")
            }
            show.say("Scrolling $direction")
            if (state().app in Packages.BROWSERS) {
                act(ProjectorCommand.Scroll(dx.roundToInt(), dy.roundToInt()))
            } else {
                repeat(ceil(amount / FOCUS_STEP).toInt()) {
                    act(ProjectorCommand.Key(key))
                    delay(REPEAT_GAP_MS)
                }
            }
            text("Scrolled $direction.")
        },
        tool(
            "open",
            "Opens a link on the wall in the app that suits it (YouTube, Spotify, Twitch), else the browser. Often the fastest way to get somewhere.",
            props = { str("url", "The link, starting with http:// or https://") },
            required = listOf("url"),
        ) { args ->
            agent.beforeAction()
            val given = args.string("url")
            val url = extractUrl(given) ?: throw ToolFailure("not a link: $given")
            show.say("Opening ${runCatching { URI(url).host.removePrefix("www.") }.getOrNull() ?: url}")
            act(ProjectorCommand.Open(url))
            text("Opening $url.")
        },
    )

    private suspend fun act(command: RemoteCommand) {
        lastActionAt = now()
        execute(command)?.let { throw ToolFailure(it.message) }
    }

    /** Lets an animation or page load that the last action started finish before looking. */
    private suspend fun settle() {
        val wait = SETTLE_MS - (now() - lastActionAt)
        if (wait > 0) delay(wait)
    }

    // ── locate ──

    private suspend fun locate(target: String): String {
        show.say("Looking for $target")
        val dump = shell.exec("uiautomator dump /dev/tty", timeoutSeconds = DUMP_TIMEOUT_S).decodeToString()
        val nodes = parseUiDump(dump)
        if (nodes.isEmpty()) {
            val why = dump.lineSequence().firstOrNull { it.startsWith("ERROR") }
                ?.let { "Android said \"${it.trim()}\" (usually video or an animation still playing): try again in a moment, or" }
                ?: "This app draws its own screen (video players and some apps do), so"
            return "Can't read the layout. $why take a screenshot and look for \"$target\" yourself."
        }
        val matches = findNodes(nodes, target)
        if (matches.isNotEmpty()) {
            return "On screen:\n" + matches.joinToString("\n") { "- ${place(it)}" } +
                "\nTap its centre, or move focus to it with the arrow keys and press ok."
        }
        return buildString {
            // A browser's page is one opaque WebView to Android: its text never reaches the layout tree.
            if (nodes.any { "WebView" in it.kind && it.box.coversHalf() }) {
                append("This is a web page, whose content Android can't read out: take a screenshot and look for \"$target\" there.")
                offPage(target)?.let { append("\n$it") }
                return@buildString
            }
            append("\"$target\" isn't on screen.")
            offPage(target)?.let { append("\n$it") }
            nodes.firstOrNull { it.focused }?.let { append("\nFocus is on: ${place(it)}.") }
            if (nodes.any { it.scrollable }) append("\nPart of the screen scrolls, so it may be further along: scroll and locate again.")
            // Only what a person can read; ids like "rlRoot" are noise here.
            val visible = nodes.map { it.text.ifBlank { it.description } }.filter { it.isNotBlank() }.distinct().take(VISIBLE_SAMPLE)
            if (visible.isNotEmpty()) append("\nVisible now: ${visible.joinToString(" · ")}")
        }
    }

    private fun place(node: UiNode): String {
        val b = node.box
        val pressable = if (node.clickable) ", pressable" else ""
        val focus = if (node.focused) ", has focus" else ""
        return "\"${node.label}\" (${node.kind}$pressable$focus) centre (${shot(b.centerX)}, ${shot(b.centerY)}), " +
            "box ${shot(b.left)},${shot(b.top)}–${shot(b.right)},${shot(b.bottom)}"
    }

    /** For a web page, where the target sits on the whole page, beyond what's on screen. */
    private suspend fun offPage(target: String): String? {
        val s = state()
        val url = s.lastOpened ?: return null
        if (s.app !in Packages.BROWSERS) return null
        val outline = try {
            pageOutline(fetch(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (outline.isEmpty()) return null
        // The page is fetched afresh from its link, so it's the page as first opened.
        val hit = outline.maxByOrNull { matchScore(it.text, target) }?.takeIf { matchScore(it.text, target) >= MIN_SCORE }
            ?: return "The page ($url) has these sections: " + outline.take(VISIBLE_SAMPLE).joinToString(" · ") { "${it.text} (${pct(it.depth)})" }
        return "On the page ($url), \"${hit.text}\" comes roughly ${pct(hit.depth)} of the way through its text: scroll down toward it and locate again."
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val uri = URI(url)
        if (uri.scheme !in setOf("http", "https")) throw IllegalArgumentException("not a web page")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0").header("Accept", "text/html").build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            val type = response.headers().firstValue("Content-Type").orElse("")
            if ("html" !in type) throw IllegalArgumentException("not HTML")
            // Read a bounded amount: a huge or endless page must not fill the relay's memory.
            String(body.readNBytes(MAX_PAGE_BYTES), Charsets.UTF_8)
        }
    }

    // ── helpers ──

    private fun describe(s: ProjectorState) = buildString {
        append("App in front: ${s.app ?: "unknown"}")
        s.nowPlaying?.let { append("\nPlaying: ${it.title ?: "untitled"}${it.subtitle?.let { sub -> " — $sub" } ?: ""} (${if (it.playing) "playing" else "paused"}) in ${it.app}") }
        s.lastOpened?.let { append("\nLast link opened: $it") }
        append("\nText field waiting for typing: ${if (s.keyboard) "yes" else "no"}")
    }

    private fun keyCaption(key: RemoteKey, count: Int, hold: Boolean): String {
        val label = KEY_LABELS[key] ?: key.wire
        return label + if (hold) " (hold)" else if (count > 1) " ×$count" else ""
    }

    private fun Box.coversHalf() = (right - left).toLong() * (bottom - top) >= Wall.WIDTH.toLong() * Wall.HEIGHT / 2

    private fun shot(screenPx: Int) = screenPx * SHOT_W / Wall.WIDTH
    private fun pct(depth: Double) = "${(depth * 100).roundToInt()}%"
    private fun text(s: String) = listOf(McpContent.Text(s))

    private fun tool(
        name: String,
        description: String,
        props: (PropsBuilder.() -> Unit)? = null,
        required: List<String> = emptyList(),
        run: suspend (JsonObject) -> List<McpContent>,
    ) = McpTool(name, description, buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props?.let { PropsBuilder(this).it() } }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }, run)

    private class PropsBuilder(private val target: JsonObjectBuilder) {
        fun str(name: String, description: String) = prop(name, "string", description)
        fun num(name: String, description: String) = prop(name, "number", description)
        fun int(name: String, description: String) = prop(name, "integer", description)
        fun bool(name: String, description: String) = prop(name, "boolean", description)
        fun enum(name: String, values: List<String>) = target.putJsonObject(name) {
            put("type", "string")
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        }
        private fun prop(name: String, type: String, description: String) = target.putJsonObject(name) {
            put("type", type)
            put("description", description)
        }
    }

    private fun JsonObject.primitive(name: String) = this[name] as? JsonPrimitive

    private fun JsonObject.string(name: String) =
        primitive(name)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ToolFailure("$name is required")

    private fun JsonObject.number(name: String, default: Double? = null) =
        primitive(name)?.doubleOrNull ?: default ?: throw ToolFailure("$name must be a number")

    private fun JsonObject.int(name: String) = primitive(name)?.intOrNull
    private fun JsonObject.flag(name: String) = primitive(name)?.booleanOrNull == true

    companion object {
        const val SHOT_W = 1280
        const val SHOT_H = 720
        private const val GLIDE_MS = 420L
        private const val SETTLE_MS = 700L
        private const val REPEAT_GAP_MS = 120L
        private const val MAX_REPEAT = 20
        private const val FOCUS_STEP = 0.2
        private const val DUMP_TIMEOUT_S = 8L
        private const val VISIBLE_SAMPLE = 20
        private const val QUIET_MS = 200L
        private const val MAX_PAGE_BYTES = 2_000_000

        private val KEY_LABELS = mapOf(
            RemoteKey.Up to "↑", RemoteKey.Down to "↓", RemoteKey.Left to "←", RemoteKey.Right to "→",
            RemoteKey.Ok to "OK", RemoteKey.Back to "Back", RemoteKey.Home to "Home", RemoteKey.Menu to "Menu",
            RemoteKey.Enter to "Enter", RemoteKey.Delete to "Delete",
        )
    }
}
