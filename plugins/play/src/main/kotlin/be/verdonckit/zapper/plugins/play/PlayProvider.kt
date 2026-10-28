package be.verdonckit.zapper.plugins.play

import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.ContinueWatchingEntry
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.DrmInfo
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.ItemDetails
import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.api.LogLevel
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.text.Normalizer

/**
 * Play (vroeger GoPlay; Play, Play Crime, Play Fictie, …) via api.play.tv.
 * Flow afgeleid van de Kodi-add-on add-ons/plugin.video.play.
 *
 * - Catalogus: de A–Z-lijst van alle programma's, zonder Play Kinepolis (huren/kopen).
 *   Per programma de details voor het liggende beeld, genre en subgenres.
 * - "Populair" en "Verder kijken": rijen van de homepage (vragen een login).
 * - Streams: DASH/HLS met Widevine; licentie bij KeyOS met de `customdata`-header.
 */
class PlayProvider : StreamingProvider {

    override val info = ProviderInfo(id = "play", displayName = "Play", brandColor = 0xFFEE3446, syncsProgress = true, supportsMyList = true, supportsContinueRemoval = true)

    private lateinit var host: HostServices
    private lateinit var auth: PlayAuth
    private lateinit var api: PlayApi

    override fun attach(host: HostServices) {
        this.host = host
        auth = PlayAuth(host)
        api = PlayApi(host, auth)
    }

    override suspend fun login(credentials: Credentials): LoginResult = auth.login(credentials)

    /**
     * Vernieuwt of logt zo nodig stil opnieuw in. Enkel "niet ingelogd" geeft false; tijdelijke fouten
     * (NETWORK: offline, throttling, login-pauze) en API_CHANGED gaan door als exception.
     */
    override suspend fun isLoggedIn(): Boolean = try {
        auth.idToken()
        true
    } catch (e: ProviderException) {
        if (e.kind != ErrorKind.NOT_LOGGED_IN) throw e
        false
    }

    override suspend fun logout() = auth.logout()

    override fun nativeCategories(): List<NativeCategory> = CATEGORIES

    // ---------- Catalogus ----------

    override suspend fun catalog(categoryId: String?, cursor: String?): Page<CatalogItem> = when (categoryId) {
        LIVE -> Page(liveChannels(), null)
        POPULAR -> Page(popular(), null)
        else -> allPrograms(cursor)
    }

    /** A–Z, telkens [LANES_PER_PAGE] letters per pagina; cursor = index van de volgende letter. */
    private suspend fun allPrograms(cursor: String?): Page<CatalogItem> {
        val lanes = api.getObject("/tv/v2/pages/programs", authenticated = false).arr("lanes").objects()
            .mapNotNull { it.optInt("index", -1).takeIf { i -> i >= 0 } }
            .sorted()
        if (lanes.isEmpty()) throw ProviderException(ErrorKind.API_CHANGED, "Play: programmapagina zonder lanes")
        val start = cursor?.toIntOrNull() ?: 0
        val chunk = lanes.filter { it >= start }.take(LANES_PER_PAGE)
        val cards = chunk.flatMap { laneCards("/tv/v2/pages/programs/lanes/$it", authenticated = false) }
        val items = programItems(cards, ALL)
        rememberTitles(items, reset = cursor == null)
        val next = lanes.firstOrNull { it > (chunk.lastOrNull() ?: Int.MAX_VALUE) }
        return Page(items, next?.toString())
    }

    /** "Moet je zien" / top 10 van de homepage; valt terug op de eerste programmarij. */
    private suspend fun popular(): List<CatalogItem> {
        val lanes = api.getObject("/tv/v2/pages/home").arr("lanes").objects()
            .filter { it.str("laneType") in setOf("PROGRAM", "TOP10") }
        val lane = lanes.firstOrNull { POPULAR_TITLE.containsMatchIn(it.str("title").orEmpty()) } ?: lanes.firstOrNull()
            ?: throw ProviderException(ErrorKind.API_CHANGED, "Play: geen programmarij op de homepage")
        val cards = laneCards("/tv/v2/pages/home/lanes/${lane.optInt("index")}", authenticated = true, max = 50)
        return programItems(cards, POPULAR)
    }

