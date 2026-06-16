package com.silverbp.android.nutrition

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The long-tail nutrition layer: thousands of TFDA/USDA foods loaded from the
 * bundled gzip-JSON asset and queried by [NutritionDatabase.match] AFTER the 66
 * curated records (which win ties). Built once, off the UI thread, at startup.
 *
 * Candidate retrieval is via an inverted index over each record's match keys —
 * latin word tokens AND individual Han characters (so a CJK substring query like
 * "蝦" still surfaces "中國對蝦"). The caller scores the small candidate set with
 * the shared scorer, so recall — not ranking — is this class's job.
 */
class BulkNutritionStore private constructor(
    private val records: List<NutritionRecord>,
) : BulkNutritionSource {

    /** Normalised token / Han-char -> record indices that contain it. */
    private val index: Map<String, IntArray>

    init {
        val acc = HashMap<String, MutableList<Int>>()
        records.forEachIndexed { idx, rec ->
            val seen = HashSet<String>()
            for (key in rec.matchKeys) {
                // Latin word tokens + whole-CJK-name token.
                for (tok in NutritionDatabase.tokens(key)) {
                    val nt = NutritionDatabase.normalize(tok)
                    if (nt.isNotEmpty() && seen.add(nt)) acc.getOrPut(nt) { mutableListOf() }.add(idx)
                }
                // Individual Han characters for CJK substring recall.
                for (ch in key) {
                    if (ch.isHan() && seen.add(ch.toString())) {
                        acc.getOrPut(ch.toString()) { mutableListOf() }.add(idx)
                    }
                }
            }
        }
        index = acc.mapValues { it.value.toIntArray() }
    }

    fun recordCount(): Int = records.size

    /** Exposed for content-validation tests (sodium invariant, collisions). */
    fun allRecords(): List<NutritionRecord> = records

    override fun candidates(query: String, qTokens: Set<String>): List<NutritionRecord> {
        val counts = HashMap<Int, Int>()
        fun bump(key: String) {
            index[key]?.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        for (t in qTokens) bump(NutritionDatabase.normalize(t))
        for (ch in query) if (ch.isHan()) bump(ch.toString())
        if (counts.isEmpty()) return emptyList()
        // Rank by overlap count (a record sharing more tokens/chars with the
        // query is a likelier match) and keep the top slice for full scoring.
        return counts.entries
            .sortedByDescending { it.value }
            .take(MAX_CANDIDATES)
            .map { records[it.key] }
    }

    companion object {
        private const val MAX_CANDIDATES = 100
        const val ASSET_PATH = "nutrition/foods.v1.json.gz"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Load from the app's bundled asset (Android runtime). */
        fun fromAsset(context: Context): BulkNutritionStore =
            context.assets.open(ASSET_PATH).use { fromGzip(it) }

        /** Load from a gzip-JSON stream — shared by the asset path and JVM tests. */
        fun fromGzip(stream: InputStream): BulkNutritionStore {
            val text = GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val doc = JSON.decodeFromString(NutritionAssetDoc.serializer(), text)
            return BulkNutritionStore(doc.records.map { it.toRecord() })
        }

        private fun Char.isHan(): Boolean = code in 0x4E00..0x9FFF
    }
}
