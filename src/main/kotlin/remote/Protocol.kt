package remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
/** [keycode] names the Android key for `input keyevent`; [code] is its number for direct injection. */
enum class RemoteKey(val keycode: String, val code: Int) {
    @SerialName("up") Up("DPAD_UP", 19),
    @SerialName("down") Down("DPAD_DOWN", 20),
    @SerialName("left") Left("DPAD_LEFT", 21),
    @SerialName("right") Right("DPAD_RIGHT", 22),
    @SerialName("ok") Ok("DPAD_CENTER", 23),
    @SerialName("back") Back("BACK", 4),
    @SerialName("home") Home("HOME", 3),
    @SerialName("menu") Menu("MENU", 82),
    @SerialName("playpause") PlayPause("MEDIA_PLAY_PAUSE", 85),
    @SerialName("volup") VolumeUp("VOLUME_UP", 24),
    @SerialName("voldown") VolumeDown("VOLUME_DOWN", 25),
    @SerialName("mute") Mute("VOLUME_MUTE", 164),
    @SerialName("enter") Enter("ENTER", 66),
    @SerialName("delete") Delete("DEL", 67),
    // YouTube answers this with its subtitles picker.
    @SerialName("captions") Captions("CAPTIONS", 175),
}

/** Goes to the active media session regardless of which window has focus, unlike key events. */
@Serializable
enum class MediaAction(val dispatch: String) {
    @SerialName("play-pause") PlayPause("play-pause"),
    @SerialName("next") Next("next"),
    @SerialName("previous") Previous("previous"),
    @SerialName("rewind") Rewind("rewind"),
    @SerialName("fast-forward") FastForward("fast-forward"),
}

// ── Remote → relay ──

@Serializable
sealed interface RemoteCommand

/** Commands carried out on the projector itself (input helper, or the adb shell as a fallback). */
@Serializable
sealed interface ProjectorCommand : RemoteCommand {
    @Serializable @SerialName("key")
    data class Key(val key: RemoteKey) : ProjectorCommand

    /** Typed or dictated text, inserted into whatever field has focus on the projector. */
    @Serializable @SerialName("text")
    data class Text(val text: String) : ProjectorCommand

    /** Positive [dy] scrolls the page down, like a trackpad. */
    @Serializable @SerialName("scroll")
    data class Scroll(val dx: Int = 0, val dy: Int = 0) : ProjectorCommand

    /** Opens the link in the app that suits it (YouTube, Spotify, Twitch), else the browser. */
    @Serializable @SerialName("open")
    data class Open(val url: String) : ProjectorCommand

    /** A tap on the screen preview; [x] and [y] are fractions of the screen (0–1). */
    @Serializable @SerialName("tap")
    data class Tap(val x: Double, val y: Double) : ProjectorCommand

    @Serializable @SerialName("media")
    data class Media(val action: MediaAction) : ProjectorCommand
}

/** Commands the relay handles itself. */
@Serializable
sealed interface RelayCommand : RemoteCommand {

    @Serializable @SerialName("shelf-add")
    data class ShelfAdd(val url: String, val from: String = "") : RelayCommand

    @Serializable @SerialName("shelf-project")
    data class ShelfProject(val id: String) : RelayCommand

    @Serializable @SerialName("shelf-remove")
    data class ShelfRemove(val id: String) : RelayCommand

    /** Moves the on-screen cursor by these pixels (the phone applies acceleration). */
    @Serializable @SerialName("pointer")
    data class Pointer(val dx: Int, val dy: Int) : RelayCommand

    /** Left click at the cursor. */
    @Serializable @SerialName("click")
    data object Click : RelayCommand

    /** Starts drawing live over whatever is on the wall (overlay app). */
    @Serializable @SerialName("draw")
    data object Draw : RelayCommand

    /** Part of a stroke: points are fractions of the wall (0–1); the same id continues a stroke. */
    @Serializable @SerialName("ink")
    data class Ink(val id: String, val points: List<List<Double>>, val color: String, val size: Double, val tool: String = "pen") : RelayCommand

    /** Leaves drawing: hides the live overlay. */
    @Serializable @SerialName("draw-end")
    data object DrawEnd : RelayCommand

    /** Opens the playing app's own settings (quality, audio) where we know how to reach them. */
    @Serializable @SerialName("player-settings")
    data object PlayerSettings : RelayCommand

    @Serializable @SerialName("ink-clear")
    data object InkClear : RelayCommand

    @Serializable @SerialName("ink-undo")
    data object InkUndo : RelayCommand
}

// ── Relay → remote ──

@Serializable
data class NowPlaying(
    val app: String,
    val title: String?,
    val subtitle: String?,
    val playing: Boolean,
)

@Serializable
data class ProjectorState(
    /** Package of the app in front, e.g. com.google.android.youtube.tv. */
    val app: String? = null,
    val nowPlaying: NowPlaying? = null,
    val lastOpened: String? = null,
    /** The projector's on-screen keyboard is up: a text field on the wall is waiting for typing. */
    val keyboard: Boolean = false,
)

@Serializable
data class ShelfItem(
    val id: String,
    val url: String,
    val app: String,
    val from: String,
    val addedAt: Long,
)

@Serializable
sealed interface RelayEvent {
    @Serializable @SerialName("status")
    data class Status(val connected: Boolean, val device: String) : RelayEvent

    @Serializable @SerialName("error")
    data class Error(val message: String) : RelayEvent

    @Serializable @SerialName("state")
    data class State(val state: ProjectorState) : RelayEvent

    @Serializable @SerialName("shelf")
    data class Shelf(val items: List<ShelfItem>) : RelayEvent
}
