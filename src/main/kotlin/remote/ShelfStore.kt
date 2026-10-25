package remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Links waiting to go on the wall, and the ones recently shown there so you can go back to them;
 * shared by every remote and kept across relay restarts.
 */
class ShelfStore(private val file: File) {
    private val log = LoggerFactory.getLogger(ShelfStore::class.java)
    private val writes = Mutex()
    private val saved = load()
    private val _items = MutableStateFlow(saved.items)
    private val _recent = MutableStateFlow(saved.recent)
    val items: StateFlow<List<ShelfItem>> = _items.asStateFlow()
    val recent: StateFlow<List<ShelfItem>> = _recent.asStateFlow()

    suspend fun add(url: String, app: String, from: String): ShelfItem {
        val item = ShelfItem(UUID.randomUUID().toString(), url, app, from, System.currentTimeMillis())
        // Re-sharing a link moves it to the top instead of duplicating it, even from Recent.
        _items.update { current -> listOf(item) + current.filter { it.url != url } }
        _recent.update { current -> current.filter { it.url != url } }
        save()
        return item
    }

    fun find(id: String): ShelfItem? = _items.value.firstOrNull { it.id == id } ?: _recent.value.firstOrNull { it.id == id }

    /** The link went on the wall: it leaves the waiting list and goes to the top of Recent. */
    suspend fun shown(item: ShelfItem) {
        _items.update { current -> current.filter { it.id != item.id } }
        _recent.update { current -> (listOf(item) + current.filter { it.url != item.url }).take(MAX_RECENT) }
        save()
    }

    suspend fun remove(id: String) {
        _items.update { current -> current.filter { it.id != id } }
        _recent.update { current -> current.filter { it.id != id } }
        save()
    }

    private fun load(): Saved = runCatching {
        if (!file.exists()) return Saved()
        val text = file.readText()
        // Shelves saved before Recent existed are a bare list of waiting links.
        if (text.trimStart().startsWith("[")) Saved(protocolJson.decodeFromString(ListSerializer(ShelfItem.serializer()), text))
        else protocolJson.decodeFromString(Saved.serializer(), text)
    }.getOrElse {
        log.warn("could not read {}, starting with an empty shelf: {}", file, it.message)
        Saved()
    }

    private suspend fun save() = writes.withLock {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(protocolJson.encodeToString(Saved.serializer(), Saved(_items.value, _recent.value))) }
                .onFailure { log.warn("could not save {}: {}", file, it.message) }
        }
    }

    @Serializable
    private data class Saved(val items: List<ShelfItem> = emptyList(), val recent: List<ShelfItem> = emptyList())

    private companion object {
        const val MAX_RECENT = 20
    }
}
