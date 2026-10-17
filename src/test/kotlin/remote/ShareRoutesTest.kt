package remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareRoutesTest {
    @Test
    fun `only form posts are percent-decoded`() {
        assertEquals("url=https://x.com/a b", shareBody(true, "url=https%3A%2F%2Fx.com%2Fa+b"))
        assertEquals("50% off https://x.com/a+b", shareBody(false, "50% off https://x.com/a+b"))
    }

    @Test
    fun `a malformed form body is kept as sent`() {
        assertEquals("50% off", shareBody(true, "50% off"))
    }

    @Test
    fun `shares are labelled by device`() {
        assertEquals("iPhone", deviceFrom("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"))
        assertEquals("Android", deviceFrom("Mozilla/5.0 (Linux; Android 14; SM-F956U)"))
        assertEquals("Android", deviceFrom("Mozilla/5.0 (X11; Linux x86_64) Chrome/130"))
        assertEquals("Windows", deviceFrom("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
        assertEquals("Shared", deviceFrom(null))
    }

    @Test
    fun `the saved page escapes what it shows`() {
        val page = sharedPage("https://x.com/?q=<script>", null)
        assertTrue("&lt;script&gt;" in page)
        assertFalse("<script>alert" in page)
    }
}
