plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly: zit in de host-APK (provider-api) of in Android zelf (org.json).
    // De plugin-jar bevat zo enkel de eigen klassen en blijft een paar tientallen KB.
    compileOnly(project(":provider-api"))
    compileOnly(libs.org.json)

    testImplementation(project(":plugin-testkit"))
}

tasks.test {
    useJUnitPlatform()
    // Credentials komen uit de omgeving (CI-secrets of lokaal geëxporteerd).
    environment("VRTMAX_USERNAME", System.getenv("VRTMAX_USERNAME") ?: "")
    environment("VRTMAX_PASSWORD", System.getenv("VRTMAX_PASSWORD") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
