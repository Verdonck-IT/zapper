package be.verdonckit.zapper.plugins.vrtmax

import be.verdonckit.zapper.testkit.ContractTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Base64

class VrtMaxContractTest : ContractTest("VRTMAX") {

    override fun createProvider() = VrtMaxProvider()

    /** Live tegen VRT MAX. Wordt overgeslagen zonder VRTMAX_USERNAME/PASSWORD. */
    @Test
    @Tag("live")
    fun `live contract`() = runContract()
}

class VrtMaxUnitTest {

    @Test
    fun `zoeklijst-id is identiek aan die van de Kodi-add-on`() {
        val id = VrtMaxProvider.searchListId("drama")
        val decoded = String(Base64.getDecoder().decode(id.removePrefix("$")))
        assertEquals(
            "o%14|{\"facets\": [{\"name\": \"genre\", \"values\": [\"drama\"]}, " +
                "{\"name\": \"entitytype\", \"values\": [\"video-program\"]}], \"resultType\": \"watch\"}|watch%",
            decoded,
        )
    }

    @Test
    fun `programmanaam uit link`() {
        assertEquals("de-ideale-wereld", VrtMaxProvider.programNameFromLink("/vrtmax/a-z/de-ideale-wereld/"))
        assertEquals("de-campus-cup", VrtMaxProvider.programNameFromLink("//www.vrt.be/vrtnu/a-z/de-campus-cup.relevant/"))
    }

    @Test
    fun `seizoen en afleveringsnummer`() {
        assertEquals(2, VrtMaxProvider.numberAfter("Seizoen 2", "Seizoen"))
        assertEquals(null, VrtMaxProvider.numberAfter("Aflevering 5", "Seizoen"))
    }
}
