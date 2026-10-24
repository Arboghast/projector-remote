package remote

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** A box on the wall in screen pixels (1920×1080). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** One element of what's on screen, from Android's accessibility tree. */
data class UiNode(
    val text: String,
    val description: String,
    val id: String,
    val kind: String,
    val box: Box,
    val clickable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
) {
    /** What a person would call it: its text, else its spoken label, else its id's last part. */
    val label: String get() = text.ifBlank { description }.ifBlank { id.substringAfterLast('/').replace('_', ' ') }
}

/** A heading on a web page and how far down the page it sits (0 = top, 1 = bottom). */
data class Heading(val text: String, val depth: Double)

private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

/** Parses `uiautomator dump` output; the vendor build prints stray lines around the XML. Unreadable gives nothing. */
fun parseUiDump(output: String): List<UiNode> = runCatching { parseNodes(output) }.getOrElse { emptyList() }

private fun parseNodes(output: String): List<UiNode> {
    val start = output.indexOf("<?xml").takeIf { it >= 0 } ?: output.indexOf("<hierarchy")
    val end = output.lastIndexOf("</hierarchy>")
    if (start < 0 || end < 0) return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        // The dump is from our own projector, but never let XML fetch anything.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(output.substring(start, end + "</hierarchy>".length))))
    val nodes = doc.getElementsByTagName("node")
    return (0 until nodes.length).mapNotNull { i ->
        val e = nodes.item(i) as Element
        val (l, t, r, b) = BOUNDS.find(e.getAttribute("bounds"))?.destructured ?: return@mapNotNull null
        val box = Box(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        if (box.right <= box.left || box.bottom <= box.top) return@mapNotNull null
        UiNode(
            text = e.getAttribute("text").trim(),
            description = e.getAttribute("content-desc").trim(),
            id = e.getAttribute("resource-id"),
            kind = e.getAttribute("class").substringAfterLast('.'),
            box = box,
            clickable = e.getAttribute("clickable") == "true",
            focused = e.getAttribute("focused") == "true",
            scrollable = e.getAttribute("scrollable") == "true",
        )
    }
}

private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

// Words people use to describe a thing rather than name it: "the sign-in prompt", "search box".
private val FILLER = setOf("a", "an", "the", "of", "to", "on", "in", "for", "my", "button", "box", "field", "prompt", "section", "link", "icon", "tab", "bar")
private val INPUT_WORDS = setOf("box", "field", "input", "bar")

/** How well [text] answers [query], 0–1: whole phrase first, else the share of meaningful words found. */
fun matchScore(text: String, query: String): Double {
    val q = query.trim().lowercase()
    val t = text.trim().lowercase()
    if (q.isEmpty() || t.isEmpty()) return 0.0
    if (t == q) return 1.0
    if (t.startsWith(q)) return 0.9
    if (q in t) return 0.8
    val asked = words(q)
    val wanted = asked.filter { it !in FILLER }.ifEmpty { asked }
    if (wanted.isEmpty()) return 0.0
    val have = words(t)
    val hits = wanted.count { w -> have.any { it == w || (w.length >= 3 && it.startsWith(w)) } }
    return 0.7 * hits / wanted.size
}

/** [matchScore] over everything that names [node], nudged toward the kind of thing asked for. */
fun score(node: UiNode, query: String): Double {
    val best = listOf(node.text, node.description, node.id.substringAfterLast('/').replace('_', ' ')).maxOf { matchScore(it, query) }
    if (best == 0.0) return 0.0
    val asked = words(query).toSet()
    val kindFits = (asked.any { it in INPUT_WORDS } && "EditText" in node.kind) || ("button" in asked && node.clickable)
    return best + if (kindFits) 0.1 else 0.0
}

/** The best matches on screen, most likely first; ties go to things you can press. */
fun findNodes(nodes: List<UiNode>, query: String, limit: Int = 5): List<UiNode> =
    nodes.map { it to score(it, query) }
        .filter { it.second >= MIN_SCORE }
        .sortedWith(compareByDescending<Pair<UiNode, Double>> { it.second }.thenByDescending { it.first.clickable })
        .map { it.first }
        .distinctBy { it.box }
        .take(limit)

/** At least half the meaningful words must be there. */
const val MIN_SCORE = 0.35

/** Headings of a web page with their depth, measured over the page's visible text. */
fun pageOutline(html: String): List<Heading> {
    val body = html
        .replace(Regex("""(?is)<(script|style|noscript)\b.*?</\1>"""), " ")
        .replace(Regex("(?is)<!--.*?-->"), " ")
    val headings = Regex("""(?is)<h([1-4])\b[^>]*>(.*?)</h\1>""").findAll(body).toList()
    val total = stripTags(body).length.coerceAtLeast(1)
    // One pass: the text before each heading is the text before the last one plus the gap since.
    var before = 0
    var from = 0
    return headings.mapNotNull { m ->
        before += stripTags(body.substring(from, m.range.first)).length
        from = m.range.last + 1
        val text = stripTags(m.groupValues[2]).trim()
        before += text.length
        if (text.isEmpty()) null else Heading(text, (before - text.length).toDouble() / total)
    }
}

private fun stripTags(html: String) = html
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("&nbsp;|&#160;"), " ")
    .replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")
