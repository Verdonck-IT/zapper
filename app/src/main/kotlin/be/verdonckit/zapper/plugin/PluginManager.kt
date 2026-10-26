package be.verdonckit.zapper.plugin

import android.content.Context
import android.util.Log
import be.verdonckit.zapper.BuildConfig
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.LoginMethod
import be.verdonckit.zapper.api.PROVIDER_API_VERSION
import be.verdonckit.zapper.api.StreamingProvider
import be.verdonckit.zapper.api.support.ProviderSelfTest
import be.verdonckit.zapper.api.support.SelfTestResult
import be.verdonckit.zapper.api.support.SelfTestStep
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LoadedPlugin(
    val id: String,
    val name: String,
    val build: Int,
    val version: String,
    val provider: StreamingProvider,
    val host: HostServices,
    val isDev: Boolean,
)

sealed interface UpdateOutcome {
    val id: String
    data class Installed(override val id: String, val version: String) : UpdateOutcome
    data class RolledBack(override val id: String, val version: String, val reason: String) : UpdateOutcome
    data class Failed(override val id: String, val reason: String) : UpdateOutcome
}

/**
 * Laadt providers als DEX-plugins, houdt ze up-to-date en rolt terug als een
 * nieuwe versie de self-test niet haalt.
 *
 * Opslag: files/plugins/<id>/<build>.jar (read-only, vereist sinds Android 14).
 * Status in SharedPreferences: current/previous build, entryClass per build, slechte builds.
 */
class PluginManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val hostFactory: (providerId: String) -> HostServices,
) {
    private val dir = File(context.filesDir, "plugins")
    private val prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _plugins = MutableStateFlow<Map<String, LoadedPlugin>>(emptyMap())
    val plugins: StateFlow<Map<String, LoadedPlugin>> = _plugins.asStateFlow()

    private val _compatibility = MutableStateFlow(loadCompatibility())

    /** Laatste beoordeling van de plugin-index voor deze app; null = nog nooit gecontroleerd. */
    val compatibility: StateFlow<Compatibility?> = _compatibility.asStateFlow()

    /** Geïnstalleerde zenders die niet geladen konden worden (andere plugin-API): update-check nodig. */
    @Volatile var hasUnloadedPlugins: Boolean = false
        private set

    fun get(id: String): LoadedPlugin? = _plugins.value[id]

    // ---------- Opstarten ----------

    suspend fun loadInstalled() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val loaded = LinkedHashMap<String, LoadedPlugin>()

            installedIds().forEach { id ->
                val current = prefs.getInt("current.$id", -1)
                val previous = prefs.getInt("previous.$id", -1)
                // Een build voor een andere plugin-API (bv. na een app-update) nooit laden: wacht op een passende build.
                val currentFits = fitsThisApp(id, current)
                val plugin = (if (currentFits) runCatching { loadBuild(id, current) }.getOrNull() else null)
                    ?: (if (fitsThisApp(id, previous)) runCatching { loadBuild(id, previous) }.getOrNull() else null)?.also {
                        if (currentFits) {
                            Log.w(TAG, "$id: build $current laadt niet, terug naar $previous")
                            markBad(id, current)
                            prefs.edit().putInt("current.$id", previous).apply()
                        }
                    }
                if (plugin == null && !currentFits) Log.i(TAG, "$id: build $current is voor plugin-API ${apiOf(id, current)}, deze app heeft $PROVIDER_API_VERSION")
                plugin?.let { loaded[id] = it }
            }
            hasUnloadedPlugins = installedIds().any { it !in loaded }

            // Debug: plugins rechtstreeks van het classpath hebben voorrang.
            BuildConfig.DEV_PLUGINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { cls ->
                runCatching { instantiate(Class.forName(cls)) }
                    .onSuccess { (provider, host) ->
                        loaded[provider.info.id] = LoadedPlugin(provider.info.id, provider.info.displayName, 0, "dev", provider, host, true)
                    }
                    .onFailure { Log.w(TAG, "dev-plugin $cls niet geladen", it) }
            }
            _plugins.value = loaded
        }
    }

    // ---------- Updates ----------

    suspend fun checkForUpdates(): List<UpdateOutcome> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val site = BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/"
            val base = site + "plugins/"
            val indexBytes = fetch(base + "index.json")
            val signature = fetch(base + "index.json.sig").toString(Charsets.UTF_8)
            check(PluginVerifier.verifyIndex(indexBytes, signature, BuildConfig.PLUGIN_PUBLIC_KEY)) {
                "handtekening van plugin-index ongeldig: update geweigerd"
            }

            val entries = PluginIndex.parse(indexBytes.toString(Charsets.UTF_8))
            saveCompatibility(PluginCompat.evaluate(entries, PROVIDER_API_VERSION, System.currentTimeMillis()))
            PluginCompat.installable(entries, PROVIDER_API_VERSION)
                .mapNotNull { entry -> runCatching { install(entry, site) }.getOrElse { UpdateOutcome.Failed(entry.id, it.message ?: it.toString()) } }
                .also { hasUnloadedPlugins = installedIds().any { it !in _plugins.value } }
        }
    }

    // ---------- Compatibiliteit ----------

    private fun saveCompatibility(c: Compatibility) {
        val json = JSONObject()
            .put("appApi", c.appApi)
            .put("requiredApi", c.requiredApi ?: JSONObject.NULL)
            .put("needsNewerApp", JSONArray(c.needsNewerApp))
            .put("awaitingPlugins", JSONArray(c.awaitingPlugins))
            .put("checkedAt", c.checkedAt)
        prefs.edit().putString("compatibility", json.toString()).apply()
        _compatibility.value = c
    }

    /** Bewaard oordeel; telt enkel als het voor deze app-versie (zelfde plugin-API) gemaakt is. */
    private fun loadCompatibility(): Compatibility? = runCatching {
        val o = JSONObject(prefs.getString("compatibility", null) ?: return null)
        fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        Compatibility(
            appApi = o.getInt("appApi"),
            requiredApi = if (o.isNull("requiredApi")) null else o.getInt("requiredApi"),
            needsNewerApp = list("needsNewerApp"),
            awaitingPlugins = list("awaitingPlugins"),
            checkedAt = o.optLong("checkedAt"),
        ).takeIf { it.appApi == PROVIDER_API_VERSION }
    }.getOrNull()

    /** [site] = root van de dist-site; `entry.file` is daar relatief aan ("plugins/<id>/<id>-<build>.jar"), net als de APK in app.json. */
    private suspend fun install(entry: PluginEntry, site: String): UpdateOutcome? {
        val current = prefs.getInt("current.${entry.id}", -1)
        if (entry.build <= current || isBad(entry.id, entry.build)) return null
        if (_plugins.value[entry.id]?.isDev == true) return null

        val bytes = fetch(site + entry.file.trimStart('/'))
        check(PluginVerifier.sha256Hex(bytes) == entry.sha256) { "sha256 van ${entry.file} klopt niet" }

        val target = jarFile(entry.id, entry.build)
        target.parentFile!!.mkdirs()
        if (target.exists()) { target.setWritable(true); target.delete() }
        val tmp = File(target.parentFile, "${entry.build}.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target)) { "kon ${target.name} niet wegschrijven" }
        target.setReadOnly() // Android 14+ weigert anders dynamisch geladen code

        prefs.edit().putString("entry.${entry.id}.${entry.build}", entry.entryClass)
            .putInt("api.${entry.id}.${entry.build}", entry.apiVersion)
            .putString("name.${entry.id}", entry.name)
            .putString("version.${entry.id}.${entry.build}", entry.version)
            .apply()

        val candidate = runCatching { loadBuild(entry.id, entry.build) }.getOrElse {
            markBad(entry.id, entry.build)
            return UpdateOutcome.RolledBack(entry.id, entry.version, "laden mislukt: ${it.message}")
        }

        val health = selfTest(candidate)
        saveHealth(health)
        // Terugrollen kan enkel naar een build die deze app kan laden; anders liever deze dan niets.
        if (!health.ok && fitsThisApp(entry.id, current)) {
            markBad(entry.id, entry.build)
            target.setWritable(true); target.delete()
            return UpdateOutcome.RolledBack(entry.id, entry.version, "${health.firstFailure?.name}: ${health.firstFailure?.detail}")
        }

        prefs.edit()
            .putInt("previous.${entry.id}", current)
            .putInt("current.${entry.id}", entry.build)
            .apply()
        cleanup(entry.id, keep = setOf(entry.build, current))
        _plugins.update { it + (entry.id to candidate) }
        return UpdateOutcome.Installed(entry.id, entry.version)
    }

    // ---------- Health ----------

    /**
     * Zonder login kunnen we enkel checken dat de plugin laadt en categorieën heeft;
     * met login draait de volledige keten tot en met het manifest.
     */
    suspend fun selfTest(plugin: LoadedPlugin): SelfTestResult {
        val p = plugin.provider
        // Zonder login (geen wachtwoord bewaard, of nog nooit met code ingelogd) valt er niets verder te testen.
        val canLogin = when (p.info.loginMethod) {
            LoginMethod.PASSWORD -> plugin.host.credentials() != null
            LoginMethod.DEVICE_CODE -> runCatching { p.isLoggedIn() }.getOrDefault(true)
        }
        if (p.info.requiresLogin && !canLogin) {
            val ok = p.nativeCategories().isNotEmpty()
            return SelfTestResult(p.info.id, listOf(SelfTestStep("load", ok, if (ok) "geladen (geen login om verder te testen)" else "geen categorieën")))
        }
        // Geen expliciete login: de stap "login" (isLoggedIn) vernieuwt of logt zelf stil in.
        return ProviderSelfTest.run(p, plugin.host.http)
    }

    fun saveHealth(result: SelfTestResult) {
        prefs.edit().putString("health.${result.providerId}", result.toJson())
            .putLong("health.${result.providerId}.at", System.currentTimeMillis()).apply()
    }

    fun lastHealth(id: String): String? = prefs.getString("health.$id", null)

    // ---------- intern ----------

    private fun loadBuild(id: String, build: Int): LoadedPlugin {
        require(build >= 0) { "geen build" }
        val file = jarFile(id, build)
        require(file.exists()) { "${file.name} ontbreekt" }
        val entryClass = prefs.getString("entry.$id.$build", null) ?: error("entryClass onbekend voor $id/$build")
        val loader = DexClassLoader(file.absolutePath, null, null, PluginManager::class.java.classLoader)
        val (provider, host) = instantiate(loader.loadClass(entryClass))
        check(provider.info.id == id) { "plugin meldt id ${provider.info.id}, verwacht $id" }
        return LoadedPlugin(
            id = id,
            name = prefs.getString("name.$id", provider.info.displayName)!!,
            build = build,
            version = prefs.getString("version.$id.$build", build.toString())!!,
            provider = provider,
            host = host,
            isDev = false,
        )
    }

    private fun instantiate(cls: Class<*>): Pair<StreamingProvider, HostServices> {
        val provider = cls.getDeclaredConstructor().newInstance() as StreamingProvider
        val host = hostFactory(provider.info.id)
        provider.attach(host)
        return provider to host
    }

    private fun fetch(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} voor $url" }
            r.body!!.bytes()
        }

    private fun installedIds(): Set<String> =
        prefs.all.keys.filter { it.startsWith("current.") }.map { it.removePrefix("current.") }.toSet()

    private fun jarFile(id: String, build: Int) = File(dir, "$id/$build.jar")

    /** Plugin-API van een geïnstalleerde build; -1 = geïnstalleerd vóór dit bijgehouden werd (plugin-API ≤ 4). */
    private fun apiOf(id: String, build: Int) = prefs.getInt("api.$id.$build", -1)

    private fun fitsThisApp(id: String, build: Int) = build >= 0 && apiOf(id, build) == PROVIDER_API_VERSION

    private fun isBad(id: String, build: Int) = prefs.getStringSet("bad.$id", emptySet())!!.contains(build.toString())

    private fun markBad(id: String, build: Int) {
        val set = prefs.getStringSet("bad.$id", emptySet())!!.toMutableSet().apply { add(build.toString()) }
        prefs.edit().putStringSet("bad.$id", set).apply()
    }

    private fun cleanup(id: String, keep: Set<Int>) {
        File(dir, id).listFiles()?.forEach { f ->
            val build = f.nameWithoutExtension.toIntOrNull()
            if (build != null && build !in keep) { f.setWritable(true); f.delete() }
        }
    }

    companion object { private const val TAG = "PluginManager" }
}
