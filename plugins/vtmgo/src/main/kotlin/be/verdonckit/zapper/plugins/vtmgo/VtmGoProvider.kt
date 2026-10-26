package be.verdonckit.zapper.plugins.vtmgo

import be.verdonckit.zapper.api.CatalogItem
import be.verdonckit.zapper.api.ContinueWatchingEntry
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.DeviceLogin
import be.verdonckit.zapper.api.DrmInfo
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.ItemDetails
import be.verdonckit.zapper.api.ItemKind
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginMethod
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.NativeCategory
import be.verdonckit.zapper.api.Page
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.ProviderInfo
import be.verdonckit.zapper.api.Season
import be.verdonckit.zapper.api.StreamFormat
import be.verdonckit.zapper.api.StreamInfo
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.Subtitle
import be.verdonckit.zapper.api.WatchProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Base64

/**
 * VTM GO (DPG Media: VTM, VTM 2–4, VTM GOLD, …) via lfvp-api.dpgmedia.net, zoals de Android TV-app.
 * Flow afgeleid van de Kodi-add-on (fork frankdpGH/plugin.video.vtm.go, GPLv3).
 *
 * - Login met code (OAuth device flow), geen wachtwoord: zie [VtmGoAuth].
 * - Catalogus: VTM GO heeft geen A–Z of vaste genres meer, enkel thematische rijen per storefront
 *   (main, series, films, docu, kids). Elke storefront is een native categorie; de rijtitel geeft
 *   extra thema's ("Komische series" → `thema-comedy`) voor de mapping in categories.json.
 * - Streams: DASH met Widevine via DRMtoday; het licentieantwoord is JSON, dus [acquireLicense].
 * - Enkel gratis VTM GO: alles van VTM GO+, Streamz en Cinema (`premiumProductLogo`, afleveringen
 *   met een UPSELL-badge) wordt weggelaten, ook seizoenen zonder gratis afleveringen.
 * - Kijk verder: rij CONTINUE_WATCHING van de homepage; voortgang via /userData/playerPosition
 *   (zoals de website: PUT {position, duration}, DELETE = uit "Kijk verder").
 */
class VtmGoProvider : StreamingProvider {

    override val info = ProviderInfo(
        id = "vtmgo",
        displayName = "VTM GO",
        brandColor = 0xFFE4003A,
        syncsProgress = true,
        supportsMyList = true,
        supportsContinueRemoval = true,
        loginMethod = LoginMethod.DEVICE_CODE,
    )

    private lateinit var host: HostServices
    private lateinit var auth: VtmGoAuth
    private lateinit var api: VtmGoApi

    override fun attach(host: HostServices) {
        this.host = host
        auth = VtmGoAuth(host)
        api = VtmGoApi(host, auth)
    }

    override suspend fun login(credentials: Credentials): LoginResult =
        LoginResult.Failure(LoginFailure.ACCOUNT_PROBLEM, "VTM GO logt in met een code, niet met een wachtwoord")

    override suspend fun startDeviceLogin(): DeviceLogin = auth.startDeviceLogin()

    override suspend fun pollDeviceLogin(): LoginResult? = auth.pollDeviceLogin()

    /** Vernieuwt zo nodig met het refresh token. Enkel "niet ingelogd" geeft false; tijdelijke fouten gaan door. */
    override suspend fun isLoggedIn(): Boolean = try {
        auth.accessToken()
        auth.profile()
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
        else -> storefrontPage(categoryId ?: MAIN, cursor?.toIntOrNull() ?: 0)
    }

    /** Een pagina = [ROWS_PER_PAGE] rijen van de storefront, elk volledig (de overzichtsrij is ingekort). */
    private suspend fun storefrontPage(storefront: String, page: Int): Page<CatalogItem> {
        val rows = api.get("/storefronts/$storefront").arr("rows").objects()
            .filter { it.str("rowType")?.startsWith("SWIMLANE") == true && !SKIP_ROW.containsMatchIn(it.str("title").orEmpty()) }
        val slice = rows.drop(page * ROWS_PER_PAGE).take(ROWS_PER_PAGE)
        val gate = Semaphore(ROW_PARALLELISM)
        val items = coroutineScope {
            slice.map { row ->
                async {
                    gate.withPermit {
                        val teasers = if (row.optBoolean("hasDetail")) {
                            runCatching { api.get("/storefronts/$storefront/detail/${row.str("id")}").obj("row") }
                                .getOrNull()?.arr("teasers")?.objects() ?: row.arr("teasers").objects()
                        } else {
                            row.arr("teasers").objects()
                        }
                        val natives = listOf(storefront) + themes(row.str("title").orEmpty())
                        val portrait = row.str("rowType") == "SWIMLANE_PORTRAIT"
                        teasers.mapNotNull { teaserItem(it, natives, portrait, storefront) }
                    }
                }
            }.awaitAll().flatten()
        }
        val next = if ((page + 1) * ROWS_PER_PAGE < rows.size) (page + 1).toString() else null
        return Page(items, next)
    }

