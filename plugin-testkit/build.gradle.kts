plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":provider-api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.org.json)
    api(libs.junit.jupiter)
}
