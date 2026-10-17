package remote

/**
 * Parses the projector's dumpsys output. Formats differ between Android builds, so every match is
 * narrow and anything unexpected degrades to "unknown" (null) rather than failing.
 */
object StateParser {
    const val SECTION = "___SECTION___"

    /** One adb call that gathers everything [parse] needs. */
    val POLL_SCRIPT = listOf(
        "dumpsys media_session",
        "echo $SECTION",
        "dumpsys activity activities | grep -m1 mResumedActivity",
    ).joinToString("; ")

    /** Polled on its own, more often: see [inputShown]. */
    const val KEYBOARD_SCRIPT = "dumpsys input_method | grep -m1 mInputShown"

    private val sessionHeader = Regex("""^ {4}\S.* ([\w.]+)/\S+ \(userId=\d+\)$""")
    private val playback = Regex("""state=PlaybackState \{state=(\d+),""")
    private val description = Regex("""metadata: size=\d+, description=(.*)$""")
    private val resumed = Regex("""mResumedActivity: ActivityRecord\{\S+ \S+ ([\w.]+)/""")

    private const val STATE_PAUSED = 2
    private const val STATE_PLAYING = 3
    private val IGNORED = setOf("com.android.bluetooth")

    /** Whether the projector's on-screen keyboard is up, i.e. something on the wall wants typing. */
    fun inputShown(output: String): Boolean = "mInputShown=true" in output

    fun parse(output: String): Pair<String?, NowPlaying?> {
        val parts = output.split(SECTION)
        val app = parts.getOrNull(1)?.let { resumed.find(it)?.groupValues?.get(1) }
        return app to nowPlaying(parts.firstOrNull().orEmpty())
    }

    private data class Session(val app: String, val state: Int, val description: String?)

    private fun nowPlaying(dump: String): NowPlaying? {
        val sessions = sessions(dump).filter { it.app !in IGNORED }
        val chosen = sessions.firstOrNull { it.state == STATE_PLAYING }
            ?: sessions.firstOrNull { it.state == STATE_PAUSED }
            ?: return null
        val playing = chosen.state == STATE_PLAYING
        // description is "Title, Subtitle, Description"; absent pieces print as "null".
        val pieces = chosen.description?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() && it != "null" }.orEmpty()
        return NowPlaying(
            app = chosen.app,
            title = pieces.firstOrNull(),
            subtitle = pieces.drop(1).joinToString(" · ").ifEmpty { null },
            playing = playing,
        )
    }

    private fun sessions(dump: String): List<Session> {
        val result = mutableListOf<Session>()
        var app: String? = null
        var match: MatchResult? = null
        var desc: String? = null

        fun flush() {
            val a = app
            val m = match
            if (a != null && m != null) {
                result += Session(a, m.groupValues[1].toInt(), desc)
            }
            app = null; match = null; desc = null
        }

        for (line in dump.lineSequence()) {
            sessionHeader.find(line)?.let {
                flush()
                app = it.groupValues[1]
                return@let
            }
            if (app == null) continue
            playback.find(line)?.let { match = it }
            description.find(line.trim())?.let { desc = it.groupValues[1] }
        }
        flush()
        return result
    }
}
