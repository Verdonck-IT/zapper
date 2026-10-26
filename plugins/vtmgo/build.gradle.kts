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
    // Login met code: eenmalig interactief (ZAPPER_DEVICE_LOGIN=1), daarna het bewaarde refresh token.
    environment("ZAPPER_DEVICE_LOGIN", System.getenv("ZAPPER_DEVICE_LOGIN") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
