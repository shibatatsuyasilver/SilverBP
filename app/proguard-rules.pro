# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers in stack traces and hide source-file names so de-obfuscated
# crashes from Play Console map cleanly back to source via mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * { @androidx.room.* <methods>; }

# ---- kotlinx.serialization ----
# Keep generated serializer companions and the @Serializable classes themselves.
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * {
    *;
}

# ---- MediaPipe / LiteRT-LM (on-device LLM + vision) ----
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.ai.edge.**

# ---- ML Kit GenAI / object detection / barcode ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- Health Connect (defensive — androidx ships consumer rules) ----
-keep class androidx.health.connect.** { *; }
-dontwarn androidx.health.connect.**

# ---- LAN sync module (Noise XK / NSD; uses reflection on serializable types) ----
-keep class com.silverbp.android.sync.** { *; }

# ---- ZXing / barcode ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- OkHttp / Okio (HTTPS for model downloads) ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---- Google Maps SDK / Play Services Location ----
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.**

# ---- Vico charts ----
-keep class com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# ---- Coil image loader ----
-dontwarn coil.**

# ---- SQLCipher (net.zetetic sqlcipher-android) ----
# JNI-bound classes; R8 must not rename/strip them or the native bridge breaks.
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# ---- AndroidX Biometric ----
-dontwarn androidx.biometric.**
