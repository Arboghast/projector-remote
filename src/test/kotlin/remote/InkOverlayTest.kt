package remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InkOverlayTest {
    @Test
    fun `reads the installed version from pm's listing`() {
        assertEquals(1, InkOverlay.installedVersion("package:com.projectorremote.ink versionCode:1\n"))
    }

    @Test
    fun `not installed when the listing doesn't name it`() {
        assertNull(InkOverlay.installedVersion(""))
        assertNull(InkOverlay.installedVersion("package:com.projectorremote.inkwell versionCode:3"))
    }

    @Test
    fun `ink without a tool is a pen stroke, and the tool reaches the overlay`() {
        val plain = protocolJson.decodeFromString(RemoteCommand.serializer(), """{"type":"ink","id":"a","points":[[0.1,0.2]],"color":"#fff","size":0.005}""")
        assertEquals("pen", (plain as RelayCommand.Ink).tool)
        val arrow = protocolJson.decodeFromString(RemoteCommand.serializer(), """{"type":"ink","id":"b","points":[[0.1,0.2],[0.3,0.4]],"color":"#fff","size":0.005,"tool":"arrow"}""")
        assert(protocolJson.encodeToString(RemoteCommand.serializer(), arrow).contains(""""tool":"arrow""""))
    }
}
