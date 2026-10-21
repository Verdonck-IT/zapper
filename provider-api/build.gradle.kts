plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// Bewust géén dependencies: deze module zit in de host-APK én is het enige
// contract dat plugins zien. Alles wat hier bijkomt, moet de host meeleveren.