    /** "Moet je nu zien" en de top 10 van de homepage, in die volgorde. */
    private suspend fun popular(): List<CatalogItem> {
        val rows = api.get("/storefronts/$MAIN").arr("rows").objects()
            .filter { it.str("rowType")?.startsWith("SWIMLANE") == true && POPULAR_ROW.containsMatchIn(it.str("title").orEmpty()) }
        return rows.flatMap { row ->
            val portrait = row.str("rowType") == "SWIMLANE_PORTRAIT"
            row.arr("teasers").objects().mapNotNull { teaserItem(it, listOf(POPULAR), portrait, MAIN) }
        }.distinctBy { it.id }
    }

    private fun teaserItem(t: JSONObject, natives: List<String>, portrait: Boolean, storefront: String): CatalogItem? {
        val id = t.str("detailId") ?: t.path("target")?.str("id") ?: return null
        val title = t.str("title") ?: return null
        if (t.opt("comingSoon").let { it != null && it != JSONObject.NULL }) return null
        if (isPremium(t)) return null
        val labels = t.arr("labels").objects().mapNotNull { it.str("label") }
        val image = t.str("imageUrl")
        return CatalogItem(
            id = id,
            kind = kindFromLabels(labels, storefront),
            title = title,
            description = t.str("description"),
            posterUrl = if (portrait) image else null,
            backdropUrl = t.obj("heroImage")?.str("url1280") ?: t.str("heroImageUrl") ?: image.takeUnless { portrait },
            logoUrl = t.str("titleArtImageUrl"),
            nativeCategoryIds = natives,
            year = yearFromLabels(t.arr("labels").objects()),
        )
    }

    private suspend fun liveChannels(): List<CatalogItem> =
        api.get("/live").arr("channels").objects().mapNotNull { ch ->
            val id = ch.str("channelId") ?: return@mapNotNull null
            val name = ch.str("name") ?: return@mapNotNull null
            val now = currentBroadcast(ch.arr("broadcasts").objects(), host.nowMillis())?.path("title")?.str("label")
            CatalogItem(
                id = "live-$id",
                kind = ItemKind.LIVE,
                title = name,
                description = now?.takeIf { it != "Geen uitzending" }?.let { "Nu: $it" } ?: "Live op $name",
                backdropUrl = ch.str("backgroundImageUrl"),
                logoUrl = ch.str("channelLogoUrl"),
                nativeCategoryIds = listOf(LIVE),
                playableId = "$LIVE_PREFIX$id",
            )
        }

    // ---------- Details & afleveringen ----------

