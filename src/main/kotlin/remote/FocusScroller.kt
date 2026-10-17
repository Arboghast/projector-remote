package remote

import kotlin.math.abs
import kotlin.math.sign

/**
 * TV apps (YouTube, the home screen…) ignore touch drags and move by focus, so a scroll there is
 * turned into D-pad steps in the same direction, one per [step] units of travel. Vertical travel
 * wins; a pause longer than [resetMs] starts counting afresh.
 */
class FocusScroller(private val step: Double = 130.0, private val resetMs: Long = 400) {
    private var x = 0.0
    private var y = 0.0
    private var lastAt = Long.MIN_VALUE

    @Synchronized
    fun steps(dx: Int, dy: Int, nowMs: Long): List<RemoteKey> {
        if (lastAt == Long.MIN_VALUE || nowMs - lastAt > resetMs) {
            x = 0.0
            y = 0.0
        }
        lastAt = nowMs
        x += dx
        y += dy
        val keys = mutableListOf<RemoteKey>()
        while (abs(y) >= step) {
            keys += if (y < 0) RemoteKey.Up else RemoteKey.Down
            y -= step * sign(y)
            x = 0.0
        }
        while (abs(x) >= step) {
            keys += if (x < 0) RemoteKey.Left else RemoteKey.Right
            x -= step * sign(x)
        }
        return keys
    }
}
