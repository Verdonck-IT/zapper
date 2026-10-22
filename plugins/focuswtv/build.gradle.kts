plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // compileOnly: zit in de host-APK (provider-api).
    compileOnly(project(":provider-api"))

    testImplementation(project(":plugin-testkit"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
