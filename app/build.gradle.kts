plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val updateBaseUrl = (findProperty("zapperUpdateBaseUrl") as String?) ?: "https://CHANGE-ME.github.io/zapper-dist/"

android {
    namespace = "be.verdonckit.zapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.verdonckit.zapper"
        minSdk = 26
        targetSdk = 35
        // In CI overschreven met het run-nummer zodat de auto-updater versies kan vergelijken.
        versionCode = (findProperty("zapperVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("zapperVersionName") as String?) ?: "0.1.0-dev"

        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        // Publieke sleutel (X.509/DER, base64) waarmee plugins/index.json gesigneerd is.
        // Genereer met tools/signing/keygen.py en zet in ~/.gradle/gradle.properties.
        buildConfigField("String", "PLUGIN_PUBLIC_KEY", "\"${findProperty("zapperPluginPublicKey") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            // Altijd met dezelfde sleutel signeren: Android weigert anders de update over de bestaande app.
            val ks = findProperty("zapperKeystore") as String?
            if (ks != null) {
                storeFile = file(ks)
                storePassword = findProperty("zapperKeystorePassword") as String?
                keyAlias = findProperty("zapperKeyAlias") as String?
                keyPassword = findProperty("zapperKeyPassword") as String?
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // In debug worden plugins rechtstreeks van het classpath geladen (geen DEX/download nodig).
            buildConfigField(
                "String",
                "DEV_PLUGINS",
                "\"be.verdonckit.zapper.plugins.vrtmax.VrtMaxProvider,be.verdonckit.zapper.plugins.play.PlayProvider\"",
            )
        }
        release {
            isMinifyEnabled = false // R8 zou provider-api-klassen strippen die enkel plugins gebruiken
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "DEV_PLUGINS", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Elke dev-plugin draagt een eigen manifest; enkel CI (plugins.yml) leest het, uit de bronmap.
        resources.excludes += "zapper-plugin.properties"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":provider-api"))
    debugImplementation(project(":plugins:vrtmax"))
    debugImplementation(project(":plugins:play"))

    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
