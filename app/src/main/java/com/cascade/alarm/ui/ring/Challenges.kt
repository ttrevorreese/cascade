package com.cascade.alarm.ui.ring

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cascade.alarm.data.challenge.Barcodes
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.GiveUp
import com.cascade.alarm.data.challenge.MathProblems
import com.cascade.alarm.data.challenge.Phrases
import com.cascade.alarm.data.challenge.challengeSeed
import com.cascade.alarm.engine.ring.RingingAlarm
import com.cascade.alarm.ui.camera.BarcodeScanner
import com.cascade.alarm.ui.camera.hasCameraPermission
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.Sora
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * The challenge actually shown for [configured]: one that cannot run here (no camera permission,
 * or a type whose screen arrives in M3b) becomes the math fallback, never a dead end (SPEC §6.8).
 */
fun runnableChallenge(configured: ChallengeConfig, context: Context): ChallengeConfig = when (configured) {
    is ChallengeConfig.Math, is ChallengeConfig.TypePhrase -> configured
    is ChallengeConfig.BarcodeScan -> if (hasCameraPermission(context)) configured else ChallengeConfig.FALLBACK
    is ChallengeConfig.Steps, is ChallengeConfig.PhotoMatch, is ChallengeConfig.Memory -> ChallengeConfig.FALLBACK
}

/** What the firing screen says is being asked, and what its button does (DESIGN.md §7). */
fun challengeAsk(config: ChallengeConfig): Pair<String, String> = when (config) {
    is ChallengeConfig.Math ->
        (if (config.problemCount == 1) "Solve a sum to dismiss." else "Solve ${config.problemCount} sums to dismiss.") to "Start the sums"
    is ChallengeConfig.TypePhrase -> "Type a phrase to dismiss." to "Show the phrase"
    is ChallengeConfig.BarcodeScan ->
        (if (config.label.isBlank()) "Scan your code to dismiss." else "Scan the code at ${config.label}.") to "Open the scanner"
    is ChallengeConfig.Steps -> "Walk ${config.count} steps to dismiss." to "Start walking"
    is ChallengeConfig.PhotoMatch -> "Take the matching photo to dismiss." to "Open the camera"
    is ChallengeConfig.Memory -> "Repeat the sequence to dismiss." to "Start"
}

/**
 * Runs [ringing]'s challenge. [onInteract] marks activity for sound ducking; [onComplete] ends
 * the alarm; the give-up escape hatch appears after [GiveUp.FAILED_ATTEMPTS] misses or
 * [GiveUp.AFTER] of ringing, and is confirmed before it is used (SPEC §6.7).
 */
