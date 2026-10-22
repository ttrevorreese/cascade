package com.cascade.alarm.ui.ring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cascade.alarm.R
import com.cascade.alarm.engine.ring.RingingAlarm
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Legible half-asleep: fixed dark palette, very large time, one huge button (SPEC §7). */
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
    val wakeCheck = ringing?.isWakeCheck == true
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
            color = Color.White,
            fontSize = 88.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = if (wakeCheck) stringResource(R.string.wake_check_title) else ringing?.label ?: stringResource(R.string.ring_title_default),
            color = Color(0xFFFCA311),
            fontSize = 28.sp,
        )
        if (wakeCheck) {
            val seconds = Duration.between(instant, ringing!!.deadline).seconds.coerceAtLeast(0)
            Text(
                text = stringResource(R.string.wake_check_countdown, seconds),
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.height(96.dp))
        Button(
            onClick = onDismiss,
            enabled = ringing != null,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFCA311), contentColor = Color.Black),
        ) {
            Text(
                stringResource(if (wakeCheck) R.string.wake_check_action else R.string.ring_action_dismiss),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
