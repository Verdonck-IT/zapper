package be.verdonckit.zapper.plugins.play

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.ProviderException
import org.json.JSONObject

/**
 * Login bij Play via AWS Cognito (USER_SRP_AUTH), zoals de Kodi-add-on plugin.video.play.
 * De API gebruikt het IdToken als Bearer (1 u geldig); het RefreshToken (30 dagen) vernieuwt het.
 * Tokens staan enkel in host.storage en worden nooit gelogd.
 */
internal class PlayAuth(private val host: HostServices) {

    suspend fun login(creds: Credentials): LoginResult {
        clear()
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
            store(result)
            if (host.storage.get(RT) == null) return LoginResult.Failure(LoginFailure.API_CHANGED, "Cognito: geen RefreshToken")
            LoginResult.Success
        } catch (e: ProviderException) {
            LoginResult.Failure(if (e.kind == ErrorKind.NETWORK) LoginFailure.NETWORK else LoginFailure.UNKNOWN, e.message ?: "fout")
        }
    }

    fun hasSession(): Boolean = host.storage.get(RT) != null

    fun logout() = clear()

    /** Geldig IdToken; vernieuwt met het RefreshToken als het (bijna) verlopen is. */
    suspend fun idToken(): String {
        val exp = host.storage.get(EXP)?.toLongOrNull() ?: 0
        host.storage.get(ID)?.takeIf { exp > host.nowMillis() + 60_000 }?.let { return it }
        val rt = host.storage.get(RT) ?: throw ProviderException(ErrorKind.NOT_LOGGED_IN, "Play: niet ingelogd")
        val resp = cognito(
            "InitiateAuth",
            JSONObject()
                .put("AuthFlow", "REFRESH_TOKEN")
                .put("ClientId", CLIENT_ID)
                .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", rt)),
        )
        resp.failure()?.let { f ->
            // RefreshToken verlopen of ingetrokken: opnieuw inloggen met de bewaarde credentials.
            val creds = host.credentials()
            if (creds != null && login(creds) is LoginResult.Success) return host.storage.get(ID)!!
            throw ProviderException(ErrorKind.NOT_LOGGED_IN, "Play: sessie verlopen (${f.message})")
        }
        val result = resp.obj("AuthenticationResult")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "Cognito refresh: geen AuthenticationResult")
        store(result)
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

    /** Cognito-fouten komen als {"__type": "...", "message": "..."}. */
    private fun JSONObject.failure(): LoginResult.Failure? {
        val type = str("__type") ?: return null
        val message = str("message") ?: type
        val reason = when {
            "NotAuthorized" in type || "UserNotFound" in type -> LoginFailure.INVALID_CREDENTIALS
            "UserNotConfirmed" in type || "PasswordResetRequired" in type -> LoginFailure.ACCOUNT_PROBLEM
            "TooManyRequests" in type || "LimitExceeded" in type -> LoginFailure.NETWORK
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
