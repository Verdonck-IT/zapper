package be.verdonckit.zapper.testkit

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.support.ProviderSelfTest
import be.verdonckit.zapper.api.support.SelfTestResult
import be.verdonckit.zapper.api.support.SelfTestStep
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Basis voor de live contracttest van een plugin. Draait enkel als de
 * credentials als environment variables gezet zijn (`<PREFIX>_USERNAME`, `<PREFIX>_PASSWORD`),
 * dus `./gradlew test` blijft lokaal en in gewone CI groen.
 *
 * Schrijft altijd een rapport naar `build/health/<provider>.json` — dat is de input
 * voor de fix-agent.
 */
abstract class ContractTest(private val envPrefix: String) {

    protected abstract fun createProvider(): StreamingProvider

    protected fun runContract() {
        val user = System.getenv("${envPrefix}_USERNAME")
        val pass = System.getenv("${envPrefix}_PASSWORD")
        assumeTrue(!user.isNullOrBlank() && !pass.isNullOrBlank(), "Geen ${envPrefix}_USERNAME/PASSWORD: contracttest overgeslagen")

        val creds = Credentials(user!!, pass!!)
        val host = JvmHostServices(creds)
        val provider = createProvider().also { it.attach(host) }

        val result = runBlocking {
            when (val login = runCatching { provider.login(creds) }.getOrElse { LoginResult.Failure(be.verdonckit.zapper.api.LoginFailure.UNKNOWN, "${it::class.simpleName}: ${it.message}") }) {
                is LoginResult.Success -> ProviderSelfTest.run(provider, host.http)
                is LoginResult.Failure -> SelfTestResult(
                    provider.info.id,
                    listOf(SelfTestStep("login", false, "${login.reason}: ${login.message}")),
                )
            }
        }

        val out = File("build/health/${provider.info.id}.json")
        out.parentFile.mkdirs()
        out.writeText(result.toJson())
        result.steps.forEach { println("${if (it.ok) "✔" else "✘"} ${it.name}: ${it.detail}") }

        assertTrue(result.ok, "Contract gefaald bij stap '${result.firstFailure?.name}': ${result.firstFailure?.detail}")
    }
}
