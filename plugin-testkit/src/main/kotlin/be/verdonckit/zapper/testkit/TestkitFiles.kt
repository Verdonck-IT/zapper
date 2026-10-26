package be.verdonckit.zapper.testkit

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.KeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * Lokale bestanden van de testkit, buiten de repo (nooit committen):
 *
 *  - `credentials.properties` – `<PREFIX>_USERNAME` / `<PREFIX>_PASSWORD`, als terugval voor de env-variabelen
 *  - `<prefix>.properties`     – tokens en login-pauze van die provider, zoals host.storage in de app
 *
 * Standaard in `~/.zapper/testkit/`; ander pad via env `ZAPPER_TESTKIT_DIR`.
 * Zo overleven tokens `gradlew clean` en de nachtelijke runs op de runner thuis,
 * en logt een test enkel met wachtwoord in als het refresh token niet meer werkt.
 */
object TestkitFiles {

    val dir: File
        get() = System.getenv("ZAPPER_TESTKIT_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".zapper/testkit")

    /** Env-variabelen gaan voor; anders `credentials.properties`. */
    fun credentials(envPrefix: String): Credentials? {
        val user = System.getenv("${envPrefix}_USERNAME")
        val pass = System.getenv("${envPrefix}_PASSWORD")
        if (!user.isNullOrBlank() && !pass.isNullOrBlank()) return Credentials(user, pass)

        val file = File(dir, "credentials.properties")
        if (!file.isFile) return null
        val props = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
        val fUser = props.getProperty("${envPrefix}_USERNAME")?.trim()
        val fPass = props.getProperty("${envPrefix}_PASSWORD")
        return if (!fUser.isNullOrBlank() && !fPass.isNullOrBlank()) Credentials(fUser, fPass) else null
    }

    /** Tokenopslag voor één provider; gewist als de gebruikersnaam wijzigt. */
    fun tokenStore(envPrefix: String, username: String): KeyValueStore =
        FileKeyValueStore(File(dir, "${envPrefix.lowercase()}.properties")).also { store ->
            if (store.get(OWNER) != username) {
                store.clear()
                store.put(OWNER, username)
            }
        }

    private const val OWNER = "testkit.username"
}

/** KeyValueStore in een properties-bestand; elke wijziging wordt meteen (atomair) weggeschreven. */
class FileKeyValueStore(private val file: File) : KeyValueStore {

    private val props = Properties().apply {
        if (file.isFile) file.reader(Charsets.UTF_8).use(::load)
    }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)

    @Synchronized override fun put(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    @Synchronized override fun remove(key: String) {
        if (props.remove(key) != null) save()
    }

    @Synchronized override fun clear() {
        props.clear()
        save()
    }

    private fun save() {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writer(Charsets.UTF_8).use { props.store(it, "Zapper testkit: tokens, niet delen of committen") }
        restrictToOwner(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Enkel leesbaar voor de eigenaar (POSIX); op Windows is het gebruikersprofiel al afgeschermd. */
    private fun restrictToOwner(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }
}
