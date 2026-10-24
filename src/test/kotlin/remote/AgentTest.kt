package remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTest {
    @Test
    fun `any call drives the glow and end puts it away`() = runTest {
        val agent = Agent(backgroundScope) { testScheduler.currentTime }
        agent.beforeAction()
        assertTrue(agent.driving.value)
        agent.end()
        assertFalse(agent.driving.value)
        agent.beforeAction()
        assertTrue(agent.driving.value)
    }

    @Test
    fun `silence ends it, but steady calls keep it going`() = runTest {
        val agent = Agent(backgroundScope) { testScheduler.currentTime }
        agent.beforeAction()
        repeat(4) {
            advanceTimeBy(Agent.IDLE_END_MS / 2)
            agent.beforeAction()
        }
        runCurrent()
        assertTrue(agent.driving.value)
        advanceTimeBy(Agent.IDLE_END_MS + 10_000)
        runCurrent()
        assertFalse(agent.driving.value)
    }

    @Test
    fun `stop holds until a new task, whatever Claude calls`() = runTest {
        val agent = Agent(backgroundScope) { testScheduler.currentTime }
        agent.beforeAction()
        agent.stop()
        assertFalse(agent.driving.value)
        assertFailsWith<ToolFailure> { agent.beforeAction() }
        assertFailsWith<ToolFailure> { agent.beforeAction() }
        agent.newTask()
        agent.beforeAction()
        assertTrue(agent.driving.value)
    }
}
