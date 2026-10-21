package be.verdonckit.zapper.data

import android.util.Log
import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.plugin.LoadedPlugin

sealed interface SyncResult {
    data class Ok(val providerId: String, val items: Int) : SyncResult
    data class Skipped(val providerId: String, val reason: String) : SyncResult
    data class Failed(val providerId: String, val reason: String) : SyncResult
}

/**
 * Bouwt de lokale index op. De UI leest uitsluitend uit Room, zodat het
 * startscherm direct opent, ook als een platform even plat ligt.
 */
class CatalogRepository(
    private val dao: CatalogDao,
    private val mapper: CategoryMapper,
) {

    suspend fun sync(plugin: LoadedPlugin, maxPagesPerCategory: Int = 6): SyncResult {
        val p = plugin.provider
        val id = p.info.id
        if (p.info.requiresLogin && !p.isLoggedIn()) {
            plugin.host.credentials()?.let { runCatching { p.login(it) } }
            if (!p.isLoggedIn()) return SyncResult.Skipped(id, "niet ingelogd")
        }

        // Zelfde programma kan in meerdere native categorieën zitten: samenvoegen.
        val merged = LinkedHashMap<String, Pair<CatalogItem, Int>>()
        // Positie per native categorie, voor de volgorde binnen elke rij.
        val nativeRanks = HashMap<String, MutableMap<String, Int>>()
        var failures = 0
        val categories = p.nativeCategories()
        for (cat in categories) {
            var cursor: String? = null
            var page = 0
            try {
                do {
                    val result = p.catalog(cat.id, cursor)
                    result.items.forEachIndexed { idx, item ->
                        val rank = page * 100 + idx
                        nativeRanks.getOrPut(item.id) { HashMap() }.merge(cat.id, rank) { a, b -> minOf(a, b) }
                        val existing = merged[item.id]
                        merged[item.id] = if (existing == null) {
                            item to rank
                        } else {
                            existing.first.copy(
                                // Een film blijft een film, ook als hij eerst in "populair" opdook.
                                kind = if (item.kind == ItemKind.MOVIE) ItemKind.MOVIE else existing.first.kind,
                                nativeCategoryIds = (existing.first.nativeCategoryIds + item.nativeCategoryIds).distinct(),
                                backdropUrl = existing.first.backdropUrl ?: item.backdropUrl,
                                posterUrl = existing.first.posterUrl ?: item.posterUrl,
                                logoUrl = existing.first.logoUrl ?: item.logoUrl,
                            ) to minOf(existing.second, rank)
                        }
                    }
                    cursor = result.nextCursor
                    page++
                } while (cursor != null && page < maxPagesPerCategory)
            } catch (e: ProviderException) {
                failures++
                Log.w(TAG, "$id/${cat.id}: ${e.kind} ${e.message}")
            }
        }

        if (merged.isEmpty()) return SyncResult.Failed(id, "geen items ($failures van ${categories.size} categorieën faalden)")

        val now = System.currentTimeMillis()
        val items = merged.values.map { (item, rank) ->
            ItemEntity(
                key = "$id:${item.id}",
                providerId = id,
                itemId = item.id,
                kind = item.kind.name,
                title = item.title,
                description = item.description,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                logoUrl = item.logoUrl,
                playableId = item.playableId,
                year = item.year,
                dedupKey = CategoryMapper.dedupKey(item),
                rank = rank,
                syncedAt = now,
            )
        }
        val cats = merged.values.flatMap { (item, rank) ->
            val ranks = nativeRanks[item.id].orEmpty()
            mapper.map(id, item).map { unified ->
                val position = mapper.nativeSources(id, unified).mapNotNull { ranks[it] }.minOrNull() ?: rank
                ItemCategoryEntity("$id:${item.id}", unified, position)
            }
        }
        dao.replaceProvider(id, items, cats)
        return SyncResult.Ok(id, items.size)
    }

    companion object { private const val TAG = "CatalogRepository" }
}
