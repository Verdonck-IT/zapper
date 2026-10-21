plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Elke module onder :plugins:* krijgt een taak die de compile-classpath klaarzet
// voor d8 (DEX-conversie in CI). Zie .github/workflows/plugins.yml.
subprojects {
    if (path.startsWith(":plugins:")) {
        afterEvaluate {
            tasks.register<Copy>("dexClasspath") {
                description = "Kopieert de compile-classpath (provider-api, kotlin-stdlib, org.json) voor d8."
                from(configurations.getByName("compileClasspath"))
                into(layout.buildDirectory.dir("dex-classpath"))
            }
        }
    }
}
