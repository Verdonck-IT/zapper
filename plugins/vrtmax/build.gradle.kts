plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Gedeelde plugin-code (LoginGuard) als bron meecompileren, in het eigen package: zo hoeft provider-api
// niet te wijzigen en botst ze niet met andere plugins (de debug-app laadt ze allemaal samen).
val sharedSources = tasks.register<Copy>("sharedSources") {
    from(rootProject.file("plugin-shared/src/main/kotlin"))
    into(layout.buildDirectory.dir("generated/shared"))
    filter { it.replace("be.verdonckit.zapper.plugins.shared", "be.verdonckit.zapper.plugins.vrtmax.shared") }
}

kotlin {
    jvmToolchain(17)
    sourceSets.main { kotlin.srcDir(sharedSources) }
}

dependencies {
    // compileOnly: zit in de host-APK (provider-api, coroutines) of in Android zelf (org.json).
    // De plugin-jar bevat zo enkel de eigen klassen en blijft een paar tientallen KB.
    compileOnly(project(":provider-api"))
    compileOnly(libs.org.json)
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(project(":plugin-testkit"))
}

tasks.test {
    useJUnitPlatform()
    // Credentials komen uit de omgeving (CI-secrets of lokaal geëxporteerd).
    environment("VRTMAX_USERNAME", System.getenv("VRTMAX_USERNAME") ?: "")
    environment("VRTMAX_PASSWORD", System.getenv("VRTMAX_PASSWORD") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
