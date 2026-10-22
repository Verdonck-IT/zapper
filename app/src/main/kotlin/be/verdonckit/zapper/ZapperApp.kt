package be.verdonckit.zapper

import android.app.Application
import be.verdonckit.zapper.core.AndroidHostServices
import be.verdonckit.zapper.core.CredentialStore
import be.verdonckit.zapper.core.OkHttpHostHttp
import be.verdonckit.zapper.core.SecureStore
import be.verdonckit.zapper.data.CatalogRepository
import be.verdonckit.zapper.data.CategoryMapper
import be.verdonckit.zapper.data.MyListSync
import be.verdonckit.zapper.data.ProgressSync
import be.verdonckit.zapper.data.ZapperDatabase
import be.verdonckit.zapper.plugin.PluginManager
import be.verdonckit.zapper.sync.MaintenanceWorker
import be.verdonckit.zapper.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Handmatige DI: alles wat app-breed één keer moet bestaan. */
class AppGraph(app: Application) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val db = ZapperDatabase.create(app)
    val dao = db.catalog()
    val categoryMapper = CategoryMapper(app)
    private val tokenStore = SecureStore(app, "tokens")
    val credentials = CredentialStore(SecureStore(app, "credentials"))

    val plugins = PluginManager(app, okHttp) { providerId ->
        AndroidHostServices(
            providerId = providerId,
            http = OkHttpHostHttp(okHttp),
            storage = tokenStore.namespace(providerId),
            credentialStore = credentials,
        )
    }

    val repository = CatalogRepository(dao, categoryMapper)
    val progressSync = ProgressSync(dao, plugins)
    val myListSync = MyListSync(dao, plugins)

    /** Aflevering die van de reeks naar de afleveringspagina doorgegeven wordt (sleutel: "<itemKey>|<playableId>"). */
    val episodeSelection = java.util.concurrent.ConcurrentHashMap<String, be.verdonckit.zapper.api.Episode>()
    val appUpdater = AppUpdater(app, okHttp)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class ZapperApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            graph.plugins.loadInstalled()
            graph.progressSync.refresh(force = true)
            graph.myListSync.refresh(force = true)
            // Eerste start of lege index: meteen updaten + syncen, daarna periodiek.
            MaintenanceWorker.runNow(this@ZapperApp, onlyIfStale = true)
        }
        MaintenanceWorker.schedule(this)
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ZapperApp).graph
