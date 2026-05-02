package com.silverbp.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(systolic.toString(), style = BpReadingDisplay, color = sbpColor)
        Text("/", style = BpSlashSeparator, color = separatorColor)
        Text(diastolic.toString(), style = BpReadingDisplay, color = dbpColor)
    }
}
