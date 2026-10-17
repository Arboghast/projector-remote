package remote

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLDecoder

/**
 * Links arriving from outside the remote: `/share` opens one now; `/shelf` (iOS Shortcut, browser
 * extension) and `/share-target` (Android share sheet, bookmark) save it for later; `/suggest`
 * offers searches as you type.
 */
fun Route.shareRoutes(execute: suspend (RemoteCommand) -> RelayEvent.Error?, suggestions: Suggestions) {
    get("/share") { share(execute, call.parameters["url"] ?: call.parameters["text"]) }
    post("/share") { share(execute, body()) }
    get("/share-target") {
        val p = call.parameters
        val raw = listOfNotNull(p["url"], p["text"], p["title"]).joinToString(" ")
        val error = execute(RelayCommand.ShelfAdd(raw, p["from"] ?: deviceFrom(call.request.headers["User-Agent"])))
        call.respondText(sharedPage(if (error == null) extractUrl(raw) else null, error?.message), ContentType.Text.Html)
    }
    get("/suggest") { call.respond(suggestions.forText(call.parameters["q"].orEmpty())) }
    post("/shelf") {
        val body = body()
        when (val error = execute(RelayCommand.ShelfAdd(body, call.parameters["from"] ?: "Shortcut"))) {
            // The Shortcut shows this text as its notification, so name what was saved.
            null -> call.respondText("Saved to the Shelf: ${extractUrl(body)}")
            else -> call.respondText(error.message, status = HttpStatusCode.BadRequest)
        }
    }
}

private suspend fun RoutingContext.body(): String =
    shareBody(call.request.contentType().withoutParameters() == ContentType.Application.FormUrlEncoded, call.receiveText())

/** Only form posts are percent-encoded; plain text keeps its `+` and `%` as typed. */
internal fun shareBody(formEncoded: Boolean, text: String): String =
    if (formEncoded) runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text) else text

private suspend fun RoutingContext.share(execute: suspend (RemoteCommand) -> RelayEvent.Error?, raw: String?) {
    val url = extractUrl(raw) ?: return call.respondText("no link found", status = HttpStatusCode.BadRequest)
    when (val error = execute(ProjectorCommand.Open(url))) {
        null -> call.respondText("opened $url")
        else -> call.respondText(error.message, status = HttpStatusCode.BadGateway)
    }
}

internal fun deviceFrom(userAgent: String?) = when {
    userAgent == null -> "Shared"
    "Windows" in userAgent -> "Windows"
    "iPhone" in userAgent || "iPad" in userAgent -> "iPhone"
    // Chrome on an unfolded Fold or a tablet asks for desktop sites with a Linux desktop agent.
    "Android" in userAgent || "X11; Linux" in userAgent -> "Android"
    else -> "Shared"
}

/**
 * What a share lands on: a brief "Saved" that closes itself, so the share sheet hands you back to
 * the app you shared from instead of leaving you in the remote.
 */
internal fun sharedPage(url: String?, error: String?): String {
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val title = if (error == null) "Saved to the Shelf" else "Couldn't save"
    val detail = esc(url ?: error ?: "")
    val close = if (error == null) "<script>setTimeout(function(){window.close()},900)</script>" else ""
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        |<meta name="theme-color" content="#000000"><title>$title</title>
        |<style>html,body{margin:0;height:100%;background:#000;color:#eceef2;font:16px system-ui,sans-serif}
        |main{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:24px;box-sizing:border-box;text-align:center}
        |b{font-size:20px;color:${if (error == null) "#3ecf8e" else "#ff5c5c"}}span{color:#8e95a1;word-break:break-all;max-width:32ch}
        |a{color:#5b8cff;margin-top:14px}</style></head>
        |<body><main><b>$title</b><span>$detail</span><a href="/?layout=shelf">Open the Shelf</a></main>$close</body></html>""".trimMargin()
}
