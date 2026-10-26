package be.verdonckit.zapper.plugins.vtmgo

import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.testkit.ContractTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.Base64

class VtmGoContractTest : ContractTest("VTMGO") {

    override fun createProvider() = VtmGoProvider()

    /** Live tegen VTM GO. Overgeslagen zolang er nooit met code ingelogd werd (ZAPPER_DEVICE_LOGIN=1). */
    @Test
    @Tag("live")
    fun `live contract`() = runContract()
}

class VtmGoUnitTest {

    @Test
    fun `thema's uit rijtitels`() {
        assertEquals(listOf("thema-comedy"), VtmGoProvider.themes("Komische series"))
        assertEquals(listOf("thema-crime", "thema-truecrime"), VtmGoProvider.themes("True crime"))
        assertEquals(listOf("thema-lifestyle"), VtmGoProvider.themes("Op je menu vandaag"))
        assertEquals(emptyList<String>(), VtmGoProvider.themes("Moet je nu zien"))
    }

    @Test
    fun `film of reeks uit de labels`() {
        assertEquals(ItemKind.SERIES, VtmGoProvider.kindFromLabels(listOf("2001", "7 seizoenen"), "main"))
        assertEquals(ItemKind.MOVIE, VtmGoProvider.kindFromLabels(listOf("2024", "1u 45min"), "main"))
        assertEquals(ItemKind.MOVIE, VtmGoProvider.kindFromLabels(listOf("2024"), "films"))
        assertEquals(ItemKind.SERIES, VtmGoProvider.kindFromLabels(listOf("2024"), "series"))
    }

    @Test
    fun productiejaar() {
        val labels = listOf(
            JSONObject("""{"label":"2001","accessibilityLabel":"Productiejaar"}"""),
            JSONObject("""{"label":"7 seizoenen"}"""),
        )
        assertEquals(2001, VtmGoProvider.yearFromLabels(labels))
    }

    @Test
    fun `licentie uit DRMtoday-JSON of ruw`() {
        val license = byteArrayOf(1, 2, 3, 4)
        val json = """{"license":"${Base64.getEncoder().encodeToString(license)}"}""".toByteArray()
        assertArrayEquals(license, VtmGoProvider.licenseFromResponse(json))
        assertArrayEquals(license, VtmGoProvider.licenseFromResponse(license))
    }

    @Test
    fun `huidige uitzending`() {
        val broadcasts = listOf(
            JSONObject("""{"title":{"label":"Nacht"},"startsAt":"2026-10-26T02:55:00+01:00","endsAt":"2026-10-26T05:35:00+01:00"}"""),
            JSONObject("""{"title":{"label":"Ochtend"},"startsAt":"2026-10-26T05:35:00+01:00","endsAt":"2026-10-26T09:00:00+01:00"}"""),
        )
        val at = OffsetDateTime.parse("2026-10-26T06:00:00+01:00").toInstant().toEpochMilli()
        assertEquals("Ochtend", VtmGoProvider.currentBroadcast(broadcasts, at)?.optJSONObject("title")?.optString("label"))
        assertNull(VtmGoProvider.currentBroadcast(broadcasts, at + 24 * 3600_000L))
    }

    @Test
    fun `betalend aanbod herkennen`() {
        assertEquals(true, VtmGoProvider.isPremium(JSONObject("""{"premiumProductLogo":{"name":"Streamz Basic"}}""")))
        assertEquals(false, VtmGoProvider.isPremium(JSONObject("""{"premiumProductLogo":null}""")))
        assertEquals(false, VtmGoProvider.isPremium(JSONObject("""{"title":"Got Talent"}""")))
    }

    @Test
    fun `seizoen en aflevering uit Kijk verder`() {
        assertEquals(2 to 35, VtmGoProvider.seasonAndEpisode("S2 A35"))
        assertNull(VtmGoProvider.seasonAndEpisode("Aflevering 5"))
        assertNull(VtmGoProvider.seasonAndEpisode(null))
    }

    @Test
    fun `vervaldatum uit JWT`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1800000000}""".toByteArray())
        assertEquals(1_800_000_000_000L, VtmGoAuth.jwtExpiry("x.$payload.y"))
        assertNull(VtmGoAuth.jwtExpiry("geen-jwt"))
    }
}
