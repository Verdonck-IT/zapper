package be.verdonckit.zapper.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.plugin.UpdateOutcome
import be.verdonckit.zapper.sync.MaintenanceWorker
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
    val username: String?,
    val health: String?,
)

data class SettingsState(
    val providers: List<ProviderStatus> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

class SettingsViewModel(private val app: Application, private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val statuses = graph.plugins.plugins.value.values.map { p ->
            ProviderStatus(
                id = p.id,
                name = p.name,
                version = if (p.isDev) "dev" else p.version,
                isDev = p.isDev,
                loggedIn = runCatching { p.provider.isLoggedIn() }.getOrNull(),
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
