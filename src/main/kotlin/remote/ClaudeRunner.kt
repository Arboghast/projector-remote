package remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs Claude Code (`claude -p`) on the relay's machine to carry out a spoken request, with the
 * projector's MCP tools plus a shell (and adb) for anything they can't do. One request at a time;
 * its progress goes to the phones as [RelayEvent.Claude] events.
 */
class ClaudeRunner(
    private val config: Config,
    private val agent: Agent,
    private val scope: CoroutineScope,
    private val report: suspend (RelayEvent.Claude) -> Unit,
) {
    private val log = LoggerFactory.getLogger(ClaudeRunner::class.java)
    @Volatile private var process: Process? = null
    @Volatile private var stopped = false
    private var job: Job? = null

    /** The latest progress, for a phone that opens the remote mid-request. */
    @Volatile var latest: RelayEvent.Claude? = null
        private set

    val working: Boolean get() = job?.isActive == true

    @Synchronized
    fun ask(request: String) {
        val text = request.trim().take(MAX_REQUEST)
        if (text.isEmpty()) throw InvalidCommand("say what you'd like Claude to do")
        if (working) throw InvalidCommand("Claude is still on the last request; stop it first")
        stopped = false
        agent.newTask()
        job = scope.launch(Dispatchers.IO) { run(text) }
    }

    /** A person pressed Stop: end the run, and with it the glow. */
    fun stop() {
        stopped = true
        agent.stop()
        process?.let { p ->
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
        }
    }

    private suspend fun publish(event: RelayEvent.Claude) {
        latest = event
        report(event)
    }

    private suspend fun run(text: String) {
        publish(RelayEvent.Claude("working", "On it…"))
        val outcome = try {
            launchAndFollow(text)
        } catch (e: Exception) {
            log.warn("claude run failed", e)
            RelayEvent.Claude("failed", "Couldn't start Claude: ${e.message}")
        } finally {
            process = null
            agent.end()
        }
        publish(if (stopped) RelayEvent.Claude("stopped", "Stopped.") else outcome)
    }

    private suspend fun launchAndFollow(text: String): RelayEvent.Claude {
        val dir = File(config.claudeDir).apply { mkdirs() }
        val mcp = File(dir, "mcp.json").apply { writeText(mcpConfig(config.port)) }
        val command = buildList {
            addAll(listOf(config.claudeBin, "-p", "--output-format", "stream-json", "--verbose"))
            addAll(listOf("--mcp-config", mcp.absolutePath, "--strict-mcp-config"))
            addAll(listOf("--allowedTools", ALLOWED_TOOLS, "--append-system-prompt", instructions(config.device)))
            config.claudeModel?.let { addAll(listOf("--model", it)) }
        }
        val p = ProcessBuilder(command).directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = p
        // The request goes in on stdin: as an argument, one starting with "-" would read as a flag.
        p.outputStream.bufferedWriter().use { it.write(text) }
        val watchdog = scope.launch(Dispatchers.IO) {
            if (!p.waitFor(MAX_RUN_MINUTES, TimeUnit.MINUTES)) p.destroyForcibly()
        }
        var result: RelayEvent.Claude? = null
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    for (event in readStreamLine(line)) {
                        if (event.phase == "working") publish(event) else result = event
                    }
                }
            }
            withContext(Dispatchers.IO) { p.waitFor() }
        } finally {
            watchdog.cancel()
        }
        return result ?: RelayEvent.Claude("failed", "Claude stopped without an answer (exit ${p.exitValue()}).")
    }

    companion object {
        private const val MAX_REQUEST = 2_000
        private const val MAX_RUN_MINUTES = 6L
        private const val ALLOWED_TOOLS = "mcp__projector,Bash,Read,Grep,Glob,WebSearch,WebFetch"

        fun mcpConfig(port: Int) = """{"mcpServers":{"projector":{"type":"http","url":"http://127.0.0.1:$port/mcp"}}}"""

        fun instructions(device: String) = """
            Someone in the living room asked for this by voice, from the projector remote on their phone.
            Carry it out on the projector with the projector tools: call start first and end when done, so the wall glows while you work.
            Prefer the projector tools, because people watch your cursor and captions on the wall. If they can't do something, you may use
            the shell, including `adb -s $device shell ...`, but keep it to what the request needs.
            Be quick; people are waiting. Finish with one or two plain sentences saying what you did, or what stopped you.
        """.trimIndent()
    }
}

private val streamJson = Json { ignoreUnknownKeys = true }

/**
 * What one line of `claude -p --output-format stream-json` means for the phone: steps as Claude
 * works (what it's doing, what it says, what it's thinking), then how it ended.
 */
fun readStreamLine(line: String): List<RelayEvent.Claude> {
    val event = runCatching { streamJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return emptyList()
    return when (event.str("type")) {
        "assistant" -> ((event["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> b.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { RelayEvent.Claude("working", it, kind = "said") }
                "thinking" -> b.str("thinking")?.trim()?.takeIf { it.isNotEmpty() }?.let { RelayEvent.Claude("working", it, kind = "thought") }
                "tool_use" -> stepFor(b.str("name").orEmpty(), b["input"] as? JsonObject)?.let { RelayEvent.Claude("working", it, kind = "step") }
                else -> null
            }
        }
        "result" -> {
            val failed = (event["is_error"] as? JsonPrimitive)?.booleanOrNull == true || event.str("subtype") != "success"
            val answer = event.str("result")?.trim().orEmpty()
            listOf(RelayEvent.Claude(if (failed) "failed" else "done", answer.ifEmpty { if (failed) "Something went wrong." else "Done." }))
        }
        else -> emptyList()
    }
}

/** A tool call in words people would use; start and end say nothing (the glow says it). */
private fun stepFor(tool: String, input: JsonObject?): String? {
    val arg = { name: String -> input?.str(name) }
    return when (tool.removePrefix("mcp__projector__")) {
        "start", "end" -> null
        "screenshot", "what_is_on" -> "Looking at the wall"
        "locate" -> "Looking for ${arg("target") ?: "something"}"
        "tap" -> "Tapping"
        "key" -> "Pressing ${arg("key") ?: "a button"}"
        "media" -> arg("action")?.replace('-', ' ')?.replaceFirstChar { it.uppercase() } ?: "Controlling playback"
        "type" -> "Typing"
        "scroll" -> "Scrolling ${arg("direction").orEmpty()}".trim()
        "open" -> "Opening ${arg("url") ?: "a link"}"
        "Bash" -> "Running a command"
        "WebSearch" -> "Searching the web"
        "WebFetch" -> "Reading a web page"
        else -> "Working"
    }
}

private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
