package com.silverbp.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.silverbp.android.R
import com.silverbp.android.core.WeightUnit
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure range/index math for the weight wheel, factored out so it is unit-testable
 * without Compose. The wheel shows values in the user's display unit at 0.1
 * resolution; storage is always canonical kg. See ValuePickersTest.
 */
object WeightWheel {
    /** kg wheel bounds (matches plausible human-body range, 0.1 step). */
    const val KG_MIN = 20.0
    const val KG_MAX = 300.0
    const val STEP = 0.1

    /** Bounds for [unit] in that unit's own numbers (lb derived from the kg range). */
    fun displayMin(unit: WeightUnit): Double =
        if (unit == WeightUnit.Lb) WeightUnit.kgToLb(KG_MIN) else KG_MIN

    fun displayMax(unit: WeightUnit): Double =
        if (unit == WeightUnit.Lb) WeightUnit.kgToLb(KG_MAX) else KG_MAX

    /** Number of 0.1 steps in the wheel for [unit] (inclusive of both ends). */
    fun count(unit: WeightUnit): Int =
        ((displayMax(unit) - displayMin(unit)) / STEP).roundToInt() + 1

    /** The display value (in [unit]) at wheel position [index]. */
    fun valueAt(index: Int, unit: WeightUnit): Double =
        displayMin(unit) + index.coerceIn(0, count(unit) - 1) * STEP

    /** Nearest wheel index for a display value [value] (in [unit]). */
    fun indexOf(value: Double, unit: WeightUnit): Int =
        ((value - displayMin(unit)) / STEP).roundToInt().coerceIn(0, count(unit) - 1)

    /** Nearest wheel index for a canonical [kg] value, displayed in [unit]. */
    fun indexOfKg(kg: Double, unit: WeightUnit): Int {
        val display = if (unit == WeightUnit.Lb) WeightUnit.kgToLb(kg) else kg
        return indexOf(display, unit)
    }

    /** Canonical kg for wheel position [index] shown in [unit]. */
    fun kgAt(index: Int, unit: WeightUnit): Double {
        val display = valueAt(index, unit)
        return if (unit == WeightUnit.Lb) WeightUnit.lbToKg(display) else display
    }

    fun formatDisplay(value: Double): String = String.format(Locale.US, "%.1f", value)
}

/**
 * Tappable value row (the shared visual for all three pickers): a label on the
 * left and an [OutlinedButton] on the right showing the current value or a
 * "not set" placeholder — the same idiom as ConfirmReadingScreen's timestamp
 * row. Tapping opens the wheel dialog.
 */
@Composable
private fun PickerValueRow(
    label: String,
    valueText: String,
    isSet: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowCd = stringResource(R.string.picker_row_a11y, label, valueText)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = rowCd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = onOpen) {
            Text(valueText)
        }
    }
}

/**
 * The wheel dialog scaffold: hosts the [WheelPicker] with Cancel / Done and an
 * optional Clear (for optional fields). [pendingIndex]/[onPendingChange] hold the
 * in-dialog selection so Cancel discards it.
 */
@Composable
private fun <T> WheelDialog(
    title: String,
    items: List<T>,
    pendingIndex: Int,
    onPendingChange: (Int) -> Unit,
    label: (T) -> String,
    wheelContentDescription: String,
    showClear: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                WheelPicker(
                    items = items,
                    selectedIndex = pendingIndex,
                    onSelectedIndexChange = onPendingChange,
                    label = label,
                    contentDescription = wheelContentDescription,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showClear) {
                        TextButton(onClick = onClear) {
                            Text(stringResource(R.string.picker_clear))
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.picker_cancel))
                    }
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.picker_done))
                    }
                }
            }
        }
    }
}

/**
 * Birth-year picker. Wheel over 1900..[LocalDate.now].year. [value] null = not
 * set; Clear restores null. Defaults the wheel to 1970 when not set.
 */
