package be.verdonckit.zapper.api

data class ProviderInfo(
    val id: String,
    val displayName: String,
    /** ARGB, bv. 0xFF00D1A7 */
    val brandColor: Long,
    val requiresLogin: Boolean = true,
    /**
     * True = het platform houdt zelf kijkvoortgang bij en de plugin implementeert
     * [StreamingProvider.continueWatching] en [StreamingProvider.reportProgress].
     * De host gebruikt dan de gegevens van het platform als bron van waarheid voor
     * "Verder kijken" en bekeken afleveringen.
     */
    val syncsProgress: Boolean = false,
    /** True = de plugin implementeert [StreamingProvider.myList] en [StreamingProvider.setInMyList]. */
    val supportsMyList: Boolean = false,
)

data class Credentials(val username: String, val password: String)

sealed interface LoginResult {
    data object Success : LoginResult
    data class Failure(val reason: LoginFailure, val message: String) : LoginResult
}

enum class LoginFailure { INVALID_CREDENTIALS, ACCOUNT_PROBLEM, NETWORK, API_CHANGED, UNKNOWN }

data class NativeCategory(val id: String, val name: String)

data class Page<T>(val items: List<T>, val nextCursor: String?)

enum class ItemKind { SERIES, MOVIE, LIVE, OTHER }

data class CatalogItem(
    /** Uniek binnen de provider en stabiel over syncs heen. */
    val id: String,
    val kind: ItemKind,
    val title: String,
    val description: String? = null,
    /** Staand beeld (2:3). Mag null zijn; de host valt dan terug op [backdropUrl]. */
    val posterUrl: String? = null,
    /** Liggend beeld (16:9), gebruikt voor kaarten en de hero-banner. */
    val backdropUrl: String? = null,
    /** Titel-art / logo met transparante achtergrond. */
    val logoUrl: String? = null,
    val nativeCategoryIds: List<String> = emptyList(),
    val year: Int? = null,
    /** Rechtstreeks afspeelbaar (films, live). Null = via [ItemDetails]. */
    val playableId: String? = null,
)

data class ItemDetails(
    val item: CatalogItem,
    val seasons: List<Season>,
)

data class Season(
    val id: String,
    val title: String,
    /** Optioneel: afleveringen die al meekwamen met de details-call. */
    val episodesPreview: List<Episode> = emptyList(),
)

data class Episode(
    val id: String,
    val title: String,
    val playableId: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val durationSeconds: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val available: Boolean = true,
    /** Kijkvoortgang volgens het platform; null = niet bekeken of niet ondersteund. */
    val progress: WatchProgress? = null,
)

/** Kijkvoortgang zoals het platform die bijhoudt. */
data class WatchProgress(
    val positionSeconds: Int,
    val durationSeconds: Int,
    /** Het platform beschouwt de aflevering als uitgekeken. */
    val completed: Boolean = false,
)

/**
 * Eén item uit de eigen "Verder kijken"-lijst van het platform. Een aflevering zonder
 * [Episode.progress] is de volgende aflevering die het platform voorstelt.
 */
data class ContinueWatchingEntry(
    /** Id van de reeks of film zoals in [CatalogItem.id]; null als het platform dat niet geeft. */
    val itemId: String?,
    val itemTitle: String,
    val episode: Episode,
)

enum class StreamFormat { DASH, HLS, PROGRESSIVE }

enum class DrmScheme { WIDEVINE }

data class DrmInfo(
    val scheme: DrmScheme = DrmScheme.WIDEVINE,
    val licenseUrl: String?,
    val licenseHeaders: Map<String, String> = emptyMap(),
    /** True = de host roept [StreamingProvider.acquireLicense] aan in plaats van zelf te POSTen. */
    val handledByProvider: Boolean = false,
)

data class Subtitle(val url: String, val language: String, val mimeType: String = "text/vtt")

data class StreamInfo(
    val manifestUrl: String,
    val format: StreamFormat,
    val drm: DrmInfo? = null,
    /** Headers voor manifest- en segment-requests. */
    val requestHeaders: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
    val isLive: Boolean = false,
    /** Waar het platform zegt dat je gebleven was; null = van het begin of onbekend. */
    val resumePositionSeconds: Int? = null,
    /** Vrije context die de plugin terugkrijgt in [StreamingProvider.acquireLicense]. */
    val extra: Map<String, String> = emptyMap(),
)

enum class ErrorKind { NOT_LOGGED_IN, GEO_BLOCKED, NOT_AVAILABLE, AGE_RESTRICTED, API_CHANGED, NETWORK, UNKNOWN }

/**
 * Gooi dit bij elke verwachte fout. [ErrorKind.API_CHANGED] is het signaal voor
 * de health-check dat de plugin bijgestuurd moet worden.
 */
class ProviderException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
