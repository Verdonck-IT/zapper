package be.verdonckit.zapper.plugins.focuswtv

import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.ItemDetails
import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.NativeCategory
import be.verdonckit.zapper.api.Page
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.ProviderInfo
import be.verdonckit.zapper.api.StreamFormat
import be.verdonckit.zapper.api.StreamInfo
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.get
import java.net.URLDecoder

/**
 * Focus en WTV (West-Vlaamse regionale tv): enkel de twee livestreams, zonder login.
 *
 * De live-pagina's (focus-wtv.be/focus-live, /wtv-live) bevatten een iframe naar
 * apps.focus-wtv.be/embed/LivePlayer.html?stream=<url-encoded m3u8>. Die URL lezen we uit
 * de pagina (één keer per dag), met de huidige Bunny-CDN-URL als terugval. Gewone HLS,
 * zonder DRM of token.
 */
class FocusWtvProvider : StreamingProvider {

    override val info = ProviderInfo(id = "focuswtv", displayName = "Focus & WTV", brandColor = 0xFF00AEEF, requiresLogin = false)

    private lateinit var host: HostServices

    override fun attach(host: HostServices) {
        this.host = host
    }

    override suspend fun login(credentials: Credentials): LoginResult = LoginResult.Success

    override suspend fun isLoggedIn(): Boolean = true

    override suspend fun logout() {}

    override fun nativeCategories(): List<NativeCategory> = listOf(NativeCategory(LIVE, "Live tv"))

    override suspend fun catalog(categoryId: String?, cursor: String?): Page<CatalogItem> =
        Page(
            CHANNELS.map { ch ->
                CatalogItem(
                    id = "live-${ch.id}",
                    kind = ItemKind.LIVE,
                    title = ch.name,
                    description = ch.description,
                    // Kant-en-klare 16:9-kaart in de huisstijl (logo + "livestream").
                    backdropUrl = ch.card,
                    logoUrl = ch.card,
                    nativeCategoryIds = listOf(LIVE),
                    playableId = "$LIVE_PREFIX${ch.id}",
                )
            },
            null,
        )

    override suspend fun details(itemId: String): ItemDetails =
        throw ProviderException(ErrorKind.NOT_AVAILABLE, "Focus & WTV: enkel livestreams")

    override suspend fun episodes(seasonId: String, cursor: String?): Page<Episode> = Page(emptyList(), null)

    override suspend fun resolveStream(playableId: String): StreamInfo {
        val ch = CHANNELS.firstOrNull { "$LIVE_PREFIX${it.id}" == playableId }
            ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "Focus & WTV: onbekende zender $playableId")
        return StreamInfo(manifestUrl = streamUrl(ch), format = StreamFormat.HLS, isLive = true)
    }

    /** Stream-URL uit de live-pagina, één keer per dag; bij een fout of geen treffer: de bekende URL. */
    private suspend fun streamUrl(ch: Channel): String {
        val key = "stream.${ch.id}"
        val exp = host.storage.get("$key.exp")?.toLongOrNull() ?: 0
        host.storage.get(key)?.takeIf { exp > host.nowMillis() }?.let { return it }
        val found = runCatching {
            val resp = host.http.get(ch.page)
            if (resp.code !in 200..299) error("HTTP ${resp.code}")
            streamFromPage(resp.text())
        }.onFailure { host.log(LogLevel.WARN, "Focus & WTV: live-pagina ${ch.id} niet gelezen: ${it.message}") }
            .getOrNull()
        if (found == null) {
            host.log(LogLevel.WARN, "Focus & WTV: geen stream in ${ch.page}, terugval op de bekende URL")
            return ch.fallbackStream
        }
        host.storage.put(key, found)
        host.storage.put("$key.exp", (host.nowMillis() + 24 * 3600 * 1000).toString())
        return found
    }

    private data class Channel(
        val id: String,
        val name: String,
        val description: String,
        val page: String,
        val fallbackStream: String,
        val card: String,
    )

    companion object {
        private const val LIVE = "live"
        private const val LIVE_PREFIX = "live:"

        private val CHANNELS = listOf(
            Channel(
                id = "focus",
                name = "Focus",
                description = "Live op Focus, de regionale zender van Noord- en Midden-West-Vlaanderen",
                page = "https://focus-wtv.be/focus-live",
                fallbackStream = "https://focuslive.b-cdn.net/playlist.m3u8",
                card = "https://focus-wtv.be/public/stream_focus.png",
            ),
            Channel(
                id = "wtv",
                name = "WTV",
                description = "Live op WTV, de regionale zender van Zuid-West-Vlaanderen",
                page = "https://focus-wtv.be/wtv-live",
                fallbackStream = "https://wtvlive.b-cdn.net/playlist.m3u8",
                card = "https://focus-wtv.be/public/stream_wtv.png",
            ),
        )

        private val EMBED_STREAM = Regex("""LivePlayer\.html\?(?:[^"'\s]*&(?:amp;)?)?stream=([^"'&\s]+)""")
        private val DIRECT_M3U8 = Regex("""https://[^"'\s<>]+\.m3u8""")

        /** Eerst de stream-parameter van de embed-speler, anders de eerste .m3u8 in de pagina. */
        internal fun streamFromPage(html: String): String? {
            EMBED_STREAM.find(html)?.groupValues?.get(1)?.let { encoded ->
                val url = URLDecoder.decode(encoded.replace("&amp;", "&"), "UTF-8")
                if (url.startsWith("https://") && ".m3u8" in url) return url
            }
            return DIRECT_M3U8.find(html)?.value
        }
    }
}
