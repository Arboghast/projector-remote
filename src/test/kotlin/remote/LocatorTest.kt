package remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocatorTest {
    private val dump = """
        WARNING: linker: stray vendor line
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
        <node index="0" text="" resource-id="" class="android.widget.FrameLayout" content-desc="" clickable="false" focused="false" scrollable="false" bounds="[0,0][1920,1080]">
          <node index="0" text="Search" resource-id="com.app:id/search_box" class="android.widget.EditText" content-desc="" clickable="true" focused="false" scrollable="false" bounds="[100,40][700,120]" />
          <node index="1" text="" resource-id="com.app:id/play_button" class="android.widget.ImageButton" content-desc="Play" clickable="true" focused="true" scrollable="false" bounds="[900,500][1020,620]" />
          <node index="2" text="Episode 3: The Return" resource-id="" class="android.widget.TextView" content-desc="" clickable="false" focused="false" scrollable="false" bounds="[100,700][600,760]" />
          <node index="3" text="" resource-id="" class="android.widget.ListView" content-desc="" clickable="false" focused="false" scrollable="true" bounds="[0,640][1920,1080]" />
          <node index="4" text="ghost" resource-id="" class="android.view.View" content-desc="" clickable="false" focused="false" scrollable="false" bounds="[0,0][0,0]" />
        </node>
        </hierarchy>
        UI hierchary dumped to: /dev/tty
    """.trimIndent()

    @Test
    fun `parses a dump with stray lines around it and drops empty boxes`() {
        val nodes = parseUiDump(dump)
        assertEquals(5, nodes.size)
        val play = nodes.single { it.description == "Play" }
        assertEquals(Box(900, 500, 1020, 620), play.box)
        assertTrue(play.focused && play.clickable)
        assertTrue(nodes.any { it.scrollable })
    }

    @Test
    fun `garbage gives nothing rather than failing`() {
        assertEquals(emptyList(), parseUiDump("ERROR: could not get idle state."))
    }

    @Test
    fun `finds by text, spoken label and id, best first`() {
        val nodes = parseUiDump(dump)
        assertEquals("Search", findNodes(nodes, "search box").first().text)
        assertEquals("Play", findNodes(nodes, "play").first().description)
        assertEquals("Episode 3: The Return", findNodes(nodes, "episode 3").first().text)
        assertEquals(emptyList(), findNodes(nodes, "sign in"))
    }

    @Test
    fun `a label falls back from text to description to id`() {
        val play = parseUiDump(dump).single { it.description == "Play" }
        assertEquals("Play", play.label)
        assertEquals("search box", UiNode("", "", "com.app:id/search_box", "EditText", Box(0, 0, 1, 1), false, false, false).label)
    }

    @Test
    fun `outline places headings by how much text comes before them`() {
        val filler = "word ".repeat(200)
        val html = "<html><script>var h = '<h2>fake</h2>'</script><h1>Title</h1><p>$filler</p><h2>Method</h2><p>$filler</p><h2 class=x>Conclusion</h2><p>end</p></html>"
        val outline = pageOutline(html)
        assertEquals(listOf("Title", "Method", "Conclusion"), outline.map { it.text })
        assertTrue(outline[0].depth < 0.05)
        assertTrue(outline[2].depth > 0.9)
    }

    @Test
    fun `descriptions match what's on screen, not only exact names`() {
        val nodes = parseUiDump(dump.replace("text=\"Search\"", "text=\"\"") + "")
        assertEquals("com.app:id/search_box", findNodes(nodes, "the search box").first().id)
        assertTrue(matchScore("Sign in", "a sign-in prompt") >= MIN_SCORE)
        assertTrue(matchScore("Conclusion", "the conclusion") >= MIN_SCORE)
        assertTrue(matchScore("Settings", "the conclusion") < MIN_SCORE)
    }

    @Test
    fun `a truncated dump gives nothing rather than failing`() {
        assertEquals(emptyList(), parseUiDump(dump.substringBefore("<node index=\"2\"") + "</hierarchy>"))
    }
}