@Composable
fun YearPickerField(
    value: Int?,
    onChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val maxYear = remember { LocalDate.now().year }
    val years = remember(maxYear) { (1900..maxYear).toList() }
    val notSet = stringResource(R.string.picker_not_set)
    val valueText = value?.toString() ?: notSet

    var open by remember { mutableStateOf(false) }
    val defaultYear = 1970.coerceIn(years.first(), years.last())
    var pendingIndex by remember { mutableIntStateOf(0) }

    PickerValueRow(
        label = label,
        valueText = valueText,
        isSet = value != null,
        onOpen = {
            pendingIndex = years.indexOf(value ?: defaultYear).coerceAtLeast(0)
            open = true
        },
        modifier = modifier,
    )

    if (open) {
        WheelDialog(
            title = label,
            items = years,
            pendingIndex = pendingIndex,
            onPendingChange = { pendingIndex = it },
            label = { it.toString() },
            wheelContentDescription = label,
            showClear = true,
            onDismiss = { open = false },
            onClear = { onChange(null); open = false },
            onConfirm = { onChange(years[pendingIndex]); open = false },
        )
    }
}

/**
 * Height picker. Wheel over 50..250 cm. [value] null = not set; Clear restores
 * null. Defaults the wheel to 170 cm when not set.
 */
@Composable
fun HeightPickerField(
    value: Int?,
    onChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val heights = remember { (50..250).toList() }
    val notSet = stringResource(R.string.picker_not_set)
    val unitCm = stringResource(R.string.weight_height_label)
    val valueText = value?.let { stringResource(R.string.picker_height_value, it) } ?: notSet

    var open by remember { mutableStateOf(false) }
    val defaultHeight = 170
    var pendingIndex by remember { mutableIntStateOf(0) }

    PickerValueRow(
        label = label,
        valueText = valueText,
        isSet = value != null,
        onOpen = {
            pendingIndex = heights.indexOf(value ?: defaultHeight).coerceAtLeast(0)
            open = true
        },
        modifier = modifier,
    )

    if (open) {
        WheelDialog(
            title = label,
            items = heights,
            pendingIndex = pendingIndex,
            onPendingChange = { pendingIndex = it },
            label = { context.getString(R.string.picker_height_value, it) },
            wheelContentDescription = unitCm,
            showClear = true,
            onDismiss = { open = false },
            onClear = { onChange(null); open = false },
            onConfirm = { onChange(heights[pendingIndex]); open = false },
        )
    }
}

/**
 * Weight picker. Wheel over [WeightWheel]'s range for [unit] (kg ~20..300 / lb
 * the converted range, 0.1 step). Stores/returns canonical kg via [onChange].
 * [required] true hides Clear (ConfirmWeight); false allows null (onboarding).
 * Re-derives the wheel range/index whenever [unit] changes.
 */
@Composable
fun WeightPickerField(
    valueKg: Double?,
    unit: WeightUnit,
    onChange: (Double?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
) {
    val context = LocalContext.current
    val unitLabel = stringResource(
        if (unit == WeightUnit.Lb) R.string.weight_unit_lb else R.string.weight_unit_kg,
    )
    val notSet = stringResource(R.string.picker_not_set)
    val valueText = valueKg?.let { kg ->
        val display = if (unit == WeightUnit.Lb) WeightUnit.kgToLb(kg) else kg
        stringResource(R.string.weight_value_unit, WeightWheel.formatDisplay(display), unitLabel)
    } ?: notSet

    // The wheel items are display-unit values; rebuild when the unit changes so
    // kg/lb show the right numbers and range.
    val values = remember(unit) {
        List(WeightWheel.count(unit)) { i -> WeightWheel.valueAt(i, unit) }
    }
    val defaultKg = 70.0

    var open by remember { mutableStateOf(false) }
    var pendingIndex by remember { mutableIntStateOf(0) }

    PickerValueRow(
        label = label,
        valueText = valueText,
        isSet = valueKg != null,
        onOpen = {
            pendingIndex = WeightWheel.indexOfKg(valueKg ?: defaultKg, unit)
            open = true
        },
        modifier = modifier,
    )

    if (open) {
        WheelDialog(
            title = label,
            items = values,
            pendingIndex = pendingIndex,
            onPendingChange = { pendingIndex = it },
            label = { v ->
                context.getString(R.string.weight_value_unit, WeightWheel.formatDisplay(v), unitLabel)
            },
            wheelContentDescription = unitLabel,
            showClear = !required,
            onDismiss = { open = false },
            onClear = { onChange(null); open = false },
            onConfirm = { onChange(WeightWheel.kgAt(pendingIndex, unit)); open = false },
        )
    }
}
