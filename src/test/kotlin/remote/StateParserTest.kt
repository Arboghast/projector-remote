package remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StateParserTest {
    // Trimmed from the Halo's real `dumpsys media_session`, plus a playing YouTube session.
    private val dump = """
        |MEDIA SESSION SERVICE (dumpsys media_session)
        |  Sessions Stack - have 3 sessions:
        |    BluetoothMediaBrowserService com.android.bluetooth/BluetoothMediaBrowserService (userId=0)
        |      package=com.android.bluetooth
        |      state=PlaybackState {state=7, position=0, buffered position=0, speed=0.0, updated=26939, actions=0, custom actions=[], active item id=-1, error=Bluetooth audio disconnected}
        |      metadata: null
        |    youtube com.google.android.youtube.tv/youtube (userId=0)
        |      package=com.google.android.youtube.tv
        |      state=PlaybackState {state=3, position=60000, buffered position=0, speed=1.0, updated=100000, actions=0, custom actions=[], active item id=-1, error=null}
        |      metadata: size=4, description=How projectors work, Some Channel, null
        |    spotify-android-tv-media-session com.spotify.tv.android/spotify-android-tv-media-session (userId=0)
        |      package=com.spotify.tv.android
        |      state=PlaybackState {state=0, position=0, buffered position=0, speed=1.0, updated=40212, actions=2368383, custom actions=[], active item id=-1, error=null}
        |      metadata: null
    """.trimMargin()

    private fun output(media: String) =
        listOf(media, "    mResumedActivity: ActivityRecord{af71df0 u0 com.google.android.youtube.tv/.MainActivity t50}")
            .joinToString("\n${StateParser.SECTION}\n")

    @Test
    fun `finds the playing session and what it is`() {
        val (app, now) = StateParser.parse(output(dump))
        assertEquals("com.google.android.youtube.tv", app)
        assertEquals(
            NowPlaying("com.google.android.youtube.tv", "How projectors work", "Some Channel", playing = true),
            now,
        )
    }

    @Test
    fun `stopped sessions mean nothing is playing`() {
        val idle = dump.replace("state=3, position=60000", "state=1, position=0")
        assertNull(StateParser.parse(output(idle)).second)
    }

    @Test
    fun `garbage degrades to unknown`() {
        assertEquals(null to null, StateParser.parse("nonsense"))
    }

    @Test
    fun `reads whether the on-screen keyboard is up`() {
        assertEquals(true, StateParser.inputShown("  mShowRequested=true mInputShown=true"))
        assertEquals(false, StateParser.inputShown("  mShowRequested=false mInputShown=false"))
        assertEquals(false, StateParser.inputShown("nonsense"))
    }

    @Test
    fun `a paused session still counts as now playing`() {
        val paused = dump.replace("state=3, position=60000", "state=2, position=60000")
        assertEquals(false, StateParser.parse(output(paused)).second?.playing)
    }
}
