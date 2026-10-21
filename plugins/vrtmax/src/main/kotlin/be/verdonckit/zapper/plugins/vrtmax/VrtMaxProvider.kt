package be.verdonckit.zapper.plugins.vrtmax

import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.DrmInfo
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.ItemDetails
import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.NativeCategory
import be.verdonckit.zapper.api.Page
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.ProviderInfo
import be.verdonckit.zapper.api.Season
import be.verdonckit.zapper.api.StreamFormat
import be.verdonckit.zapper.api.StreamInfo
import be.verdonckit.zapper.api.StreamingProvider
import org.json.JSONObject
import java.util.Base64

class VrtMaxProvider : StreamingProvider {

    override val info = ProviderInfo(id = "vrtmax", displayName = "VRT MAX", brandColor = 0xFF00D1A7)

    private lateinit var host: HostServices
    private lateinit var auth: VrtAuth
    private lateinit var api: VrtApi

    override fun attach(host: HostServices) {
        this.host = host
        auth = VrtAuth(host)
        api = VrtApi(host, auth)
    }

    override suspend fun login(credentials: Credentials): LoginResult = auth.login(credentials)

    override suspend fun isLoggedIn(): Boolean =
        auth.hasSession() && runCatching { auth.accessToken() }.isSuccess

    override suspend fun logout() = auth.logout()

    override fun nativeCategories(): List<NativeCategory> = CATEGORIES

    // ---------- Catalogus ----------

    override suspend fun catalog(categoryId: String?, cursor: String?): Page<CatalogItem> {
        if (categoryId == LIVE) return Page(LIVE_CHANNELS, null)
        val listId = if (categoryId == POPULAR) popularListId() else searchListId(categoryId)

        val vars = "{\"listId\":${jsonString(listId)},\"lazyItemCount\":$PAGE_SIZE" +
            (cursor?.let { ",\"after\":${jsonString(it)}" } ?: "") + "}"
        val data = api.graphql("TileList", VrtQueries.TILE_LIST, vars)
        val conn = data.path("list", "paginatedItems")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "TileList zonder paginatedItems (categorie $categoryId)")