    override suspend fun details(itemId: String): ItemDetails {
        val d = api.get("/detail3/$itemId")
        if (isPremium(d)) throw ProviderException(ErrorKind.NOT_AVAILABLE, "VTM GO: enkel met een betalend abonnement")
        val headerLabels = d.arr("headerLabels").objects()
        val genres = headerLabels.filter { it.str("accessibilityLabel") == "Genre" }.mapNotNull { it.str("label") }
        val picker = d.obj("seasonPicker")
        val title = d.obj("title")?.str("label") ?: d.str("name") ?: "VTM GO"
        val item = CatalogItem(
            id = itemId,
            kind = if (picker == null) ItemKind.MOVIE else ItemKind.SERIES,
            title = title,
            description = d.str("description"),
            backdropUrl = d.str("backgroundImageUrl") ?: d.str("landscapeTeaserImageUrl"),
            logoUrl = d.obj("title")?.str("imageUrl"),
            nativeCategoryIds = genres.flatMap { themes(it) },
            year = yearFromLabels(headerLabels),
        )

        if (picker == null) {
            // Film of losse uitzending: één "seizoen" met de video zelf.
            val playable = d.obj("primaryButton")?.str("playableId")
            if (playable == null || upsell(d) != null) {
                throw ProviderException(ErrorKind.NOT_AVAILABLE, "VTM GO: '$title' is niet afspeelbaar (${upsell(d) ?: "geen video"})")
            }
            val episode = Episode(
                id = playable,
                title = title,
                playableId = playable,
                description = d.str("description"),
                imageUrl = d.str("landscapeTeaserImageUrl"),
                durationSeconds = d.optInt("durationSeconds", 0).takeIf { it > 0 },
            )
            return ItemDetails(item, listOf(Season("$itemId$SEASON_SEP$SINGLE", title, listOf(episode))))
        }

        // Elk seizoen ophalen: seizoenen met enkel VTM GO+-afleveringen vallen weg (vaak de oudere).
        val selected = picker.obj("selected")
        val selectedIndex = selected?.optInt("index", -1) ?: -1
        val indices = picker.arr("indices").let { a -> (0 until a.length()).map { a.optInt(it) } }.ifEmpty { listOf(selectedIndex) }
        if (selected != null) cacheSeason(itemId, selectedIndex, episodesOf(selected, selectedIndex))
        val gate = Semaphore(ROW_PARALLELISM)
        val seasons = coroutineScope {
            indices.map { index ->
                async {
                    // Ophalen mislukt = onbekend: seizoen tonen, de app vraagt de afleveringen later opnieuw.
                    val eps = gate.withPermit { runCatching { seasonEpisodes(itemId, index) }.getOrNull() }
                    Season(id = "$itemId$SEASON_SEP$index", title = "Seizoen $index", episodesPreview = eps.orEmpty())
                        .takeIf { eps == null || eps.isNotEmpty() }
                }
            }.awaitAll().filterNotNull()
        }
        if (seasons.isEmpty()) throw ProviderException(ErrorKind.NOT_AVAILABLE, "VTM GO: '$title' enkel met een betalend abonnement")
        return ItemDetails(item, seasons)
    }

