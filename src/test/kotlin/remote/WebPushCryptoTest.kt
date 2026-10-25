package remote

import remote.WebPushCrypto.unb64
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebPushCryptoTest {
    @Test
    fun `encrypts the RFC 8291 example to the exact body`() {
        val server = WebPushCrypto.keyPair(
            unb64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"),
            unb64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"),
        )
        val body = WebPushCrypto.encrypt(
            payload = unb64("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24"),
            uaPublic = unb64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            authSecret = unb64("BTBZMqHH6r4Tts7J_aSIgg"),
            server = server,
            salt = unb64("DGv6ra1nlYgDCS1FRnbzlw"),
        )
        assertEquals(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            WebPushCrypto.b64(body),
        )
    }

    @Test
    fun `the VAPID token verifies with the key it names`() {
        val keys = WebPushCrypto.newKeyPair()
        val header = WebPushCrypto.vapidAuthorization("https://web.push.apple.com", "mailto:a@b.example", keys, 1_900_000_000)
        val (token, key) = Regex("""vapid t=([^,]+), k=(.+)""").find(header)!!.destructured
        val parts = token.split(".")
        val claims = String(unb64(parts[1]))
        assertTrue(""""aud":"https://web.push.apple.com"""" in claims)
        val verified = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(WebPushCrypto.publicKey(unb64(key)))
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(unb64(parts[2]))
        }
        assertTrue(verified)
    }
}
