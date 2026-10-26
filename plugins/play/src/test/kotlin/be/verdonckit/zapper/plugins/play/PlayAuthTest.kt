package be.verdonckit.zapper.plugins.play

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostHttp
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.testkit.InMemoryKeyValueStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** Sessiegedrag zonder netwerk: een tijdelijke fout mag het RefreshToken nooit kosten. */
class PlayAuthTest {

    private class FakeHost(val handler: (flow: String) -> HttpResponse) : HostServices {
        var now = 1_000_000_000L
        val flows = mutableListOf<String>()
        override val storage = InMemoryKeyValueStore()
        override val http = object : HostHttp {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                val body = request.body!!.toString(Charsets.UTF_8)
                val flow = Regex("\"(?:AuthFlow|ChallengeName)\":\"([A-Z_]+)\"").find(body)!!.groupValues[1]
                flows += flow
                return handler(flow)
            }
            override fun cookies(url: String) = emptyMap<String, String>()
            override fun clearCookies() {}
        }
        override fun credentials() = Credentials("user@example.be", "geheim")
        override fun log(level: LogLevel, message: String, error: Throwable?) {}
        override fun nowMillis() = now

        fun logins() = flows.count { it == "USER_SRP_AUTH" }

        fun withExpiredSession() = apply {
            storage.put("play_id_token", "oud-id")
            storage.put("play_id_token_exp", (now - 1).toString())
            storage.put("play_refresh_token", "rt")
        }
    }

    private fun json(code: Int, body: String) = HttpResponse(code, emptyMap(), body.toByteArray(), "")

    private fun cognitoError(type: String) = json(400, "{\"__type\":\"$type\",\"message\":\"$type\"}")

    @Test
    fun `netwerkfout bij refresh houdt RefreshToken en logt niet in`() = runBlocking {
        val host = FakeHost { throw IOException("geen netwerk") }.withExpiredSession()
        val e = assertThrows<ProviderException> { runBlocking { PlayAuth(host).idToken() } }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals("rt", host.storage.get("play_refresh_token"))
        assertEquals(0, host.logins())
    }

    @Test
    fun `throttling bij refresh houdt RefreshToken en logt niet in`() = runBlocking {
        val host = FakeHost { cognitoError("TooManyRequestsException") }.withExpiredSession()
        val e = assertThrows<ProviderException> { runBlocking { PlayAuth(host).idToken() } }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals("rt", host.storage.get("play_refresh_token"))
        assertEquals(0, host.logins())
    }

    @Test
    fun `refresh geeft nieuw IdToken en behoudt RefreshToken`() = runBlocking {
        val host = FakeHost { json(200, "{\"AuthenticationResult\":{\"IdToken\":\"nieuw-id\",\"ExpiresIn\":3600}}") }
            .withExpiredSession()
        assertEquals("nieuw-id", PlayAuth(host).idToken())
        assertEquals("rt", host.storage.get("play_refresh_token"))
    }

    @Test
    fun `geweigerd RefreshToken - één stille login, geweigerd wachtwoord niet opnieuw`() = runBlocking {
        val host = FakeHost { cognitoError("NotAuthorizedException") }.withExpiredSession()
        val auth = PlayAuth(host)

        val first = assertThrows<ProviderException> { runBlocking { auth.idToken() } }
        assertEquals(ErrorKind.NOT_LOGGED_IN, first.kind)
        assertNull(host.storage.get("play_refresh_token"))
        assertEquals(1, host.logins())

        assertThrows<ProviderException> { runBlocking { auth.idToken() } }
        assertEquals(1, host.logins()) // zelfde credentials: geen tweede poging

        // Expliciet (Instellingen) mag wel opnieuw.
        assertTrue(auth.login(host.credentials()) is LoginResult.Failure)
        assertEquals(2, host.logins())
    }

    @Test
    fun `mislukte expliciete login wist de bestaande sessie niet`() = runBlocking {
        val host = FakeHost { throw IOException("geen netwerk") }.withExpiredSession()
        val r = PlayAuth(host).login(host.credentials())
        assertEquals(LoginFailure.NETWORK, (r as LoginResult.Failure).reason)
        assertEquals("rt", host.storage.get("play_refresh_token"))
    }
}
