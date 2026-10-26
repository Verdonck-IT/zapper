package be.verdonckit.zapper.plugins.play

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.plugins.play.shared.LoginGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Login bij Play via AWS Cognito (USER_SRP_AUTH), zoals de Kodi-add-on plugin.video.play.
 * De API gebruikt het IdToken als Bearer (1 u geldig); het RefreshToken (30 dagen) vernieuwt het.
 * Tokens staan enkel in host.storage en worden nooit gelogd.
 *
 * Een mislukte login of refresh door netwerk of throttling laat de bestaande tokens staan;
 * enkel een geweigerd RefreshToken wordt gewist. Stil inloggen is bewaakt door [LoginGuard].
 */
internal class PlayAuth(private val host: HostServices) {

    private val guard by lazy { LoginGuard(host.storage, host::nowMillis) }

    /** Eén refresh of login tegelijk: parallelle calls (sync, rijen) wachten op dezelfde vernieuwing. */
    private val tokenLock = Mutex()

    /** Login die de gebruiker zelf vraagt (Instellingen, contracttest). */
    suspend fun login(creds: Credentials): LoginResult = guardedLogin(creds, explicit = true)

    private suspend fun guardedLogin(creds: Credentials, explicit: Boolean): LoginResult {
        guard.check(creds, explicit)?.let { return LoginResult.Failure(it.reason, "Play: ${it.message}") }
        return performLogin(creds).also { guard.record(creds, it) }
    }

    private suspend fun performLogin(creds: Credentials): LoginResult {
        return try {
            val srp = CognitoSrp(POOL_ID)
            val init = cognito(
                "InitiateAuth",
                JSONObject()
                    .put("AuthFlow", "USER_SRP_AUTH")
                    .put("ClientId", CLIENT_ID)
                    .put("AuthParameters", JSONObject().put("USERNAME", creds.username).put("SRP_A", srp.srpA)),
            )
            init.failure()?.let { return it }
            if (init.str("ChallengeName") != "PASSWORD_VERIFIER") {
                return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: onverwachte challenge ${init.str("ChallengeName")}")
            }
            val params = init.obj("ChallengeParameters")
                ?: return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen ChallengeParameters")
            val userIdForSrp = params.str("USER_ID_FOR_SRP")
                ?: return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen USER_ID_FOR_SRP")
            val secretBlock = params.str("SECRET_BLOCK")
                ?: return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen SECRET_BLOCK")
            val timestamp = CognitoSrp.timestamp()
            val signature = srp.passwordClaimSignature(
                userIdForSrp = userIdForSrp,
                password = creds.password,
                srpB = params.str("SRP_B") ?: return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen SRP_B"),
                salt = params.str("SALT") ?: return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen SALT"),
                secretBlock = secretBlock,
                timestamp = timestamp,
            )
            val answer = cognito(
                "RespondToAuthChallenge",
                JSONObject()
                    .put("ChallengeName", "PASSWORD_VERIFIER")
                    .put("ClientId", CLIENT_ID)
                    .put(
                        "ChallengeResponses",
                        JSONObject()
                            .put("USERNAME", params.str("USERNAME") ?: userIdForSrp)
                            .put("TIMESTAMP", timestamp)
                            .put("PASSWORD_CLAIM_SECRET_BLOCK", secretBlock)
                            .put("PASSWORD_CLAIM_SIGNATURE", signature),
                    ),
            )
            answer.failure()?.let { return it }
            val result = answer.obj("AuthenticationResult")
                ?: return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen AuthenticationResult (${answer.str("ChallengeName")})")
            if (result.str("RefreshToken") == null) return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen RefreshToken")
            store(result)
            host.log(LogLevel.INFO, "Play: ingelogd met wachtwoord")
            LoginResult.Success
        } catch (e: ProviderException) {
            val reason = when (e.kind) {
                ErrorKind.NETWORK -> LoginFailure.NETWORK
                ErrorKind.API_CHANGED -> LoginFailure.API_CHANGED
                else -> LoginFailure.UNKNOWN
            }
            LoginResult.Failure(reason, e.message ?: "fout")
        }
    }

    fun logout() = clear()

    /** Geldig IdToken; vernieuwt met het RefreshToken als het (bijna) verlopen is. */
    suspend fun idToken(): String = validIdToken() ?: tokenLock.withLock { validIdToken() ?: renewIdToken() }

    private fun validIdToken(): String? {
        val exp = host.storage.get(EXP)?.toLongOrNull() ?: 0
        return host.storage.get(ID)?.takeIf { exp > host.nowMillis() + 60_000 }
    }

