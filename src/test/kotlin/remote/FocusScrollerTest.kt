package remote

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusScrollerTest {
    @Test
    fun `travel becomes one step per threshold, vertical first`() {
        val f = FocusScroller(step = 100.0, resetMs = 400)
        assertEquals(emptyList(), f.steps(0, 60, nowMs = 0))
        assertEquals(listOf(RemoteKey.Down), f.steps(0, 60, nowMs = 10))
        assertEquals(listOf(RemoteKey.Up, RemoteKey.Up), f.steps(0, -220, nowMs = 20))
    }

    @Test
    fun `sideways travel moves left and right`() {
        val f = FocusScroller(step = 100.0, resetMs = 400)
        assertEquals(listOf(RemoteKey.Right), f.steps(150, 0, nowMs = 0))
        assertEquals(listOf(RemoteKey.Left), f.steps(-160, 0, nowMs = 10))
    }

    @Test
    fun `a pause starts counting afresh`() {
        val f = FocusScroller(step = 100.0, resetMs = 400)
        f.steps(0, 90, nowMs = 0)
        assertEquals(emptyList(), f.steps(0, 20, nowMs = 1000))
    }
}
