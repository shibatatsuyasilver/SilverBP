package com.silverbp.android.strength

import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.toEntity

/**
 * Curated starter library of common strength moves (中文). Stable [id]s let the
 * favorite flag + workout set logs reference an exercise across app launches
 * and across the seed list growing in future versions.
 */
object StrengthSeed {

    val items: List<ExerciseCatalogItem> = listOf(
        // ─── 上半身 (upper) ──────────────────────────────────────────────
        ExerciseCatalogItem("st_barbell_bench_press", "槓鈴臥推", BodyPart.UpperBody,
            listOf("胸部", "三頭肌", "肩膀"), "平躺槓鈴推舉,鍛鍊胸大肌與三頭肌。"),
        ExerciseCatalogItem("st_dumbbell_bench_press", "啞鈴臥推", BodyPart.UpperBody,
            listOf("胸部", "三頭肌"), "啞鈴平板推舉,活動範圍更大。"),
        ExerciseCatalogItem("st_incline_dumbbell_press", "上斜啞鈴推舉", BodyPart.UpperBody,
            listOf("上胸", "肩膀"), "斜板強化上胸肌群。"),
        ExerciseCatalogItem("st_dumbbell_shoulder_press", "啞鈴肩推", BodyPart.UpperBody,
            listOf("肩膀", "三頭肌"), "坐姿或站姿啞鈴過頭推舉。"),
        ExerciseCatalogItem("st_lateral_raise", "啞鈴側平舉", BodyPart.UpperBody,
            listOf("三角肌中束"), "雕塑肩部寬度的孤立動作。"),
        ExerciseCatalogItem("st_lat_pulldown", "滑輪下拉", BodyPart.UpperBody,
            listOf("背闊肌", "二頭肌"), "坐姿下拉,建立背部寬度。"),
        ExerciseCatalogItem("st_seated_row", "坐姿划船", BodyPart.UpperBody,
            listOf("背部", "二頭肌"), "拉動握把回收,鍛鍊中背厚度。"),
        ExerciseCatalogItem("st_bent_over_row", "槓鈴俯身划船", BodyPart.UpperBody,
            listOf("背部", "後三角肌"), "俯身上拉槓鈴,強化整體背肌。"),
        ExerciseCatalogItem("st_face_pull", "臉拉", BodyPart.UpperBody,
            listOf("後三角肌", "上背"), "繩索拉向臉部,改善肩部姿勢。"),
        ExerciseCatalogItem("st_biceps_curl", "二頭彎舉", BodyPart.UpperBody,
            listOf("二頭肌"), "啞鈴或槓鈴彎舉,鍛鍊上臂前側。"),
        ExerciseCatalogItem("st_hammer_curl", "錘式彎舉", BodyPart.UpperBody,
            listOf("二頭肌", "前臂"), "中立握法彎舉,兼顧前臂。"),
        ExerciseCatalogItem("st_triceps_pushdown", "三頭下壓", BodyPart.UpperBody,
            listOf("三頭肌"), "繩索下壓伸展三頭肌。"),
        ExerciseCatalogItem("st_overhead_triceps_ext", "過頭三頭伸展", BodyPart.UpperBody,
            listOf("三頭肌"), "啞鈴過頭下放,拉長三頭肌。"),
        ExerciseCatalogItem("st_chest_fly", "啞鈴飛鳥", BodyPart.UpperBody,
            listOf("胸部"), "弧線開合,孤立胸大肌。"),

        // ─── 下半身 (lower) ──────────────────────────────────────────────
        ExerciseCatalogItem("st_barbell_squat", "槓鈴深蹲", BodyPart.LowerBody,
            listOf("股四頭肌", "臀部", "核心"), "背槓下蹲,下肢力量基礎動作。"),
        ExerciseCatalogItem("st_goblet_squat", "高腳杯深蹲", BodyPart.LowerBody,
            listOf("股四頭肌", "臀部"), "胸前抱啞鈴深蹲,適合入門。"),
        ExerciseCatalogItem("st_deadlift", "槓鈴硬舉", BodyPart.LowerBody,
            listOf("臀部", "腿後肌", "下背"), "從地面拉起槓鈴,後鏈主力動作。"),
        ExerciseCatalogItem("st_romanian_deadlift", "羅馬尼亞硬舉", BodyPart.LowerBody,
            listOf("腿後肌", "臀部"), "微屈膝髖鉸鏈,強化腿後與臀。"),
        ExerciseCatalogItem("st_leg_press", "腿推", BodyPart.LowerBody,
            listOf("股四頭肌", "臀部"), "器械蹬腿,下肢安全推力訓練。"),
        ExerciseCatalogItem("st_walking_lunge", "行走弓步", BodyPart.LowerBody,
            listOf("股四頭肌", "臀部"), "交替前跨下蹲,訓練單腿穩定。"),
        ExerciseCatalogItem("st_bulgarian_split_squat", "保加利亞分腿蹲", BodyPart.LowerBody,
            listOf("股四頭肌", "臀部"), "後腳墊高單腿蹲,挑戰平衡與力量。"),
        ExerciseCatalogItem("st_leg_curl", "腿後勾", BodyPart.LowerBody,
            listOf("腿後肌"), "器械屈膝,孤立腿後肌群。"),
        ExerciseCatalogItem("st_leg_extension", "腿伸展", BodyPart.LowerBody,
            listOf("股四頭肌"), "器械伸膝,孤立大腿前側。"),
        ExerciseCatalogItem("st_calf_raise", "小腿提踵", BodyPart.LowerBody,
            listOf("小腿"), "墊腳尖上提,鍛鍊小腿肌肉。"),
        ExerciseCatalogItem("st_glute_bridge", "臀橋", BodyPart.LowerBody,
            listOf("臀部", "腿後肌"), "仰臥挺髖,激活臀部肌群。"),
        ExerciseCatalogItem("st_hip_thrust", "槓鈴臀推", BodyPart.LowerBody,
            listOf("臀部"), "肩靠長凳挺髖,臀部增肌首選。"),

        // ─── 核心 (core) ─────────────────────────────────────────────────
        ExerciseCatalogItem("st_plank", "棒式", BodyPart.Core,
            listOf("核心", "腹部"), "前臂撐地維持身體一直線。"),
        ExerciseCatalogItem("st_side_plank", "側棒式", BodyPart.Core,
            listOf("腹斜肌", "核心"), "側身單臂支撐,強化側腹。"),
        ExerciseCatalogItem("st_crunch", "捲腹", BodyPart.Core,
            listOf("腹直肌"), "仰臥捲起上半身,訓練上腹。"),
        ExerciseCatalogItem("st_russian_twist", "俄羅斯轉體", BodyPart.Core,
            listOf("腹斜肌"), "坐姿左右轉體,鍛鍊腹斜肌。"),
        ExerciseCatalogItem("st_dead_bug", "死蟲式", BodyPart.Core,
            listOf("核心", "腹部"), "仰臥對側伸展,穩定核心。"),
        ExerciseCatalogItem("st_hanging_leg_raise", "懸吊抬腿", BodyPart.Core,
            listOf("下腹", "髖屈肌"), "懸吊抬腿,挑戰下腹力量。"),

        // ─── 全身 (full) ─────────────────────────────────────────────────
        ExerciseCatalogItem("st_pull_up", "引體向上", BodyPart.FullBody,
            listOf("背闊肌", "二頭肌", "核心"), "握槓上拉,經典自體重量動作。"),
        ExerciseCatalogItem("st_push_up", "伏地挺身", BodyPart.FullBody,
            listOf("胸部", "三頭肌", "核心"), "俯撐推起,隨處可做的胸推。"),
        ExerciseCatalogItem("st_burpee", "波比跳", BodyPart.FullBody,
            listOf("全身", "心肺"), "蹲、撐、跳結合,全身高強度。"),
        ExerciseCatalogItem("st_kettlebell_swing", "壺鈴擺盪", BodyPart.FullBody,
            listOf("臀部", "核心", "背部"), "髖部爆發擺盪壺鈴,後鏈與心肺兼顧。"),
    )

    /** Insert the starter library only when the table is empty. */
    suspend fun seedIfEmpty(dao: ExerciseLibraryDao) {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        dao.upsertAll(items.map { it.toEntity(createdAt = now, updatedAt = now) })
    }
}