    private suspend fun laneCards(path: String, authenticated: Boolean, max: Int = Int.MAX_VALUE): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var offset = 0
        while (out.size < max) {
            val page = api.getObject("$path?limit=$LANE_LIMIT&offset=$offset", authenticated)
            val cards = page.arr("cards").objects()
            out += cards
            offset += LANE_LIMIT
            if (cards.isEmpty() || offset >= page.optInt("total", 0)) break
        }
        return out.take(max)
    }

    /** Details parallel ophalen (publiek, geen login nodig) voor beeld, genre en omschrijving. */
    private suspend fun programItems(cards: List<JSONObject>, categoryId: String): List<CatalogItem> {
        val programs = cards
            .filter { it.str("type") == "PROGRAM" && it.str("brand") !in EXCLUDED_BRANDS }
            .mapNotNull { it.str("uuid") }
            .distinct()
        val gate = Semaphore(DETAIL_PARALLELISM)
        return coroutineScope {
            programs.map { uuid ->
                async {
                    gate.withPermit {
                        runCatching { programItem(api.getObject("/tv/v2/programs/$uuid", authenticated = false), categoryId) }
                            .onFailure { host.log(LogLevel.WARN, "Play: programma $uuid overgeslagen: ${it.message}") }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun programItem(d: JSONObject, categoryId: String): CatalogItem? {
        val id = d.str("programUuid") ?: return null
        // Huren/kopen (Play Kinepolis) en programma's zonder actieve video's horen niet in de catalogus.
        if (d.obj("tvod") != null || d.str("brand") in EXCLUDED_BRANDS) return null
        if (d.has("amountOfActiveVideos") && d.optInt("amountOfActiveVideos", 1) == 0) return null
        val images = d.obj("images")
        val category = d.str("category")
        val isMovie = d.str("type") == "MOVIE" || category.equals("Film", ignoreCase = true)
        val natives = buildList {
            add(categoryId)
            category?.let { add(slug(it)) }
            d.arr("subgenres").let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) } }
                .forEach { add(slug(it)) }
            d.str("brand")?.let { add("brand-${slug(it)}") }
        }.distinct()
        return CatalogItem(
            id = id,
            kind = if (isMovie) ItemKind.MOVIE else ItemKind.SERIES,
            title = d.str("title") ?: return null,
            description = d.str("description")?.let(::stripHtml),
            posterUrl = image(images?.str("portrait")),
            backdropUrl = image(images?.str("background")),
            logoUrl = image(images?.str("titleLogo")) ?: image(images?.str("logo")),
            nativeCategoryIds = natives,
            // Film: meteen afspeelbaar vanuit de detailpagina.
            playableId = if (isMovie) d.str("nextVideo") else null,
        )
    }

    private suspend fun liveChannels(): List<CatalogItem> =
        api.getArray("/tv/v1/liveStreams", authenticated = false).objects().mapNotNull { ch ->
            val uuid = ch.str("uuid") ?: return@mapNotNull null
            val title = ch.str("title") ?: return@mapNotNull null
            CatalogItem(
                id = "live-$uuid",
                kind = ItemKind.LIVE,
                title = title,
                description = ch.str("description")?.let(::stripHtml)?.takeIf { it != title } ?: "Live op $title",
                logoUrl = ch.arr("transparentLogo").objects().firstNotNullOfOrNull { image(it.str("url")) },
                nativeCategoryIds = listOf(LIVE),
                playableId = "$LIVE_PREFIX$uuid",
            )
        }

    // ---------- Details & afleveringen ----------

    override suspend fun details(itemId: String): ItemDetails {
        val d = api.getObject("/tv/v2/programs/$itemId", authenticated = false)
        val item = programItem(d, ALL) ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "Play: programma $itemId niet beschikbaar")
        val seasons = d.arr("playlists").objects().mapNotNull { pl ->
            val uuid = pl.str("playlistUuid") ?: return@mapNotNull null
            Season(uuid, pl.str("title") ?: "Afleveringen", pl.arr("videos").objects().mapNotNull { episode(it, pl.str("title")) })
        }.toMutableList()
        // Film of losse video zonder playlist: één "seizoen" met enkel die video.
        if (seasons.isEmpty()) {
            d.str("nextVideo")?.let { seasons += Season("$SINGLE_PREFIX$it", item.title) }
        }
        if (seasons.isEmpty()) throw ProviderException(ErrorKind.API_CHANGED, "Play: geen playlists voor $itemId")
        return ItemDetails(item, seasons)
    }

    override suspend fun episodes(seasonId: String, cursor: String?): Page<Episode> {
        if (seasonId.startsWith(SINGLE_PREFIX)) {
            val uuid = seasonId.removePrefix(SINGLE_PREFIX)
            return Page(listOf(Episode(id = uuid, title = "Afspelen", playableId = uuid)), null)
        }
        val offset = cursor?.toIntOrNull() ?: 0
        val data = api.getObject("/tv/v1/playlists/$seasonId?offset=$offset&limit=$PLAYLIST_LIMIT")
        val videos = data.arr("videos").objects()
        val episodes = videos.mapNotNull { episode(it, data.str("title")) }
        val total = data.optInt("total", -1)
        val more = if (total >= 0) offset + videos.size < total else videos.size >= PLAYLIST_LIMIT
        return Page(episodes, if (more && videos.isNotEmpty()) (offset + videos.size).toString() else null)
    }

    private fun episode(v: JSONObject, seasonTitle: String?): Episode? {
        val uuid = v.str("videoUuid") ?: v.str("uuid") ?: return null
        val title = v.str("title") ?: "Aflevering"
        val duration = v.optInt("duration", 0).takeIf { it > 0 }
        val position = v.optInt("position", 0)
        return Episode(
            id = uuid,
            title = title,
            playableId = uuid,
            description = v.str("description")?.let(::stripHtml),
            imageUrl = image(v.str("image")) ?: v.arr("images").objects().firstNotNullOfOrNull { image(it.str("url")) },
            durationSeconds = duration,
            seasonNumber = v.optInt("seasonNumber", 0).takeIf { it > 0 } ?: seasonTitle?.let(::trailingNumber),
            episodeNumber = v.optInt("episodeNumber", 0).takeIf { it > 0 } ?: episodeNumberFromTitle(title),
            progress = watchProgress(position, duration),
        )
    }

    // ---------- Stream ----------

    override suspend fun resolveStream(playableId: String): StreamInfo {
        val live = playableId.startsWith(LIVE_PREFIX)
        val uuid = playableId.removePrefix(LIVE_PREFIX)
        val data = api.getObject(if (live) "/tv/v1/liveStreams/$uuid" else "/tv/v1/videos/long-form/$uuid")

        val urls = data.obj("manifestUrls")
        var manifest = urls?.str("dash")
        var format = StreamFormat.DASH
        if (manifest == null) {
            manifest = urls?.str("hls")
            format = StreamFormat.HLS
        }
        if (manifest == null && data.str("adType") == "SSAI") {
            // Server-side reclame (Google DAI): het manifest met reclame komt van pubads.
            val ssai = data.obj("ssai") ?: throw ProviderException(ErrorKind.API_CHANGED, "Play: SSAI zonder gegevens")
            val url = "https://pubads.g.doubleclick.net/ondemand/dash/content/${ssai.str("contentSourceID")}/vid/${ssai.str("videoID")}/streams"
            val resp = host.http.execute(HttpRequest(url, "POST", body = ByteArray(0)))
            manifest = runCatching { JSONObject(resp.text()).str("stream_manifest") }.getOrNull()
            format = StreamFormat.DASH
        }
        if (manifest == null) throw ProviderException(ErrorKind.NOT_AVAILABLE, "Play: geen manifest voor $uuid")

        val drm = data.str("drmXml")?.let { DrmInfo(licenseUrl = LICENSE_URL, licenseHeaders = mapOf("customdata" to it)) }
        return StreamInfo(
            manifestUrl = manifest,
            format = format,
            drm = drm,
            isLive = live,
            resumePositionSeconds = if (live) null else data.optInt("position", 0).takeIf { it > RESUME_MARGIN },
        )
    }

    // ---------- Kijkvoortgang ----------

    /** De rij "Kijk verder" van de homepage van Play. */
    override suspend fun continueWatching(): List<ContinueWatchingEntry> {
        val lane = api.getObject("/tv/v2/pages/home").arr("lanes").objects()
            .firstOrNull { it.str("laneType") == "VIDEO" && CONTINUE_TITLE.containsMatchIn(it.str("title").orEmpty()) }
            ?: return emptyList()
        val titles = knownTitles()
        return laneCards("/tv/v2/pages/home/lanes/${lane.optInt("index")}", authenticated = true, max = 50)
            .filter { it.str("type") == "VIDEO" }
            .mapNotNull { card ->
                val uuid = card.str("uuid") ?: return@mapNotNull null
                val programTitle = card.str("title") ?: return@mapNotNull null
                val duration = card.optInt("duration", 0).takeIf { it > 0 }
                val progress = watchProgress(card.optInt("position", 0), duration)
                if (progress?.completed == true) return@mapNotNull null
                // Ondertitel zoals "S24-A10" of "S5-A1: Anneke".
                val subtitle = card.str("subtitle")
                val code = subtitle?.let { CODE_IN_SUBTITLE.find(it) }
                ContinueWatchingEntry(
                    itemId = card.str("programUuid") ?: titles[normalize(programTitle)],
                    itemTitle = programTitle,
                    episode = Episode(
                        id = uuid,
                        title = code?.groupValues?.get(3)?.takeIf { it.isNotBlank() } ?: subtitle ?: programTitle,
                        playableId = uuid,
                        description = card.str("description")?.let(::stripHtml),
                        imageUrl = card.arr("images").objects().firstNotNullOfOrNull { image(it.str("url")) },
                        durationSeconds = duration,
                        seasonNumber = code?.groupValues?.get(1)?.toIntOrNull(),
                        episodeNumber = code?.groupValues?.get(2)?.toIntOrNull(),
                        progress = progress,
                    ),
                )
            }
    }

    /** Zelfde call als de Kodi-add-on ("delete_position"). */
    override suspend fun removeFromContinueWatching(playableId: String) {
        if (playableId.startsWith(LIVE_PREFIX)) return
        api.delete("/web/v1/videos/continue-watching/$playableId")
    }

    // ---------- Mijn lijst ----------

    /** GET /tv/v1/programs/myList geeft de programma-id's; de details zijn publiek. */
    override suspend fun myList(): List<CatalogItem> {
        val ids = api.getArray("/tv/v1/programs/myList").let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() && it != "null" } ?: arr.optJSONObject(i)?.str("uuid") }
        }
        val gate = Semaphore(DETAIL_PARALLELISM)
        return coroutineScope {
            ids.map { uuid ->
                async {
                    gate.withPermit {
                        runCatching { programItem(api.getObject("/tv/v2/programs/$uuid", authenticated = false), ALL) }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Zelfde call als de Kodi-add-on: PUT /tv/v1/programs/{id}/myList. */
    override suspend fun setInMyList(itemId: String, inList: Boolean) {
        api.put("/tv/v1/programs/$itemId/myList", JSONObject().put("onMyList", inList))
    }

    /** Zelfde call als de Kodi-add-on: PUT /tv/v1/videos/{id}/position. */
    override suspend fun reportProgress(stream: StreamInfo, playableId: String, positionSeconds: Int, durationSeconds: Int) {
        if (stream.isLive || playableId.startsWith(LIVE_PREFIX)) return
        api.put("/tv/v1/videos/$playableId/position", JSONObject().put("position", positionSeconds))
    }

    private fun watchProgress(position: Int, duration: Int?): WatchProgress? {
        if (position <= 0) return null
        val dur = duration ?: 0
        return WatchProgress(position, dur, completed = dur > 0 && position >= dur - RESUME_MARGIN)
    }

    // ---------- Titel → programma (voor "Kijk verder"-kaarten zonder programma-id) ----------

    private val pendingTitles = HashMap<String, String>()

    private fun rememberTitles(items: List<CatalogItem>, reset: Boolean) {
        if (reset) pendingTitles.clear()
        items.forEach { pendingTitles[normalize(it.title)] = it.id }
        host.storage.put(TITLES_KEY, JSONObject(pendingTitles as Map<*, *>).toString())
    }

    private fun knownTitles(): Map<String, String> = runCatching {
        val o = JSONObject(host.storage.get(TITLES_KEY) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    companion object {
        private const val LIVE = "live"
        private const val POPULAR = "populair"
        private const val ALL = "alles"
        private const val LIVE_PREFIX = "live:"
        private const val SINGLE_PREFIX = "video:"
        private const val LANES_PER_PAGE = 6
        private const val LANE_LIMIT = 100
        private const val PLAYLIST_LIMIT = 100
        private const val DETAIL_PARALLELISM = 8
        private const val RESUME_MARGIN = 30
        private const val TITLES_KEY = "programs.byTitle"
        private const val LICENSE_URL = "https://widevine.keyos.com/api/v4/getLicense"
        private val EXCLUDED_BRANDS = setOf("kinepolis")
        private val POPULAR_TITLE = Regex("moet je zien|top ?10|populair|10 om", RegexOption.IGNORE_CASE)
        private val CONTINUE_TITLE = Regex("kijk verder|verder kijken", RegexOption.IGNORE_CASE)
        private val EPISODE_IN_TITLE = Regex("""(?i)aflevering\s+(\d+)""")
        private val CODE_IN_SUBTITLE = Regex("""^\s*S(\d+)\s*-\s*A(\d+)\s*:?\s*(.*)$""")

        private val CATEGORIES = listOf(
            NativeCategory(POPULAR, "Populair"),
            NativeCategory(LIVE, "Live tv"),
            NativeCategory(ALL, "Alle programma's"),
        )

        /** "Actua & Sport" → "actua-sport", "Human Interest" → "human-interest". */
        internal fun slug(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

        internal fun normalize(s: String): String = slug(s).replace('-', ' ')

        /** Play geeft lege beelden soms als de tekst "None". */
        internal fun image(url: String?): String? =
            url?.takeIf { it.startsWith("http") && !it.equals("None", ignoreCase = true) }

        /**
         * "Aflevering 3" of "S24-A12": zonder nummer valt de app terug op de lijstvolgorde,
         * en Play zet de nieuwste aflevering vooraan (dan wordt "volgende" een oudere).
         */
        internal fun episodeNumberFromTitle(title: String): Int? =
            CODE_IN_SUBTITLE.find(title)?.groupValues?.get(2)?.toIntOrNull()
                ?: EPISODE_IN_TITLE.find(title)?.groupValues?.get(1)?.toIntOrNull()

        internal fun trailingNumber(s: String): Int? = Regex("""(\d+)\s*$""").find(s)?.groupValues?.get(1)?.toIntOrNull()

        internal fun stripHtml(html: String): String =
            html.replace(Regex("<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
                .replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
