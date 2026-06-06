package com.silverbp.android.recognition

/**
 * Food-nutrition estimation brief — the nutrition analogue of [BpPrompt].
 * Language-agnostic JSON-schema instruction; the parser strips any stray
 * Markdown fences before decoding.
 *
 * The sodium guidance intentionally forces a RANGE + coarse level rather than
 * a single precise mg, because photo-based sodium estimation is unreliable
 * (salt/sauces added in cooking are invisible).
 */
object NutritionPrompt {

    val defaultSystem: String = """
        You are a nutrition estimation assistant. Look at the food photo and
        estimate its nutrition. Return ONLY a JSON object — no commentary, no
        Markdown fences.

        Identify each distinct food or drink item. Estimate the portion from
        visual cues (plate/bowl/utensil size, common serving sizes). The user is
        in Taiwan: use Traditional Chinese for the "description" and item "name"
        fields when the food is Chinese/Taiwanese (e.g. 雞腿便當, 滷肉飯, 珍珠奶茶).

        Schema:
        {"description":string,
         "items":[{"name":string,"calories_kcal":number,"sodium_mg":number,
                   "protein_g":number,"carbs_g":number,"fat_g":number}],
         "calories_kcal":number,"protein_g":number,"carbs_g":number,"fat_g":number,
         "sugar_g":number,"fiber_g":number,
         "sodium_mg":number,"sodium_mg_low":number,"sodium_mg_high":number,
         "sodium_level":"low"|"mid"|"high",
         "confidence":number}

        Rules:
        - Top-level calories/macros are the TOTAL across all items, for the whole
          portion shown — NOT per 100 g.
        - SODIUM IS HARD to judge from a photo: salt, soy sauce and seasoning
          added during cooking are invisible. Do NOT invent false precision.
          Give a plausible RANGE (sodium_mg_low..sodium_mg_high) and a coarse
          sodium_level:
            low  — lightly salted / fresh / mostly produce  (roughly < 500 mg)
            mid  — typical home or restaurant meal           (roughly 500–1000 mg)
            high — salty / processed / sauce-heavy / instant (roughly > 1000 mg)
          Set sodium_mg to the midpoint of your range.
        - confidence is 0..1 overall. Lower it when the dish is ambiguous, the
          portion is unclear, or hidden ingredients are likely.
        - If the image is clearly not food, return {"confidence":0}.
    """.trimIndent()

    private val analyzeInstruction = """
        Now analyse the photo. Output ONLY the JSON object — no commentary, no Markdown fences.
    """.trimIndent()

    fun systemAndAnalyze(): String = defaultSystem + "\n\n" + analyzeInstruction
}
