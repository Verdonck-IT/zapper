package be.verdonckit.zapper.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import be.verdonckit.zapper.BuildConfig
import be.verdonckit.zapper.plugin.PluginVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Self-update voor de gesideloade APK. CI publiceert app/app.json:
 *   {"versionCode": 42, "versionName": "0.1.42", "apk": "app/zapper-42.apk", "sha256": "..."}
 * Integriteit: sha256 hier + Android zelf weigert een APK met een andere signeersleutel.
 * De gebruiker moet "onbekende bronnen" toestaan voor Zapper (eenmalig).
 */
class AppUpdater(private val context: Context, private val client: OkHttpClient) {

    data class Available(val versionCode: Int, val versionName: String, val apkPath: String, val sha256: String)

    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        val json = JSONObject(get("app/app.json").toString(Charsets.UTF_8))
        val available = Available(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            apkPath = json.getString("apk"),
            sha256 = json.getString("sha256").lowercase(),
        )
        available.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    suspend fun install(update: Available) = withContext(Dispatchers.IO) {
        val bytes = get(update.apkPath)
        check(PluginVerifier.sha256Hex(bytes) == update.sha256) { "sha256 van APK klopt niet" }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("zapper.apk", 0, bytes.size.toLong()).use { it.write(bytes); session.fsync(it) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags,
            )
            session.commit(intent.intentSender)
        }
    }

    private fun get(path: String): ByteArray {
        val url = BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
        return client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} voor $url" }
            r.body!!.bytes()
        }
    }
}

/** Toont de systeembevestiging; zonder user-actie installeert Android niets. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("AppUpdater", "update geïnstalleerd")
            else -> Log.w("AppUpdater", "installatie mislukt ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
