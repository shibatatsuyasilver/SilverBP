package com.silverbp.android.ui.nutrition

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.NutritionInputMethod
import com.silverbp.android.nutrition.NutritionRepository
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.nutrition.SodiumSource
import com.silverbp.android.nutrition.currentMealType
import com.silverbp.android.recognition.NutritionRecognizerFactory
import com.silverbp.android.recognition.decodeUriWithExif
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One day's summed sodium estimate, for the 7-day trend. */
data class DaySodium(val date: LocalDate, val sodiumMg: Double)

data class NutritionUiState(
    val recent: List<FoodLog> = emptyList(),
    val today: List<FoodLog> = emptyList(),
    val todayCalories: Double = 0.0,
    val todaySodiumMg: Double = 0.0,
    val todaySodiumLow: Double = 0.0,
    val todaySodiumHigh: Double = 0.0,
    val todayProteinG: Double = 0.0,
    val todayCarbsG: Double = 0.0,
    val todayFatG: Double = 0.0,
    val sodiumTargetMg: Int = 2000,
    val last7DaysSodium: List<DaySodium> = emptyList(),
    val isLoading: Boolean = true,
)

/** Capture/analysis phase for the camera/gallery → confirm hand-off. */
sealed interface NutritionCapturePhase {
    data object Idle : NutritionCapturePhase
    data object Analyzing : NutritionCapturePhase
    data class Error(val message: String) : NutritionCapturePhase
}

class NutritionViewModel(
    private val repo: NutritionRepository = ServiceLocator.nutritionRepository,
    settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val appContext: Context = ServiceLocator.context,
) : ViewModel() {

    val state: StateFlow<NutritionUiState> = combine(
        repo.observeAll(),
        settings.flow,
    ) { all, user ->
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val today = all.filter { !it.timestamp.isBefore(todayStart) }
        NutritionUiState(
            recent = all,
            today = today,
            todayCalories = today.sumOf { it.calories ?: 0.0 },
            todaySodiumMg = today.sumOf { it.sodiumMg ?: 0.0 },
            todaySodiumLow = today.sumOf { it.sodiumMgLow ?: it.sodiumMg ?: 0.0 },
            todaySodiumHigh = today.sumOf { it.sodiumMgHigh ?: it.sodiumMg ?: 0.0 },
            todayProteinG = today.sumOf { it.proteinG ?: 0.0 },
            todayCarbsG = today.sumOf { it.carbsG ?: 0.0 },
            todayFatG = today.sumOf { it.fatG ?: 0.0 },
            sodiumTargetMg = user.dailySodiumTargetMg,
            last7DaysSodium = last7Days(all, zone),
            isLoading = false,
        )
    }
        .catch { emit(NutritionUiState(isLoading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NutritionUiState())

    private val _capturePhase = MutableStateFlow<NutritionCapturePhase>(NutritionCapturePhase.Idle)
    val capturePhase: StateFlow<NutritionCapturePhase> = _capturePhase.asStateFlow()

    fun resetCapture() { _capturePhase.value = NutritionCapturePhase.Idle }

    /** Analyse a camera preview bitmap, stage a draft, then [onReady] to navigate. */
    fun analyzeBitmap(bitmap: Bitmap, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = NutritionCapturePhase.Analyzing
            val photoName = withContext(Dispatchers.IO) { runCatching { writePhoto(bitmap) }.getOrNull() }
            try {
                val recognizer = NutritionRecognizerFactory.current()
                if (recognizer.isReady()) {
                    val downsized = withContext(Dispatchers.Default) { downsample(bitmap, MAX_DIM) }
                    val extracted = recognizer.analyze(downsized)
                    NutritionDraftHolder.putMeal(
                        RecognizedMeal(
                            photoFilename = photoName,
                            items = extracted.items,
                            overallConfidence = extracted.confidence,
                            backendTag = recognizer.backendTag,
                        )
                    )
                    _capturePhase.value = NutritionCapturePhase.Idle
                    onReady()
                } else {
                    // Model not loaded / no API key — fall back to manual entry,
                    // keeping the photo so the user can fill in the numbers.
                    NutritionDraftHolder.put(manualDraft(photoName))
                    _capturePhase.value = NutritionCapturePhase.Idle
                    onReady()
                }
            } catch (t: Throwable) {
                // Analysis failed — still let the user log it manually with the photo.
                NutritionDraftHolder.put(manualDraft(photoName))
                _capturePhase.value = NutritionCapturePhase.Error(t.message ?: "analysis failed")
            }
        }
    }

    /** Decode a gallery Uri then run [analyzeBitmap]. */
    fun analyzeUri(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = NutritionCapturePhase.Analyzing
            val bmp = withContext(Dispatchers.IO) { decodeUriWithExif(appContext, uri) }
            if (bmp == null) {
                _capturePhase.value = NutritionCapturePhase.Error("cannot load image")
                return@launch
            }
            analyzeBitmap(bmp, onReady)
        }
    }

    /** Manual-entry draft (no photo, no analysis) for the "手動新增" entry point. */
    fun stageManual() { NutritionDraftHolder.put(manualDraft(null)) }

    private fun manualDraft(photoName: String?) = FoodLog(
        timestamp = Instant.now(),
        mealType = currentMealType(),
        inputMethod = if (photoName != null) NutritionInputMethod.Photo else NutritionInputMethod.Manual,
        photoFilename = photoName,
        sodiumLevel = SodiumLevel.Mid,
        sodiumSource = SodiumSource.Manual,
        analysisBackend = "manual",
        confidence = 1.0,
    )

    private fun writePhoto(bitmap: Bitmap): String {
        val dir = File(appContext.filesDir, "photos").apply { mkdirs() }
        val name = "${UUID.randomUUID()}.jpg"
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    private fun downsample(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val scale = maxDim.toFloat() / maxSide
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun last7Days(all: List<FoodLog>, zone: ZoneId): List<DaySodium> {
        val today = LocalDate.now(zone)
        return (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val sum = all.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }
                .sumOf { it.sodiumMg ?: 0.0 }
            DaySodium(day, sum)
        }
    }

    private companion object {
        const val MAX_DIM = 1024
    }
}
