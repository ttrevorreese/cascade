package com.cascade.alarm.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cascade.alarm.R

/**
 * Sora, bundled as one variable font so the firing screen has it offline and before first unlock
 * (DESIGN.md §4). Only the four weights the design uses are declared.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Sora = FontFamily(
    listOf(300, 400, 500, 600).map { w ->
        Font(R.font.sora, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** Tabular figures, so times don't jitter as digits change. */
private const val TABULAR = "tnum"

/** The type roles from DESIGN.md §4. Times are tabular. */
object CascadeType {
    val firingTime = TextStyle(fontFamily = Sora, fontSize = 92.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.045).em, fontFeatureSettings = TABULAR)
    val firingTimeLate = TextStyle(fontFamily = Sora, fontSize = 68.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.04).em, fontFeatureSettings = TABULAR)
    val homeNextAlarm = TextStyle(fontFamily = Sora, fontSize = 62.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.035).em, fontFeatureSettings = TABULAR)
    val screenTitle = TextStyle(fontFamily = Sora, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em)
    val alarmRowTime = TextStyle(fontFamily = Sora, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR)
    val cardTitle = TextStyle(fontFamily = Sora, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val rowLabel = TextStyle(fontFamily = Sora, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = Sora, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = Sora, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val button = TextStyle(fontFamily = Sora, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}
