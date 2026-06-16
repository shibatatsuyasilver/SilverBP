package com.silverbp.android.nutrition

/**
 * One food's per-100g nutrition + a sensible default portion. Ported 1:1 from
 * iOS `NutritionDatabase.swift` — an M1 curated dataset of common Taiwanese /
 * staple foods (NOT authoritative). The `match` + record architecture is the
 * deliverable; swap [records] for a bundled USDA FoodData Central + 台灣 TFDA
 * import later without touching callers.
 *
 * The model NEVER outputs these numbers (VLMs have 36–110% MAPE estimating
 * nutrition directly) — it only identifies foods; the numbers come from here.
 */
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

/** Per-100g values × the chosen portion's grams. Mirrors iOS `nutritionRows`. */
fun NutritionRecord.compute(portion: Portion): ComputedNutrition {
    val grams = portion.grams(defaultPortionGrams)
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

/**
 * The optional long-tail nutrition layer queried by [NutritionDatabase.match]
 * after the curated seed. Implemented by the bundled-asset store
 * ([com.silverbp.android.nutrition.BulkNutritionStore], wired at startup) so
 * the curated layer stays the source of truth for common Taiwanese dishes.
 */
interface BulkNutritionSource {
    /**
     * A small candidate set for the already-normalised [query] + its [qTokens].
     * The caller scores these with the shared scorer, so recall — not ranking —
     * is the job here. Return an empty list when nothing plausibly matches.
     */
    fun candidates(query: String, qTokens: Set<String>): List<NutritionRecord>
}

object NutritionDatabase {

    /**
     * Optional long-tail layer (bundled TFDA/USDA open data), wired once at
     * startup. Null until loaded — [match] then uses only the curated [records].
     * The curated layer always wins ties so common dishes keep their hand-tuned
     * portions/aliases.
     */
    @Volatile
    var bulk: BulkNutritionSource? = null

    /** Minimum score to accept a match (curated or bulk). */
    private const val THRESHOLD = 0.5

    /**
     * Best record for a recognised food name, or null if nothing clears
     * [THRESHOLD]. Two layers, one scorer: the curated [records] (priority) and
     * the optional [bulk] long tail, with curated winning ties. Per-key scoring:
     * normalisation → exact/token (1.0) → containment (0.6–0.9) → token-overlap
     * (Jaccard). Ported from iOS `NutritionDatabase.match`.
     */
    fun match(name: String, nameEn: String? = null): NutritionRecord? {
        val nName = normalize(name)
        val query = nName + " " + normalize(nameEn ?: "")
        val qTokens = tokens(query)

        // Layer 1 — curated seed (priority). ~66 records: a full scan is cheap
        // and behaviourally identical to the original single-list match.
        var bestCurated: NutritionRecord? = null
        var bestCuratedScore = 0.0
        for (record in records) {
            val s = scoreRecord(record, query, qTokens, nName)
            if (s > bestCuratedScore) { bestCuratedScore = s; bestCurated = record }
        }

        // Layer 2 — bulk long tail (TFDA/USDA): candidates from an inverted
        // index, then scored with the SAME scorer for identical ranking.
        var bestBulk: NutritionRecord? = null
        var bestBulkScore = 0.0
        bulk?.candidates(query, qTokens)?.forEach { record ->
            val s = scoreRecord(record, query, qTokens, nName)
            if (s > bestBulkScore) { bestBulkScore = s; bestBulk = record }
        }

        // Curated wins ties (>=) so a hand-tuned entry beats an equal-scoring
        // bulk row; otherwise take whichever layer clears the threshold.
        if (bestCurated != null && bestCuratedScore >= THRESHOLD &&
            (bestBulk == null || bestCuratedScore >= bestBulkScore)
        ) {
            return bestCurated
        }
        if (bestBulk != null && bestBulkScore >= THRESHOLD) return bestBulk
        if (bestCurated != null && bestCuratedScore >= THRESHOLD) return bestCurated
        return null
    }

    /**
     * Score one record against the query. Unchanged from the original inline
     * scoring so the curated layer's behaviour (and the merged false-match fix)
     * is preserved exactly; shared with [bulk] for identical ranking.
     */
    private fun scoreRecord(
        record: NutritionRecord,
        query: String,
        qTokens: Set<String>,
        nName: String,
    ): Double {
        var score = 0.0
        for (key in record.matchKeys) {
            val nk = normalize(key)
            if (nk.isEmpty()) continue
            score = when {
                query == nk || qTokens.contains(nk) -> maxOf(score, 1.0)
                query.contains(nk) || nk.contains(nName) -> {
                    val ratio = nk.length.toDouble() / maxOf(query.length, nk.length).toDouble()
                    maxOf(score, 0.6 + 0.3 * ratio)
                }
                else -> maxOf(score, jaccard(qTokens, tokens(nk)))
            }
        }
        return score
    }

    /** Normalised match key — lowercase, strip spaces/dashes/underscores. */
    internal fun normalize(s: String): String =
        s.lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .trim()

    /** Split a name into tokens on space / comma / slash. */
    internal fun tokens(s: String): Set<String> =
        s.split(' ', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /** Curated seed (~60 foods), ported 1:1 from iOS NutritionDatabase.swift. */
    val records: List<NutritionRecord> = listOf(
        // ── 主食 / 飯麵 ──────────────────────────────────────────────
        NutritionRecord("白飯", listOf("米飯", "steamed rice", "white rice", "bai fan"), 1.0, 130.0, 2.7, 0.3, 28.0, 200.0),
        NutritionRecord("糙米飯", listOf("brown rice"), 4.0, 123.0, 2.7, 1.0, 26.0, 200.0),
        NutritionRecord("炒飯", listOf("fried rice", "chao fan"), 400.0, 180.0, 5.0, 6.0, 26.0, 300.0, true),
        NutritionRecord("滷肉飯", listOf("lu rou fan", "braised pork rice", "minced pork rice"), 430.0, 200.0, 7.0, 8.0, 27.0, 250.0, true),
        NutritionRecord("雞肉飯", listOf("chicken rice", "ji rou fan"), 380.0, 175.0, 10.0, 5.0, 24.0, 250.0, true),
        NutritionRecord("白粥", listOf("congee", "rice porridge", "zhou"), 60.0, 50.0, 1.0, 0.1, 11.0, 350.0, true),
        NutritionRecord("陽春麵", listOf("noodle soup", "plain noodles", "mian"), 500.0, 120.0, 4.0, 2.0, 22.0, 350.0, true),
        NutritionRecord("牛肉麵", listOf("beef noodle", "niu rou mian"), 600.0, 110.0, 7.0, 3.0, 13.0, 500.0, true),
        NutritionRecord("義大利麵", listOf("pasta", "spaghetti"), 320.0, 160.0, 6.0, 5.0, 24.0, 300.0, true),
        NutritionRecord("水餃", listOf("餃子", "dumpling", "gyoza", "shui jiao"), 350.0, 220.0, 9.0, 9.0, 25.0, 200.0, true),
        NutritionRecord("小籠包", listOf("xiaolongbao", "soup dumpling"), 400.0, 240.0, 10.0, 12.0, 23.0, 150.0, true),
        NutritionRecord("饅頭", listOf("mantou", "steamed bun"), 200.0, 230.0, 7.0, 1.0, 47.0, 100.0),
        NutritionRecord("吐司", listOf("toast", "white bread"), 490.0, 265.0, 9.0, 3.2, 49.0, 60.0),

        // ── 便當 / 快餐 ──────────────────────────────────────────────
        NutritionRecord("雞腿便當", listOf("chicken bento", "chicken lunchbox", "便當", "bento"), 500.0, 180.0, 12.0, 7.0, 18.0, 500.0, true),
        NutritionRecord("排骨便當", listOf("pork chop bento", "pai gu"), 520.0, 200.0, 11.0, 9.0, 19.0, 500.0, true),
        NutritionRecord("漢堡", listOf("hamburger", "burger"), 490.0, 250.0, 13.0, 12.0, 22.0, 220.0, true),
        NutritionRecord("薯條", listOf("fries", "french fries"), 350.0, 312.0, 3.4, 15.0, 41.0, 115.0, true),
        NutritionRecord("披薩", listOf("pizza"), 600.0, 266.0, 11.0, 10.0, 33.0, 250.0, true),

        // ── 肉類 ────────────────────────────────────────────────────
        NutritionRecord("雞胸肉", listOf("chicken breast"), 70.0, 165.0, 31.0, 3.6, 0.0, 150.0),
        NutritionRecord("雞腿", listOf("chicken leg", "chicken thigh", "炸雞", "fried chicken"), 300.0, 250.0, 18.0, 16.0, 8.0, 150.0, true),
        NutritionRecord("豬排", listOf("pork chop", "排骨", "pork cutlet"), 330.0, 240.0, 19.0, 16.0, 6.0, 150.0, true),
        NutritionRecord("牛排", listOf("steak", "beef steak"), 60.0, 250.0, 26.0, 17.0, 0.0, 200.0, true),
        NutritionRecord("三層肉", listOf("五花肉", "pork belly"), 40.0, 290.0, 14.0, 26.0, 0.0, 120.0),
        NutritionRecord("香腸", listOf("sausage", "xiang chang"), 900.0, 300.0, 13.0, 25.0, 5.0, 80.0, true),
        NutritionRecord("培根", listOf("bacon"), 1500.0, 400.0, 12.0, 39.0, 1.0, 40.0, true),

        // ── 海鮮 ────────────────────────────────────────────────────
        NutritionRecord("鮭魚", listOf("salmon"), 60.0, 208.0, 20.0, 13.0, 0.0, 150.0),
        NutritionRecord("蝦", listOf("shrimp", "prawn", "xia"), 220.0, 99.0, 24.0, 0.3, 0.2, 120.0),
        NutritionRecord("虱目魚", listOf("milkfish"), 80.0, 180.0, 20.0, 11.0, 0.0, 150.0),

        // ── 蛋 / 豆 ─────────────────────────────────────────────────
        NutritionRecord("水煮蛋", listOf("boiled egg", "雞蛋"), 124.0, 155.0, 13.0, 11.0, 1.1, 50.0),
        NutritionRecord("滷蛋", listOf("茶葉蛋", "braised egg", "tea egg"), 300.0, 150.0, 13.0, 10.0, 1.0, 55.0, true),
        NutritionRecord("煎蛋", listOf("fried egg", "炒蛋", "scrambled egg"), 200.0, 196.0, 14.0, 15.0, 1.0, 60.0, true),
        NutritionRecord("豆腐", listOf("嫩豆腐", "板豆腐", "dou fu"), 12.0, 76.0, 8.0, 4.8, 1.9, 150.0),
        NutritionRecord("豆漿", listOf("soy milk", "soybean milk", "dou jiang"), 30.0, 45.0, 3.5, 1.8, 3.0, 350.0),

        // ── 蔬菜 ────────────────────────────────────────────────────
        NutritionRecord("燙青菜", listOf("青菜", "boiled greens", "蔬菜"), 120.0, 40.0, 2.5, 1.5, 4.0, 120.0, true),
        NutritionRecord("生菜沙拉", listOf("salad", "green salad"), 150.0, 90.0, 2.0, 6.0, 7.0, 150.0, true),
        NutritionRecord("高麗菜", listOf("cabbage", "gao li cai"), 18.0, 25.0, 1.3, 0.1, 6.0, 120.0),
        NutritionRecord("花椰菜", listOf("broccoli", "cauliflower"), 33.0, 34.0, 2.8, 0.4, 7.0, 120.0),
        NutritionRecord("玉米", listOf("corn", "yu mi"), 15.0, 96.0, 3.4, 1.5, 21.0, 120.0),

        // ── 便當小菜 / 滷味 / 醃漬 ──────────────────────────────────
        // Recurring lunchbox sides. Canonical Traditional-Chinese names give an
        // exact (1.0) match so the model's labels stop falling onto over-broad
        // aliases of unrelated foods (braised pork was logging as boiled greens).
        NutritionRecord("豆皮", listOf("腐皮", "豆包", "油豆腐", "滷豆皮", "braised tofu skin", "tofu skin", "dou pi"), 480.0, 190.0, 16.0, 11.0, 6.0, 80.0, true),
        NutritionRecord("滷肉", listOf("滷肉片", "焢肉", "控肉", "滷三層", "滷味", "braised pork", "braised pork belly", "braised meat", "lu rou"), 480.0, 250.0, 14.0, 19.0, 3.0, 100.0, true),
        NutritionRecord("筍絲", listOf("筍乾", "滷筍", "braised bamboo", "shredded bamboo shoots", "sun si"), 700.0, 35.0, 2.5, 0.3, 6.0, 60.0, true),
        NutritionRecord("酸菜", listOf("榨菜", "pickled mustard greens", "pickled mustard", "suan cai", "zha cai"), 1400.0, 25.0, 1.5, 0.3, 4.5, 40.0, true),

        // ── 湯 ──────────────────────────────────────────────────────
        NutritionRecord("味噌湯", listOf("miso soup", "miso"), 500.0, 35.0, 2.5, 1.0, 4.0, 250.0, true),
        NutritionRecord("貢丸湯", listOf("meatball soup", "gong wan"), 450.0, 70.0, 5.0, 4.0, 3.0, 300.0, true),
        NutritionRecord("玉米濃湯", listOf("corn soup", "corn chowder"), 340.0, 60.0, 1.5, 2.0, 9.0, 250.0, true),

        // ── 小吃 / 夜市 ─────────────────────────────────────────────
        NutritionRecord("鹹酥雞", listOf("popcorn chicken", "fried chicken bites", "xian su ji"), 600.0, 280.0, 17.0, 18.0, 12.0, 200.0, true),
        NutritionRecord("蚵仔煎", listOf("oyster omelette", "o a jian"), 420.0, 150.0, 6.0, 8.0, 14.0, 250.0, true),
        NutritionRecord("臭豆腐", listOf("stinky tofu", "chou dou fu"), 530.0, 190.0, 9.0, 12.0, 10.0, 200.0, true),
        NutritionRecord("刈包", listOf("gua bao", "pork belly bun"), 450.0, 240.0, 9.0, 11.0, 27.0, 150.0, true),
        NutritionRecord("潤餅", listOf("潤餅卷", "popiah", "run bing"), 300.0, 180.0, 6.0, 7.0, 24.0, 180.0, true),

        // ── 早餐 ────────────────────────────────────────────────────
        NutritionRecord("蛋餅", listOf("egg crepe", "dan bing"), 380.0, 210.0, 8.0, 11.0, 20.0, 150.0, true),
        NutritionRecord("飯糰", listOf("fan tuan", "rice ball", "onigiri"), 350.0, 200.0, 5.0, 5.0, 34.0, 200.0, true),
        NutritionRecord("三明治", listOf("sandwich"), 480.0, 250.0, 10.0, 12.0, 26.0, 180.0, true),

        // ── 飲料 ────────────────────────────────────────────────────
        NutritionRecord("珍珠奶茶", listOf("bubble tea", "boba", "milk tea", "zhen zhu nai cha"), 30.0, 90.0, 1.0, 2.0, 18.0, 500.0),
        NutritionRecord("美式咖啡", listOf("americano", "black coffee"), 2.0, 2.0, 0.1, 0.0, 0.0, 350.0),
        NutritionRecord("拿鐵", listOf("latte", "caffe latte"), 40.0, 60.0, 3.0, 3.0, 5.0, 350.0),
        NutritionRecord("可樂", listOf("cola", "coke"), 4.0, 42.0, 0.0, 0.0, 11.0, 350.0),
        NutritionRecord("柳橙汁", listOf("orange juice"), 1.0, 45.0, 0.7, 0.2, 10.0, 300.0),

        // ── 水果 ────────────────────────────────────────────────────
        NutritionRecord("香蕉", listOf("banana"), 1.0, 90.0, 1.1, 0.3, 23.0, 120.0),
        NutritionRecord("蘋果", listOf("apple"), 1.0, 52.0, 0.3, 0.2, 14.0, 180.0),
        NutritionRecord("芭樂", listOf("guava", "ba le"), 2.0, 68.0, 2.6, 1.0, 14.0, 160.0),
        NutritionRecord("西瓜", listOf("watermelon"), 1.0, 30.0, 0.6, 0.2, 8.0, 250.0),

        // ── 包裝 / 零食 ─────────────────────────────────────────────
        NutritionRecord("洋芋片", listOf("potato chips", "chips", "crisps"), 525.0, 536.0, 7.0, 35.0, 53.0, 60.0, true),
        NutritionRecord("泡麵", listOf("instant noodle", "ramen", "pao mian"), 1200.0, 440.0, 9.0, 18.0, 60.0, 90.0, true),
        NutritionRecord("餅乾", listOf("biscuit", "cookie", "cracker"), 400.0, 480.0, 6.0, 22.0, 64.0, 50.0, true),
    )
}
