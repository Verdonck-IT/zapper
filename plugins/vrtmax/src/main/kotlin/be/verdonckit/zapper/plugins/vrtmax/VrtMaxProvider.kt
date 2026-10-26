package be.verdonckit.zapper.plugins.vrtmax

import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.ContinueWatchingEntry
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
import be.verdonckit.zapper.api.WatchProgress
import be.verdonckit.zapper.api.postJson
import org.json.JSONObject
import java.util.Base64

class VrtMaxProvider : StreamingProvider {

    override val info = ProviderInfo(id = "vrtmax", displayName = "VRT MAX", brandColor = 0xFF00D1A7, syncsProgress = true, supportsMyList = true, supportsContinueRemoval = true)

    private lateinit var host: HostServices
    private lateinit var auth: VrtAuth
    private lateinit var api: VrtApi

    override fun attach(host: HostServices) {
        this.host = host
        auth = VrtAuth(host)
        api = VrtApi(host, auth)
    }

    override suspend fun login(credentials: Credentials): LoginResult = auth.login(credentials)

    /**
     * Vernieuwt of logt zo nodig stil opnieuw in. Enkel "niet ingelogd" geeft false; tijdelijke fouten
     * (NETWORK: offline, throttling, login-pauze) en API_CHANGED gaan door als exception.
     */
    override suspend fun isLoggedIn(): Boolean = try {
        auth.accessToken()
        true
    } catch (e: ProviderException) {
        if (e.kind != ErrorKind.NOT_LOGGED_IN) throw e
        false
    }

    override suspend fun logout() = auth.logout()

    override fun nativeCategories(): List<NativeCategory> = CATEGORIES

    // ---------- Catalogus ----------

    override suspend fun catalog(categoryId: String?, cursor: String?): Page<CatalogItem> {
        if (categoryId == LIVE) return Page(liveChannels(), null)
        val conn = if (categoryId == POPULAR) popularConnection(cursor) else searchConnection(categoryId, cursor)

        val items = conn.arr("edges").objects().mapNotNull { it.obj("node") }
            .filter { it.str("__typename") == "ProgramTile" }
            .mapNotNull { programTile(it, categoryId) }
        return Page(items, nextCursor(conn))
    }

