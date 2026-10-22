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
    useJUnitPlatform {
        // De live test hangt af van focus-wtv.be: in gewone CI overslaan, in de nachtelijke check (LIVE_TESTS=1) wel.
        if (System.getenv("CI") != null && System.getenv("LIVE_TESTS") == null) excludeTags("live")
    }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
