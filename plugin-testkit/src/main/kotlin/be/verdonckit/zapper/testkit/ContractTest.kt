package be.verdonckit.zapper.testkit

import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.support.ProviderSelfTest
import be.verdonckit.zapper.api.support.SelfTestResult
import be.verdonckit.zapper.api.support.SelfTestStep
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
 * Schrijft een rapport naar `build/health/<provider>.json` — dat is de input voor de fix-agent.
 * Is inloggen tijdelijk niet mogelijk (netwerk, throttling, pauze), dan wordt de test
 * overgeslagen zonder rapport: geen fix-agent en geen extra loginpogingen.
 */
abstract class ContractTest(private val envPrefix: String) {

    protected abstract fun createProvider(): StreamingProvider

    protected fun runContract() {
        val creds = TestkitFiles.credentials(envPrefix)
        assumeTrue(
            creds != null,
            "Geen ${envPrefix}_USERNAME/PASSWORD (env of ${TestkitFiles.dir}/credentials.properties): contracttest overgeslagen",
        )

        val host = JvmHostServices(creds, storage = TestkitFiles.tokenStore(envPrefix, creds!!.username))
        val provider = createProvider().also { it.attach(host) }
        val out = File("build/health/${provider.info.id}.json").apply { delete() }

        val session = runBlocking { runCatching { provider.isLoggedIn() } }
        val error = session.exceptionOrNull()
        assumeFalse(
            error is ProviderException && error.kind == ErrorKind.NETWORK,
            "Inloggen tijdelijk niet mogelijk, contracttest overgeslagen: ${error?.message}",
        )

        val result = when {
            error != null -> loginFailed(provider, "${error::class.simpleName}: ${error.message}")
            session.getOrNull() != true -> loginFailed(provider, "niet ingelogd: wachtwoord geweigerd of geen geldige sessie (zie log)")
            else -> runBlocking { ProviderSelfTest.run(provider, host.http) }
        }

        out.parentFile.mkdirs()
        out.writeText(result.toJson())
        result.steps.forEach { println("${if (it.ok) "✔" else "✘"} ${it.name}: ${it.detail}") }

        assertTrue(result.ok, "Contract gefaald bij stap '${result.firstFailure?.name}': ${result.firstFailure?.detail}")
    }

    private fun loginFailed(provider: StreamingProvider, detail: String) =
        SelfTestResult(provider.info.id, listOf(SelfTestStep("login", false, detail)))
}
