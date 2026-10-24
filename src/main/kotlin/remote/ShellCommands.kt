package remote

import java.net.URI

class InvalidCommand(message: String) : IllegalArgumentException(message)

/** Turns projector commands into lines for the projector's `adb shell`. */
class ShellCommands(private val browser: String?) {

    fun lineFor(command: ProjectorCommand): String = when (command) {
        is ProjectorCommand.Key -> "input keyevent KEYCODE_${command.key.keycode}"
        is ProjectorCommand.Text -> text(command.text)
        is ProjectorCommand.Scroll -> scroll(command.dx, command.dy)
        is ProjectorCommand.Open -> open(command.url)
        is ProjectorCommand.Tap -> tap(command.x, command.y)
        is ProjectorCommand.Media -> "cmd media_session dispatch ${command.action.dispatch}"
    }

    /** The package a link opens in; the browser (or null, letting Android choose) for ordinary pages. */
    fun appFor(url: String): String? {
        val host = webUri(url).host.lowercase().removePrefix("www.").removePrefix("m.")
        return APPS.entries.firstOrNull { (hosts, _) -> host in hosts }?.value ?: browser
    }

    private fun text(raw: String): String {
        // `input text` only types printable ASCII and reads "%s" as a space.
        val typable = raw.filter { it in ' '..'~' }
        if (typable.isBlank()) throw InvalidCommand("nothing typable in text (only plain ASCII is supported)")
        return "input text ${quote(typable.replace(" ", "%s"))}"
    }

    private fun scroll(dx: Int, dy: Int): String {
        val x = dx.coerceIn(-MAX_SWIPE, MAX_SWIPE)
        val y = dy.coerceIn(-MAX_SWIPE, MAX_SWIPE)
        // The finger moves against the scroll direction.
        return "input swipe $CENTER_X $CENTER_Y ${CENTER_X - x} ${CENTER_Y - y} $SWIPE_MS"
    }

    private fun tap(x: Double, y: Double): String {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) throw InvalidCommand("tap is outside the screen")
        return "input tap ${(x * (SCREEN_W - 1)).toInt()} ${(y * (SCREEN_H - 1)).toInt()}"
    }

    /** Opens the link in the browser (or Android's choice): the fallback when the link's own app can't take it. */
    fun openInBrowser(url: String): String? = if (appFor(url) == browser) null else open(url, browser)

    private fun open(url: String, app: String? = appFor(url)): String {
        val uri = webUri(url)
        val target = app?.let { " -p ${quote(it)}" } ?: ""
        // TV Bro opens every incoming link in a new tab, building a WebView each time; on this
        // projector that eventually fails with "Can not link to WebView component" and tabs pile up.
        // Its own extra reuses the current tab instead.
        val sameTab = if (app == Packages.TV_BRO) " --ez com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB true" else ""
        val data = if (app == Packages.TWITCH) twitchLink(uri) else uri.toString()
        return "am start -a android.intent.action.VIEW -d ${quote(data)}$target$sameTab"
    }

    /**
     * The Twitch TV app ignores https://twitch.tv links (it has no handler, so `am start` quietly
     * fails); it only takes its own twitch://stream|video|clip/<id> links.
     */
    private fun twitchLink(uri: URI): String {
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val first = parts.firstOrNull()
        return when {
            uri.host.lowercase() == "clips.twitch.tv" && first != null -> "twitch://clip/$first"
            first == "videos" && parts.size > 1 -> "twitch://video/${parts[1]}"
            parts.size > 2 && parts[1] == "clip" -> "twitch://clip/${parts[2]}"
            first != null && first !in TWITCH_PAGES -> "twitch://stream/$first"
            else -> "twitch://home"
        }
    }

    private fun webUri(url: String): URI =
        runCatching { URI(url.trim()) }.getOrNull()
            ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() }
            ?: throw InvalidCommand("not an http(s) link: $url")

    private companion object {
        const val SCREEN_W = Wall.WIDTH
        const val SCREEN_H = Wall.HEIGHT
        const val CENTER_X = SCREEN_W / 2
        const val CENTER_Y = SCREEN_H / 2
        const val MAX_SWIPE = 500
        const val SWIPE_MS = 150

        val APPS = mapOf(
            setOf("youtube.com", "youtu.be") to Packages.YOUTUBE,
            setOf("open.spotify.com") to Packages.SPOTIFY,
            setOf("twitch.tv", "clips.twitch.tv") to Packages.TWITCH,
        )

        /** twitch.tv paths that are site pages, not channel names. */
        val TWITCH_PAGES = setOf("directory", "search", "settings", "videos", "downloads", "p", "jobs", "turbo")

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** `am start` exits 0 even when nothing opened; its "Error:" line is the only sign. */
fun amStartFailure(reply: String): String? =
    reply.lineSequence().firstOrNull { it.startsWith("Error") }?.removePrefix("Error:")?.trim()

/** No app on the projector takes this link (as opposed to the app refusing to start). */
fun isUnresolved(failure: String) = "unable to resolve" in failure

private val urlPattern = Regex("""https?://[^\s<>"']+""")

/** The first web link in shared text, without sentence punctuation stuck to its end. */
fun extractUrl(text: String?): String? = text?.let { urlPattern.find(it)?.value?.trimEnd(')', '.', ',', ';', '!', '?', ']') }
