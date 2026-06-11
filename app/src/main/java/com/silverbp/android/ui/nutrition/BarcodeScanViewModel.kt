package com.silverbp.android.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.nutrition.BarcodeLookupResult
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.NutrimentBasis
import com.silverbp.android.nutrition.NutritionInputMethod
import com.silverbp.android.nutrition.OpenFoodFactsClient
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.nutrition.SodiumSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface BarcodePhase {
    data object Scanning : BarcodePhase
    data object LookingUp : BarcodePhase
    data class NotFound(val code: String) : BarcodePhase
    data class Error(val code: String) : BarcodePhase
}

/**
 * Backs [BarcodeScanScreen]: takes a detected barcode, looks it up in Open
 * Food Facts, and stages a label-sourced [FoodLog] draft (accurate sodium) for
 * [NutritionConfirmScreen]. On miss/error the user can still log manually with
 * the barcode prefilled.
 */
class BarcodeScanViewModel : ViewModel() {

    private val _phase = MutableStateFlow<BarcodePhase>(BarcodePhase.Scanning)
    val phase: StateFlow<BarcodePhase> = _phase.asStateFlow()

    // Guards against the analyzer firing repeatedly for the same code.
    private var handled = false

    fun onDetected(code: String, onFound: () -> Unit) {
        if (handled) return
        handled = true
        _phase.value = BarcodePhase.LookingUp
        viewModelScope.launch {
            when (val r = OpenFoodFactsClient.lookup(code)) {
                is BarcodeLookupResult.Found -> {
                    NutritionDraftHolder.put(r.draft)
                    BarcodeBasisHolder.put(r.basis)
                    onFound()
                }
                BarcodeLookupResult.NotFound -> _phase.value = BarcodePhase.NotFound(code)
                BarcodeLookupResult.Error -> _phase.value = BarcodePhase.Error(code)
            }
        }
    }

    /** Fall back to manual entry, keeping the scanned barcode on the draft. */
    fun manualWithBarcode(onReady: () -> Unit) {
        val code = when (val p = _phase.value) {
            is BarcodePhase.NotFound -> p.code
            is BarcodePhase.Error -> p.code
            else -> null
        }
        NutritionDraftHolder.put(
            FoodLog(
                timestamp = Instant.now(),
                inputMethod = NutritionInputMethod.Barcode,
                barcode = code,
                sodiumLevel = SodiumLevel.Mid,
                sodiumSource = SodiumSource.Manual,
                analysisBackend = "barcode",
            )
        )
        onReady()
    }

    fun rescan() {
        handled = false
        _phase.value = BarcodePhase.Scanning
    }
}

/**
 * In-memory hand-off of a barcode draft's [NutrimentBasis] to
 * [NutritionConfirmScreen], alongside [NutritionDraftHolder]. Lets the confirm
 * screen warn when label values are per 100 g/ml rather than per serving.
 */
object BarcodeBasisHolder {
    @Volatile private var basis: NutrimentBasis? = null

    fun put(b: NutrimentBasis) { basis = b }

    /** Consume the pending basis (cleared after read). */
    fun take(): NutrimentBasis? = basis.also { basis = null }
}
