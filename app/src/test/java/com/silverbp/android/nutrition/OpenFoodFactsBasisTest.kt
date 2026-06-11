package com.silverbp.android.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the atomic nutriment-basis resolution that previously broke: a
 * product with partial per-serving data used to mix per-serving kcal with
 * per-100g sodium in one draft, corrupting the sodium badge and the Coach
 * diet rollup.
 */
class OpenFoodFactsBasisTest {

    private fun resolve(n: OffNutriments?, servingSize: String? = null) =
        OpenFoodFactsClient.resolveNutriments(n, servingSize)

    // ---- serving-complete ----

    @Test fun `complete serving data uses serving basis for every field`() {
        val r = resolve(
            OffNutriments(
                energyKcalServing = 250.0, energyKcal100g = 500.0,
                proteinsServing = 10.0, proteins100g = 20.0,
                sodiumServing = 0.4, sodium100g = 0.8,
            )
        )
        assertEquals(NutrimentBasis.Serving, r.basis)
        assertEquals(250.0, r.calories!!, 1e-9)
        assertEquals(10.0, r.proteinG!!, 1e-9)
        assertEquals(400.0, r.sodiumMg!!, 1e-9)
    }

    @Test fun `serving basis converts salt_serving to sodium mg`() {
        // salt 1 g -> sodium 0.4 g -> 400 mg.
        val r = resolve(OffNutriments(energyKcalServing = 100.0, saltServing = 1.0, salt100g = 5.0))
        assertEquals(NutrimentBasis.Serving, r.basis)
        assertEquals(400.0, r.sodiumMg!!, 1e-9)
    }

    // ---- serving-partial: never backfill from per-100g ----

    @Test fun `serving basis leaves missing fields null instead of falling back to 100g`() {
        val r = resolve(
            OffNutriments(
                energyKcalServing = 250.0,
                // Only per-100g sodium/protein exist — must NOT leak in.
                proteins100g = 20.0,
                sodium100g = 0.8,
                salt100g = 2.0,
            )
        )
        assertEquals(NutrimentBasis.Serving, r.basis)
        assertEquals(250.0, r.calories!!, 1e-9)
        assertNull(r.proteinG)
        assertNull(r.sodiumMg)
    }

    @Test fun `serving sodium without serving energy is not enough to pick the serving basis`() {
        // Energy is the minimum for a usable serving basis; with only
        // sodium_serving the whole draft stays on the per-100g basis.
        val r = resolve(OffNutriments(energyKcal100g = 500.0, sodiumServing = 0.4, sodium100g = 0.8))
        assertEquals(NutrimentBasis.Per100g, r.basis)
        assertEquals(500.0, r.calories!!, 1e-9)
        assertEquals(800.0, r.sodiumMg!!, 1e-9)
    }

    // ---- 100g-only with parseable serving_size: scaled estimate ----

    @Test fun `100g-only with parseable serving_size scales everything to one serving`() {
        val r = resolve(
            OffNutriments(energyKcal100g = 500.0, proteins100g = 20.0, salt100g = 1.5),
            servingSize = "30 g",
        )
        assertEquals(NutrimentBasis.ScaledPer100g, r.basis)
        assertEquals(150.0, r.calories!!, 1e-9)
        assertEquals(6.0, r.proteinG!!, 1e-9)
        // salt 1.5 g/100g -> sodium 600 mg/100g -> 180 mg per 30 g serving.
        assertEquals(180.0, r.sodiumMg!!, 1e-9)
    }

    // ---- 100g-only without parseable serving_size: honest per-100g ----

    @Test fun `100g-only without serving_size keeps per-100g values and flags the basis`() {
        val r = resolve(OffNutriments(energyKcal100g = 500.0, sodium100g = 0.8))
        assertEquals(NutrimentBasis.Per100g, r.basis)
        assertEquals(500.0, r.calories!!, 1e-9)
        assertEquals(800.0, r.sodiumMg!!, 1e-9)
    }

    @Test fun `100g-only with unparseable serving_size keeps per-100g basis`() {
        val r = resolve(OffNutriments(energyKcal100g = 500.0), servingSize = "1 portion")
        assertEquals(NutrimentBasis.Per100g, r.basis)
        assertEquals(500.0, r.calories!!, 1e-9)
    }

    @Test fun `null nutriments resolves to per-100g basis with all nulls`() {
        val r = resolve(null)
        assertEquals(NutrimentBasis.Per100g, r.basis)
        assertNull(r.calories)
        assertNull(r.sodiumMg)
    }

    // ---- serving_size parsing ----

    @Test fun `parses gram and millilitre quantities`() {
        assertEquals(30.0, OpenFoodFactsClient.parseServingQuantity("30 g")!!, 1e-9)
        assertEquals(250.0, OpenFoodFactsClient.parseServingQuantity("250ml")!!, 1e-9)
        assertEquals(28.5, OpenFoodFactsClient.parseServingQuantity("28,5 g (1 oz)")!!, 1e-9)
        assertEquals(30.0, OpenFoodFactsClient.parseServingQuantity("2 x 30 g")!!, 1e-9)
    }

    @Test fun `rejects missing or unparseable serving sizes`() {
        assertNull(OpenFoodFactsClient.parseServingQuantity(null))
        assertNull(OpenFoodFactsClient.parseServingQuantity(""))
        assertNull(OpenFoodFactsClient.parseServingQuantity("1 portion"))
        assertNull(OpenFoodFactsClient.parseServingQuantity("0 g"))
    }
}