    override suspend fun episodes(seasonId: String, cursor: String?): Page<Episode> {
        val (itemId, index) = seasonId.split(SEASON_SEP, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (index == SINGLE) return Page(details(itemId).seasons.first().episodesPreview, null)
        val number = index.toIntOrNull() ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: ongeldig seizoen $seasonId")
        return Page(seasonEpisodes(itemId, number), null)
    }

    /** Gratis afleveringen van één seizoen, kort gecachet: details() en de app vragen ze kort na elkaar. */
    private suspend fun seasonEpisodes(itemId: String, index: Int): List<Episode> {
        val key = "$itemId$SEASON_SEP$index"
        seasonCache[key]?.takeIf { host.nowMillis() - it.first < SEASON_CACHE_MS }?.let { return it.second }
        val selected = api.get("/detail3/$itemId?selectedSeasonIndex=$index").path("seasonPicker", "selected")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: geen seizoen $index voor $itemId")
        return episodesOf(selected, index).also { cacheSeason(itemId, index, it) }
    }

    private val seasonCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Episode>>>()

    private fun cacheSeason(itemId: String, index: Int, episodes: List<Episode>) {
        seasonCache["$itemId$SEASON_SEP$index"] = host.nowMillis() to episodes
    }

    /** Afleveringen van een seizoen, zonder die van VTM GO+ (UPSELL-badge). */
    private fun episodesOf(season: JSONObject?, seasonNumber: Int?): List<Episode> =
        season?.arr("episodes")?.objects().orEmpty().mapNotNull { e ->
            val id = e.str("id") ?: return@mapNotNull null
            if (e.obj("badge")?.str("icon") == UPSELL) return@mapNotNull null
            val raw = e.str("title") ?: "Aflevering"
            val numbered = NUMBERED_TITLE.find(raw)
            val duration = e.optInt("durationSeconds", 0).takeIf { it > 0 }
            val percent = e.optInt("userProgressPercentage", 0)
            Episode(
                id = id,
                title = numbered?.groupValues?.get(2)?.ifBlank { null } ?: raw,
                playableId = id,
                description = e.str("description"),
                imageUrl = e.str("imageUrl"),
                durationSeconds = duration,
                seasonNumber = seasonNumber,
                episodeNumber = e.optInt("index", 0).takeIf { it > 0 } ?: numbered?.groupValues?.get(1)?.toIntOrNull(),
                progress = if (percent > 0 && duration != null) {
                    WatchProgress(duration * percent / 100, duration, completed = percent >= 95)
                } else {
                    null
                },
            )
        }

    /** Tekst van een "upsell"-badge (VTM GO+ of Streamz nodig), anders null. */
    private fun upsell(d: JSONObject): String? =
        d.arr("badges").objects().firstOrNull { it.str("icon") == UPSELL }?.str("label")
            ?: d.obj("badge")?.takeIf { it.str("icon") == UPSELL }?.str("label")

    // ---------- Stream ----------

    override suspend fun resolveStream(playableId: String): StreamInfo {
        val live = playableId.startsWith(LIVE_PREFIX)
        val id = playableId.removePrefix(LIVE_PREFIX)
        val tokens = api.get(if (live) "/live" else "/play/$id")
        val playerToken = tokens.str("playerToken")
            ?: throw ProviderException(ErrorKind.NOT_AVAILABLE, "VTM GO: geen playerToken voor $id")
        val video = api.playConfig(id, playerToken).obj("video")!!
        val streams = video.arr("streams").objects()
        val dash = streams.firstOrNull { it.str("type") == "dash" }
            ?: throw ProviderException(
                if (streams.isEmpty()) ErrorKind.NOT_AVAILABLE else ErrorKind.API_CHANGED,
                "VTM GO: geen DASH-stream (types: ${streams.mapNotNull { it.str("type") }})",
            )
        val url = dash.str("url") ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: DASH-stream zonder url")

        val widevine = dash.obj("drm")?.obj("com.widevine.alpha")
        val licenseUrl = widevine?.str("licenseUrl")
        val authToken = widevine?.path("drmtoday")?.str("authToken")
        val drm = when {
            licenseUrl == null -> null
            widevine.str("provider") == "drmtoday" && authToken != null ->
                DrmInfo(licenseUrl = licenseUrl, handledByProvider = true)
            else -> DrmInfo(licenseUrl = licenseUrl)
        }
        return StreamInfo(
            manifestUrl = url,
            format = StreamFormat.DASH,
            drm = drm,
            subtitles = video.arr("subtitles").objects().mapNotNull { s ->
                s.str("url")?.let { Subtitle(it, s.str("language") ?: "nl") }
            },
            isLive = live || video.str("streamType") == "live",
            resumePositionSeconds = if (live) null else tokens.optInt("playerPositionSeconds", 0).takeIf { it > RESUME_MARGIN },
            extra = buildMap {
                licenseUrl?.let { put(EXTRA_LICENSE_URL, it) }
                authToken?.let { put(EXTRA_DRM_TOKEN, it) }
            },
        )
    }

    /** DRMtoday: challenge als octet-stream met x-dt-auth-token; antwoord is JSON {"license": "<base64>"}. */
    override suspend fun acquireLicense(stream: StreamInfo, challenge: ByteArray): ByteArray {
        val url = stream.extra[EXTRA_LICENSE_URL] ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: geen licentie-URL")
        val token = stream.extra[EXTRA_DRM_TOKEN] ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: geen DRM-token")
        val resp = api.postRaw(url, challenge, mapOf("x-dt-auth-token" to token, "Content-Type" to "application/octet-stream"))
        if (resp.code !in 200..299) {
            throw ProviderException(
                if (resp.code >= 500) ErrorKind.NETWORK else ErrorKind.NOT_AVAILABLE,
                "VTM GO licentie: HTTP ${resp.code}",
            )
        }
        return licenseFromResponse(resp.body)
    }

    // ---------- Kijk verder ----------

    /**
     * De rij "Kijk Verder" van de homepage. De tegel heeft enkel een percentage; duur, titel en
     * beeld van de aflevering komen uit het seizoen (gecachet). Afleveringen van VTM GO+ vallen weg.
     */
    override suspend fun continueWatching(): List<ContinueWatchingEntry> {
        val row = api.get("/storefronts/$MAIN").arr("rows").objects().firstOrNull { it.str("rowType") == CONTINUE_ROW }
            ?: return emptyList()
        val teasers = if (row.optBoolean("hasDetail")) {
            runCatching { api.get("/storefronts/$MAIN/detail/${row.str("id")}").obj("row") }
                .getOrNull()?.arr("teasers")?.objects() ?: row.arr("teasers").objects()
        } else {
            row.arr("teasers").objects()
        }
        val gate = Semaphore(ROW_PARALLELISM)
        return coroutineScope {
            teasers.filterNot { isPremium(it) }.map { t ->
                async { gate.withPermit { continueEntry(t) } }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun continueEntry(t: JSONObject): ContinueWatchingEntry? {
        val playableId = t.str("playableId") ?: return null
        val itemId = t.str("detailId")
        val itemTitle = t.str("title") ?: return null
        val percent = t.optInt("userProgressPercentage", 0)
        val (season, number) = seasonAndEpisode(t.obj("seasonLabel")?.str("label")) ?: (null to null)
        val fromSeason = if (itemId != null && season != null) {
            val eps = runCatching { seasonEpisodes(itemId, season) }.getOrNull()
            // Seizoen gekend maar aflevering niet bij de gratis afleveringen: VTM GO+, weglaten.
            if (eps != null) eps.firstOrNull { it.playableId == playableId } ?: return null else null
        } else {
            null
        }
        val episode = fromSeason ?: Episode(
            id = playableId,
            title = t.obj("seasonLabel")?.str("accessibilityLabel") ?: itemTitle,
            playableId = playableId,
            description = t.str("description"),
            imageUrl = t.str("imageUrl"),
            seasonNumber = season,
            episodeNumber = number,
        )
        val duration = episode.durationSeconds
        val progress = episode.progress ?: if (percent > 0 && duration != null) {
            WatchProgress(duration * percent / 100, duration, completed = percent >= 95)
        } else {
            null
        }
        return ContinueWatchingEntry(itemId = itemId, itemTitle = itemTitle, episode = episode.copy(progress = progress))
    }

    /** Zoals de website: PUT /userData/playerPosition/{id} met {position, duration} in seconden. */
    override suspend fun reportProgress(stream: StreamInfo, playableId: String, positionSeconds: Int, durationSeconds: Int) {
        if (stream.isLive || playableId.startsWith(LIVE_PREFIX)) return
        api.put(
            "/userData/playerPosition/$playableId",
            JSONObject().put("position", positionSeconds).put("duration", durationSeconds),
        )
    }

    /** DELETE van de spelerpositie haalt de aflevering uit "Kijk verder" (live getest 26/10/2026). */
    override suspend fun removeFromContinueWatching(playableId: String) {
        if (playableId.startsWith(LIVE_PREFIX)) return
        api.delete("/userData/playerPosition/$playableId")
        seasonCache.clear()
    }

    // ---------- Mijn lijst ----------

    /**
     * De rij MY_LIST van de homepage: die tegels hebben `premiumProductLogo` (die van /my-list niet),
     * zodat VTM GO+, Streamz en Cinema wegvallen. Zonder die rij (lege lijst?) /my-list als terugval.
     */
    override suspend fun myList(): List<CatalogItem> {
        val row = api.get("/storefronts/$MAIN").arr("rows").objects().firstOrNull { it.str("rowType") == MY_LIST_ROW }
        val teasers = when {
            row == null -> api.get("/my-list").arr("teasers").objects()
            row.optBoolean("hasDetail") -> runCatching { api.get("/storefronts/$MAIN/detail/${row.str("id")}").obj("row") }
                .getOrNull()?.arr("teasers")?.objects() ?: row.arr("teasers").objects()
            else -> row.arr("teasers").objects()
        }
        return teasers.mapNotNull { teaserItem(it, emptyList(), portrait = false, storefront = MAIN) }
    }

    override suspend fun setInMyList(itemId: String, inList: Boolean) {
        if (inList) api.put("/userData/myList/$itemId") else api.delete("/userData/myList/$itemId")
    }

    companion object {
        private const val POPULAR = "populair"
        private const val LIVE = "live"
        private const val MAIN = "main"
        private const val LIVE_PREFIX = "live:"
        private const val SEASON_SEP = "#"
        private const val SINGLE = "film"
        private const val UPSELL = "UPSELL"
        private const val CONTINUE_ROW = "CONTINUE_WATCHING"
        private const val MY_LIST_ROW = "MY_LIST"
        private const val RESUME_MARGIN = 30
        private const val SEASON_CACHE_MS = 2 * 60_000L
        private const val ROWS_PER_PAGE = 8
        private const val ROW_PARALLELISM = 4
        private const val EXTRA_LICENSE_URL = "licenseUrl"
        private const val EXTRA_DRM_TOKEN = "drmToken"

        private val CATEGORIES = listOf(
            NativeCategory(POPULAR, "Populair"),
            NativeCategory(LIVE, "Live tv"),
            NativeCategory("series", "Series"),
            NativeCategory("films", "Films"),
            NativeCategory("docu", "Docu"),
            NativeCategory("kids", "Kids"),
            NativeCategory(MAIN, "Home"),
        )

        private val POPULAR_ROW = Regex("moet je (nu )?zien|top ?10 op vtm go", RegexOption.IGNORE_CASE)

        /** Rijen zonder video's die je hier (gratis) kan kijken: podcasts, aankondigingen, betaalaanbod. */
        private val SKIP_ROW = Regex("podcast|binnenkort|vtm go\\+|streamz|cinema|filmdeal", RegexOption.IGNORE_CASE)

        private val NUMBERED_TITLE = Regex("""^\s*(\d+)\.\s*(.*)$""")
        private val SEASON_EPISODE = Regex("""S(\d+)\s*A(\d+)""", RegexOption.IGNORE_CASE)

        /** Tegel of detail van VTM GO+, Streamz of Cinema (huren): heeft een `premiumProductLogo`. */
        internal fun isPremium(o: JSONObject): Boolean = o.obj("premiumProductLogo") != null

        /** "S1 A5" → (1, 5). */
        internal fun seasonAndEpisode(label: String?): Pair<Int, Int>? =
            label?.let { SEASON_EPISODE.find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        private val DURATION_LABEL = Regex("""^\s*(\d+\s*u|\d+\s*min)""", RegexOption.IGNORE_CASE)

        /**
         * Thema's uit een rijtitel of genre ("Komische series" → thema-comedy). De ids staan in
         * categories.json onder providers.vtmgo; zo blijft de mapping werken als VTM GO rij-id's wijzigt.
         */
        private val THEMES = listOf(
            "thema-comedy" to Regex("komisch|comedy|lachen|stand-up|humor", RegexOption.IGNORE_CASE),
            "thema-crime" to Regex("crime|misdaad|politie|thriller|nagelbijten|puntje van je stoel|spannend", RegexOption.IGNORE_CASE),
            "thema-truecrime" to Regex("true crime", RegexOption.IGNORE_CASE),
            "thema-drama" to Regex("drama|series van hier|topseries|bekroonde|historisch|western|sci-fi|fantasy|horror|love is in the air", RegexOption.IGNORE_CASE),
            "thema-reality" to Regex("reality|date|patser|love island|achter de schermen|hun job|verraders|open en bloot", RegexOption.IGNORE_CASE),
            "thema-lifestyle" to Regex("menu|tafel|koken|droom weg|wonen|reizen", RegexOption.IGNORE_CASE),
            "thema-music" to Regex("muziek", RegexOption.IGNORE_CASE),
            "thema-sport" to Regex("sport|supporter", RegexOption.IGNORE_CASE),
            "thema-news" to Regex("op de hoogte|nieuws|actua", RegexOption.IGNORE_CASE),
            "thema-archive" to Regex("nostalgie", RegexOption.IGNORE_CASE),
            "thema-kids" to Regex("kids|kinderen|cartoons", RegexOption.IGNORE_CASE),
            "thema-docu" to Regex("docu", RegexOption.IGNORE_CASE),
            "thema-films" to Regex("film|movie night", RegexOption.IGNORE_CASE),
        )

        internal fun themes(title: String): List<String> = THEMES.filter { it.second.containsMatchIn(title) }.map { it.first }

        /** De uitzending die nu loopt (startsAt ≤ nu < endsAt). */
        internal fun currentBroadcast(broadcasts: List<JSONObject>, nowMillis: Long): JSONObject? = broadcasts.firstOrNull { b ->
            val start = b.str("startsAt")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            val end = b.str("endsAt")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            start != null && end != null && nowMillis in start until end
        }

        /** Films hebben een duur als label ("1u 45min"), reeksen "7 seizoenen". */
        internal fun kindFromLabels(labels: List<String>, storefront: String): ItemKind = when {
            labels.any { it.contains("seizoen", ignoreCase = true) } -> ItemKind.SERIES
            labels.any { DURATION_LABEL.containsMatchIn(it) } -> ItemKind.MOVIE
            storefront == "films" -> ItemKind.MOVIE
            else -> ItemKind.SERIES
        }

        internal fun yearFromLabels(labels: List<JSONObject>): Int? =
            labels.firstOrNull { it.str("accessibilityLabel") == "Productiejaar" }?.str("label")?.toIntOrNull()
                ?: labels.firstNotNullOfOrNull { it.str("label")?.takeIf { l -> l.matches(Regex("(19|20)\\d\\d")) }?.toIntOrNull() }

        /** JSON {"license": base64} (DRMtoday); anders is het antwoord zelf de licentie. */
        internal fun licenseFromResponse(body: ByteArray): ByteArray {
            val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return body
            val b64 = json.str("license") ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO licentie: JSON zonder 'license'")
            return Base64.getDecoder().decode(b64)
        }
    }
}