        val items = conn.arr("edges").objects().mapNotNull { it.obj("node") }
            .filter { it.str("__typename") == "ProgramTile" }
            .mapNotNull { programTile(it, categoryId) }
        return Page(items, nextCursor(conn))
    }

    /**
     * VRT MAX heeft geen vaste "populair"-lijst-id; die staat als component op de homepage.
     * We zoeken een programmalijst met een herkenbare titel, anders de eerste programmalijst
     * (de redactionele selectie bovenaan). Eén keer per 6 uur opgezocht.
     */
    private suspend fun popularListId(): String {
        host.storage.get("popular.listId")?.let { cached ->
            val exp = host.storage.get("popular.listId.exp")?.toLongOrNull() ?: 0
            if (exp > host.nowMillis()) return cached
        }
        val data = api.graphql(
            "Page", VrtQueries.HOME_LISTS,
            "{\"pageId\":\"/vrtmax/\",\"pageContext\":{\"mediaType\":\"watch\"},\"componentCount\":50}",
        )
        val lists = data.path("page", "paginatedComponents")?.arr("edges")?.objects().orEmpty()
            .mapNotNull { it.obj("node") }
            .filter { it.str("tileContentType") == "program" && it.str("listId") != null }
        fun title(n: JSONObject) = (n.str("title") ?: n.path("banner")?.str("title")).orEmpty()
        val chosen = lists.firstOrNull { POPULAR_TITLE.containsMatchIn(title(it)) } ?: lists.firstOrNull()
            ?: throw ProviderException(ErrorKind.API_CHANGED, "geen programmalijsten op de homepage")
        val id = chosen.str("listId")!!
        host.storage.put("popular.listId", id)
        host.storage.put("popular.listId.exp", (host.nowMillis() + 6 * 3600 * 1000).toString())
        host.log(be.verdonckit.zapper.api.LogLevel.INFO, "VRT MAX populair = '${title(chosen)}'")
        return id
    }

    private fun programTile(node: JSONObject, categoryId: String?): CatalogItem? {
        val programName = programNameFromLink(node.str("link") ?: return null) ?: return null
        val heading = node.path("preview", "heading")
        return CatalogItem(
            id = programName,
            kind = if (categoryId == "films") ItemKind.MOVIE else ItemKind.SERIES,
            title = node.str("title") ?: programName,
            description = node.str("description"),
            posterUrl = image(node.obj("image")),
            backdropUrl = image(heading?.obj("image")),
            logoUrl = image(heading?.obj("titleArt")),
            nativeCategoryIds = listOfNotNull(categoryId),
        )
    }

    // ---------- Details & afleveringen ----------

    override suspend fun details(itemId: String): ItemDetails {
        val data = api.graphql("ProgramPage", VrtQueries.PROGRAM_PAGE, "{\"pageId\":${jsonString("/vrtmax/a-z/$itemId/")}}")
        val page = data.obj("page") ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "programma $itemId niet gevonden")
        val header = page.obj("header")

        val seasons = mutableListOf<Season>()
        for (navItem in page.path("menu")?.arr("items")?.objects().orEmpty()) {
            for (comp in navItem.arr("components").objects()) {
                when (comp.str("__typename")) {
                    "ContainerNavigation" -> for (inner in comp.arr("items").objects()) {
                        val list = inner.arr("components").objects()
                            .firstOrNull { it.str("listId") != null && it.str("tileContentType") != "program" }
                            ?: continue
                        seasons += Season(list.str("listId")!!, inner.str("title") ?: list.str("title") ?: "Seizoen")
                    }
                    "PaginatedTileList", "StaticTileList" -> if (comp.str("tileContentType") == "episode") {
                        val id = comp.str("listId") ?: continue
                        seasons += Season(id, navItem.str("title") ?: comp.str("title") ?: "Afleveringen")
                    }
                }
            }
        }
        if (seasons.isEmpty()) throw ProviderException(ErrorKind.API_CHANGED, "geen seizoenen/afleveringslijsten gevonden voor $itemId")

        val item = CatalogItem(
            id = itemId,
            kind = ItemKind.SERIES,
            title = header?.str("title") ?: itemId,
            description = header?.path("richDescription")?.str("html")?.let(::stripHtml),
            logoUrl = image(header?.obj("titleArt")),
        )
        return ItemDetails(item, seasons.distinctBy { it.id })
    }

    override suspend fun episodes(seasonId: String, cursor: String?): Page<Episode> {
        val vars = "{\"listId\":${jsonString(seasonId)},\"lazyItemCount\":$PAGE_SIZE" +
            (cursor?.let { ",\"after\":${jsonString(it)}" } ?: "") + "}"
        val data = api.graphql("TileList", VrtQueries.TILE_LIST, vars)
        val conn = data.path("list", "paginatedItems")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "TileList zonder paginatedItems (lijst $seasonId)")
        val episodes = conn.arr("edges").objects().mapNotNull { it.obj("node") }
            .filter { it.str("__typename") == "EpisodeTile" }
            .mapNotNull { node ->
                val link = node.path("action")?.str("link") ?: return@mapNotNull null
                val metas = node.arr("primaryMeta").objects().mapNotNull { it.str("longValue") ?: it.str("value") }
                Episode(
                    id = node.str("id") ?: link,
                    title = node.str("title") ?: "Aflevering",
                    playableId = link,
                    description = node.str("description"),
                    imageUrl = image(node.obj("image")),
                    durationSeconds = node.path("progress")?.optInt("durationInSeconds", 0)?.takeIf { it > 0 },
                    seasonNumber = metas.firstNotNullOfOrNull { numberAfter(it, "Seizoen") },
                    episodeNumber = metas.firstNotNullOfOrNull { numberAfter(it, "Aflevering") },
                    available = node.optBoolean("available", true),
                )
            }
        return Page(episodes, nextCursor(conn))
    }

    // ---------- Stream ----------

    override suspend fun resolveStream(playableId: String): StreamInfo {
        val live = playableId.startsWith(LIVE_PREFIX)
        val streamId = if (live) {
            playableId.removePrefix(LIVE_PREFIX)
        } else {
            val pageId = playableId.substringAfter("vrt.be", playableId)
            val data = api.graphql("StreamId", VrtQueries.STREAM_ID, "{\"pageId\":${jsonString(pageId)}}")
            data.path("page", "player")?.arr("modes")?.objects()
                ?.firstNotNullOfOrNull { it.str("streamId") }
                ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "geen streamId voor $pageId")
        }

        val json = api.mediaItem(streamId, live)
        json.str("code")?.let { code -> throw mediaError(code, json.str("message")) }

        val targets = json.arr("targetUrls").objects()
        val dash = targets.firstOrNull { it.str("type") == "mpeg_dash" }?.str("url")
        val hls = targets.firstOrNull { it.str("type") == "hls" }?.str("url")
        val manifest = dash ?: hls
            ?: throw ProviderException(ErrorKind.API_CHANGED, "geen bruikbare targetUrls: ${targets.map { it.str("type") }}")

        val vudrm = json.str("drm")
        val drm = when {
            "uplynk.com" in manifest -> DrmInfo(licenseUrl = UPLYNK_LICENSE)
            vudrm != null -> DrmInfo(licenseUrl = VUALTO_LICENSE, licenseHeaders = mapOf("X-VUDRM-TOKEN" to vudrm))
            else -> null
        }
        return StreamInfo(
            manifestUrl = manifest,
            format = if (dash != null) StreamFormat.DASH else StreamFormat.HLS,
            drm = drm,
            isLive = live,
        )
    }

    private fun mediaError(code: String, message: String?): ProviderException {
        val kind = when (code) {
            "INVALID_LOCATION", "INCOMPLETE_ROAMING_CONFIG",
            "CONTENT_AVAILABLE_ONLY_IN_BE", "CONTENT_AVAILABLE_ONLY_FOR_BE_RESIDENTS" -> ErrorKind.GEO_BLOCKED
            "VIDEO_NOT_FOUND" -> ErrorKind.NOT_AVAILABLE
            "ERROR_AGE_RESTRICTED" -> ErrorKind.AGE_RESTRICTED
            "AUTHENTICATION_REQUIRED", "INVALID_TOKEN" -> ErrorKind.NOT_LOGGED_IN
            else -> ErrorKind.UNKNOWN
        }
        return ProviderException(kind, "VRT media-items: $code${message?.let { " – $it" } ?: ""}")
    }

    // ---------- helpers ----------

    private fun nextCursor(conn: JSONObject): String? {
        val info = conn.obj("pageInfo") ?: return null
        return if (info.optBoolean("hasNextPage", false)) info.str("endCursor") else null
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val LIVE = "live"
        private const val LIVE_PREFIX = "live:"
        private const val POPULAR = "populair"
        private val POPULAR_TITLE = Regex("populair|meest bekeken|trending|top ?10|nu populair", RegexOption.IGNORE_CASE)
        private const val VUALTO_LICENSE = "https://widevine-proxy.drm.technology/proxy"
        private const val UPLYNK_LICENSE = "https://content.uplynk.com/wv"

        private val CATEGORIES = listOf(
            NativeCategory(POPULAR, "Populair"),
            NativeCategory(LIVE, "Live TV"),
            NativeCategory("series", "Series"),
            NativeCategory("films", "Films"),
            NativeCategory("docu", "Docu"),
            NativeCategory("entertainment", "Entertainment"),
            NativeCategory("humor", "Humor"),
            NativeCategory("human-interest", "Human interest"),
            NativeCategory("cultuur", "Cultuur"),
            NativeCategory("koken", "Koken"),
            NativeCategory("lifestyle", "Lifestyle"),
            NativeCategory("muziek", "Muziek"),
            NativeCategory("nieuws-en-actua", "Nieuws en actua"),
            NativeCategory("nostalgie", "Nostalgie"),
            NativeCategory("sport", "Sport"),
            NativeCategory("talkshows", "Talkshows"),
            NativeCategory("voor-kinderen", "Kinderen en jongeren"),
            NativeCategory("wetenschap-en-natuur", "Wetenschap en natuur"),
            NativeCategory("levensbeschouwing", "Levensbeschouwing"),
        )

        private val LIVE_CHANNELS = listOf(
            "vualto_een_geo" to "VRT 1",
            "vualto_canvas_geo" to "VRT Canvas",
            "vualto_ketnet_geo" to "Ketnet",
            "vualto_sporza_geo" to "Sporza",
            "vualto_nieuws" to "VRT NWS",
        ).map { (id, name) ->
            CatalogItem(
                id = "live-$id",
                kind = ItemKind.LIVE,
                title = name,
                description = "Live op $name",
                nativeCategoryIds = listOf(LIVE),
                playableId = "$LIVE_PREFIX$id",
            )
        }

        /** VRT MAX zoeklijst-id: "$" + base64("o%14|<json>|watch%"), met Python-json.dumps-opmaak. */
        internal fun searchListId(category: String?): String {
            val facets = mutableListOf<String>()
            if (category != null) {
                val facetName = if (category in setOf("docu", "films", "series", "talkshows")) "contenttype" else "genre"
                facets += "{\"name\": \"$facetName\", \"values\": [${jsonString(category)}]}"
            }
            facets += "{\"name\": \"entitytype\", \"values\": [\"video-program\"]}"
            val search = "{\"facets\": [${facets.joinToString(", ")}], \"resultType\": \"watch\"}"
            val raw = "o%14|$search|watch%"
            return "$" + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        }

        internal fun programNameFromLink(link: String): String? =
            link.substringAfter("/a-z/", "").substringBefore('/').substringBefore(".relevant").takeIf { it.isNotBlank() }

        internal fun image(obj: JSONObject?): String? {
            val url = obj?.str("templateUrl") ?: return null
            val sized = url.replace("images.vrt.be/orig", "images.vrt.be/w1920hx")
            return if (sized.startsWith("//")) "https:$sized" else sized
        }

        internal fun numberAfter(text: String, word: String): Int? =
            if (text.contains(word, ignoreCase = true)) Regex("\\d+").find(text)?.value?.toIntOrNull() else null

        internal fun stripHtml(html: String): String =
            html.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&nbsp;", " ").trim()
    }
}
