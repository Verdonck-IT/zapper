package be.verdonckit.zapper.api

/**
 * Versie van het plugin-contract. Verhoog dit bij elke breaking change in deze module.
 * De host laadt enkel plugins waarvan `apiVersion` in het manifest exact gelijk is.
 */
const val PROVIDER_API_VERSION = 1

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
    suspend fun acquireLicense(stream: StreamInfo, challenge: ByteArray): ByteArray =
        throw UnsupportedOperationException("${info.id} handelt licenties niet zelf af")
}
