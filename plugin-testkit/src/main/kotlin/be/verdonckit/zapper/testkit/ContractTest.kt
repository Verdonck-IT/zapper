package be.verdonckit.zapper.testkit

import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.LoginMethod
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.support.ProviderSelfTest
import be.verdonckit.zapper.api.support.SelfTestResult
import be.verdonckit.zapper.api.support.SelfTestStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Basis voor de live contracttest van een plugin. Draait enkel als er credentials zijn:
 * env-variabelen `<PREFIX>_USERNAME`/`<PREFIX>_PASSWORD`, of anders `credentials.properties`
 * in [TestkitFiles.dir]. Zonder blijft `./gradlew test` lokaal en in gewone CI groen.
 *
 * Login gaat zoals in de app: de tokens staan in een bestand ([TestkitFiles.tokenStore]), dus
 * meestal volstaat het refresh token. Werkt dat niet meer, dan logt de plugin zelf stil in met
 * het wachtwoord, met dezelfde pauze na mislukte pogingen. De test roept nooit zelf `login()` aan.
 *
 * Zenders met een login met code ([LoginMethod.DEVICE_CODE], bv. VTM GO) hebben geen credentials:
 * log eenmalig in met `ZAPPER_DEVICE_LOGIN=1` (de test toont de code), daarna volstaat het bewaarde
 * refresh token. Nooit ingelogd = overgeslagen; sessie kwijt = falen met "niet ingelogd".
 *
 * Schrijft een rapport naar `build/health/<provider>.json` — dat is de input voor de fix-agent.
 * Is inloggen tijdelijk niet mogelijk (netwerk, throttling, pauze), dan wordt de test
 * overgeslagen zonder rapport: geen fix-agent en geen extra loginpogingen.
 */
abstract class ContractTest(private val envPrefix: String) {

    protected abstract fun createProvider(): StreamingProvider

    protected fun runContract() {
        val provider = createProvider()
        val deviceLogin = provider.info.loginMethod == LoginMethod.DEVICE_CODE
        val creds = if (deviceLogin) null else TestkitFiles.credentials(envPrefix)
        assumeTrue(
            deviceLogin || creds != null,
            "Geen ${envPrefix}_USERNAME/PASSWORD (env of ${TestkitFiles.dir}/credentials.properties): contracttest overgeslagen",
        )

        val store = TestkitFiles.tokenStore(envPrefix, creds?.username ?: DEVICE_OWNER)
        val host = JvmHostServices(creds, storage = store)
        provider.attach(host)
        val out = File("build/health/${provider.info.id}.json").apply { delete() }

        var session = runBlocking { runCatching { provider.isLoggedIn() } }
        if (deviceLogin && session.getOrNull() == false) {
            if (System.getenv("ZAPPER_DEVICE_LOGIN") == "1") {
                session = runBlocking { runCatching { interactiveDeviceLogin(provider) } }
                if (session.getOrNull() == true) store.put(DEVICE_MARKER, "1")
            } else {
                assumeTrue(
                    store.get(DEVICE_MARKER) != null,
                    "${provider.info.displayName} nog nooit ingelogd: eenmalig ZAPPER_DEVICE_LOGIN=1 ./gradlew " +
                        ":plugins:${provider.info.id}:test --tests '*ContractTest*'",
                )
            }
        }
        val error = session.exceptionOrNull()
        assumeFalse(
            error is ProviderException && error.kind == ErrorKind.NETWORK,
            "Inloggen tijdelijk niet mogelijk, contracttest overgeslagen: ${error?.message}",
        )

        val result = when {
            error != null -> loginFailed(provider, "${error::class.simpleName}: ${error.message}")
            session.getOrNull() != true -> loginFailed(
                provider,
                if (deviceLogin) "niet ingelogd: sessie verlopen, opnieuw inloggen met code (ZAPPER_DEVICE_LOGIN=1)"
                else "niet ingelogd: wachtwoord geweigerd of geen geldige sessie (zie log)",
            )
            else -> runBlocking { ProviderSelfTest.run(provider, host.http) }
        }

        out.parentFile.mkdirs()
        out.writeText(result.toJson())
        result.steps.forEach { println("${if (it.ok) "✔" else "✘"} ${it.name}: ${it.detail}") }

        assertTrue(result.ok, "Contract gefaald bij stap '${result.firstFailure?.name}': ${result.firstFailure?.detail}")
    }

    private fun loginFailed(provider: StreamingProvider, detail: String) =
        SelfTestResult(provider.info.id, listOf(SelfTestStep("login", false, detail)))

    /** Toont de code in de console en wacht tot ze bevestigd is (of verloopt). */
    private suspend fun interactiveDeviceLogin(provider: StreamingProvider): Boolean {
        val code = provider.startDeviceLogin()
        println()
        println("=== ${provider.info.displayName}: ga naar ${code.verificationUrl} en geef code ${code.userCode} in ===")
        code.verificationUrlComplete?.let { println("    (of open $it)") }
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.pollIntervalSeconds * 1000L)
            when (val r = provider.pollDeviceLogin()) {
                null -> continue
                is LoginResult.Success -> return true
                is LoginResult.Failure -> error("login met code mislukt: ${r.message}")
            }
        }
        error("code verlopen zonder bevestiging")
    }

    private companion object {
        const val DEVICE_OWNER = "device-login"

        /** Gezet na een geslaagde login met code: daarna is "niet ingelogd" een fout, geen reden om over te slaan. */
        const val DEVICE_MARKER = "testkit.deviceLogin"
    }
}
