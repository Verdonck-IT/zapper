package be.verdonckit.zapper.api

/**
 * Versie van het plugin-contract. Verhoog dit bij elke breaking change in deze module.
 * De host laadt enkel plugins waarvan `apiVersion` in het manifest exact gelijk is.
 */
const val PROVIDER_API_VERSION = 4

/**
 * Eén streamingplatform (VRT MAX, VTM GO, ...).
 *
 * Een plugin bevat enkel logica: inloggen, catalogus parsen en een stream resolven.
 * Netwerk, opslag, DRM-afspelen en UI zijn de verantwoordelijkheid van de host.
 *
 * Implementaties moeten een publieke constructor zonder argumenten hebben;
 * de host roept daarna eenmalig [attach] aan.
 */
interface StreamingProvider {

    val info: ProviderInfo

    /** Wordt exact één keer aangeroepen, vóór alle andere methodes. */
    fun attach(host: HostServices)

    suspend fun login(credentials: Credentials): LoginResult

    suspend fun isLoggedIn(): Boolean

    suspend fun logout()

    /** De categorieën zoals het platform ze zelf kent. De host mapt ze naar de eigen taxonomie. */
    fun nativeCategories(): List<NativeCategory>

    /**
     * Catalogus ophalen, gepagineerd.
     * @param categoryId een id uit [nativeCategories], of null voor "alles".
     * @param cursor null voor de eerste pagina, daarna [Page.nextCursor].
     */
    suspend fun catalog(categoryId: String?, cursor: String?): Page<CatalogItem>

    suspend fun details(itemId: String): ItemDetails

    suspend fun episodes(seasonId: String, cursor: String?): Page<Episode>

    suspend fun resolveStream(playableId: String): StreamInfo

    /**
     * Enkel nodig als [DrmInfo.handledByProvider] true is: de plugin krijgt de
     * Widevine-challenge en geeft het licentieantwoord terug (bv. als het platform
     * de challenge in JSON verpakt). Standaard handelt de host het af via een POST.
     */
    /**
     * De eigen "Verder kijken"-lijst van het platform, in de volgorde van het platform.
     * Enkel nodig als [ProviderInfo.syncsProgress] true is.
     */
    suspend fun continueWatching(): List<ContinueWatchingEntry> = emptyList()

    /**
     * Meldt kijkvoortgang aan het platform, zodat die ook in de eigen app of website
     * zichtbaar is. De host roept dit periodiek tijdens het kijken en bij het stoppen aan.
     * [stream] is de [StreamInfo] uit [resolveStream] (incl. [StreamInfo.extra]).
     */
    suspend fun reportProgress(stream: StreamInfo, playableId: String, positionSeconds: Int, durationSeconds: Int) {}

    /**
     * "Mijn lijst" zoals het platform die bijhoudt (zelfde lijst als in de eigen app/website),
     * in de volgorde van het platform. Enkel nodig als [ProviderInfo.supportsMyList] true is.
     */
    suspend fun myList(): List<CatalogItem> = emptyList()

    /** Programma toevoegen aan of verwijderen uit "Mijn lijst" bij het platform. */
    suspend fun setInMyList(itemId: String, inList: Boolean): Unit =
        throw UnsupportedOperationException("${info.id} ondersteunt geen Mijn lijst")

    /**
     * Haalt een aflevering uit de "Verder kijken"-lijst van het platform.
     * @param playableId zoals in [ContinueWatchingEntry.episode]
     */
    suspend fun removeFromContinueWatching(playableId: String): Unit =
        throw UnsupportedOperationException("${info.id} kan niets uit Verder kijken verwijderen")

    suspend fun acquireLicense(stream: StreamInfo, challenge: ByteArray): ByteArray =
        throw UnsupportedOperationException("${info.id} handelt licenties niet zelf af")
}
