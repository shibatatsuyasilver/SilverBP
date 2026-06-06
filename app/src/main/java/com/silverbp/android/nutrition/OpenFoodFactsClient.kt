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
    /** Product found; [draft] carries label-sourced (accurate) sodium. */
    data class Found(val draft: FoodLog) : BarcodeLookupResult
    data object NotFound : BarcodeLookupResult
    data object Error : BarcodeLookupResult
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
            if (!it.isSuccessful) return@withContext BarcodeLookupResult.Error
            val parsed = try {
                json.decodeFromString<OffResponse>(bodyStr)
            } catch (e: Exception) {
                return@withContext BarcodeLookupResult.Error
            }
            val p = parsed.product
            if (parsed.status != 1 || p == null) return@withContext BarcodeLookupResult.NotFound

            val n = p.nutriments
            val useServing = n != null &&
                (n.energyKcalServing != null || n.sodiumServing != null || n.saltServing != null)
            fun pick(serv: Double?, hundred: Double?): Double? = if (useServing) serv ?: hundred else hundred

            // sodium_* is in grams; salt_* is grams (sodium = salt / 2.5).
            val sodiumG = if (useServing) {
                n?.sodiumServing ?: n?.saltServing?.div(2.5) ?: n?.sodium100g ?: n?.salt100g?.div(2.5)
            } else {
                n?.sodium100g ?: n?.salt100g?.div(2.5)
            }
            val sodiumMg = sodiumG?.times(1000.0)

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
                calories = pick(n?.energyKcalServing, n?.energyKcal100g),
                proteinG = pick(n?.proteinsServing, n?.proteins100g),
                carbsG = pick(n?.carbsServing, n?.carbs100g),
                fatG = pick(n?.fatServing, n?.fat100g),
                sugarG = pick(n?.sugarsServing, n?.sugars100g),
                fiberG = pick(n?.fiberServing, n?.fiber100g),
                sodiumMg = sodiumMg,
                sodiumLevel = SodiumLevel.forMealMg(sodiumMg),
                sodiumSource = SodiumSource.Label,
                analysisBackend = "barcode",
                confidence = 1.0,
            )
            BarcodeLookupResult.Found(draft)
        }
    }

    private fun guessMealType(): MealType = when (LocalTime.now().hour) {
        in 4..10 -> MealType.Breakfast
        in 11..14 -> MealType.Lunch
        in 17..21 -> MealType.Dinner
        else -> MealType.Snack
    }
}

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
private data class OffNutriments(
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
