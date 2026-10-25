package remote

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Web Push on the JDK's own crypto: message encryption (RFC 8291) and the VAPID token (RFC 8292). */
object WebPushCrypto {
    private const val RECORD_SIZE = 4096
    private val random = SecureRandom()
    private val p256: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(p256) }.generateKeyPair()

    /** A P-256 public key as the 65-byte uncompressed point push services use. */
    fun rawPublic(key: ECPublicKey): ByteArray = byteArrayOf(4) + key.w.affineX.fixed() + key.w.affineY.fixed()

    fun rawPrivate(key: ECPrivateKey): ByteArray = key.s.fixed()

    fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256)) as ECPublicKey
    }

    fun keyPair(rawPrivate: ByteArray, rawPublic: ByteArray): KeyPair = KeyPair(
        publicKey(rawPublic),
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, rawPrivate), p256)),
    )

    /** The aes128gcm body for one subscription; the keys and salt are parameters only so tests can pin them. */
    fun encrypt(
        payload: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        server: KeyPair = newKeyPair(),
        salt: ByteArray = ByteArray(16).also(random::nextBytes),
    ): ByteArray {
        val asPublic = rawPublic(server.public as ECPublicKey)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(server.private)
            doPhase(publicKey(uaPublic), true)
            generateSecret()
        }
        val ikm = hkdf(authSecret, shared, "WebPush: info\u0000".toByteArray() + uaPublic + asPublic, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\u0000".toByteArray(), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce\u0000".toByteArray(), 12)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(payload + byteArrayOf(2)) // 2 marks the last (and only) record
        }
        return salt + ByteBuffer.allocate(4).putInt(RECORD_SIZE).array() + byteArrayOf(asPublic.size.toByte()) + asPublic + sealed
    }

    /** The `Authorization` header value for a push service at [audience] (its scheme and host). */
    fun vapidAuthorization(audience: String, subject: String, keys: KeyPair, expiresAt: Long): String {
        val header = b64("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64("""{"aud":"$audience","exp":$expiresAt,"sub":"$subject"}""".toByteArray())
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keys.private)
            update("$header.$claims".toByteArray())
            sign()
        }
        return "vapid t=$header.$claims.${b64(signature)}, k=${b64(rawPublic(keys.public as ECPublicKey))}"
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

    // Single-block HKDF: every output here is at most one SHA-256 block.
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun BigInteger.fixed(): ByteArray {
        val bytes = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }
}
