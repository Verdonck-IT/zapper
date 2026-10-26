package be.verdonckit.zapper.api.support

import be.verdonckit.zapper.api.HostHttp
import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.get

data class SelfTestStep(val name: String, val ok: Boolean, val detail: String)

data class SelfTestResult(val providerId: String, val steps: List<SelfTestStep>) {
    val ok: Boolean get() = steps.isNotEmpty() && steps.all { it.ok }
    val firstFailure: SelfTestStep? get() = steps.firstOrNull { !it.ok }

    fun toJson(): String = buildString {
        append("{\"provider\":").append(q(providerId))
        append(",\"ok\":").append(ok)
        append(",\"steps\":[")
        steps.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"name\":").append(q(s.name))
            append(",\"ok\":").append(s.ok)
            append(",\"detail\":").append(q(s.detail)).append('}')
        }
        append("]}")
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/**
 * De end-to-end check die zowel de app (na een plugin-update) als de nachtelijke
 * CI-job uitvoert: ingelogd → catalogus → details → stream → manifest bereikbaar.
 * Stopt bij de eerste fout; die stap is meteen de diagnose voor de fix-agent.
 */
object ProviderSelfTest {

    /** Zoveel seizoenen probeert de details-stap hoogstens voor een beschikbare aflevering. */
    private const val MAX_SEASONS = 5

    suspend fun run(provider: StreamingProvider, http: HostHttp, playStream: Boolean = true): SelfTestResult {
        val steps = mutableListOf<SelfTestStep>()
        suspend fun <T> step(name: String, block: suspend () -> Pair<T, String>): T? = try {
            val (value, detail) = block()
            steps += SelfTestStep(name, true, detail)
            value
        } catch (t: Throwable) {
            steps += SelfTestStep(name, false, "${t::class.simpleName}: ${t.message}")
            null
        }

        val id = provider.info.id

        step("login") {
            val ok = provider.isLoggedIn()
            check(ok) { "niet ingelogd (sessie verlopen of login gewijzigd)" }
            Unit to "sessie geldig"
        } ?: return SelfTestResult(id, steps)

        if (provider.info.syncsProgress) {
            step("continue") {
                val list = provider.continueWatching()
                Unit to "${list.size} item(s) in verder kijken"
            } ?: return SelfTestResult(id, steps)
        }

        val category = provider.nativeCategories().firstOrNull { it.id != "live" }?.id
        val items = step("catalog") {
            val page = provider.catalog(category, null)
            check(page.items.isNotEmpty()) { "lege catalogus voor categorie $category" }
            val withImage = page.items.count { it.backdropUrl != null || it.posterUrl != null }
            check(withImage > 0) { "geen enkel item heeft een afbeelding" }
            page.items to "${page.items.size} items, $withImage met beeld (categorie $category)"
        } ?: return SelfTestResult(id, steps)

        val target = items.firstOrNull { it.kind == ItemKind.SERIES } ?: items.first()
        val playableId = step("details") {
            target.playableId?.let { return@step it to "direct afspeelbaar: ${target.title}" }
            val details = provider.details(target.id)
            check(details.seasons.isNotEmpty()) { "geen seizoenen voor ${target.title}" }
            // Oudere seizoenen kunnen achter een betaalmuur zitten (bv. VTM GO+): het eerste seizoen met een
            // beschikbare aflevering, te beginnen bij de seizoenen waarvan de afleveringen al meekwamen.
            val ordered = details.seasons.sortedBy { it.episodesPreview.isEmpty() }.take(MAX_SEASONS)
            val ep = ordered.firstNotNullOfOrNull { season ->
                season.episodesPreview.ifEmpty { provider.episodes(season.id, null).items }.firstOrNull { it.available }
            } ?: error("geen beschikbare aflevering in ${ordered.joinToString { "'${it.title}'" }}")
            ep.playableId to "${target.title}: ${details.seasons.size} seizoen(en), test '${ep.title}'"
        } ?: return SelfTestResult(id, steps)

        if (!playStream) return SelfTestResult(id, steps)

        val stream = step("stream") {
            val s = provider.resolveStream(playableId)
            check(s.manifestUrl.startsWith("http")) { "ongeldige manifest-URL" }
            if (s.drm != null && !s.drm.handledByProvider) {
                check(!s.drm.licenseUrl.isNullOrBlank()) { "DRM zonder licentie-URL" }
            }
            s to "${s.format}, drm=${s.drm != null}"
        } ?: return SelfTestResult(id, steps)

        step("manifest") {
            val r = http.get(stream.manifestUrl, stream.requestHeaders)
            check(r.isSuccessful) { "HTTP ${r.code} op manifest" }
            Unit to "HTTP ${r.code}, ${r.body.size} bytes"
        }

        return SelfTestResult(id, steps)
    }
}
