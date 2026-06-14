package com.silverbp.android.recognition

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * End-to-end image harness for the blood-glucose photo recognizer, validated
 * against REAL meter photos (Roche/Accu-Chek, OneTouch, Contour, FreeStyle, …)
 * shipped as androidTest fixtures under `assets/glucose_meters/`.
 *
 * WHY this is an instrumented test that is NOT expected to run in CI:
 *  - The on-device LiteRT Gemma path CANNOT run on a CI emulator (no GPU/large
 *    model), so this harness forces the CLOUD [GeminiCloudGlucoseRecognizer].
 *  - The cloud path needs a Gemini API key + network. When either is missing the
 *    test SKIPs via JUnit [assumeTrue] (Assume), it never FAILS — so it is safe
 *    to leave in the androidTest source set: it must only COMPILE in CI
 *    (`./gradlew :app:compileDebugAndroidTestKotlin`), and is exercised manually
 *    on a real device / connected emulator with a key.
 *  - The fixtures dir may legitimately be empty on a fresh checkout (test images
 *    have copyright constraints — see `assets/glucose_meters/SOURCES.md`), so the
 *    harness also SKIPs (never fails) when no images / no manifest are present.
 *
 * HOW TO RUN (real device or connected emulator with internet):
 * ```
 * export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.silverbp.android.recognition.GlucoseRecognitionImageTest \
 *     -Pandroid.testInstrumentationRunnerArguments.geminiKey=AIza...your-key...
 * ```
 * The per-image PASS/FAIL/SKIP lines and the aggregate pass-rate are written to
 * logcat under the tag "GlucoseImageTest" (filter: `adb logcat -s GlucoseImageTest`).
 * Optionally override the model with `-P...geminiModel=gemini-2.5-pro`.
 *
 * MANIFEST FORMAT — `assets/glucose_meters/expected.json` (provided by the
 * fixture/images track), a JSON ARRAY of entries each carrying its image `file`:
 * ```
 * [
 *   {
 *     "file": "glucose_relion_prime_544mgdl.jpg",
 *     "valueMgdl": 544.0,          // ground-truth in mg/dL (preferred), OR …
 *     "displayValue": "544",       // … the value AS SHOWN on the meter (string), with …
 *     "unit": "mgdl",              // … its unit ("mgdl" | "mmol" | null)
 *     "measureContext": null,      // optional; "fasting"|"before_meal"|… or null
 *     "toleranceMgdl": 5.0,        // optional per-image tolerance (default 6 mg/dL)
 *     "note": "ReliOn Prime, mg/dL"
 *   },
 *   { "file": "glucose_lo_warning_nonnumeric.jpg", "valueMgdl": null, "unit": null }
 * ]
 * ```
 * Each entry needs a `file`; `valueMgdl` is the ground truth in mg/dL (mmol entries
 * are pre-converted via the canonical 18.016 factor). An entry whose `valueMgdl` is
 * null (e.g. a "Lo"/"HI" word) asserts the recognizer returns a NULL value.
 */
@RunWith(AndroidJUnit4::class)
class GlucoseRecognitionImageTest {

    private companion object {
        const val TAG = "GlucoseImageTest"
        const val ASSET_DIR = "glucose_meters"
        const val MANIFEST = "expected.json"

        /** 1 mmol/L = 18.016 mg/dL (matches GlucosePrompt / domain conversion). */
        const val MMOL_TO_MGDL = 18.016

        /** Default match window in mg/dL when the manifest entry omits one. */
        const val DEFAULT_TOLERANCE_MGDL = 6.0
    }

    /** One manifest entry: ground-truth + optional per-image expectations. */
    @Serializable
    private data class Expected(
        /** Image filename this entry describes (key into `assets/glucose_meters/`). */
        val file: String = "",
        /** Ground-truth value in mg/dL (mmol entries are pre-converted ×18.016); null = no numeric reading. */
        val valueMgdl: Double? = null,
        /** Human-readable value AS SHOWN on the meter (e.g. "5.3", "544", "Lo") — for logs only. */
        val displayValue: String? = null,
        val unit: String? = null,
        val measureContext: String? = null,
        val toleranceMgdl: Double? = null,
        val note: String? = null,
    )

    @Test
    fun recognizesRealMeterPhotos_withinTolerance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val assets = instrumentation.context.assets

