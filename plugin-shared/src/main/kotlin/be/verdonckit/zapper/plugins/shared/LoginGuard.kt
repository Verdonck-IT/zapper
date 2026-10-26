package be.verdonckit.zapper.plugins.shared

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.KeyValueStore
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginResult
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Beschermt het account tegen te veel logins met wachtwoord.
 *
 *  - Throttling of onverwachte fouten (5xx, 429, API gewijzigd): oplopende pauze 15 min → 1 u → 4 u.
 *  - Credentials geweigerd: geen automatische poging meer met diezelfde credentials
 *    (een expliciete login met andere gegevens heft dat op).
 *  - Netwerkfouten tellen niet mee: daar kan het platform niets aan doen.
 *
 * Wordt als bron meegecompileerd in elke plugin, met het package herschreven naar
 * `be.verdonckit.zapper.plugins.<id>.shared` (taak `sharedSources` in de build.gradle.kts van de plugin).
 * Zo blijven provider-api en PROVIDER_API_VERSION ongewijzigd.
 */
class LoginGuard(private val store: KeyValueStore, private val now: () -> Long) {

    /**
     * Mislukking die een login nu meteen moet geven, of null als inloggen mag.
     * Gepauzeerd → [LoginFailure.NETWORK] (tijdelijk); geweigerde credentials zonder [explicit]
     * (= de gebruiker vraagt het zelf) → [LoginFailure.INVALID_CREDENTIALS].
     */
    fun check(creds: Credentials, explicit: Boolean): LoginResult.Failure? {
        val until = store.get(BLOCKED_UNTIL)?.toLongOrNull() ?: 0
        if (now() < until) {
            return LoginResult.Failure(LoginFailure.NETWORK, "inloggen gepauzeerd tot ${time(until)} (te veel pogingen of tijdelijke fout)")
        }
        if (!explicit && store.get(REJECTED) == fingerprint(creds)) {
            return LoginResult.Failure(LoginFailure.INVALID_CREDENTIALS, "opgeslagen login werd geweigerd; log opnieuw in via Instellingen")
        }
        return null
    }

    /** Houdt de uitkomst van een echte loginpoging bij. Throttling meldt de plugin apart via [backOff]. */
    fun record(creds: Credentials, result: LoginResult) {
        when (result) {
            is LoginResult.Success -> succeeded()
            is LoginResult.Failure -> when (result.reason) {
                LoginFailure.INVALID_CREDENTIALS, LoginFailure.ACCOUNT_PROBLEM -> rejected(creds)
                LoginFailure.API_CHANGED, LoginFailure.UNKNOWN -> backOff()
                LoginFailure.NETWORK -> Unit
            }
        }
    }

    private fun succeeded() {
        store.remove(BLOCKED_UNTIL)
        store.remove(STRIKES)
        store.remove(REJECTED)
    }

    /** Throttling of onverwacht antwoord: oplopende pauze. */
    fun backOff() {
        val strikes = (store.get(STRIKES)?.toIntOrNull() ?: 0) + 1
        store.put(STRIKES, strikes.toString())
        store.put(BLOCKED_UNTIL, (now() + BACKOFF_MS[minOf(strikes, BACKOFF_MS.size) - 1]).toString())
    }

    /** Credentials geweigerd: niet automatisch opnieuw proberen met dezelfde gegevens. */
    private fun rejected(creds: Credentials) {
        store.put(REJECTED, fingerprint(creds))
    }

    private fun fingerprint(creds: Credentials): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${creds.username}\u0000${creds.password}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun time(millis: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(millis))

    companion object {
        /** Stil inloggen mislukt: welke [ErrorKind] de plugin gooit. Tijdelijk = NETWORK, zodat de app "offline" toont. */
        fun errorKind(reason: LoginFailure): ErrorKind = when (reason) {
            LoginFailure.NETWORK -> ErrorKind.NETWORK
            LoginFailure.API_CHANGED -> ErrorKind.API_CHANGED
            else -> ErrorKind.NOT_LOGGED_IN
        }

        private const val BLOCKED_UNTIL = "autologin.blockedUntil"
        private const val STRIKES = "autologin.strikes"
        private const val REJECTED = "autologin.rejected"
        private val BACKOFF_MS = longArrayOf(15 * 60_000L, 60 * 60_000L, 4 * 60 * 60_000L)
    }
}
