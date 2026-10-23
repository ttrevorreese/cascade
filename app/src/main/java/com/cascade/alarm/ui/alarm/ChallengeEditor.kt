package com.cascade.alarm.ui.alarm

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.ui.camera.BarcodeScanner
import com.cascade.alarm.ui.camera.hasCameraPermission
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors

private enum class Kind(val label: String) { NONE("None"), MATH("Sums"), PHRASE("Type a phrase"), BARCODE("Scan a code") }

private val ChallengeConfig?.kind: Kind
    get() = when (this) {
        null -> Kind.NONE
        is ChallengeConfig.Math -> Kind.MATH
        is ChallengeConfig.TypePhrase -> Kind.PHRASE
        is ChallengeConfig.BarcodeScan -> Kind.BARCODE
        // M3b types are not offered yet; show them as what the alarm would fall back to.
        else -> Kind.MATH
    }

/**
 * Picks and configures what dismissing the alarm takes (SPEC §6). Permissions are asked for here,
 * never at alarm time (SPEC §6.8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChallengeEditor(challenge: ChallengeConfig?, onChange: (ChallengeConfig?) -> Unit) {
    val c = cascadeColors
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    var scanning by remember { mutableStateOf(false) }
    var showingQr by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
        Text("Challenge", style = CascadeType.rowLabel, color = c.textPrimary)
        Text("What it takes to dismiss this alarm.", style = CascadeType.body, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            Kind.entries.forEach { kind ->
                FilterChip(
                    selected = challenge.kind == kind,
                    onClick = {
                        if (challenge.kind == kind) return@FilterChip
                        onChange(
                            when (kind) {
                                Kind.NONE -> null
                                Kind.MATH -> ChallengeConfig.Math()
                                Kind.PHRASE -> ChallengeConfig.TypePhrase()
                                Kind.BARCODE -> ChallengeConfig.BarcodeScan(expectedPayload = "", label = "")
                            },
                        )
                        if (kind == Kind.BARCODE && !cameraGranted) cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    label = { Text(kind.label) },
                    shape = CascadeShapes.small,
                    modifier = Modifier.testTag("challenge-${kind.name.lowercase()}"),
                )
            }
        }

        when (challenge) {
            is ChallengeConfig.Math -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
                    Difficulty.entries.forEach { d ->
                        FilterChip(
                            selected = challenge.difficulty == d,
                            onClick = { onChange(challenge.copy(difficulty = d)) },
                            label = { Text(difficultyLabel(d)) },
                            shape = CascadeShapes.small,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
                    Text(
                        if (challenge.problemCount == 1) "1 sum" else "${challenge.problemCount} sums",
                        style = CascadeType.rowLabel,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Fewer", {
                        onChange(challenge.copy(problemCount = (challenge.problemCount - 1).coerceAtLeast(ChallengeConfig.MIN_PROBLEMS)))
                    }, small = true)
                    SecondaryButton("More", {
                        onChange(challenge.copy(problemCount = (challenge.problemCount + 1).coerceAtMost(ChallengeConfig.MAX_PROBLEMS)))
                    }, small = true)
                }
            }
            is ChallengeConfig.TypePhrase -> {
                OutlinedTextField(
                    value = challenge.phrase,
                    onValueChange = { onChange(challenge.copy(phrase = it)) },
                    label = { Text("Phrase") },
                    supportingText = { Text("Leave empty for four random words each time.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is ChallengeConfig.BarcodeScan -> {
                OutlinedTextField(
                    value = challenge.label,
                    onValueChange = { onChange(challenge.copy(label = it)) },
                    label = { Text("Where the code lives") },
                    placeholder = { Text("Bathroom mirror") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!cameraGranted) {
                    Text(
                        "Without camera access this alarm asks for sums instead.",
                        style = CascadeType.body,
                        color = c.status.warn,
                    )
                    SecondaryButton("Allow the camera", { cameraPermission.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                }
                if (challenge.expectedPayload.isEmpty()) {
                    Text("Pick a code: one you already have, or one to print.", style = CascadeType.body, color = c.textSecondary)
                } else {
                    Text("Code saved. The alarm asks you to scan this exact code.", style = CascadeType.body, color = c.textSecondary)
                }
                SecondaryButton(
                    if (challenge.expectedPayload.isEmpty()) "Scan a code you have" else "Scan a different code",
                    { if (cameraGranted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA) },
                    Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    if (QrCodes.isOurs(challenge.expectedPayload)) "Show the code to print" else "Make a code to print",
                    {
                        if (!QrCodes.isOurs(challenge.expectedPayload)) onChange(challenge.copy(expectedPayload = QrCodes.newPayload()))
                        showingQr = true
                    },
                    Modifier.fillMaxWidth(),
                )
                if (scanning) {
                    ScannerDialog(
                        onScanned = { onChange(challenge.copy(expectedPayload = it)); scanning = false },
                        onClose = { scanning = false },
                    )
                }
                if (showingQr && QrCodes.isOurs(challenge.expectedPayload)) {
                    QrDialog(challenge.expectedPayload, challenge.label, onClose = { showingQr = false })
                }
            }
            else -> Unit
        }
    }
}

private fun difficultyLabel(d: Difficulty) = when (d) {
    Difficulty.EASY -> "Easy"
    Difficulty.MEDIUM -> "Medium"
    Difficulty.HARD -> "Hard"
}

@Composable
private fun ScannerDialog(onScanned: (String) -> Unit, onClose: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(cascadeColors.ground).safeDrawingPadding().padding(CascadeSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.l),
        ) {
            Text("Point the camera at the code", style = CascadeType.screenTitle, color = cascadeColors.textPrimary)
            if (failed) {
                Text("The camera could not be opened.", style = CascadeType.body, color = cascadeColors.status.warn)
            } else {
                BarcodeScanner(
                    onScanned = onScanned,
                    onUnavailable = { failed = true },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CascadeShapes.card),
                )
            }
            SecondaryButton("Cancel", onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QrDialog(payload: String, label: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val image = remember(payload) { QrCodes.bitmap(payload).asImageBitmap() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(cascadeColors.ground).safeDrawingPadding().padding(CascadeSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.l),
        ) {
            Text("Your code", style = CascadeType.screenTitle, color = cascadeColors.textPrimary)
            Text(
                "Print it and stick it where you have to walk to, like the bathroom mirror. The alarm asks you to scan it.",
                style = CascadeType.body,
                color = cascadeColors.textSecondary,
            )
            // White behind the code whatever the theme: scanners need the contrast.
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CascadeShapes.card).background(Color.White).padding(16.dp)) {
                Image(image, contentDescription = "QR code for this alarm", modifier = Modifier.fillMaxSize())
            }
            PrimaryButton("Share or print", { QrCodes.share(context, payload, label) }, Modifier.fillMaxWidth())
            SecondaryButton("Done", onClose, Modifier.fillMaxWidth())
        }
    }
}
