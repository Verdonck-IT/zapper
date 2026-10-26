package be.verdonckit.zapper.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginCompatTest {

    private fun entry(id: String, build: Int, api: Int) =
        PluginEntry(id, id.uppercase(), build, "v$build", api, "Entry", "plugins/$id/$id-$build.jar", "sha")

    @Test
    fun `alles op de API van de app`() {
        val c = PluginCompat.evaluate(listOf(entry("vrtmax", 10, 5), entry("play", 10, 5)), appApi = 5, now = 0)
        assertFalse(c.updateRequired)
        assertTrue(c.needsNewerApp.isEmpty() && c.awaitingPlugins.isEmpty())
    }

    @Test
    fun `nieuwste builds vragen een nieuwere app`() {
        // Oude app (v4): VRT heeft nog een v4-build maar de nieuwste is v5; VTM GO bestaat enkel als v5.
        val entries = listOf(entry("vrtmax", 8, 4), entry("vrtmax", 12, 5), entry("vtmgo", 12, 5), entry("play", 8, 4))
        val c = PluginCompat.evaluate(entries, appApi = 4, now = 0)
        assertTrue(c.updateRequired)
        assertEquals(5, c.requiredApi)
        assertEquals(listOf("VRTMAX", "VTMGO"), c.needsNewerApp)
        // De v4-builds blijven installeerbaar tot de app bijgewerkt is.
        assertEquals(listOf("vrtmax" to 8, "play" to 8), PluginCompat.installable(entries, 4).map { it.id to it.build })
    }

    @Test
    fun `app nieuwer dan de gepubliceerde plugins`() {
        val c = PluginCompat.evaluate(listOf(entry("vrtmax", 8, 4), entry("vrtmax", 12, 5), entry("focuswtv", 8, 4)), appApi = 5, now = 0)
        assertNull(c.requiredApi)
        assertEquals(listOf("FOCUSWTV"), c.awaitingPlugins)
    }

    @Test
    fun `installeerbaar = nieuwste build met dezelfde API`() {
        val entries = listOf(entry("vrtmax", 9, 5), entry("vrtmax", 11, 5), entry("vrtmax", 12, 6))
        assertEquals(listOf(11), PluginCompat.installable(entries, 5).map { it.build })
    }
}
