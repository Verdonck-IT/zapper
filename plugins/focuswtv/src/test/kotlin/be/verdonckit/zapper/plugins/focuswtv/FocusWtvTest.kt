package be.verdonckit.zapper.plugins.focuswtv

import be.verdonckit.zapper.api.support.ProviderSelfTest
import be.verdonckit.zapper.api.get
import be.verdonckit.zapper.testkit.JvmHostServices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

class FocusWtvUnitTest {

    @Test
    fun `stream uit de embed-speler van de live-pagina`() {
        val html = """<iframe src="https://apps.focus-wtv.be/embed/LivePlayer.html?stream=https%3A%2F%2Ffocuslive.b-cdn.net%2Fplaylist.m3u8" allow="autoplay"></iframe>"""
        assertEquals("https://focuslive.b-cdn.net/playlist.m3u8", FocusWtvProvider.streamFromPage(html))
    }

    @Test
    fun `terugval op een rechtstreekse m3u8 in de pagina`() {
        assertEquals("https://wtvlive.b-cdn.net/playlist.m3u8", FocusWtvProvider.streamFromPage("""var src = "https://wtvlive.b-cdn.net/playlist.m3u8";"""))
        assertNull(FocusWtvProvider.streamFromPage("<html>geen stream</html>"))
    }
}

/**
 * Live tegen focus-wtv.be. Geen login nodig, dus altijd uitgevoerd (zelfde stappen als de
 * nachtelijke health-check: catalogus → stream → manifest); rapport in build/health/focuswtv.json.
 */
class FocusWtvContractTest {

    @Test
    @Tag("live")
    fun `live contract`() = runBlocking {
        val host = JvmHostServices(null)
        val provider = FocusWtvProvider().also { it.attach(host) }
        val result = ProviderSelfTest.run(provider, host.http)
        File("build/health/focuswtv.json").apply { parentFile.mkdirs() }.writeText(result.toJson())
        result.steps.forEach { println("${if (it.ok) "✔" else "✘"} ${it.name}: ${it.detail}") }
        assertTrue(result.ok, "Contract gefaald bij stap '${result.firstFailure?.name}': ${result.firstFailure?.detail}")

        // Beide zenders: manifest bereikbaar, en ook het logo (de site weigert sommige user-agents).
        for (item in provider.catalog("live", null).items) {
            val stream = provider.resolveStream(item.playableId!!)
            val manifest = host.http.get(stream.manifestUrl)
            println("${item.title}: ${stream.manifestUrl} HTTP ${manifest.code}, logo HTTP ${host.http.get(item.logoUrl!!).code}")
            assertEquals(200, manifest.code)
            assertTrue(manifest.text().startsWith("#EXTM3U"))
        }
    }
}
