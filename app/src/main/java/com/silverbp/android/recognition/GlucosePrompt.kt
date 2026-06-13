package com.silverbp.android.recognition

/**
 * Blood-glucose-meter OCR brief — the glucose analogue of [BpPrompt]. The model
 * READS the number on a glucometer LCD AND detects its unit into structured
 * JSON; the app then lets the user confirm/edit before saving.
 *
 * Design rules baked in (see roadmap §4-2):
 *  - UNIT is ambiguous and load-bearing (1 mmol/L = 18.016 mg/dL — a wrong unit
 *    is a ~18× error), so the model must read the on-screen unit LABEL, not
 *    assume one. A numeric-range heuristic cross-checks the claim: mmol/L meters
 *    show a value WITH a decimal point, typically < 35; mg/dL meters show a 2–3
 *    digit INTEGER, typically ~40–600. When the claimed unit contradicts the
 *    range the model is told to LOWER confidence; [GlucoseResponseParser] also
 *    re-checks and lowers it independently so the confirm screen can warn.
 *  - Don't hallucinate: if a digit is unclear, lower confidence rather than
 *    guessing a plausible "textbook" value.
 *  - The meal/context marker (fasting / pre/post-meal icons) is OPTIONAL on the
 *    meter; report it only when clearly shown, else null — the user picks it.
 */
object GlucosePrompt {

    val defaultSystem: String = """
        You are an OCR specialist for home blood-glucose meters (glucometers).
        Read the digital LCD display in the photo and return ONLY a JSON object.

        Schema:
        {"value":<number as shown on the meter, or null if unreadable>,
         "unit":"mgdl|mmol|null",
         "measure_context":"fasting|before_meal|after_meal|bedtime|random|null",
         "timestamp_on_device":"YYYY-MM-DDTHH:mm"|null,
         "confidence":<float 0-1 overall>}

        Rules:
        - Read each digit position-by-position. 7-segment displays only have shapes
          0-9 (plus a possible decimal point). NEVER infer letters or punctuation.
        - READ THE EXACT VALUE shown. Do NOT round to a multiple of 5/10 and do NOT
          default to "textbook" values (100, 120). If the screen shows 137, output 137.
        - UNIT is critical — 1 mmol/L = 18.016 mg/dL, so a wrong unit is a huge error.
          Read the on-screen unit LABEL ("mg/dL" or "mmol/L"); do NOT assume.
          Heuristic to CROSS-CHECK your reading (not to override a clear label):
            • mmol/L meters show a value WITH a decimal point, typically 1.0–35.0.
            • mg/dL meters show a 2–3 digit INTEGER, typically 40–600.
          If your claimed unit CONTRADICTS the numeric range (e.g. unit "mmol" but
          value 137, or unit "mgdl" but value 6.5), you likely misread — re-examine,
          and if still unsure LOWER the confidence.
        - measure_context: only if the meter clearly shows a meal/sleep marker
          (apple/fork icon, "AC"/"PC", "BEFORE"/"AFTER MEAL", "FASTING", moon icon).
          Map: fasting/空腹→"fasting"; before meal/餐前/AC→"before_meal";
          after meal/餐後/PC→"after_meal"; bedtime/睡前/moon→"bedtime".
          If no marker is shown, return null — do NOT guess the context.
        - If a digit is partially obscured/glared, give your best guess but LOWER
          confidence. If unreadable, return null for value.
        - Confidence calibration:
            0.95+ — every digit crisp, unit label unambiguous
            0.70-0.95 — readable but minor glare/blur, or unit inferred from range
            0.40-0.70 — at least one digit guessed, or unit/range conflict
            below 0.40 — too uncertain; return null value
        - Recognize Accu-Chek, OneTouch, Contour, FreeStyle, Bionime, Fora, Omron meters.
        - Do NOT guess or invent values to "look reasonable".
        - IMPORTANT: The meter might be photographed in landscape orientation, so the
          LCD and digits could be rotated 90 degrees. Mentally rotate the digits to
          their upright position and read the correct value even if the screen tilts.
    """.trimIndent()

    private val instruction = """
        Now READ the glucose meter in this photo. Output ONLY the JSON object — no commentary, no Markdown fences.
    """.trimIndent()

    /** [systemOverride] = blank/null → use [defaultSystem]. Mirrors [BpPrompt.systemAndExtract]. */
    fun systemAndExtract(systemOverride: String? = null): String {
        val sys = systemOverride?.takeIf { it.isNotBlank() } ?: defaultSystem
        return sys + "\n\n" + instruction
    }
}
