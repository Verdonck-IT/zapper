package be.verdonckit.zapper.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.LoginMethod
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.plugin.Compatibility
import be.verdonckit.zapper.plugin.UpdateOutcome
import be.verdonckit.zapper.update.AppUpdater
import be.verdonckit.zapper.sync.MaintenanceWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ProviderStatus(
    val id: String,
    val name: String,
    val version: String,
    val isDev: Boolean,
    val loggedIn: Boolean?,
    val requiresLogin: Boolean = true,
    val loginMethod: LoginMethod = LoginMethod.PASSWORD,
    /** Sessie kon niet gecontroleerd worden: offline, throttling of login-pauze. Wordt later opnieuw geprobeerd. */
    val offline: Boolean = false,
    val username: String?,
    val health: String?,
)

data class SettingsState(
    val providers: List<ProviderStatus> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
    /** Lopende login met code: toon de code tot ze bevestigd is. */
    val deviceLogin: DeviceLoginUi? = null,
    /** Oordeel over de plugin-index: verplichte app-update of wachten op plugins. */
    val compatibility: Compatibility? = null,
    /** Gepubliceerde app-versie die de gevraagde plugin-API heeft (enkel gezocht bij een verplichte update). */
    val appUpdate: AppUpdater.Available? = null,
    /** Er is een app-versie gepubliceerd, maar ze heeft de gevraagde plugin-API nog niet. */
    val appUpdateTooOld: Boolean = false,
)

data class DeviceLoginUi(
    val providerId: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMillis: Long,
)

class SettingsViewModel(private val app: Application, private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            graph.plugins.compatibility.collect { compat ->
                _state.update { it.copy(compatibility = compat) }
                if (compat?.updateRequired == true) findAppUpdate(compat)
            }
        }
    }

    /** Is er al een app-versie met de plugin-API die de plugins vragen? */
    private suspend fun findAppUpdate(compat: Compatibility) {
        val required = compat.requiredApi ?: return
        val available = runCatching { graph.appUpdater.check() }.getOrNull()
        // Zonder providerApiVersion (oudere app.json) gaan we ervan uit dat een nieuwere APK past.
        val fits = available != null && (available.providerApiVersion ?: required) >= required
        _state.update { it.copy(appUpdate = available?.takeIf { fits }, appUpdateTooOld = available != null && !fits) }
    }

    fun refresh() = viewModelScope.launch {
        val statuses = graph.plugins.plugins.value.values.map { p ->
            val loginState = runCatching { p.provider.isLoggedIn() }
            ProviderStatus(
                id = p.id,
                name = p.name,
                version = if (p.isDev) "dev" else p.version,
                isDev = p.isDev,
                loggedIn = loginState.getOrNull(),
                offline = (loginState.exceptionOrNull() as? ProviderException)?.kind == ErrorKind.NETWORK,
                requiresLogin = p.provider.info.requiresLogin,
                loginMethod = p.provider.info.loginMethod,
                username = graph.credentials.get(p.id)?.username,
                health = graph.plugins.lastHealth(p.id)?.let(::summarize),
            )
        }
        _state.update { it.copy(providers = statuses) }
    }

    fun login(id: String, username: String, password: String) = busy("Inloggen bij $id…") {
        val plugin = graph.plugins.get(id) ?: return@busy "Plugin $id niet geladen"
        val creds = Credentials(username.trim(), password)
        when (val r = plugin.provider.login(creds)) {
            is LoginResult.Success -> {
                graph.credentials.put(id, creds)
                MaintenanceWorker.runNow(app)
                "Ingelogd bij ${plugin.name}. De catalogus wordt nu opgebouwd."
            }
            is LoginResult.Failure -> "Login mislukt (${r.reason}): ${r.message}"
        }
    }

    private var deviceJob: Job? = null

    /** Login met code: code tonen, dan pollen tot de gebruiker ze op een ander toestel bevestigt of ze verloopt. */
    fun startDeviceLogin(id: String) {
        deviceJob?.cancel()
        deviceJob = viewModelScope.launch {
            val plugin = graph.plugins.get(id) ?: return@launch
            val code = runCatching { plugin.provider.startDeviceLogin() }.getOrElse {
                _state.update { s -> s.copy(message = "Code vragen bij ${plugin.name} mislukt: ${it.message}") }
                return@launch
            }
            val expiresAt = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            _state.update { it.copy(deviceLogin = DeviceLoginUi(id, code.userCode, code.verificationUrl, expiresAt), message = null) }
            var outcome = "De code is verlopen. Vraag een nieuwe code."
            while (System.currentTimeMillis() < expiresAt) {
                delay(code.pollIntervalSeconds * 1000L)
                when (val r = runCatching { plugin.provider.pollDeviceLogin() }.getOrNull()) {
                    null -> continue
                    is LoginResult.Success -> {
                        MaintenanceWorker.runNow(app)
                        outcome = "Ingelogd bij ${plugin.name}. De catalogus wordt nu opgebouwd."
                    }
                    is LoginResult.Failure -> outcome = "Login mislukt: ${r.message}"
                }
                break
            }
            _state.update { it.copy(deviceLogin = null, message = outcome) }
            refresh()
        }
    }

    fun cancelDeviceLogin() {
        deviceJob?.cancel()
        _state.update { it.copy(deviceLogin = null) }
    }

    fun logout(id: String) = busy("Uitloggen…") {
        graph.plugins.get(id)?.provider?.logout()
        graph.credentials.clear(id)
        "Uitgelogd."
    }

    fun updatePlugins() = busy("Plugins controleren…") {
        val outcomes = graph.plugins.checkForUpdates()
        if (outcomes.isEmpty()) "Alle plugins zijn up-to-date." else outcomes.joinToString("\n") {
            when (it) {
                is UpdateOutcome.Installed -> "${it.id}: ${it.version} geïnstalleerd"
                is UpdateOutcome.RolledBack -> "${it.id}: ${it.version} teruggedraaid – ${it.reason}"
                is UpdateOutcome.Failed -> "${it.id}: ${it.reason}"
            }
        }
    }

    fun syncNow() = busy("Synchroniseren…") {
        MaintenanceWorker.runNow(app)
        "Synchronisatie gestart op de achtergrond."
    }

    fun healthCheck(id: String) = busy("Test $id…") {
        val plugin = graph.plugins.get(id) ?: return@busy "Plugin $id niet geladen"
        val result = graph.plugins.selfTest(plugin)
        graph.plugins.saveHealth(result)
        result.steps.joinToString("\n") { "${if (it.ok) "✔" else "✘"} ${it.name}: ${it.detail}" }
    }

    fun checkAppUpdate() = busy("Zoeken naar app-update…") {
        val update = graph.appUpdater.check() ?: return@busy "De app is up-to-date."
        graph.appUpdater.install(update)
        "Versie ${update.versionName} gedownload; bevestig de installatie."
    }

    private fun busy(label: String, block: suspend () -> String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = label) }
        val msg = runCatching { block() }.getOrElse { "Fout: ${it.message}" }
        _state.update { it.copy(busy = false, message = msg) }
        refresh()
    }

    private fun summarize(json: String): String = runCatching {
        val o = JSONObject(json)
        val steps = o.getJSONArray("steps")
        if (o.getBoolean("ok")) {
            "Laatste test OK (${steps.length()} stappen)"
        } else {
            val fail = (0 until steps.length()).map(steps::getJSONObject).first { !it.getBoolean("ok") }
            "Laatste test faalde bij '${fail.getString("name")}': ${fail.getString("detail")}"
        }
    }.getOrDefault("onbekend")
}