@Composable
fun ChallengeHost(
    ringing: RingingAlarm,
    config: ChallengeConfig,
    onColor: Color,
    onInteract: () -> Unit,
    onComplete: () -> Unit,
    onGiveUp: () -> Unit,
) {
    val context = LocalContext.current
    var cameraFailed by rememberSaveable { mutableStateOf(false) }
    val shown = if (cameraFailed) ChallengeConfig.FALLBACK else runnableChallenge(config, context)
    val seed = challengeSeed(ringing.alarmId, ringing.scheduledFor)
    var misses by rememberSaveable { mutableStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val miss: () -> Unit = { misses++ }
    // A scanner reports the same code on every frame; the alarm must be ended exactly once.
    var completed by remember { mutableStateOf(false) }
    val complete = {
        if (!completed) {
            completed = true
            onComplete()
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (shown) {
            is ChallengeConfig.Math -> MathChallenge(shown, seed, onColor, onInteract, miss, complete)
            is ChallengeConfig.TypePhrase -> PhraseChallenge(shown.phrase.ifBlank { Phrases.generated(seed) }, onColor, onInteract, complete)
            is ChallengeConfig.BarcodeScan -> BarcodeChallenge(shown, onColor, onInteract, miss, complete) { cameraFailed = true }
            else -> Unit // runnableChallenge never returns the M3b types yet
        }
        if (GiveUp.available(misses, Duration.between(ringing.startedAt, now))) GiveUpHatch(onColor, onGiveUp)
    }
}

@Composable
private fun GiveUpHatch(onColor: Color, onGiveUp: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { confirming = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Give up", color = onColor, style = CascadeType.rowLabel)
        }
        Text("Recorded in your history", color = onColor.copy(alpha = 0.8f), style = CascadeType.caption)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Give up on this alarm?", style = CascadeType.screenTitle) },
            text = { Text("It stops ringing, and your history records that you gave up.") },
            confirmButton = { TextButton(onClick = { confirming = false; onGiveUp() }) { Text("Give up") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun MathChallenge(
    config: ChallengeConfig.Math,
    seed: Long,
    onColor: Color,
    onInteract: () -> Unit,
    onMiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val problems = remember(seed, config) { MathProblems.generate(seed, config.difficulty, config.problemCount) }
    var index by rememberSaveable { mutableStateOf(0) }
    var entry by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    val problem = problems[index.coerceAtMost(problems.lastIndex)]
    val numberStyle = TextStyle(fontFamily = Sora, fontSize = 40.sp, fontFeatureSettings = "tnum", textAlign = TextAlign.Center)

    Text("Sum ${index + 1} of ${problems.size}", color = onColor, style = CascadeType.body)
    Text("${problem.text} =", color = onColor, style = numberStyle, modifier = Modifier.padding(top = 8.dp))
    Box(
        Modifier.padding(vertical = 12.dp).fillMaxWidth(0.6f).heightIn(min = 64.dp)
            .border(BorderStroke(2.dp, onColor), CascadeShapes.medium)
            .semantics { contentDescription = "Your answer: ${entry.ifEmpty { "empty" }}" }
            .testTag("math-entry"),
        contentAlignment = Alignment.Center,
    ) { Text(entry, color = onColor, style = numberStyle) }
    Text(
        if (wrong) "Not quite. Try again." else " ",
        color = onColor,
        style = CascadeType.rowLabel,
    )
    val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val label = when (key) {
                        "⌫" -> "Delete"
                        "✓" -> "Check answer"
                        else -> key
                    }
                    OutlinedButton(
                        onClick = {
                            onInteract()
                            when (key) {
                                "⌫" -> entry = entry.dropLast(1)
                                "✓" -> if (entry.toIntOrNull() == problem.answer) {
                                    wrong = false
                                    entry = ""
                                    if (index == problems.lastIndex) onComplete() else index++
                                } else if (entry.isNotEmpty()) {
                                    wrong = true
                                    entry = ""
                                    onMiss()
                                }
                                else -> if (entry.length < 5) {
                                    entry += key
                                    wrong = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).semantics { contentDescription = label },
                        shape = CascadeShapes.medium,
                        border = BorderStroke(1.5.dp, onColor.copy(alpha = 0.6f)),
                    ) { Text(key, color = onColor, style = CascadeType.screenTitle) }
                }
            }
        }
    }
}

/** No cut / copy / paste menu, so the phrase has to be typed (SPEC §6.5). */
private object NoTextToolbar : TextToolbar {
    override val status = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}

@Composable
private fun PhraseChallenge(phrase: String, onColor: Color, onInteract: () -> Unit, onComplete: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    // Open the keyboard straight away: one less tap at 6am.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text("Type this:", color = onColor, style = CascadeType.body)
    Text(
        phrase,
        color = onColor,
        style = CascadeType.screenTitle,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 12.dp),
    )
    CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
        OutlinedTextField(
            value = typed,
            onValueChange = { next ->
                onInteract()
                // A jump of several characters at once is a paste or a clipboard suggestion.
                if (Phrases.acceptEdit(typed, next)) typed = next
                if (Phrases.matches(phrase, typed)) onComplete()
            },
            singleLine = true,
            textStyle = CascadeType.rowLabel.copy(color = onColor),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = onColor,
                unfocusedBorderColor = onColor.copy(alpha = 0.6f),
                cursorColor = onColor,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("phrase-entry"),
        )
    }
}

@Composable
private fun BarcodeChallenge(
    config: ChallengeConfig.BarcodeScan,
    onColor: Color,
    onInteract: () -> Unit,
    onMiss: () -> Unit,
    onComplete: () -> Unit,
    onCameraFailed: () -> Unit,
) {
    var lastWrong by remember { mutableStateOf<String?>(null) }
    Text(
        if (config.label.isBlank()) "Point the camera at your code." else "Point the camera at the code at ${config.label}.",
        color = onColor,
        style = CascadeType.rowLabel,
        textAlign = TextAlign.Center,
    )
    BarcodeScanner(
        onScanned = { payload ->
            onInteract()
            if (Barcodes.matches(config.expectedPayload, payload)) {
                onComplete()
            } else if (payload != lastWrong) {
                // Count each wrong code once, not once per camera frame.
                lastWrong = payload
                onMiss()
            }
        },
        onUnavailable = onCameraFailed,
        modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth().aspectRatio(1f).clip(CascadeShapes.firing),
    )
    Text(if (lastWrong != null) "That is a different code." else " ", color = onColor, style = CascadeType.rowLabel)
}
