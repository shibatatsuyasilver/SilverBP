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
// (do NOT commit). Release artifacts fail fast when any signing value is missing.
val keystorePath: String? = localProps.getProperty("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val keystorePass: String? = localProps.getProperty("KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
val keystoreAlias: String? = localProps.getProperty("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val keystoreKeyPass: String? = localProps.getProperty("KEY_PASS")?.takeIf { it.isNotBlank() }
val hasReleaseSigning: Boolean =
    keystorePath != null && keystorePass != null && keystoreAlias != null && keystoreKeyPass != null

// Hosted via GitHub Pages from /docs in this repo.
val privacyPolicyUrl: String = "https://shibatatsuyasilver.github.io/SilverBP/privacy.html"
val termsPolicyUrl: String = "https://shibatatsuyasilver.github.io/SilverBP/terms.html"

android {
    namespace = "com.silverbp.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.silverbp.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 8
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources.localeFilters += listOf("en", "zh-rTW")

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
        buildConfigField("String", "TERMS_POLICY_URL", "\"$termsPolicyUrl\"")

        // Debug/beta stays unlocked for local testing; release overrides this to
        // true so paid gates are enforced in production artifacts.
        buildConfigField("boolean", "PREMIUM_ENFORCED", "false")

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
            buildConfigField("boolean", "PREMIUM_ENFORCED", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    testOptions {
        // Let JVM unit tests call android.util.Log etc. without "not mocked"
        // crashes (e.g. SessionCheckpointStore logs on its swallowed error paths).
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Bundle the exported Room schema JSONs as androidTest assets so
    // MigrationTestHelper (RoomMigrationTest) can validate each migration.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // The Expressive theme APIs are `internal` in the material3 build this BOM
        // resolves, so we implement the Expressive look with stable M3 (custom
        // Shapes/Typography/colors + manual spring motion). This project-wide opt-in
        // just spares the scattered @OptIn(ExperimentalMaterial3Api) for TopAppBar etc.
        freeCompilerArgs.add(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
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

    // Google auth + Drive auto-backup (encrypted .sbpbk → Drive appDataFolder).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // Play Billing (subscriptions). Degrades gracefully when no products are
    // configured (emulator) — see BillingClientWrapper.
    implementation(libs.play.billing.ktx)

    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests (android.jar's is stubbed under
    // unitTests.isReturnDefaultValues=true) so JSON-building/parsing code
    // (e.g. GoogleDriveBackupClient) can be exercised in plain unit tests.
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Release artifacts must ship a real signing key and Maps key. Checked only
// when a release artifact is actually assembled, so a fresh clone can still
// build debug/test. See RELEASE.md for setup details.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Add KEYSTORE_PATH, KEYSTORE_PASS, " +
                    "KEY_ALIAS and KEY_PASS to local.properties before building release artifacts.",
            )
        }
        if (mapsApiKey.isBlank()) {
            throw GradleException(
                "MAPS_API_KEY is missing in local.properties — a release build would ship a blank " +
                    "exercise map. Add it (restricted to the release SHA-1 + applicationId " +
                    "com.silverbp.android) before building. See RELEASE.md.",
            )
        }
    }
}
