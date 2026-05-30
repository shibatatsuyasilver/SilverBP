package com.silverbp.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silverbp.android.R
import com.silverbp.android.ui.theme.BpRedSbp

val BpReadingDisplay = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    fontSize = 64.sp,
    lineHeight = 68.sp,
    letterSpacing = (-1).sp,
)

val BpSlashSeparator = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Light,
    fontSize = 48.sp,
    lineHeight = 52.sp,
)

@Composable
fun BpReadingValue(
    systolic: Int,
    diastolic: Int,
    modifier: Modifier = Modifier,
    sbpColor: Color = BpRedSbp,
    dbpColor: Color = MaterialTheme.colorScheme.onSurface,
    separatorColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // The number font is in sp, so it already honours the system font-scale
    // setting. The a11y win here is merging the three Texts into one TalkBack
    // announcement ("Blood pressure reading 120 / 80") instead of reading the
    // bare "/" as "slash".
    val a11yLabel = stringResource(R.string.a11y_blood_pressure_reading)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$a11yLabel $systolic / $diastolic"
        },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(systolic.toString(), style = BpReadingDisplay, color = sbpColor)
        Text("/", style = BpSlashSeparator, color = separatorColor)
        Text(diastolic.toString(), style = BpReadingDisplay, color = dbpColor)
    }
}
