package remote

import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpTest {
    private val echo = McpTool("echo", "Says it back", JsonObject(emptyMap())) { args ->
        val said = args["text"]?.jsonPrimitive?.content ?: throw ToolFailure("nothing to say")
        listOf(McpContent.Text(said), McpContent.Image(byteArrayOf(1, 2, 3), "image/jpeg"))
    }

    private fun rpc(body: String, check: suspend (status: HttpStatusCode, reply: String) -> Unit) = testApplication {
        routing { mcpRoutes(listOf(echo)) }
        val response = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status, response.bodyAsText())
    }

    private fun parse(reply: String) = Json.parseToJsonElement(reply).jsonObject

    @Test
    fun `initialize agrees a version it knows and offers tools`() = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""") { _, reply ->
        val result = parse(reply)["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
    }

    @Test
    fun `an unknown version gets the newest`() = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""") { _, reply ->
        assertEquals("2025-06-18", parse(reply)["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `notifications get no reply body`() = rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") { status, _ ->
        assertEquals(HttpStatusCode.Accepted, status)
    }

    @Test
    fun `tools are listed with their schema`() = rpc("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""") { _, reply ->
        val tool = parse(reply)["result"]!!.jsonObject["tools"]!!.jsonArray.single().jsonObject
        assertEquals("echo", tool["name"]!!.jsonPrimitive.content)
        assertTrue("inputSchema" in tool)
    }

    @Test
    fun `a call returns text and images`() = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""") { _, reply ->
        val result = parse(reply)["result"]!!.jsonObject
        val content = result["content"]!!.jsonArray
        assertEquals("hi", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("AQID", content[1].jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a failing tool tells Claude why instead of erroring the protocol`() = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{}}}""") { _, reply ->
        val result = parse(reply)["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("nothing to say", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown methods and tools are protocol errors`() {
        rpc("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""") { _, reply -> assertEquals(-32601, parse(reply)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()) }
        rpc("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""") { _, reply -> assertEquals(-32602, parse(reply)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()) }
    }

    @Test
    fun `unreadable bodies are parse errors`() = rpc("not json") { _, reply ->
        assertEquals(-32700, parse(reply)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `web pages and non-JSON posts are turned away`() = testApplication {
        routing { mcpRoutes(listOf(echo)) }
        val fromPage = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            header("Origin", "http://evil.example")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, fromPage.status)
        val plain = client.post("/mcp") { setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, plain.status)
    }

    @Test
    fun `answers from the client need no reply`() = rpc("""{"jsonrpc":"2.0","id":9,"result":{}}""") { status, _ ->
        assertEquals(HttpStatusCode.Accepted, status)
    }
}
