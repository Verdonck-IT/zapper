package be.verdonckit.zapper.ui

import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.ui.detail.inEpisodeOrder
import be.verdonckit.zapper.ui.home.HomeViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpisodeLabelTest {

    @Test
    fun `titel die enkel de code herhaalt valt weg`() {
        assertEquals("S24 A14", HomeViewModel.episodeLabel(24, 14, "S24-A14"))
        assertEquals("Aflevering 14", HomeViewModel.episodeLabel(null, 14, "Aflevering 14"))
        assertEquals("S2 A3", HomeViewModel.episodeLabel(2, 3, "Aflevering 3"))
    }

    @Test
    fun `echte titel blijft staan`() {
        assertEquals("S24 A14 – De finale", HomeViewModel.episodeLabel(24, 14, "De finale"))
        assertEquals("S1 A2 – Aflevering 3", HomeViewModel.episodeLabel(1, 2, "Aflevering 3"))
        assertEquals("Journaal", HomeViewModel.episodeLabel(null, null, "Journaal"))
    }

    @Test
    fun `afleveringen oplopend als ze allemaal een nummer hebben`() {
        fun ep(n: Int?) = Episode(id = "$n", title = "t", playableId = "$n", episodeNumber = n)
        assertEquals(listOf(12, 13, 14), inEpisodeOrder(listOf(ep(14), ep(13), ep(12))).map { it.episodeNumber })
        assertEquals(listOf(14, null, 12), inEpisodeOrder(listOf(ep(14), ep(null), ep(12))).map { it.episodeNumber })
    }
}
