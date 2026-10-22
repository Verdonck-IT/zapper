plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly: zit in de host-APK (provider-api, coroutines) of in Android zelf (org.json).
    compileOnly(project(":provider-api"))
    compileOnly(libs.org.json)
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(project(":plugin-testkit"))
}

tasks.test {
    useJUnitPlatform()
    // Credentials komen uit de omgeving (CI-secrets of lokaal geëxporteerd).
    environment("PLAY_USERNAME", System.getenv("PLAY_USERNAME") ?: "")
    environment("PLAY_PASSWORD", System.getenv("PLAY_PASSWORD") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
