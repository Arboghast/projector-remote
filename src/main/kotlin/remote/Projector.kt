package remote

/** The projector's screen; every tap, swipe and capture is in these pixels. */
object Wall {
    const val WIDTH = 1920
    const val HEIGHT = 1080
}

/** Apps on the projector that the remote treats specially. */
object Packages {
    const val TV_BRO = "com.phlox.tvwebbrowser"
    const val YOUTUBE = "com.google.android.youtube.tv"
    const val YOUTUBE_MUSIC = "com.google.android.youtube.tvmusic"
    const val SPOTIFY = "com.spotify.tv.android"
    const val TWITCH = "tv.twitch.android.app"

    /** Apps that scroll by touch; every other app moves by focus. */
    val BROWSERS = setOf(TV_BRO, "com.tcl.browser")
}
