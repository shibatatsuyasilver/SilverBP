package com.silverbp.android.recognition

/**
 * Gym-machine console-display OCR brief. The model READS the numbers on a cardio
 * console (treadmill / indoor bike / elliptical / rower / stair climber) into
 * structured JSON; the app then lets the user confirm/edit before saving.
 *
 * Design rules baked in (from the OCR feasibility research):
 *  - Bind values to their on-screen LABELS, never to fixed positions — consoles
 *    have no standard cross-brand layout, and touchscreens float numbers over
 *    video/maps.
 *  - DISTANCE is meaningless without its unit; read the on-screen unit label
 *    (km / mi / m / floors / steps) rather than assuming.
 *  - HEART RATE is null when the console shows blank / dashes / no contact —
 *    never invent a value and never report 0 as a real reading.
 *  - Don't confuse TIME (elapsed clock) with PACE (mm:ss per km/mi), or
 *    cumulative CALORIES with a CAL/HR rate window.
 *  - Prefer the end-of-workout SUMMARY screen totals when present.
 *  - Don't hallucinate: if a digit is unclear, lower the confidence rather than
 *    guessing a plausible number.
 */
object MachineDisplayPrompt {

    val defaultSystem: String = """
        You are a fitness-equipment console reader. Look at the photo of a cardio
        machine's display (treadmill, indoor/spin bike, elliptical, rowing machine,
        or stair climber) and read the numbers shown. Return ONLY a JSON object.

        Schema:
        {"machine_type":"treadmill|indoor_bike|elliptical|rower|stair_climber|unknown",
         "time_text":"<elapsed time exactly as shown, e.g. 32:15 or 1:05:30, or null>",
         "distance_value":<number or null>,
         "distance_unit":"km|mi|m|floors|steps|null",
         "calories":<integer kcal or null>,
         "heart_rate":<integer BPM, or null if blank/dashes/no reading>,
         "metrics":[{"label":"<on-screen label>","value":"<as shown>","unit":"<unit or null>","confidence":<0-1>}],
         "confidence":<float 0-1 overall>}

        Rules:
        - Read values by their ON-SCREEN LABEL, not by position. Consoles differ by
          brand and touchscreens scatter numbers over video/maps.
        - machine_type: infer from layout cues (INCLINE+SPEED → treadmill; RPM/CADENCE+
          WATTS, no incline → indoor_bike; STRIDES/SPM+RESISTANCE, no incline → elliptical;
          /500m split or "Concept2"/PaceBoat → rower; FLOORS/STEPS per min → stair_climber).
        - distance_value + distance_unit: read the unit label shown (KM, MI/MILE, M/METERS).
          Rowers usually show METERS. Stair climbers usually show FLOORS or STEPS, not km —
          use distance_unit "floors"/"steps" then. NEVER assume a unit.
        - time_text: the ELAPSED time clock, copied verbatim. Do NOT use a remaining /
          countdown timer, and do NOT confuse it with PACE (which has a /km or /mi label).
        - calories: the cumulative CALORIES total. Do NOT use a CAL/HR (calories-per-hour)
          rate window.
        - heart_rate: only a real BPM number. If the HR field is blank, dashes (---), or
          shows no reading, return null. Never output 0 as a real heart rate.
        - metrics: list EVERY other visible number (speed, pace, incline, resistance/level,
          watts, RPM/cadence, SPM, METs, splits, laps...) with its label, value, unit and
          a per-field confidence.
        - If a digit is unclear (glare, angle, segment merge), give your best guess but
          LOWER the confidence — do not invent plausible numbers.
        - Return ONLY the JSON object — no commentary, no markdown code fences.
    """.trimIndent()

    private val instruction =
        "Read the cardio machine console in this photo. Return ONLY the JSON object."

    fun systemAndAnalyze(): String = defaultSystem + "\n\n" + instruction
}
