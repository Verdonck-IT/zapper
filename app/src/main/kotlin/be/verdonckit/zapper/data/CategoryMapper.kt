package be.verdonckit.zapper.data

import android.content.Context
import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.ItemKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

data class UnifiedCategory(val id: String, val name: String)

/**
 * Mapt native categorieën van elke provider naar één gedeelde taxonomie, zodat
 * "Crime & thriller" op het startscherm VRT MAX, VTM GO en Play door elkaar toont.
 * Zie assets/categories.json voor de volgorde van beslissen.
 */
class CategoryMapper(json: String) {

    constructor(context: Context) : this(context.assets.open("categories.json").bufferedReader().use { it.readText() })

    private data class Rule(val whenAll: Set<String>, val add: Set<String>, val remove: Set<String>, val keepOnly: Set<String>?)

    val categories: List<UnifiedCategory>
    private val titles: Map<String, List<String>>
    private val providerMaps: Map<String, Map<String, List<String>>>
    private val keywords: Map<String, List<Regex>>
    private val rules: List<Rule>

    init {
        val root = JSONObject(json)
        categories = root.getJSONArray("categories").objects().map { UnifiedCategory(it.getString("id"), it.getString("name")) }
        titles = root.optJSONObject("titles")?.let { t ->
            t.keys().asSequence().associate { normalize(it) to t.getJSONArray(it).strings() }
        }.orEmpty()
        providerMaps = root.getJSONObject("providers").let { p ->
            p.keys().asSequence().associateWith { pid ->
                val m = p.getJSONObject(pid)
                m.keys().asSequence().associateWith { m.getJSONArray(it).strings() }
            }
        }
        keywords = root.getJSONObject("keywords").let { k ->
            k.keys().asSequence().associateWith { cat ->
                k.getJSONArray(cat).strings().map { Regex("\\b${Regex.escape(normalize(it))}\\b") }
            }
        }
        rules = root.optJSONArray("rules")?.objects()?.map { r ->
            Rule(
                whenAll = r.getJSONArray("when").strings().toSet(),
                add = r.optJSONArray("add")?.strings()?.toSet().orEmpty(),
                remove = r.optJSONArray("remove")?.strings()?.toSet().orEmpty(),
                keepOnly = r.optJSONArray("keepOnly")?.strings()?.toSet(),
            )
        }.orEmpty()
    }

    /** Welke native categorieën van deze zender leveren de eigen categorie [unified] aan? */
    fun nativeSources(providerId: String, unified: String): List<String> =
        providerMaps[providerId].orEmpty().filterValues { unified in it }.keys.toList()

    fun map(providerId: String, item: CatalogItem): Set<String> {
        if (item.kind == ItemKind.LIVE) return setOf("live")

        val result = LinkedHashSet<String>()
        titles[normalize(item.title)]?.let(result::addAll)

        val pmap = providerMaps[providerId].orEmpty()
        item.nativeCategoryIds.forEach { nat -> pmap[nat]?.let(result::addAll) }

        val haystack = normalize("${item.title} ${item.description.orEmpty()}")
        keywords.forEach { (cat, patterns) -> if (patterns.any { it.containsMatchIn(haystack) }) result += cat }

        if (item.kind == ItemKind.MOVIE) result += "films"

        for (rule in rules) {
            if (!result.containsAll(rule.whenAll)) continue
            result += rule.add
            result -= rule.remove
            rule.keepOnly?.let { keep -> result.retainAll(keep) }
        }

        if (result.isEmpty()) result += "other"
        return result
    }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** "De Ideale Wereld (2024)" en "de ideale wereld" vallen samen. */
        fun dedupKey(item: CatalogItem): String {
            val base = normalize(item.title.replace(Regex("\\(\\d{4}\\)"), ""))
                .removePrefix("de ").removePrefix("het ").removePrefix("the ")
            return when {
                item.kind == ItemKind.LIVE -> "live|$base"
                item.kind == ItemKind.MOVIE && item.year != null -> "$base|${item.year}"
                else -> base
            }
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
