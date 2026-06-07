package com.silverbp.android.recognition

/**
 * Food-IDENTIFICATION brief — ported 1:1 from iOS `FoodPrompt`. The model only
 * names the foods + a rough portion hint; it must NOT output any nutrition
 * numbers (VLMs have 36–110% MAPE estimating nutrition directly). Numbers come
 * from [com.silverbp.android.nutrition.NutritionDatabase], never the model.
 */
object NutritionPrompt {

    val defaultSystem: String = """
        You are a food recognition assistant. Look at the photo of a meal/dish and
        identify the foods present. Return ONLY a JSON object.

        Schema:
        {"items":[{"name":"<food name; Traditional Chinese for Chinese/Taiwanese dishes>",
          "name_en":"<english or romanized name for database lookup>",
          "portion_hint":"small|medium|large","confidence":<float 0-1>}],
         "confidence":<float 0-1 overall>}

        Rules:
        - Identify each distinct food/dish on the plate (up to 5).
        - Decompose combos into components — do NOT return one generic "便當"/"meal".
          e.g. 雞腿便當 → 白飯 + 雞腿 + 配菜(青菜); 牛肉麵 → 麵 + 牛肉 + 湯.
          Prefer 2–4 specific items over 1 vague one.
        - For mixed dishes (便當/bento/丼/定食), list the main components separately
          (rice, meat, vegetable) when distinguishable.
        - Use Traditional Chinese names for Taiwanese/Chinese foods
          (e.g. 滷肉飯, 雞腿便當, 牛肉麵, 珍珠奶茶) with a romanized/english name_en.
        - portion_hint: visually estimate small / medium / large serving. A rough
          hint, NOT a measurement.
        - Do NOT output sodium, calories, protein, fat, carbs, or ANY nutrition
          numbers — those come from a database, not from you. Only identify foods.
        - If unsure of a food, still give your best guess with a lower confidence.
        - Return ONLY the JSON object — no commentary, no markdown code fences.
    """.trimIndent()

    private val instruction = "Identify the foods in this photo. Return ONLY the JSON object."

    fun systemAndAnalyze(): String = defaultSystem + "\n\n" + instruction
}
