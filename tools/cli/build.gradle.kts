plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("com.silverbp.cli.MainKt")
}

kotlin {
    // litertlm-jvm requires Java 21 (class file version 65.0).
    jvmToolchain(21)
}

dependencies {
    implementation(libs.litertlm.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android) // brings JVM coroutines
}

// Reuse the small pure-JVM recognition files (Prompt, Parser, ExtractedReading)
// straight from the Android module so they stay in lock-step.
sourceSets {
    main {
        kotlin.srcDirs(
            "../../app/src/main/java/com/silverbp/android/recognition"
        )
        // Filter to only the JVM-safe files (the full directory pulls in Bitmap-using files).
        kotlin.exclude(
            "**/GemmaBpService.kt",
            "**/ImagePreprocess.kt",
            "**/ModelDownloader.kt",
            "**/ModelLoadStatus.kt",
            "**/ModelBootstrap.kt",
        )
    }
}

// Pass-through args so we can do `./gradlew :cli:run --args="--model X --image Y"`
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
