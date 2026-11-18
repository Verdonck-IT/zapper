package be.verdonckit.zapper.ui

import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.data.WatchState
import be.verdonckit.zapper.ui.detail.NextUpKind
import be.verdonckit.zapper.ui.detail.pickNextUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NextUpTest {

    private fun ep(n: Int) = Episode(id = "e$n", title = "Aflevering $n", playableId = "p$n", episodeNumber = n)
    private val order = (1..5).map { 0 to ep(it) }

    private val watched = WatchState(positionMs = 3_000_000, durationMs = 3_000_000, watched = true)
    private val half = WatchState(positionMs = 1_000_000, durationMs = 3_000_000, watched = false)

    @Test
    fun `niets bekeken - eerste aflevering`() {
        val next = pickNextUp(order, emptyMap())!!
        assertEquals("p1", next.episode.playableId)
        assertEquals(NextUpKind.START, next.kind)
    }

    @Test
    fun `oude half bekeken aflevering van het platform wint niet van nieuwere bekeken`() {
        // Aflevering 2 ooit half bekeken (staat nog in "Verder kijken" van het platform), 3 en 4 uitgekeken.
        val watch = mapOf("p2" to half, "p3" to watched, "p4" to watched)
        val next = pickNextUp(order, watch, remoteHint = "p2")!!
        assertEquals("p5", next.episode.playableId)
        assertEquals(NextUpKind.NEXT, next.kind)
    }

    @Test
    fun `hint van het platform op of na de verst bekeken aflevering telt wel`() {
        val watch = mapOf("p1" to watched, "p2" to half)
        assertEquals("p2", pickNextUp(order, watch, remoteHint = "p2")!!.episode.playableId)
        assertEquals(NextUpKind.RESUME, pickNextUp(order, watch, remoteHint = "p2")!!.kind)
        // Het platform stelt een latere (nog niet begonnen) aflevering voor.
        assertEquals("p4", pickNextUp(order, mapOf("p1" to watched), remoteHint = "p4")!!.episode.playableId)
    }

    @Test
    fun `verst bekeken half - hervatten`() {
        val watch = mapOf("p1" to watched, "p2" to half, "p3" to half)
        val next = pickNextUp(order, watch)!!
        assertEquals("p3", next.episode.playableId)
        assertEquals(NextUpKind.RESUME, next.kind)
    }

    @Test
    fun `net in Zapper een oudere aflevering bekeken - die hervatten`() {
        val watch = mapOf("p2" to half, "p4" to watched)
        assertEquals("p2", pickNextUp(order, watch, recentLocalId = "p2")!!.episode.playableId)
    }

    @Test
    fun `reeks uitgekeken - FINISHED, afspelen begint opnieuw bij 1`() {
        val watch = mapOf("p3" to half, "p5" to watched)
        val next = pickNextUp(order, watch, remoteHint = "p3")!!
        assertEquals(NextUpKind.FINISHED, next.kind)
        assertEquals("p5", next.episode.playableId)
        assertEquals("p1", next.playEpisode.playableId)
    }

    @Test
    fun `laatste aflevering net in Zapper uitgekeken - FINISHED`() {
        val watch = (1..5).associate { "p$it" to watched }
        assertEquals(NextUpKind.FINISHED, pickNextUp(order, watch, recentLocalId = "p5")!!.kind)
    }

    @Test
    fun `niet beschikbare afleveringen worden overgeslagen`() {
        val withGap = listOf(0 to ep(1), 0 to ep(2).copy(available = false), 0 to ep(3))
        assertEquals("p3", pickNextUp(withGap, mapOf("p1" to watched))!!.episode.playableId)
        val lastGone = listOf(0 to ep(1), 0 to ep(2).copy(available = false))
        assertEquals(NextUpKind.FINISHED, pickNextUp(lastGone, mapOf("p1" to watched))!!.kind)
    }

    @Test
    fun `geen afleveringen`() {
        assertNull(pickNextUp(emptyList(), emptyMap()))
    }
}
