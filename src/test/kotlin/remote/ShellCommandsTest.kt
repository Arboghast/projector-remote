package remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellCommandsTest {
    private val commands = ShellCommands(browser = null)
    private val withBrowser = ShellCommands(browser = "com.phlox.tvwebbrowser")

    @Test
    fun `keys map to Android keycodes`() {
        assertEquals("input keyevent KEYCODE_DPAD_CENTER", commands.lineFor(ProjectorCommand.Key(RemoteKey.Ok)))
    }

    @Test
    fun `text escapes spaces and single quotes`() {
        assertEquals("input text 'it'\\''s%sfine'", commands.lineFor(ProjectorCommand.Text("it's fine")))
    }

    @Test
    fun `text drops characters adb cannot type`() {
        assertEquals("input text 'caf'", commands.lineFor(ProjectorCommand.Text("café")))
        assertFailsWith<InvalidCommand> { commands.lineFor(ProjectorCommand.Text("日本")) }
    }

    @Test
    fun `scroll down swipes the finger up and clamps big deltas`() {
        assertEquals("input swipe 960 540 960 40 150", commands.lineFor(ProjectorCommand.Scroll(dy = 9999)))
    }

    @Test
    fun `taps scale preview fractions to screen pixels`() {
        assertEquals("input tap 959 539", commands.lineFor(ProjectorCommand.Tap(0.5, 0.5)))
        assertFailsWith<InvalidCommand> { commands.lineFor(ProjectorCommand.Tap(1.2, 0.5)) }
    }

    @Test
    fun `media actions use the platform's dispatch names`() {
        assertEquals(
            "cmd media_session dispatch fast-forward",
            commands.lineFor(ProjectorCommand.Media(MediaAction.FastForward)),
        )
        assertEquals("cmd media_session dispatch next", commands.lineFor(ProjectorCommand.Media(MediaAction.Next)))
    }

    @Test
    fun `ordinary pages open in the configured browser`() {
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'https://example.com/a?b=1&c=2' -p 'com.phlox.tvwebbrowser'" +
                " --ez com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB true",
            withBrowser.lineFor(ProjectorCommand.Open("https://example.com/a?b=1&c=2")),
        )
    }

    @Test
    fun `media links open in their own apps`() {
        assertEquals("com.google.android.youtube.tv", withBrowser.appFor("https://www.youtube.com/watch?v=abc"))
        assertEquals("com.google.android.youtube.tv", withBrowser.appFor("https://youtu.be/abc"))
        assertEquals("com.google.android.youtube.tv", withBrowser.appFor("https://music.youtube.com/watch?v=abc"))
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=abc' -p 'com.google.android.youtube.tv'",
            withBrowser.lineFor(ProjectorCommand.Open("https://music.youtube.com/watch?v=abc")),
        )
        assertEquals("com.spotify.tv.android", withBrowser.appFor("https://open.spotify.com/track/x"))
        assertEquals("com.phlox.tvwebbrowser", withBrowser.appFor("https://en.wikipedia.org/wiki/Projector"))
    }

    @Test
    fun `twitch links become the TV app's own links`() {
        fun data(url: String) = withBrowser.lineFor(ProjectorCommand.Open(url)).substringAfter("-d '").substringBefore("'")
        assertEquals("twitch://stream/somechannel", data("https://m.twitch.tv/somechannel?sr=a"))
        assertEquals("twitch://stream/somechannel", data("https://www.twitch.tv/somechannel/about"))
        assertEquals("twitch://video/123456", data("https://www.twitch.tv/videos/123456"))
        assertEquals("twitch://clip/FunnyClip-abc", data("https://www.twitch.tv/somechannel/clip/FunnyClip-abc"))
        assertEquals("twitch://clip/FunnyClip-abc", data("https://clips.twitch.tv/FunnyClip-abc"))
        assertEquals("twitch://home", data("https://www.twitch.tv/directory/category/chess"))
    }

    @Test
    fun `a link no app takes falls back to the browser`() {
        val reply = "Starting: Intent { ... }\nError: Activity not started, unable to resolve Intent { act=... }\n"
        val failure = amStartFailure(reply)
        assertEquals("Activity not started, unable to resolve Intent { act=... }", failure)
        assertEquals(true, failure?.let(::isUnresolved))
        assertEquals(null, amStartFailure("Starting: Intent { ... }\nWarning: Activity not started, intent has been delivered to currently running top-most instance."))
        assertEquals(true, withBrowser.openInBrowser("https://open.spotify.com/track/x")?.contains("-p 'com.phlox.tvwebbrowser'"))
        assertEquals(null, withBrowser.openInBrowser("https://example.com"))
    }

    @Test
    fun `open rejects non-web links`() {
        assertFailsWith<InvalidCommand> { commands.lineFor(ProjectorCommand.Open("file:///sdcard/x")) }
        assertFailsWith<InvalidCommand> { commands.lineFor(ProjectorCommand.Open("javascript:alert(1)")) }
    }

    @Test
    fun `commands decode from the wire format`() {
        assertEquals(
            ProjectorCommand.Key(RemoteKey.Back),
            protocolJson.decodeFromString<RemoteCommand>("""{"type":"key","key":"back"}"""),
        )
        assertEquals(
            RelayCommand.ShelfProject("x"),
            protocolJson.decodeFromString<RemoteCommand>("""{"type":"shelf-project","id":"x"}"""),
        )
    }

    @Test
    fun `shared text yields its link`() {
        assertEquals("https://example.com/x", extractUrl("Look at this https://example.com/x cool"))
    }

    @Test
    fun `sentence punctuation around a shared link is dropped`() {
        assertEquals("https://x.com/a", extractUrl("(see https://x.com/a)."))
        assertEquals("https://x.com/a?b=1", extractUrl("https://x.com/a?b=1!"))
    }
}
