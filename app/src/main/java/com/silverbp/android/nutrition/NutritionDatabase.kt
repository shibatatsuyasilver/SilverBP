package com.silverbp.android.nutrition

import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One food's per-100g nutrition + a sensible default portion. A curated dataset
 * of common Taiwanese / staple foods (NOT authoritative) bundled as
 * `res/raw/nutrition_records.json` and loaded once. The `match` + record
 * architecture is the deliverable; extend the JSON (TFDA / USDA FoodData Central
 * import) without touching callers.
 *
 * The model NEVER outputs these numbers (VLMs have 36–110% MAPE estimating
 * nutrition directly) — it only identifies foods; the numbers come from here.
 */
@Serializable
data class NutritionRecord(
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val sodiumMgPer100g: Double,
    val kcalPer100g: Double,
    val proteinGPer100g: Double,
    val fatGPer100g: Double,
    val carbGPer100g: Double,
    /** Typical single serving in grams — drives the small/medium/large picker. */
    val defaultPortionGrams: Double,
    /** Soup / braised / heavily-sauced → sodium especially uncertain from a photo. */
    val highSodiumUncertainty: Boolean = false,
) {
    /** Names to match against: canonical + aliases. */
    val matchKeys: List<String> get() = listOf(canonicalName) + aliases
}

/** Nutrition computed for one food at a chosen portion. */
data class ComputedNutrition(
    val grams: Double,
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
    val sodiumMg: Double,
    val sodiumLowMg: Double,
    val sodiumHighMg: Double,
)

/**
 * ±range to convey honest uncertainty: portion + DB error compound, and
 * soup/sauced foods are worse (sodium is invisible in a photo). Mirrors iOS
 * `FoodPhotoLogView.sodiumRange`.
 */
fun sodiumRange(estimateMg: Double, uncertain: Boolean): Pair<Double, Double> {
    val lowF = if (uncertain) 0.5 else 0.7
    val highF = if (uncertain) 1.6 else 1.3
    return estimateMg * lowF to estimateMg * highF
}

/** Per-100g values × an explicit grams amount (gram-level portion editing). */
fun NutritionRecord.compute(grams: Double): ComputedNutrition {
    val f = grams / 100.0
    val sodium = sodiumMgPer100g * f
    val (lo, hi) = sodiumRange(sodium, highSodiumUncertainty)
    return ComputedNutrition(
        grams = grams,
        kcal = kcalPer100g * f,
        proteinG = proteinGPer100g * f,
        fatG = fatGPer100g * f,
        carbG = carbGPer100g * f,
        sodiumMg = sodium,
        sodiumLowMg = lo,
        sodiumHighMg = hi,
    )
}

/** Per-100g values × the chosen portion's grams. Mirrors iOS `nutritionRows`. */
fun NutritionRecord.compute(portion: Portion): ComputedNutrition =
    compute(portion.grams(defaultPortionGrams))

object NutritionDatabase {

    private val json = Json { ignoreUnknownKeys = true }

    /** Score below which we treat a match as a guess the UI should flag. */
    const val CONFIDENT_THRESHOLD = 0.5

    /** Score below which we don't even offer an approximate match. */
    private const val MATCH_FLOOR = 0.3

    /** A best-effort lookup result: the record plus how well it matched. */
    data class Match(val record: NutritionRecord, val score: Double) {
        /** True when 0.3 ≤ score < 0.5 — usable, but show "估計對應" in the UI. */
        val approximate: Boolean get() = score < CONFIDENT_THRESHOLD
    }

    /**
     * Confident match (score ≥ 0.5) or null. Unchanged contract — kept so the
     * few callers that only want a high-confidence hit don't change.
     */
    fun match(name: String, nameEn: String? = null): NutritionRecord? =
        matchBestEffort(name, nameEn)?.takeUnless { it.approximate }?.record

    /**
     * Best record down to [MATCH_FLOOR] (0.3) with its score; null below that.
     * normalisation → exact/token → containment → token-overlap (Jaccard).
     * Callers keep the item even when [Match.approximate] so unmatched foods are
     * never silently dropped (they fall back to manual entry / online lookup).
     */
    fun matchBestEffort(name: String, nameEn: String? = null): Match? {
        val query = normalize(name) + " " + normalize(nameEn ?: "")
        val qTokens = tokens(query)
        var best: Match? = null
        for (record in records) {
            var score = 0.0
            for (key in record.matchKeys) {
                val nk = normalize(key)
                if (nk.isEmpty()) continue
                score = when {
                    query == nk || qTokens.contains(nk) -> maxOf(score, 1.0)
                    query.contains(nk) || nk.contains(normalize(name)) -> {
                        val ratio = nk.length.toDouble() / maxOf(query.length, nk.length).toDouble()
                        maxOf(score, 0.6 + 0.3 * ratio)
                    }
                    else -> maxOf(score, jaccard(qTokens, tokens(nk)))
                }
            }
            if (best == null || score > best.score) best = Match(record, score)
        }
        return best?.takeIf { it.score >= MATCH_FLOOR }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .trim()

    private fun tokens(s: String): Set<String> =
        s.split(' ', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /**
     * Loaded once from res/raw/nutrition_records.json — a curated TW/staple seed
     * (~100 foods). Extend the JSON (TFDA 食藥署 / USDA FoodData Central import)
     * without touching callers. Falls back to empty (and logs) on a read/parse
     * error so a bad asset degrades gracefully rather than crashing.
     */
    val records: List<NutritionRecord> by lazy {
        runCatching {
            ServiceLocator.context.resources.openRawResource(R.raw.nutrition_records)
                .bufferedReader().use { it.readText() }
                .let { json.decodeFromString<List<NutritionRecord>>(it) }
        }.getOrElse {
            android.util.Log.e("NutritionDatabase", "failed to load nutrition_records.json", it)
            emptyList()
        }
    }
}