        // 1) Need a Gemini key (emulator can't run local Gemma). Missing → SKIP.
        val apiKey = args.getString("geminiKey").orEmpty()
        assumeTrue("No 'geminiKey' instrumentation arg — skipping cloud image harness", apiKey.isNotBlank())
        val modelId = args.getString("geminiModel").orEmpty()
            .ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL }

        // 2) Need fixtures + a manifest. Empty/absent dir or manifest → SKIP (never fail):
        //    the images carry copyright constraints and may be absent on a checkout.
        val imageFiles = runCatching { assets.list(ASSET_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .sorted()
        assumeTrue("No images in assets/$ASSET_DIR — skipping image harness", imageFiles.isNotEmpty())

        val manifestRaw = runCatching {
            assets.open("$ASSET_DIR/$MANIFEST").bufferedReader().use { it.readText() }
        }.getOrNull()
        assumeTrue("No $ASSET_DIR/$MANIFEST manifest — skipping image harness", manifestRaw != null)

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        // The manifest is a JSON ARRAY of entries that each carry their own `file`;
        // key it by filename for per-image lookup.
        val manifest: Map<String, Expected> = runCatching {
            json.decodeFromString<List<Expected>>(manifestRaw!!)
                .filter { it.file.isNotBlank() }
                .associateBy { it.file }
        }.getOrDefault(emptyMap())
        assumeTrue("Manifest $MANIFEST has no usable entries — skipping", manifest.isNotEmpty())

        val recognizer = GeminiCloudGlucoseRecognizer(apiKey = apiKey, modelId = modelId)

        var attempted = 0
        var passed = 0
        val failures = mutableListOf<String>()

        for (name in imageFiles) {
            val expected = manifest[name]
            if (expected == null) {
                android.util.Log.w(TAG, "SKIP  $name — no manifest entry")
                continue
            }
            // A null ground-truth value is a deliberate EDGE CASE (e.g. a "Lo"/"HI"
            // warning word, not a number): the recognizer MUST return value == null.
            val expectsNullValue = expected.valueMgdl == null
            val truthMgdl = expected.valueMgdl
            val tolerance = expected.toleranceMgdl ?: DEFAULT_TOLERANCE_MGDL

            val bitmap = assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                android.util.Log.w(TAG, "SKIP  $name — could not decode bitmap")
                continue
            }

            attempted++
            val result = runCatching { recognizer.extract(bitmap) }
            val extracted = result.getOrNull()
            if (extracted == null) {
                val msg = "FAIL  $name — extract threw ${result.exceptionOrNull()?.javaClass?.simpleName}: " +
                    "${result.exceptionOrNull()?.message}"
                android.util.Log.e(TAG, msg)
                failures += msg
                continue
            }

            val readValue = extracted.value

            // Edge case: meter shows a non-numeric word → expect a null read.
            if (expectsNullValue) {
                if (readValue == null) {
                    passed++
                    android.util.Log.i(TAG, "PASS  $name — non-numeric edge case, recognizer returned null value")
                } else {
                    val msg = "FAIL  $name — expected null value (non-numeric '${expected.displayValue}') " +
                        "but recognizer read $readValue${extracted.unit ?: ""}"
                    android.util.Log.e(TAG, msg)
                    failures += msg
                }
                continue
            }

            if (readValue == null) {
                val msg = "FAIL  $name — recognizer returned null value (conf=${extracted.confidence})"
                android.util.Log.e(TAG, msg)
                failures += msg
                continue
            }

            // From here truthMgdl is a real numeric ground truth (non-null).
            val truthValueMgdl = truthMgdl!!

            // Normalise the recognized value to mg/dL using the unit the model read,
            // so we compare like-for-like against the ground truth.
            val readMgdl = when (extracted.unit?.lowercase()) {
                "mmol" -> readValue * MMOL_TO_MGDL
                else -> readValue // "mgdl"/null treated as mg/dL (parser infers from range)
            }
            val deltaMgdl = abs(readMgdl - truthValueMgdl)
            val valueOk = deltaMgdl <= tolerance

            // Unit must match when the manifest pins one (valueMgdl-only entries
            // don't assert a display unit).
            val expectedUnit = expected.unit?.lowercase()
            val unitOk = expectedUnit == null || expectedUnit == extracted.unit?.lowercase()

            // measure_context only asserted when the manifest pins a non-null one.
            val ctxOk = expected.measureContext.isNullOrBlank() ||
                expected.measureContext.equals(extracted.measureContext, ignoreCase = true)

            if (valueOk && unitOk && ctxOk) {
                passed++
                android.util.Log.i(
                    TAG,
                    "PASS  $name — read ${readValue}${extracted.unit ?: ""} " +
                        "(~${"%.1f".format(readMgdl)} mg/dL) vs truth ${"%.1f".format(truthValueMgdl)} " +
                        "mg/dL, Δ=${"%.1f".format(deltaMgdl)}, conf=${extracted.confidence}",
                )
            } else {
                val msg = buildString {
                    append("FAIL  $name — ")
                    append("read ${readValue}${extracted.unit ?: ""} (~${"%.1f".format(readMgdl)} mg/dL) ")
                    append("vs truth ${"%.1f".format(truthValueMgdl)} mg/dL Δ=${"%.1f".format(deltaMgdl)}")
                    if (!valueOk) append(" [value off >${tolerance}mg/dL]")
                    if (!unitOk) append(" [unit ${extracted.unit} != $expectedUnit]")
                    if (!ctxOk) append(" [ctx ${extracted.measureContext} != ${expected.measureContext}]")
                }
                android.util.Log.e(TAG, msg)
                failures += msg
            }
        }

        // If every image was skipped (no usable manifest entry / decode), don't
        // assert a hollow pass — surface it as a SKIP instead.
        assumeTrue("No images had usable ground truth — nothing asserted", attempted > 0)

        val rate = if (attempted == 0) 0.0 else passed * 100.0 / attempted
        android.util.Log.i(TAG, "==== Glucose image harness: $passed/$attempted passed (${"%.0f".format(rate)}%) ====")

        // Hard assert: every attempted image must read within tolerance. Loosen by
        // bumping per-image `toleranceMgdl` in the manifest if a meter photo is
        // genuinely borderline rather than weakening the bar for all images.
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Glucose image recognition failed on ${failures.size}/$attempted image(s):\n" +
                    failures.joinToString("\n"),
            )
        }
    }
}
