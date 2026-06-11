package com.silverbp.android.nutrition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Result of a barcode → Open Food Facts lookup. */
sealed interface BarcodeLookupResult {
    /**
     * Product found; [draft] carries label-sourced (accurate) sodium and
     * [basis] says what amount its nutriment values describe.
     */
    data class Found(val draft: FoodLog, val basis: NutrimentBasis) : BarcodeLookupResult
    data object NotFound : BarcodeLookupResult
    data object Error : BarcodeLookupResult
}

/**
 * What amount a barcode draft's nutriment values describe. The basis is
 * decided ONCE for the whole draft — per-serving and per-100g values are
 * never mixed within one log (a mixed log would corrupt the sodium badge
 * and the Coach diet rollup).
 */
enum class NutrimentBasis {
    /** Label per-serving values. */
    Serving,
    /** Only per-100g data existed; scaled to the label serving size (estimate). */
    ScaledPer100g,
    /** Only per-100g data existed and no parseable serving size — values are per 100 g/ml. */
    Per100g,
}

/**
 * Looks up a scanned barcode against the Open Food Facts database. This is the
 * ONE reliable sodium source for the Nutrition feature — the value comes from
 * the packaged label, not a photo estimate, so the resulting draft is marked
 * [SodiumSource.Label].
 */
object OpenFoodFactsClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun lookup(barcode: String): BarcodeLookupResult = withContext(Dispatchers.IO) {
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json" +
            "?fields=product_name,product_name_zh,brands,serving_size,nutriments"
        val req = Request.Builder()
            .url(url)
            // OFF asks API clients to identify themselves via User-Agent.
            .header("User-Agent", "SilverBP-Android - Android - blood-pressure-coach")
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            return@withContext BarcodeLookupResult.Error
        }
        resp.use {
            val bodyStr = it.body?.string().orEmpty()
            // OFF answers 404 for unknown barcodes — that's a miss, not a failure.
            if (it.code == 404) return@withContext BarcodeLookupResult.NotFound
            if (!it.isSuccessful) return@withContext BarcodeLookupResult.Error
            val parsed = try {
                json.decodeFromString<OffResponse>(bodyStr)
            } catch (e: Exception) {
                return@withContext BarcodeLookupResult.Error
            }
            val p = parsed.product
            if (parsed.status != 1 || p == null) return@withContext BarcodeLookupResult.NotFound

            val r = resolveNutriments(p.nutriments, p.servingSize)

            val name = p.productNameZh?.takeIf { it.isNotBlank() }
                ?: p.productName?.takeIf { it.isNotBlank() }
                ?: barcode

            val draft = FoodLog(
                timestamp = Instant.now(),
                mealType = guessMealType(),
                inputMethod = NutritionInputMethod.Barcode,
                description = name,
                barcode = barcode,
                productName = name,
                calories = r.calories,
                proteinG = r.proteinG,
                carbsG = r.carbsG,
                fatG = r.fatG,
                sugarG = r.sugarG,
                fiberG = r.fiberG,
                sodiumMg = r.sodiumMg,
                sodiumLevel = SodiumLevel.forMealMg(r.sodiumMg),
                sodiumSource = SodiumSource.Label,
                analysisBackend = "barcode",
                confidence = 1.0,
            )
            BarcodeLookupResult.Found(draft, r.basis)
        }
    }

    /**
     * Resolve the nutriment basis ATOMICALLY for the whole product:
     *  - per-serving only when the serving basis is usable for energy at
     *    minimum; then missing serving fields stay null — never backfilled
     *    from per-100g values (mixing bases was the original bug);
     *  - otherwise per-100g, scaled to the label `serving_size` when it has a
     *    parseable g/ml quantity ([NutrimentBasis.ScaledPer100g]);
     *  - else honest per-100g values ([NutrimentBasis.Per100g]) so the UI can
     *    warn the user.
     * sodium_* is in grams; salt_* is grams (sodium = salt / 2.5).
     */
    internal fun resolveNutriments(n: OffNutriments?, servingSize: String?): ResolvedNutriments {
        if (n?.energyKcalServing != null) {
            return ResolvedNutriments(
                basis = NutrimentBasis.Serving,
                calories = n.energyKcalServing,
                proteinG = n.proteinsServing,
                carbsG = n.carbsServing,
                fatG = n.fatServing,
                sugarG = n.sugarsServing,
                fiberG = n.fiberServing,
                sodiumMg = (n.sodiumServing ?: n.saltServing?.div(2.5))?.times(1000.0),
            )
        }
        val grams = parseServingQuantity(servingSize)
        val factor = grams?.div(100.0)
        fun scale(v: Double?): Double? = if (factor != null) v?.times(factor) else v
        return ResolvedNutriments(
            basis = if (factor != null) NutrimentBasis.ScaledPer100g else NutrimentBasis.Per100g,
            calories = scale(n?.energyKcal100g),
            proteinG = scale(n?.proteins100g),
            carbsG = scale(n?.carbs100g),
            fatG = scale(n?.fat100g),
            sugarG = scale(n?.sugars100g),
            fiberG = scale(n?.fiber100g),
            sodiumMg = scale((n?.sodium100g ?: n?.salt100g?.div(2.5))?.times(1000.0)),
        )
    }

    /** Extract a gram/millilitre quantity from OFF `serving_size`, e.g. "30 g", "250ml", "2 x 28,5 g". */
    internal fun parseServingQuantity(servingSize: String?): Double? {
        if (servingSize.isNullOrBlank()) return null
        val match = SERVING_QTY.find(servingSize) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private val SERVING_QTY = Regex("""(\d+(?:[.,]\d+)?)\s*(?:g|ml)\b""", RegexOption.IGNORE_CASE)

    private fun guessMealType(): MealType = when (LocalTime.now().hour) {
        in 4..10 -> MealType.Breakfast
        in 11..14 -> MealType.Lunch
        in 17..21 -> MealType.Dinner
        else -> MealType.Snack
    }
}

/** [OpenFoodFactsClient.resolveNutriments] output — one basis applied to every field. */
internal data class ResolvedNutriments(
    val basis: NutrimentBasis,
    val calories: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val sugarG: Double? = null,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
)

@Serializable
private data class OffResponse(
    val status: Int = 0,
    val product: OffProduct? = null,
)

@Serializable
private data class OffProduct(
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_name_zh") val productNameZh: String? = null,
    val brands: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    val nutriments: OffNutriments? = null,
)

@Serializable
internal data class OffNutriments(
    @SerialName("energy-kcal_serving") val energyKcalServing: Double? = null,
    @SerialName("energy-kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("proteins_serving") val proteinsServing: Double? = null,
    @SerialName("proteins_100g") val proteins100g: Double? = null,
    @SerialName("carbohydrates_serving") val carbsServing: Double? = null,
    @SerialName("carbohydrates_100g") val carbs100g: Double? = null,
    @SerialName("fat_serving") val fatServing: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("sugars_serving") val sugarsServing: Double? = null,
    @SerialName("sugars_100g") val sugars100g: Double? = null,
    @SerialName("fiber_serving") val fiberServing: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("sodium_serving") val sodiumServing: Double? = null,
    @SerialName("sodium_100g") val sodium100g: Double? = null,
    @SerialName("salt_serving") val saltServing: Double? = null,
    @SerialName("salt_100g") val salt100g: Double? = null,
)
