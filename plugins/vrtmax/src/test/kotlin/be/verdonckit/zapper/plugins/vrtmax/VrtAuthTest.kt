package be.verdonckit.zapper.plugins.vrtmax

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
import be.verdonckit.zapper.plugins.vrtmax.shared.LoginGuard
import be.verdonckit.zapper.testkit.InMemoryKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** Sessiegedrag zonder netwerk: een tijdelijke fout mag de sessie nooit kosten. */
class VrtAuthTest {

    private class FakeHost(val handler: (HttpRequest) -> HttpResponse) : HostServices {
        var now = 1_000_000_000L
        val requests = mutableListOf<String>()
        override val storage = InMemoryKeyValueStore()
        override val http = object : HostHttp {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests += request.url
                return handler(request)
            }
            override fun cookies(url: String) = emptyMap<String, String>()
            override fun clearCookies() {}
        }
        override fun credentials() = Credentials("user@example.be", "geheim")
        override fun log(level: LogLevel, message: String, error: Throwable?) {}
        override fun nowMillis() = now

        fun logins() = requests.count { it == VrtAuth.SSO_INIT }
    }

    private fun response(code: Int, setCookies: List<String> = emptyList()) =
        HttpResponse(code, if (setCookies.isEmpty()) emptyMap() else mapOf("set-cookie" to setCookies), ByteArray(0), "")

    /** Verlopen access token, nog wel een refresh token. */
    private fun FakeHost.withExpiredSession() = apply {
        storage.put(VrtAuth.AT, "oud-at")
        storage.put("${VrtAuth.AT}.exp", (now - 1).toString())
        storage.put(VrtAuth.RT, "rt")
        storage.put("${VrtAuth.RT}.exp", (now + 86_400_000).toString())
    }

    @Test
    fun `netwerkfout bij refresh houdt refresh token en logt niet in`() = runBlocking {
        val host = FakeHost { throw IOException("geen netwerk") }.withExpiredSession()
        val e = assertThrows<ProviderException> { runBlocking { VrtAuth(host).accessToken() } }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals("rt", host.storage.get(VrtAuth.RT))
        assertEquals(0, host.logins())
    }

    @Test
    fun `VRT 503 bij refresh houdt refresh token en logt niet in`() = runBlocking {
        val host = FakeHost { response(503) }.withExpiredSession()
        val e = assertThrows<ProviderException> { runBlocking { VrtAuth(host).accessToken() } }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals("rt", host.storage.get(VrtAuth.RT))
        assertEquals(0, host.logins())
    }

    @Test
    fun `refresh met nieuw access token`() = runBlocking {
        val host = FakeHost { response(200, listOf("${VrtAuth.AT}=nieuw-at; Max-Age=3600; Path=/")) }.withExpiredSession()
        assertEquals("nieuw-at", VrtAuth(host).accessToken())
        assertEquals(0, host.logins())
    }

    @Test
    fun `geweigerd refresh token - één stille login, daarna pauze`() = runBlocking {
        // Refresh 401; de login zelf faalt onverwacht (geen redirect) → oplopende pauze.
        val host = FakeHost { r -> if (r.url == VrtAuth.SSO_REFRESH) response(401) else response(200) }.withExpiredSession()
        val auth = VrtAuth(host)

        val first = assertThrows<ProviderException> { runBlocking { auth.accessToken() } }
        assertEquals(ErrorKind.API_CHANGED, first.kind)
        assertNull(host.storage.get(VrtAuth.RT))
        assertEquals(1, host.logins())

        val second = assertThrows<ProviderException> { runBlocking { auth.accessToken() } }
        assertEquals(ErrorKind.NETWORK, second.kind) // gepauzeerd = tijdelijk
        assertEquals(1, host.logins())

        host.now += 16 * 60_000L
        assertThrows<ProviderException> { runBlocking { auth.accessToken() } }
        assertEquals(2, host.logins())
    }

    @Test
    fun `zonder sessie en zonder pauze niet ingelogd als login geweigerd werd`() = runBlocking {
        val host = FakeHost { response(200) }
        val guard = LoginGuard(host.storage, host::nowMillis)
        guard.record(host.credentials(), LoginResult.Failure(LoginFailure.INVALID_CREDENTIALS, "fout wachtwoord"))

        val e = assertThrows<ProviderException> { runBlocking { VrtAuth(host).accessToken() } }
        assertEquals(ErrorKind.NOT_LOGGED_IN, e.kind)
        assertEquals(0, host.logins())
    }
}

class LoginGuardTest {

    private val store = InMemoryKeyValueStore()
    private var now = 0L
    private val guard = LoginGuard(store) { now }
    private val creds = Credentials("a", "b")

    @Test
    fun `oplopende pauze bij throttling`() {
        guard.backOff()
        assertEquals(LoginFailure.NETWORK, guard.check(creds, explicit = true)?.reason)
        now += 15 * 60_000L
        assertNull(guard.check(creds, explicit = false))

        guard.backOff()
        now += 59 * 60_000L
        assertNotNull(guard.check(creds, explicit = false))
        now += 2 * 60_000L
        assertNull(guard.check(creds, explicit = false))

        guard.backOff()
        guard.backOff() // plafond: 4 u
        now += 4 * 60 * 60_000L
        assertNull(guard.check(creds, explicit = false))
    }

    @Test
    fun `geweigerde credentials enkel nog expliciet of met andere gegevens`() {
        guard.record(creds, LoginResult.Failure(LoginFailure.INVALID_CREDENTIALS, "x"))
        assertEquals(LoginFailure.INVALID_CREDENTIALS, guard.check(creds, explicit = false)?.reason)
        assertNull(guard.check(creds, explicit = true))
        assertNull(guard.check(Credentials("a", "nieuw"), explicit = false))
    }

    @Test
    fun `netwerkfout telt niet, succes wist alles`() {
        guard.record(creds, LoginResult.Failure(LoginFailure.NETWORK, "offline"))
        assertNull(guard.check(creds, explicit = false))

        guard.backOff()
        guard.record(creds, LoginResult.Success)
        assertTrue(guard.check(creds, explicit = false) == null)
    }
}

class VrtAuthConcurrencyTest {

    @Test
    fun `parallelle calls delen één refresh`() = runBlocking {
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val host = object : HostServices {
            override val storage = InMemoryKeyValueStore().apply { put(VrtAuth.RT, "rt") }
            override val http = object : HostHttp {
                override suspend fun execute(request: HttpRequest): HttpResponse {
                    refreshes.incrementAndGet()
                    delay(50)
                    return HttpResponse(200, mapOf("set-cookie" to listOf("${VrtAuth.AT}=at; Max-Age=3600")), ByteArray(0), "")
                }
                override fun cookies(url: String) = emptyMap<String, String>()
                override fun clearCookies() {}
            }
            override fun credentials(): Credentials? = null
            override fun log(level: LogLevel, message: String, error: Throwable?) {}
        }
        val auth = VrtAuth(host)
        val tokens = (1..5).map { async(Dispatchers.Default) { auth.accessToken() } }
            .map { it.await() }
        assertEquals(List(5) { "at" }, tokens)
        assertEquals(1, refreshes.get())
    }
}
