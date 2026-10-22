package be.verdonckit.zapper.plugins.play

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Cognito USER_SRP_AUTH, rekenkundig identiek aan de Kodi-add-on
 * (resources/lib/play/aws/cognito_idp.py, zelf gebaseerd op amazon-cognito-identity-js).
 * Enkel de wiskunde; de HTTP-calls zitten in [PlayAuth].
 *
 * @param poolId "<regio>_<id>", bv. eu-west-1_dViSsKM5Y
 * @param smallA enkel voor tests: vaste geheime waarde a
 */
internal class CognitoSrp(private val poolId: String, smallA: BigInteger? = null) {

    private val poolName = poolId.substringAfter('_')
    private val a: BigInteger = smallA ?: BigInteger(1, ByteArray(128).also { SecureRandom().nextBytes(it) }).mod(N)
    private val largeA: BigInteger = G.modPow(a, N).also { check(it.mod(N) != BigInteger.ZERO) { "SRP: ongeldige A" } }

    /** SRP_A voor InitiateAuth. */
    val srpA: String get() = largeA.toString(16)

    /**
     * PASSWORD_CLAIM_SIGNATURE voor RespondToAuthChallenge.
     * @param userIdForSrp USER_ID_FOR_SRP uit de challenge
     * @param srpB SRP_B (hex) uit de challenge
     * @param salt SALT (hex) uit de challenge
     * @param secretBlock SECRET_BLOCK (base64) uit de challenge
     */
    fun passwordClaimSignature(
        userIdForSrp: String,
        password: String,
        srpB: String,
        salt: String,
        secretBlock: String,
        timestamp: String,
    ): String {
        val key = passwordAuthenticationKey(userIdForSrp, password, BigInteger(srpB, 16), salt)
        val msg = poolName.toByteArray(Charsets.UTF_8) +
            userIdForSrp.toByteArray(Charsets.UTF_8) +
            Base64.getDecoder().decode(secretBlock) +
            timestamp.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(hmac(key, msg))
    }

    private fun passwordAuthenticationKey(userId: String, password: String, b: BigInteger, salt: String): ByteArray {
        val u = BigInteger(hexHash(padHex(largeA) + padHex(b)), 16)
        require(u != BigInteger.ZERO) { "SRP: u mag niet 0 zijn" }
        val userPassHash = sha256Hex("$poolName$userId:$password".toByteArray(Charsets.UTF_8))
        val x = BigInteger(hexHash(padHex(salt) + userPassHash), 16)
        // Zoals Python: (B - k·g^x) kan negatief zijn; modPow geeft toch het juiste resultaat in [0, N).
        val s = b.subtract(K.multiply(G.modPow(x, N))).modPow(a.add(u.multiply(x)), N)
        return hkdf(ikm = hexToBytes(padHex(s)), salt = hexToBytes(padHex(u.toString(16))))
    }

    companion object {
        private const val N_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
        private val N = BigInteger(N_HEX, 16)
        private val G = BigInteger.valueOf(2)
        internal val K = BigInteger(hexHash("00${N_HEX}02"), 16)
        private val INFO = "Caldera Derived Key".toByteArray(Charsets.UTF_8)

        /** Formaat dat Cognito eist: "Mon Jan 5 10:00:00 UTC 2026" (dag zonder voorloopnul, Engels). */
        fun timestamp(now: Date = Date()): String =
            SimpleDateFormat("EEE MMM d HH:mm:ss 'UTC' yyyy", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(now)

        /** Zoals AuthenticationHelper.padHex: even lengte, en een voorloop-00 als het hoogste bit gezet is. */
        internal fun padHex(n: BigInteger): String = padHex(n.toString(16))

        internal fun padHex(hex: String): String = when {
            hex.length % 2 == 1 -> "0$hex"
            hex.first() in "89ABCDEFabcdef" -> "00$hex"
            else -> hex
        }

        private fun hkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
            val prk = hmac(salt, ikm)
            return hmac(prk, INFO + byteArrayOf(1)).copyOf(16)
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        private fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun hexHash(hex: String): String = sha256Hex(hexToBytes(hex))

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