    private suspend fun renewIdToken(): String {
        host.storage.get(RT)?.let { rt -> refresh(rt)?.let { return it } }

        // Geen of geweigerd RefreshToken: stil opnieuw inloggen met de bewaarde credentials.
        val creds = host.credentials() ?: throw ProviderException(ErrorKind.NOT_LOGGED_IN, "Play: niet ingelogd")
        when (val r = guardedLogin(creds, explicit = false)) {
            is LoginResult.Success -> return host.storage.get(ID)
                ?: throw ProviderException(ErrorKind.API_CHANGED, "Cognito login: geen IdToken")
            is LoginResult.Failure -> {
                host.log(LogLevel.WARN, "Play: stil inloggen mislukt (${r.reason}): ${r.message}")
                throw ProviderException(LoginGuard.errorKind(r.reason), "Play login: ${r.message}")
            }
        }
    }

    /** Nieuw IdToken, of null als het RefreshToken geweigerd werd (dan is het gewist). Netwerkfouten gaan door. */
    private suspend fun refresh(rt: String): String? {
        val resp = cognito( // gooit NETWORK bij IO-fouten en 5xx: RefreshToken blijft
            "InitiateAuth",
            JSONObject()
                .put("AuthFlow", "REFRESH_TOKEN")
                .put("ClientId", CLIENT_ID)
                .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", rt)),
        )
        resp.failure()?.let { f ->
            when (f.reason) {
                LoginFailure.NETWORK -> throw ProviderException(ErrorKind.NETWORK, "Cognito refresh: ${f.message}")
                LoginFailure.INVALID_CREDENTIALS -> {
                    host.log(LogLevel.WARN, "Play: refresh token geweigerd")
                    clear()
                    return null
                }
                else -> throw ProviderException(ErrorKind.API_CHANGED, "Cognito refresh: ${f.message}")
            }
        }
        val result = resp.obj("AuthenticationResult")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "Cognito refresh: geen AuthenticationResult")
        store(result)
        host.log(LogLevel.INFO, "Play: sessie vernieuwd met refresh token")
        return host.storage.get(ID) ?: throw ProviderException(ErrorKind.API_CHANGED, "Cognito refresh: geen IdToken")
    }

    fun invalidate() {
        host.storage.remove(ID)
        host.storage.remove(EXP)
    }

    private fun store(result: JSONObject) {
        result.str("IdToken")?.let { host.storage.put(ID, it) }
        result.str("RefreshToken")?.let { host.storage.put(RT, it) } // ontbreekt bij een refresh: oude blijft
        val seconds = result.optLong("ExpiresIn", 3600)
        host.storage.put(EXP, (host.nowMillis() + seconds * 1000).toString())
    }

    private fun clear() {
        host.storage.remove(ID)
        host.storage.remove(RT)
        host.storage.remove(EXP)
    }

    private suspend fun cognito(target: String, body: JSONObject): JSONObject {
        val resp = try {
            host.http.execute(
                HttpRequest(
                    url = COGNITO_URL,
                    method = "POST",
                    headers = mapOf("X-Amz-Target" to "AWSCognitoIdentityProviderService.$target", "Accept-Encoding" to "identity"),
                    body = body.toString().toByteArray(Charsets.UTF_8),
                    contentType = "application/x-amz-json-1.1",
                ),
            )
        } catch (e: Exception) {
            throw ProviderException(ErrorKind.NETWORK, "Cognito $target: ${e.message}", e)
        }
        if (resp.code >= 500) throw ProviderException(ErrorKind.NETWORK, "Cognito $target: HTTP ${resp.code}")
        return runCatching { JSONObject(resp.text()) }.getOrElse {
            throw ProviderException(ErrorKind.API_CHANGED, "Cognito $target: geen JSON (HTTP ${resp.code})")
        }
    }

    /** Cognito-fouten komen als {"__type": "...", "message": "..."}. Throttling zet meteen de login-pauze. */
    private fun JSONObject.failure(): LoginResult.Failure? {
        val type = str("__type") ?: return null
        val message = str("message") ?: type
        val reason = when {
            "NotAuthorized" in type || "UserNotFound" in type -> LoginFailure.INVALID_CREDENTIALS
            "UserNotConfirmed" in type || "PasswordResetRequired" in type -> LoginFailure.ACCOUNT_PROBLEM
            "TooManyRequests" in type || "LimitExceeded" in type -> LoginFailure.NETWORK.also { guard.backOff() }
            else -> LoginFailure.API_CHANGED
        }
        return LoginResult.Failure(reason, "Play: $message")
    }

    companion object {
        const val POOL_ID = "eu-west-1_dViSsKM5Y"
        const val CLIENT_ID = "6s1h851s8uplco5h6mqh1jac8m"
        private const val COGNITO_URL = "https://cognito-idp.eu-west-1.amazonaws.com/"
        private const val ID = "play_id_token"
        private const val RT = "play_refresh_token"
        private const val EXP = "play_id_token_exp"
    }
}
