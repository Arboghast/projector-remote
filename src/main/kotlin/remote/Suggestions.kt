package remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Search-as-you-type for the Shelf, so nobody has to type out a whole address. Uses Google's public
 * suggestion feed, matching the Google results a search opens; a slow or failed lookup just means no
 * suggestions, never an error.
 */
class Suggestions {
    private val log = LoggerFactory.getLogger(Suggestions::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()

    suspend fun forText(text: String): List<String> {
        val q = text.trim().take(MAX_QUERY)
        if (q.isEmpty()) return emptyList()
        return try {
            val request = HttpRequest.newBuilder(URI("$ENDPOINT${URLEncoder.encode(q, Charsets.UTF_8)}"))
                .timeout(TIMEOUT)
                .header("User-Agent", "projector-remote")
                .build()
            val body = withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()).body() }
            // ["query", ["suggestion", …]]
            (Json.parseToJsonElement(body).jsonArray.getOrNull(1) as? JsonArray)
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.take(MAX_RESULTS)
                .orEmpty()
        } catch (e: Exception) {
            log.debug("suggestions failed: {}", e.message)
            emptyList()
        }
    }

    private companion object {
        const val ENDPOINT = "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&q="
        const val MAX_QUERY = 120
        const val MAX_RESULTS = 5
        val TIMEOUT: Duration = Duration.ofMillis(1500)
    }
}
