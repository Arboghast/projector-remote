package remote

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ShelfStoreTest {
    private fun file() = File(createTempDirectory().toFile(), "shelf.json")

    @Test
    fun `a shown link moves from waiting to the top of recent`() = runBlocking {
        val store = ShelfStore(file())
        val a = store.add("https://a.example", "browser", "")
        val b = store.add("https://b.example", "browser", "")
        store.shown(a)
        store.shown(b)
        assertEquals(emptyList(), store.items.value)
        assertEquals(listOf(b, a), store.recent.value)
    }

    @Test
    fun `sharing a recent link again puts it back on the waiting list`() = runBlocking {
        val store = ShelfStore(file())
        store.shown(store.add("https://a.example", "browser", ""))
        store.add("https://a.example", "browser", "")
        assertEquals(listOf("https://a.example"), store.items.value.map { it.url })
        assertEquals(emptyList(), store.recent.value)
    }

    @Test
    fun `recent keeps the last twenty`() = runBlocking {
        val store = ShelfStore(file())
        repeat(25) { store.shown(store.add("https://$it.example", "browser", "")) }
        assertEquals(20, store.recent.value.size)
        assertEquals("https://24.example", store.recent.value.first().url)
    }

    @Test
    fun `survives a restart and reads shelves saved before recent existed`() = runBlocking {
        val f = file()
        f.writeText("""[{"id":"1","url":"https://old.example","app":"browser","from":"","addedAt":1}]""")
        val old = ShelfStore(f)
        assertEquals(listOf("https://old.example"), old.items.value.map { it.url })
        old.shown(old.items.value.first())
        assertEquals(listOf("https://old.example"), ShelfStore(f).recent.value.map { it.url })
    }
}