    private suspend fun popularConnection(cursor: String?): JSONObject {
        val vars = "{\"listId\":${jsonString(popularListId())},\"lazyItemCount\":$PAGE_SIZE" +
            (cursor?.let { ",\"after\":${jsonString(it)}" } ?: "") + "}"
        return api.graphql("TileList", VrtQueries.TILE_LIST, vars).path("list", "paginatedItems")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "TileList zonder paginatedItems (populair)")
    }

    /**
     * Genre of type, zoals de zoekpagina van de site (sinds oktober 2026; de oude zoeklijst-id's
     * "o%14|…|watch%" geven null): `pageSearch` met de facet, dan het tabblad "Kijk (n)" =
     * de lijst met `tileContentType` "program". Bladeren gebeurt in dezelfde query met `after`.
     */
    private suspend fun searchConnection(categoryId: String?, cursor: String?): JSONObject {
        val vars = "{\"input\":${searchInput(categoryId)},\"lazyItemCount\":$PAGE_SIZE" +
            (cursor?.let { ",\"after\":${jsonString(it)}" } ?: "") + "}"
        val page = api.graphql("PageSearch", VrtQueries.PAGE_SEARCH, vars).obj("pageSearch")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "pageSearch gaf geen pagina (categorie $categoryId)")
        return page.arr("components").objects()
            .flatMap { it.arr("items").objects() }
            .flatMap { it.arr("components").objects() }
            .firstOrNull { it.str("tileContentType") == "program" }
            ?.obj("paginatedItems")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "pageSearch zonder programmalijst (categorie $categoryId)")
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

    /**
     * Live-zenders met het officiële logo (variant voor donkere achtergrond) uit de
     * livestreampagina's van VRT MAX; één keer per dag opgehaald. Zenders zonder eigen
     * pagina of bij een fout: het vaste logo.
     */
    private suspend fun liveChannels(): List<CatalogItem> {
        val logos = runCatching { liveLogos() }.getOrElse {
            host.log(be.verdonckit.zapper.api.LogLevel.WARN, "VRT MAX zenderlogo's: ${it.message}")
            emptyMap()
        }
        return LIVE_CHANNELS.map { ch ->
            CatalogItem(
                id = "live-${ch.streamId}",
                kind = ItemKind.LIVE,
                title = ch.name,
                description = "Live op ${ch.name}",
                logoUrl = logos[ch.streamId] ?: ch.fallbackLogo,
                nativeCategoryIds = listOf(LIVE),
                playableId = "$LIVE_PREFIX${ch.streamId}",
            )
        }
    }

    private suspend fun liveLogos(): Map<String, String> {
        val exp = host.storage.get("live.logos.exp")?.toLongOrNull() ?: 0
        host.storage.get("live.logos")?.takeIf { exp > host.nowMillis() }?.let { cached ->
            val o = JSONObject(cached)
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        val withPage = LIVE_CHANNELS.filter { it.pageSlug != null }
        // Eén query met een alias per zender.
        val query = "query LiveLogos { " + withPage.withIndex().joinToString(" ") { (i, ch) ->
            "c$i: page(id: ${jsonString("/vrtmax/livestream/video/${ch.pageSlug}/")}) { ... on LivestreamPage { brandLogos { type primary } } }"
        } + " }"
        val data = api.graphql("LiveLogos", query, "{}")
        val out = HashMap<String, String>()
        withPage.forEachIndexed { i, ch ->
            val logos = data.obj("c$i")?.arr("brandLogos")?.objects().orEmpty()
            // PNG: Coil decodeert SVG niet zonder extra decoder.
            logos.firstOrNull { it.str("type") == "png" }?.str("primary")?.let { out[ch.streamId] = it }
        }
        val json = JSONObject()
        out.forEach { (k, v) -> json.put(k, v) }
        host.storage.put("live.logos", json.toString())
        host.storage.put("live.logos.exp", (host.nowMillis() + 24 * 3600 * 1000).toString())
        return out
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
            .mapNotNull(::episodeTile)
        return Page(episodes, nextCursor(conn))
    }

    private fun episodeTile(node: JSONObject): Episode? {
        val link = node.path("action")?.str("link") ?: return null
        val metas = node.arr("primaryMeta").objects().mapNotNull { it.str("longValue") ?: it.str("value") }
        val progress = node.obj("progress")
        // Niet elke lijst geeft "Seizoen x"/"Aflevering y" mee; de link (…-s4a5/) wel.
        val fromLink = SEASON_EPISODE_IN_LINK.find(link)?.groupValues
        return Episode(
            id = node.str("id") ?: link,
            title = node.str("title") ?: "Aflevering",
            playableId = link,
            description = node.str("description"),
            imageUrl = image(node.obj("image")),
            durationSeconds = progress?.optInt("durationInSeconds", 0)?.takeIf { it > 0 },
            seasonNumber = metas.firstNotNullOfOrNull { numberAfter(it, "Seizoen") } ?: fromLink?.get(1)?.toIntOrNull(),
            episodeNumber = metas.firstNotNullOfOrNull { numberAfter(it, "Aflevering") } ?: fromLink?.get(2)?.toIntOrNull(),
            available = node.optBoolean("available", true),
            progress = watchProgress(progress),
        )
    }

    // ---------- Kijkvoortgang ----------

    /** De lijst "Programma's verder kijken" van VRT MAX (zelfde lijst als op de website). */
    override suspend fun continueWatching(): List<ContinueWatchingEntry> {
        val out = mutableListOf<ContinueWatchingEntry>()
        val deleteActions = JSONObject()
        var cursor: String? = null
        var pages = 0
        do {
            val vars = "{\"listId\":${jsonString(CONTINUE_LIST_ID)},\"lazyItemCount\":$PAGE_SIZE" +
                (cursor?.let { ",\"after\":${jsonString(it)}" } ?: "") + "}"
            val conn = api.graphql("TileList", VrtQueries.TILE_LIST, vars).path("list", "paginatedItems")
                ?: throw ProviderException(ErrorKind.API_CHANGED, "verder-kijken-lijst zonder paginatedItems")
            for (node in conn.arr("edges").objects().mapNotNull { it.obj("node") }) {
                if (node.str("__typename") != "EpisodeTile") continue
                val ep = episodeTile(node) ?: continue
                node.arr("actionItems").objects().mapNotNull { it.obj("action") }
                    .firstOrNull { it.str("__typename") == "ListDeleteAction" }
                    ?.let { a -> deleteActions.put(ep.playableId, JSONObject().put("id", a.str("id")).put("listName", a.str("listName"))) }
                if (ep.progress?.completed == true) continue
                out += ContinueWatchingEntry(
                    itemId = programNameFromLink(ep.playableId),
                    itemTitle = node.path("preview", "heading")?.str("title")
                        ?: node.arr("primaryMeta").objects().firstNotNullOfOrNull { it.str("value") }
                        ?: ep.title,
                    episode = ep,
                )
            }
            cursor = nextCursor(conn)
            pages++
        } while (cursor != null && pages < 2)
        host.storage.put(CONTINUE_DELETE_KEY, deleteActions.toString())
        return out
    }

    /** "Verwijderen uit deze lijst" op de tegel in "Programma's verder kijken". */
    override suspend fun removeFromContinueWatching(playableId: String) {
        fun lookup() = host.storage.get(CONTINUE_DELETE_KEY)?.let { runCatching { JSONObject(it).optJSONObject(playableId) }.getOrNull() }
        val action = lookup() ?: run { continueWatching(); lookup() }
            ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "aflevering staat niet (meer) in Verder kijken")
        val id = action.str("id") ?: throw ProviderException(ErrorKind.API_CHANGED, "ListDeleteAction zonder id")
        val listName = action.str("listName") ?: throw ProviderException(ErrorKind.API_CHANGED, "ListDeleteAction zonder listName")
        api.graphql("listDelete", VrtQueries.LIST_DELETE, "{\"input\":{\"id\":${jsonString(id)},\"listName\":${jsonString(listName)}}}")
    }

    /** Zelfde endpoint en marges als de Kodi-add-on: < 30 s = niet begonnen, laatste 30 s = uitgekeken. */
    override suspend fun reportProgress(stream: StreamInfo, playableId: String, positionSeconds: Int, durationSeconds: Int) {
        if (stream.isLive || durationSeconds <= 0) return
        val mediaId = stream.extra[EXTRA_MEDIA_ID] ?: return
        val at = when {
            positionSeconds < RESUME_MARGIN -> 0
            positionSeconds > durationSeconds - RESUME_MARGIN -> durationSeconds
            else -> positionSeconds
        }
        val name = stream.extra[EXTRA_MEDIA_NAME] ?: mediaId
        val body = "{\"at\":$at,\"total\":$durationSeconds,\"gdpr\":${jsonString("$name gekeken tot $at seconden.")}}"
        val resp = host.http.postJson(
            "$RESUME_POINTS_URL/$mediaId",
            body,
            headers = mapOf("Authorization" to "Bearer ${auth.accessToken()}", "Accept" to "application/json"),
        )
        if (resp.code == 401 || resp.code == 403) throw ProviderException(ErrorKind.NOT_LOGGED_IN, "resumePoints: HTTP ${resp.code}")
        if (resp.code !in 200..299) throw ProviderException(ErrorKind.API_CHANGED, "resumePoints: HTTP ${resp.code}")
    }

    // ---------- Mijn lijst ----------

    /** De lijst "Mijn lijst" (favoriete programma's) van VRT MAX. */
    override suspend fun myList(): List<CatalogItem> {
        val out = mutableListOf<CatalogItem>()
        var cursor: String? = null
        var pages = 0
        do {
            val vars = "{\"listId\":${jsonString(MY_LIST_ID)},\"lazyItemCount\":$PAGE_SIZE" +
                (cursor?.let { ",\"after\":${jsonString(it)}" } ?: "") + "}"
            val conn = api.graphql("TileList", VrtQueries.TILE_LIST, vars).path("list", "paginatedItems")
                ?: throw ProviderException(ErrorKind.API_CHANGED, "Mijn lijst zonder paginatedItems")
            out += conn.arr("edges").objects().mapNotNull { it.obj("node") }
                .filter { it.str("__typename") == "ProgramTile" }
                .mapNotNull { programTile(it, null) }
            cursor = nextCursor(conn)
            pages++
        } while (cursor != null && pages < 4)
        return out.distinctBy { it.id }
    }

    override suspend fun setInMyList(itemId: String, inList: Boolean) {
        val data = api.graphql("ProgramFavorite", VrtQueries.PROGRAM_FAVORITE, "{\"pageId\":${jsonString("/vrtmax/a-z/$itemId/")}}")
        val favoriteId = data.path("page", "header")?.arr("actionItems")?.objects()
            ?.mapNotNull { it.obj("action") }
            ?.firstOrNull { it.str("__typename") == "FavoriteAction" }
            ?.str("id")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "geen Mijn lijst-knop voor $itemId")
        val vars = "{\"input\":{\"favorite\":$inList,\"id\":${jsonString(favoriteId)}}}"
        api.graphql("setFavorite", VrtQueries.SET_FAVORITE, vars)
    }

    private fun watchProgress(p: JSONObject?): WatchProgress? {
        if (p == null || !p.has("progressInSeconds")) return null
        val pos = p.optInt("progressInSeconds", 0)
        val dur = p.optInt("durationInSeconds", 0)
        val completed = p.optBoolean("completed", false) || (dur > 0 && pos >= dur - RESUME_MARGIN)
        if (pos <= 0 && !completed) return null
        return WatchProgress(pos, dur, completed)
    }

    // ---------- Stream ----------

    override suspend fun resolveStream(playableId: String): StreamInfo {
        val live = playableId.startsWith(LIVE_PREFIX)
        val extra = HashMap<String, String>()
        var resumeAt: Int? = null
        val streamId = if (live) {
            playableId.removePrefix(LIVE_PREFIX)
        } else {
            val pageId = playableId.substringAfter("vrt.be", playableId)
            val data = api.graphql("StreamId", VrtQueries.STREAM_ID, "{\"pageId\":${jsonString(pageId)}}")
            val player = data.path("page", "player")
            val mode = player?.arr("modes")?.objects()?.firstOrNull { it.str("streamId") != null }
                ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "geen streamId voor $pageId")
            mode.obj("resumePointTemplate")?.let { tpl ->
                tpl.str("mediaId")?.let { extra[EXTRA_MEDIA_ID] = it }
                tpl.str("mediaName")?.let { extra[EXTRA_MEDIA_NAME] = it }
            }
            resumeAt = watchProgress(player.obj("progress"))
                ?.takeIf { !it.completed && it.positionSeconds > RESUME_MARGIN }?.positionSeconds
            mode.str("streamId")!!
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
            resumePositionSeconds = resumeAt,
            extra = extra,
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
        private const val RESUME_MARGIN = 30
        private val SEASON_EPISODE_IN_LINK = Regex("""-s(\d+)a(\d+)/?$""")
        private const val RESUME_POINTS_URL = "https://ddt.profiel.vrt.be/resumePoints"
        private const val EXTRA_MEDIA_ID = "resumeMediaId"
        private const val CONTINUE_DELETE_KEY = "continue.deleteActions"
        private const val EXTRA_MEDIA_NAME = "resumeMediaName"

        /** "Mijn lijst": favoriete programma's (zelfde id als de Kodi-add-on). */
        private val MY_LIST_ID =
            "$" + Base64.getEncoder().encodeToString("o%25|o%9||p%/mijn-lijst/%|video-program|b%1%".toByteArray(Charsets.UTF_8))

        /** "Programma's verder kijken" op de VRT MAX-homepage (zelfde id als de Kodi-add-on). */
        private val CONTINUE_LIST_ID =
            "$" + Base64.getEncoder().encodeToString("o%35|p%/|list_1744022689|b%0%".toByteArray(Charsets.UTF_8))
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

        private data class LiveChannel(val streamId: String, val name: String, val pageSlug: String?, val fallbackLogo: String)

        private val LIVE_CHANNELS = listOf(
            LiveChannel("vualto_een_geo", "VRT 1", "vrt1", "https://images.vrt.be/orig/2023/04/28/c448d669-e5c1-11ed-91d7-02b7b76bf47f.png"),
            LiveChannel("vualto_canvas_geo", "VRT Canvas", "vrt-canvas", "https://images.vrt.be/orig/2023/11/09/1ae4c23e-7ef9-11ee-91d7-02b7b76bf47f.png"),
            LiveChannel("vualto_ketnet_geo", "Ketnet", "ketnet", "https://images.vrt.be/orig/2024/10/22/03aa85a7-302c-4b2e-8028-71b52ae0a38d.png"),
            LiveChannel("vualto_sporza_geo", "Sporza", null, "https://images.vrt.be/orig/logo/sporza/sporza_logo_wit.png"),
            LiveChannel("vualto_nieuws", "VRT NWS", null, "https://images.vrt.be/orig/logos/vrtnws.png"),
        )

        /** SearchInput voor `pageSearch`, met dezelfde facetten als de knoppen op vrt.be/vrtmax/zoeken/. */
        internal fun searchInput(category: String?): String {
            val facets = if (category == null) "" else {
                val facetName = if (category in setOf("docu", "films", "series", "talkshows")) "contenttype" else "genre"
                "{\"name\":\"$facetName\",\"values\":[${jsonString(category)}]}"
            }
            return "{\"facets\":[$facets],\"mediaType\":\"video\"}"
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
