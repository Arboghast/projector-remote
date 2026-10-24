package remote

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Base64

/** What a tool hands back to Claude. */
sealed interface McpContent {
    data class Text(val text: String) : McpContent
    data class Image(val bytes: ByteArray, val mimeType: String) : McpContent
}

/** One tool Claude can call; [schema] is the JSON Schema of its arguments. */
class McpTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val run: suspend (args: JsonObject) -> List<McpContent>,
)

/** A failure Claude should read and react to, rather than a protocol error. */
class ToolFailure(message: String) : Exception(message)

/**
 * MCP over HTTP (the "streamable HTTP" transport), answering each request with plain JSON.
 * Only what tool use needs: initialize, ping, tools/list and tools/call. No sessions and no
 * server-sent events, so GET is refused, as the spec allows.
 */
fun Route.mcpRoutes(tools: List<McpTool>) {
    val byName = tools.associateBy { it.name }
    post("/mcp") {
        // A web page could otherwise post here as a "simple" cross-site request and drive the
        // projector; MCP clients send JSON and no Origin.
        if (call.request.headers[HttpHeaders.Origin] != null) return@post call.respond(HttpStatusCode.Forbidden)
        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
            return@post call.respond(HttpStatusCode.UnsupportedMediaType)
        }
        val request = runCatching { mcpJson.parseToJsonElement(call.receiveText()) as? JsonObject }.getOrNull()
            ?: return@post reply(JsonNull, error(PARSE_ERROR, "expected one JSON-RPC request object"))
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
        // Notifications, and the client's answers to us, need no reply.
        val id = request["id"]?.takeIf { method != null } ?: return@post call.respond(HttpStatusCode.Accepted)
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val outcome = when (method) {
            "initialize" -> result(initialize(params))
            "ping" -> result(JsonObject(emptyMap()))
            "tools/list" -> result(buildJsonObject { put("tools", buildJsonArray { tools.forEach { add(describe(it)) } }) })
            "tools/call" -> callTool(byName, params)
            else -> error(METHOD_NOT_FOUND, "unknown method")
        }
        reply(id, outcome)
    }
    get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
}

private val mcpJson = Json { ignoreUnknownKeys = true }

private const val PARSE_ERROR = -32700
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

private fun result(value: JsonElement) = "result" to value
private fun error(code: Int, message: String) = "error" to buildJsonObject {
    put("code", code)
    put("message", message)
}

private suspend fun RoutingContext.reply(id: JsonElement, outcome: Pair<String, JsonElement>) {
    val body = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(outcome.first, outcome.second)
    }
    call.respondText(body.toString(), ContentType.Application.Json)
}

private fun initialize(params: JsonObject): JsonObject {
    val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
    return buildJsonObject {
        put("protocolVersion", asked?.takeIf { it in VERSIONS } ?: VERSIONS.first())
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", "projector")
            put("version", "1.0")
        }
        put("instructions", INSTRUCTIONS)
    }
}

private fun describe(tool: McpTool) = buildJsonObject {
    put("name", tool.name)
    put("description", tool.description)
    put("inputSchema", tool.schema)
}

private suspend fun callTool(tools: Map<String, McpTool>, params: JsonObject): Pair<String, JsonElement> {
    val name = (params["name"] as? JsonPrimitive)?.contentOrNull
    val tool = tools[name] ?: return error(INVALID_PARAMS, "no tool named $name")
    val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    val (content, failed) = try {
        tool.run(args) to false
    } catch (e: ToolFailure) {
        listOf(McpContent.Text(e.message.orEmpty())) to true
    } catch (e: InvalidCommand) {
        listOf(McpContent.Text(e.message.orEmpty())) to true
    } catch (e: IOException) {
        listOf(McpContent.Text("projector unavailable: ${e.message}")) to true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Whatever went wrong, Claude should hear about it and carry on, not see the server fail.
        listOf(McpContent.Text("failed: ${e.message ?: e::class.simpleName}")) to true
    }
    return result(buildJsonObject {
        put("content", buildJsonArray { content.forEach { add(encode(it)) } })
        put("isError", failed)
    })
}

private fun encode(content: McpContent) = when (content) {
    is McpContent.Text -> buildJsonObject {
        put("type", "text")
        put("text", content.text)
    }
    is McpContent.Image -> buildJsonObject {
        put("type", "image")
        put("data", Base64.getEncoder().encodeToString(content.bytes))
        put("mimeType", content.mimeType)
    }
}

private const val INSTRUCTIONS = """Controls the Android TV projector on the living-room wall while people watch.
Call start before your first action and end when the task is done: the wall glows while you are in control.
Look with screenshot or what_is_on, find things with locate, act with tap, key, type, scroll and open.
TV apps are driven mostly by the D-pad: key up/down/left/right moves focus and ok selects."""
