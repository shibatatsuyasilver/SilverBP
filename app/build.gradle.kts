import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY") ?: ""

// Release upload key. Generate locally with:
//   keytool -genkey -v -keystore ~/keystores/silverbp-upload.jks \
//     -alias upload -keyalg RSA -keysize 2048 -validity 10000
// Then add KEYSTORE_PATH / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS to local.properties
// (do NOT commit). When any value is missing the release config falls back to the
// debug key, so a fresh clone can still build a debug APK.
val keystorePath: String? = localProps.getProperty("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val keystorePass: String? = localProps.getProperty("KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
val keystoreAlias: String? = localProps.getProperty("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val keystoreKeyPass: String? = localProps.getProperty("KEY_PASS")?.takeIf { it.isNotBlank() }
val hasReleaseSigning: Boolean =
    keystorePath != null && keystorePass != null && keystoreAlias != null && keystoreKeyPass != null

// Hosted via GitHub Pages from /docs in this repo.
val privacyPolicyUrl: String = "https://shibatatsuyasilver.github.io/SilverBP/privacy.html"

android {
    namespace = "com.silverbp.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.silverbp.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources.localeFilters += listOf("en", "zh-rTW")

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // else: falls back to the debug signing config so a fresh clone
            // without a keystore can still produce an installable release APK.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // At-rest encryption + biometric app-lock (opt-in). security-crypto holds
    // the Keystore-wrapped DB passphrase; sqlcipher-android encrypts Room.
    implementation(libs.androidx.security.crypto)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)

    // Argon2id KDF for the recovery-code KEK in encrypted backup snapshots.
    implementation(libs.argon2kt)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.litertlm.android)
    // tasks-vision still pulled in for MPImage / future MLKit rectangle detection
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.mlkit.objectDetection)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    // ML Kit GenAI Prompt API — Gemini Nano via AICore (Pixel 9/10 series).
    // Used by AICoreBpRecognizer; gracefully unavailable on non-Pixel devices.
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.androidx.health.connect.client)

    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
