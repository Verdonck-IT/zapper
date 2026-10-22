package be.verdonckit.zapper.plugins.play

import be.verdonckit.zapper.testkit.ContractTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.Calendar
import java.util.TimeZone

class PlayContractTest : ContractTest("PLAY") {

    override fun createProvider() = PlayProvider()

    /** Live tegen Play. Wordt overgeslagen zonder PLAY_USERNAME/PASSWORD. */
    @Test
    @Tag("live")
    fun `live contract`() = runContract()
}

class PlayUnitTest {

    /**
     * Vaste testvector, berekend met de originele Python-code van de Kodi-add-on
     * (resources/lib/play/aws/cognito_idp.py) met dezelfde a, B, salt, secret block en tijd.
     * Klopt deze handtekening, dan is de SRP-berekening bit voor bit gelijk.
     */
    @Test
    fun `SRP-handtekening identiek aan de Kodi-add-on`() {
        val smallA = BigInteger("1f2e3d4c5b6a79881726354453627180".repeat(8), 16)
        val srp = CognitoSrp(PlayAuth.POOL_ID, smallA)
        assertEquals("d1c07d3d7ff39aa477cf990981bd946b7e8af81a", srp.srpA.take(40))
        assertEquals(768, srp.srpA.length)

        val n = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        val srpB = BigInteger.TWO.modPow(BigInteger("abcdef0123456789".repeat(16), 16), n).toString(16)

        val signature = srp.passwordClaimSignature(
            userIdForSrp = "user-sub-123",
            password = "Wachtwoord!123",
            srpB = srpB,
            salt = "9a8b7c6d5e4f3021",
            secretBlock = "c2VjcmV0LWJsb2NrLWJ5dGVz",
            timestamp = "Mon Jan 5 10:00:00 UTC 2026",
        )
        assertEquals("u9vwRivce/PYmZVS/xLQAa0Az77WE0qukkigevagdRg=", signature)
        assertEquals("538282c4354742d7", CognitoSrp.K.toString(16).take(16))
    }

    @Test
    fun `tijdstempel in het formaat dat Cognito eist`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.JANUARY, 5, 9, 7, 3) }
        assertEquals("Mon Jan 5 09:07:03 UTC 2026", CognitoSrp.timestamp(cal.time))
    }

    @Test
    fun `padHex zoals amazon-cognito-identity-js`() {
        assertEquals("0abc", CognitoSrp.padHex("abc"))
        assertEquals("00ff", CognitoSrp.padHex("ff"))
        assertEquals("7f", CognitoSrp.padHex("7f"))
    }

    @Test
    fun `slugs, lege beelden en seizoensnummer`() {
        assertEquals("actua-sport", PlayProvider.slug("Actua & Sport"))
        assertEquals("human-interest", PlayProvider.slug("Human Interest"))
        assertNull(PlayProvider.image("None"))
        assertEquals("https://images.play.tv/x.jpg", PlayProvider.image("https://images.play.tv/x.jpg"))
        assertEquals(3, PlayProvider.trailingNumber("Seizoen 3"))
    }
}
