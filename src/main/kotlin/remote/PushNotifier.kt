package remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration

/** A phone's push subscription, as `PushSubscription.toJSON()` gives it. */
@Serializable
data class PushSubscription(val endpoint: String, val keys: Keys) {
    @Serializable
    data class Keys(val p256dh: String, val auth: String)
}

/**
 * "Saved to the Shelf" notifications on phones that turned them on. The phone's service worker
 * shows them; tapping one puts the link on the wall. Keys and subscriptions live in [file].
 */
class PushNotifier(private val file: File, private val subject: String) {
    private val log = LoggerFactory.getLogger(PushNotifier::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val lock = Mutex()
    private var saved = load()
    private val keys: KeyPair = WebPushCrypto.keyPair(WebPushCrypto.unb64(saved.privateKey), WebPushCrypto.unb64(saved.publicKey))

    /** What phones pass to `pushManager.subscribe` as `applicationServerKey`. */
    val publicKey: String get() = saved.publicKey

    /** Saves it and sends a test notification at once; returns why the push service refused it, if it did. */
    suspend fun subscribe(subscription: PushSubscription): String? {
        lock.withLock {
            saved = saved.copy(subscriptions = saved.subscriptions.filter { it.endpoint != subscription.endpoint } + subscription)
            save()
        }
        val payload = protocolJson.encodeToString(Announcement.serializer(), Announcement(message = "Shelf notifications are on")).toByteArray()
        return send(subscription, payload).refusal
    }

    suspend fun unsubscribe(endpoint: String) = lock.withLock {
        saved = saved.copy(subscriptions = saved.subscriptions.filter { it.endpoint != endpoint })
        save()
    }

    suspend fun announce(url: String) {
        val payload = protocolJson.encodeToString(Announcement.serializer(), Announcement(url = url)).toByteArray()
        val gone = lock.withLock { saved.subscriptions }.filter { send(it, payload).gone }
        gone.forEach { unsubscribe(it.endpoint) }
    }

    private class Outcome(val refusal: String?, val gone: Boolean)

    private suspend fun send(subscription: PushSubscription, payload: ByteArray): Outcome = try {
        val endpoint = URI(subscription.endpoint)
        val body = WebPushCrypto.encrypt(payload, WebPushCrypto.unb64(subscription.keys.p256dh), WebPushCrypto.unb64(subscription.keys.auth))
        val expires = System.currentTimeMillis() / 1000 + 12 * 60 * 60
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", WebPushCrypto.vapidAuthorization("${endpoint.scheme}://${endpoint.host}", subject, keys, expires))
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            // A "Saved" note is only useful while you're still near the link you shared.
            .header("TTL", "600")
            .header("Urgency", "high")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val status = response.statusCode()
        if (status in 200..299) Outcome(null, false)
        else {
            val reason = "${endpoint.host} refused it: $status ${response.body().take(200)}".trim()
            log.warn("push failed: {}", reason)
            Outcome(reason, status == 404 || status == 410)
        }
    } catch (e: Exception) {
        log.warn("push failed: {}", e.message)
        Outcome("couldn't reach the push service: ${e.message}", false)
    }

    private fun load(): Saved {
        val existing = runCatching { protocolJson.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()
        if (existing != null) return existing
        val fresh = WebPushCrypto.newKeyPair()
        return Saved(
            privateKey = WebPushCrypto.b64(WebPushCrypto.rawPrivate(fresh.private as ECPrivateKey)),
            publicKey = WebPushCrypto.b64(WebPushCrypto.rawPublic(fresh.public as ECPublicKey)),
        ).also { writeFile(it) }
    }

    private suspend fun save() = withContext(Dispatchers.IO) { writeFile(saved) }

    private fun writeFile(content: Saved) {
        runCatching { file.writeText(protocolJson.encodeToString(Saved.serializer(), content)) }
            .onFailure { log.warn("could not save {}: {}", file, it.message) }
    }

    @Serializable
    private data class Saved(val privateKey: String, val publicKey: String, val subscriptions: List<PushSubscription> = emptyList())

    @Serializable
    private data class Announcement(val url: String? = null, val message: String? = null)
}
