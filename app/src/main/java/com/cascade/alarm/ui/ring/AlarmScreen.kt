package com.cascade.alarm.ui.ring

import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cascade.alarm.R
import com.cascade.alarm.engine.ring.RingingAlarm
import com.cascade.alarm.ui.components.SequenceDots
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.Sora
import com.cascade.alarm.ui.theme.cascadeColors
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max

/**
 * Legible half-asleep (SPEC §7, DESIGN.md §6): two things above the fold, the time and what is
 * being asked. The palette follows the alarm's place in its sequence, from a dark glow at the
 * first alarm to full-bleed ramp5 at the last, and the sequence dots say whether more are coming.
 */
@Composable
fun AlarmScreen(ringing: RingingAlarm?, onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    var instant by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            instant = Instant.now()
            delay(1_000)
        }
    }
    val c = cascadeColors
    // Until the ring state arrives, stay on the first alarm's dark palette: never flash bright.
    val palette = FiringPalette.of(c, ringing?.position ?: 0, if (ringing == null) 2 else ringing.total)
    // Dark bar icons over the bright late-sequence screens, light ones over the dark early ones.
    val view = LocalView.current
    val darkText = palette.text.luminance() < 0.5f
    SideEffect {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = darkText
            WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = darkText
        }
    }
    val wakeCheck = ringing?.isWakeCheck == true
    val isLast = ringing != null && ringing.position >= ringing.total - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        *FiringPalette.STOPS.zip(palette.gradient).toTypedArray(),
                        center = Offset(size.width / 2, size.height * 0.36f),
                        radius = max(size.width, size.height) * 0.8f,
                    ),
                )
            }
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (ringing != null) SequenceDots(ringing.position, ringing.total, onColor = palette.text)
        BasicText(
            text = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            color = { palette.text },
            maxLines = 1,
            // Shrinks rather than wraps at large font scales (DESIGN.md §9: works at 200%).
            autoSize = TextAutoSize.StepBased(minFontSize = 36.sp, maxFontSize = palette.timeSize),
            style = TextStyle(
                fontFamily = Sora,
                fontWeight = palette.timeWeight,
                letterSpacing = (-0.04).em,
                fontFeatureSettings = "tnum",
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = when {
                wakeCheck -> stringResource(R.string.wake_check_title)
                isLast -> stringResource(R.string.ring_last_one)
                ringing != null -> stringResource(R.string.ring_position, ringing.position + 1, ringing.total)
                else -> stringResource(R.string.ring_title_default)
            },
            color = palette.text,
            style = CascadeType.screenTitle,
            textAlign = TextAlign.Center,
        )
        if (wakeCheck) {
            val seconds = Duration.between(instant, ringing!!.deadline).seconds.coerceAtLeast(0)
            Text(
                text = stringResource(R.string.wake_check_countdown, seconds),
                color = palette.text,
                style = CascadeType.rowLabel,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else if (ringing?.label != null) {
            Text(ringing.label, color = palette.text, style = CascadeType.rowLabel, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(72.dp))
        Button(
            onClick = onDismiss,
            enabled = ringing != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
            shape = CascadeShapes.firing,
            colors = ButtonDefaults.buttonColors(containerColor = palette.text, contentColor = palette.gradient.first()),
            elevation = null,
        ) {
            Text(
                stringResource(if (wakeCheck) R.string.wake_check_action else R.string.ring_action_dismiss),
                style = CascadeType.screenTitle,
            )
        }
    }
}
