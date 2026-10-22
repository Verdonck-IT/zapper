package be.verdonckit.zapper.data

import android.util.Log
import be.verdonckit.zapper.plugin.PluginManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Mijn lijst" per platform. Het platform is de bron van waarheid (zelfde lijst als in de
 * eigen app/website); toevoegen en verwijderen gebeurt meteen lokaal en daarna bij het platform.
 */
class MyListSync(private val dao: CatalogDao, private val plugins: PluginManager) {

    private val mutex = Mutex()
    private var lastRefresh = 0L

    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < MIN_REFRESH_INTERVAL_MS) return@withLock
        lastRefresh = now
        for (plugin in plugins.plugins.value.values) {
            val p = plugin.provider
            if (!p.info.supportsMyList) continue
            runCatching {
                if (!p.isLoggedIn()) return@runCatching
                val entries = p.myList().mapIndexed { index, item ->
                    val key = "${plugin.id}:${item.id}"
                    // Wat de index al weet (soort, beelden) aanvullen; anders wat de lijst zelf geeft.
                    val known = dao.item(key)
                    MyListEntity(
                        key = key,
                        providerId = plugin.id,
                        itemId = item.id,
                        kind = known?.kind ?: item.kind.name,
                        title = item.title,
                        description = item.description ?: known?.description,
                        posterUrl = item.posterUrl ?: known?.posterUrl,
                        backdropUrl = item.backdropUrl ?: known?.backdropUrl,
                        logoUrl = item.logoUrl ?: known?.logoUrl,
                        playableId = item.playableId ?: known?.playableId,
                        sortOrder = index,
                        // Volgorde van het platform behouden: eerste = meest recent toegevoegd.
                        addedAt = now - index,
                    )
                }.distinctBy { it.key }
                dao.replaceMyList(plugin.id, entries)
            }.onFailure { Log.w(TAG, "${plugin.id}: Mijn lijst ophalen mislukt: ${it.message}") }
        }
    }

    /** @return null bij succes, anders een foutmelding voor de gebruiker (de wijziging is dan teruggedraaid). */
    suspend fun set(item: ItemEntity, inList: Boolean): String? {
        val plugin = plugins.get(item.providerId) ?: return "Plugin ${item.providerId} niet geladen"
        if (!plugin.provider.info.supportsMyList) return "${plugin.name} ondersteunt geen Mijn lijst"
        val previous = dao.myListEntry(item.key)
        if (inList) {
            dao.insertMyList(
                listOf(
                    MyListEntity(
                        key = item.key, providerId = item.providerId, itemId = item.itemId, kind = item.kind,
                        title = item.title, description = item.description, posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl, logoUrl = item.logoUrl, playableId = item.playableId,
                        sortOrder = 0, addedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        } else {
            dao.deleteMyList(item.key)
        }
        return try {
            plugin.provider.setInMyList(item.itemId, inList)
            null
        } catch (e: Exception) {
            Log.w(TAG, "${item.providerId}: Mijn lijst bijwerken mislukt: ${e.message}")
            if (previous != null) dao.insertMyList(listOf(previous)) else dao.deleteMyList(item.key)
            "Mijn lijst bijwerken bij ${plugin.name} mislukt: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "MyListSync"
        private const val MIN_REFRESH_INTERVAL_MS = 60_000L
    }
}
