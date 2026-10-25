package remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeRunnerTest {
    @Test
    fun `tool calls become steps in plain words, and start and end say nothing`() {
        val line = """{"type":"assistant","message":{"content":[
            {"type":"tool_use","name":"mcp__projector__start","input":{}},
            {"type":"tool_use","name":"mcp__projector__locate","input":{"target":"the search box"}},
            {"type":"tool_use","name":"mcp__projector__key","input":{"key":"down","count":2}},
            {"type":"tool_use","name":"Bash","input":{"command":"adb shell ls"}}]}}"""
        assertEquals(
            listOf("Looking for the search box", "Pressing down", "Running a command"),
            readStreamLine(line.replace("\n", "")).map { it.text },
        )
    }

    @Test
    fun `what Claude says and thinks is kept apart`() {
        val line = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Search first."},{"type":"text","text":"Opening YouTube."}]}}"""
        assertEquals(listOf("thought" to "Search first.", "said" to "Opening YouTube."), readStreamLine(line).map { it.kind to it.text })
    }

    @Test
    fun `the result ends the run, failed or not`() {
        val done = readStreamLine("""{"type":"result","subtype":"success","is_error":false,"result":"Playing it now."}""").single()
        assertEquals("done" to "Playing it now.", done.phase to done.text)
        val failed = readStreamLine("""{"type":"result","subtype":"error_max_turns","is_error":true}""").single()
        assertEquals("failed", failed.phase)
        assertTrue(failed.text.isNotBlank())
    }

    @Test
    fun `other lines and garbage are ignored`() {
        assertEquals(emptyList(), readStreamLine("""{"type":"system","subtype":"init"}"""))
        assertEquals(emptyList(), readStreamLine("not json"))
        assertEquals(emptyList(), readStreamLine("""{"type":"assistant","message":{"content":"odd"}}"""))
    }

    @Test
    fun `the MCP config points Claude at this relay`() {
        assertTrue("http://127.0.0.1:8790/mcp" in ClaudeRunner.mcpConfig(8790))
    }

    @Test
    fun `markdown marks are taken out of what the phone shows`() {
        assertEquals("It's Sunday, checked with date.", plain("It's **Sunday**, checked with `date`.  "))
    }
}
