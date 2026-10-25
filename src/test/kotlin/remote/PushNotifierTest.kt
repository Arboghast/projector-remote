package remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import remote.WebPushCrypto.unb64
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Announces to a stand-in push service, which decrypts the message the way a phone would. */
class PushNotifierTest {
    private val received = mutableListOf<Pair<Map<String, String>, ByteArray>>()
    private var status = 201
    private val service = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/push") { exchange ->
            received += exchange.requestHeaders.mapValues { it.value.first() } to exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        start()
    }
    private val file = File.createTempFile("push", ".json").apply { delete() }
    private val phone = WebPushCrypto.newKeyPair()
    private val auth = ByteArray(16).also(SecureRandom()::nextBytes)
    private val subscription = PushSubscription(
        "http://127.0.0.1:${service.address.port}/push/abc",
        PushSubscription.Keys(WebPushCrypto.b64(WebPushCrypto.rawPublic(phone.public as ECPublicKey)), WebPushCrypto.b64(auth)),
    )

    @AfterTest
    fun stop() {
        service.stop(0)
        file.delete()
    }

    @Test
    fun `a phone can decrypt the announcement and verify who sent it`() = runBlocking {
        val notifier = PushNotifier(file, "mailto:a@b.example")
        assertEquals(null, notifier.subscribe(subscription))
        assertTrue("Shelf notifications are on" in String(decrypt(received.single().second)))
        notifier.announce("https://example.com/a")

        val (headers, body) = received.last()
        assertTrue(""""url":"https://example.com/a"""" in String(decrypt(body)))
        assertEquals("aes128gcm", headers["Content-encoding"])
        val (token, key) = Regex("""vapid t=([^,]+), k=(.+)""").find(headers.getValue("Authorization"))!!.destructured
        assertEquals(notifier.publicKey, key)
        val parts = token.split(".")
        assertTrue(""""aud":"http://127.0.0.1"""" in String(unb64(parts[1])))
        assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(WebPushCrypto.publicKey(unb64(key)))
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(unb64(parts[2]))
        })
    }

    @Test
    fun `keys survive a restart and a gone subscription is dropped`() = runBlocking {
        val first = PushNotifier(file, "mailto:a@b.example")
        first.subscribe(subscription)
        val second = PushNotifier(file, "mailto:a@b.example")
        assertEquals(first.publicKey, second.publicKey)

        status = 410
        second.announce("https://example.com/a")
        status = 201
        second.announce("https://example.com/b")
        assertEquals(2, received.size) // the test push on subscribe, then the refused one
    }

    @Test
    fun `turning on reports why the push service refused`() = runBlocking {
        status = 403
        val refusal = PushNotifier(file, "mailto:a@b.example").subscribe(subscription)
        assertTrue(refusal != null && "403" in refusal)
    }

    // The receiving side of RFC 8291, as a phone's browser does it.
    private fun decrypt(body: ByteArray): ByteArray {
        val salt = body.copyOfRange(0, 16)
        assertEquals(4096, ByteBuffer.wrap(body, 16, 4).int)
        val idLength = body[20].toInt()
        val serverPublic = body.copyOfRange(21, 21 + idLength)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(phone.private)
            doPhase(WebPushCrypto.publicKey(serverPublic), true)
            generateSecret()
        }
        val phonePublic = WebPushCrypto.rawPublic(phone.public as ECPublicKey)
        val ikm = hkdf(auth, shared, "WebPush: info\u0000".toByteArray() + phonePublic + serverPublic, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\u0000".toByteArray(), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce\u0000".toByteArray(), 12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(body.copyOfRange(21 + idLength, body.size))
        }
        assertEquals(2.toByte(), plain.last())
        return plain.copyOf(plain.size - 1)
    }

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
        return hmac(hmac(salt, ikm), info + byteArrayOf(1)).copyOf(length)
    }
}
