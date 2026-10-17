package remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/** Links waiting to go on the wall, shared by every remote and kept across relay restarts. */
class ShelfStore(private val file: File) {
    private val log = LoggerFactory.getLogger(ShelfStore::class.java)
    private val serializer = ListSerializer(ShelfItem.serializer())
    private val writes = Mutex()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<ShelfItem>> = _items.asStateFlow()

    suspend fun add(url: String, app: String, from: String): ShelfItem {
        val item = ShelfItem(UUID.randomUUID().toString(), url, app, from, System.currentTimeMillis())
        // Re-sharing a link moves it to the top instead of duplicating it.
        _items.update { current -> listOf(item) + current.filter { it.url != url } }
        save()
        return item
    }

    /** Removes and returns the item in one step, so two phones can't both project it. */
    suspend fun take(id: String): ShelfItem? {
        var taken: ShelfItem? = null
        _items.update { current ->
            taken = current.firstOrNull { it.id == id }
            current.filter { it.id != id }
        }
        if (taken != null) save()
        return taken
    }

    suspend fun remove(id: String) {
        take(id)
    }

    private fun load(): List<ShelfItem> = runCatching {
        if (file.exists()) protocolJson.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrElse {
        log.warn("could not read {}, starting with an empty shelf: {}", file, it.message)
        emptyList()
    }

    private suspend fun save() = writes.withLock {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(protocolJson.encodeToString(serializer, _items.value)) }
                .onFailure { log.warn("could not save {}: {}", file, it.message) }
        }
    }
}
