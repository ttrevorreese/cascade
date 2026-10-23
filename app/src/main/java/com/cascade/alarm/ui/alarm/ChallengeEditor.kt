package com.cascade.alarm.ui.alarm

import android.Manifest
import android.os.Build
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import com.cascade.alarm.data.challenge.MemorySequence
import com.cascade.alarm.data.challenge.PhotoHash
import com.cascade.alarm.ui.camera.PhotoCamera
import com.cascade.alarm.ui.camera.PhotoCameraState
import com.cascade.alarm.ui.camera.PhotoGrids
import com.cascade.alarm.ui.camera.PhotoStore
import com.cascade.alarm.ui.camera.StepSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.cascade.alarm.ui.components.rememberPermissionRequest
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors

private enum class Kind(val label: String) {
    NONE("None"), MATH("Sums"), PHRASE("Type a phrase"), BARCODE("Scan a code"),
    PHOTO("Match a photo"), STEPS("Walk"), MEMORY("Repeat a sequence"),
}

private val ChallengeConfig.kind: Kind
    get() = when (this) {
        ChallengeConfig.None -> Kind.NONE
        is ChallengeConfig.Math -> Kind.MATH
        is ChallengeConfig.TypePhrase -> Kind.PHRASE
        is ChallengeConfig.BarcodeScan -> Kind.BARCODE
        is ChallengeConfig.PhotoMatch -> Kind.PHOTO
        is ChallengeConfig.Steps -> Kind.STEPS
        is ChallengeConfig.Memory -> Kind.MEMORY
    }

/**
 * Picks and configures a challenge (SPEC §6): an alarm's own, or a rung of the escalation ladder. Permissions are asked for here,
 * never at alarm time (SPEC §6.8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChallengeEditor(challenge: ChallengeConfig, onChange: (ChallengeConfig) -> Unit, showIntro: Boolean = true) {
    val c = cascadeColors
    val context = LocalContext.current
    val camera = rememberPermissionRequest(Manifest.permission.CAMERA) { hasCameraPermission(context) }
    val steps = rememberPermissionRequest(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null,
    ) { StepSensor.hasPermission(context) }
    var scanning by remember { mutableStateOf(false) }
    var showingQr by remember { mutableStateOf(false) }
    var photographing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
        if (showIntro) {
            Text("Challenge", style = CascadeType.rowLabel, color = c.textPrimary)
            Text("What it takes to dismiss this alarm.", style = CascadeType.body, color = c.textSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            Kind.entries.forEach { kind ->
                FilterChip(
                    selected = challenge.kind == kind,
                    onClick = {
                        if (challenge.kind == kind) return@FilterChip
                        onChange(
                            when (kind) {
                                Kind.NONE -> ChallengeConfig.None
                                Kind.MATH -> ChallengeConfig.Math()
                                Kind.PHRASE -> ChallengeConfig.TypePhrase()
                                Kind.BARCODE -> ChallengeConfig.BarcodeScan(expectedPayload = "", label = "")
                                Kind.PHOTO -> ChallengeConfig.PhotoMatch(referencePhotoId = "")
                                Kind.STEPS -> ChallengeConfig.Steps()
                                Kind.MEMORY -> ChallengeConfig.Memory()
                            },
                        )
                        if ((kind == Kind.BARCODE || kind == Kind.PHOTO) && !camera.granted) camera.request()
                        if (kind == Kind.STEPS && !steps.granted) steps.request()
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
                if (!camera.granted) {
                    Text(
                        "Without camera access this alarm asks for sums instead.",
                        style = CascadeType.body,
                        color = c.status.warn,
                    )
                    SecondaryButton("Allow the camera", { camera.request() }, Modifier.fillMaxWidth())
                }
                if (challenge.expectedPayload.isEmpty()) {
                    Text("Pick a code: one you already have, or one to print.", style = CascadeType.body, color = c.textSecondary)
                } else {
                    Text("Code saved. The alarm asks you to scan this exact code.", style = CascadeType.body, color = c.textSecondary)
                }
                SecondaryButton(
                    if (challenge.expectedPayload.isEmpty()) "Scan a code you have" else "Scan a different code",
                    { if (camera.granted) scanning = true else camera.request() },
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
            is ChallengeConfig.PhotoMatch -> {
                OutlinedTextField(
                    value = challenge.label,
                    onValueChange = { onChange(challenge.copy(label = it)) },
                    label = { Text("What it is") },
                    placeholder = { Text("Toothbrush") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!camera.granted) {
                    Text("Without camera access this alarm asks for sums instead.", style = CascadeType.body, color = c.status.warn)
                    SecondaryButton("Allow the camera", { camera.request() }, Modifier.fillMaxWidth())
                }
                val reference = remember(challenge.referencePhotoId) {
                    challenge.referencePhotoId.takeIf { it.isNotEmpty() }?.let { PhotoStore.load(context, it)?.asImageBitmap() }
                }
                if (reference != null) {
                    Image(
                        reference,
                        contentDescription = "Reference photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth(0.5f).aspectRatio(0.75f).clip(CascadeShapes.medium),
                    )
                }
                Text(
                    if (challenge.signature.isEmpty()) {
                        "Photograph something you have to get up for, like your toothbrush. Take it in the light you will have in the morning."
                    } else {
                        "To dismiss, you take a photo that matches this one."
                    },
                    style = CascadeType.body,
                    color = c.textSecondary,
                )
                SecondaryButton(
                    if (challenge.signature.isEmpty()) "Take the reference photo" else "Take a new reference photo",
                    { if (camera.granted) photographing = true else camera.request() },
                    Modifier.fillMaxWidth(),
                )
                if (photographing) {
                    ReferencePhotoDialog(
                        onTaken = { id, signature ->
                            // The old photo is no longer anyone's reference.
                            if (challenge.referencePhotoId.isNotEmpty()) PhotoStore.delete(context, challenge.referencePhotoId)
                            onChange(challenge.copy(referencePhotoId = id, signature = signature))
                            photographing = false
                        },
                        onClose = { photographing = false },
                    )
                }
            }
            is ChallengeConfig.Steps -> {
                if (!StepSensor.hasSensor(context)) {
                    Text("This phone has no step counter, so this alarm asks for sums instead.", style = CascadeType.body, color = c.status.warn)
                } else if (!steps.granted) {
                    Text("Without activity access this alarm asks for sums instead.", style = CascadeType.body, color = c.status.warn)
                    SecondaryButton("Allow step counting", steps.request, Modifier.fillMaxWidth())
                }
                Stepper(
                    "${challenge.count} steps",
                    onFewer = { onChange(challenge.copy(count = (challenge.count - 10).coerceAtLeast(ChallengeConfig.MIN_STEPS))) },
                    onMore = { onChange(challenge.copy(count = (challenge.count + 10).coerceAtMost(ChallengeConfig.MAX_STEPS))) },
                )
            }
            is ChallengeConfig.Memory -> {
                Text("Watch a sequence of shapes light up, then tap them back in order.", style = CascadeType.body, color = c.textSecondary)
                Stepper(
                    "${challenge.sequenceLength} in the sequence",
                    onFewer = { onChange(challenge.copy(sequenceLength = (challenge.sequenceLength - 1).coerceAtLeast(MemorySequence.MIN_LENGTH))) },
                    onMore = { onChange(challenge.copy(sequenceLength = (challenge.sequenceLength + 1).coerceAtMost(MemorySequence.MAX_LENGTH))) },
                )
            }
            ChallengeConfig.None -> Unit
        }
    }
}

@Composable
private fun Stepper(value: String, onFewer: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Text(value, style = CascadeType.rowLabel, color = cascadeColors.textPrimary, modifier = Modifier.weight(1f))
        SecondaryButton("Fewer", onFewer, small = true)
        SecondaryButton("More", onMore, small = true)
    }
}

@Composable
private fun ReferencePhotoDialog(onTaken: (id: String, signature: String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = remember { PhotoCameraState() }
    var failed by remember { mutableStateOf(false) }
    var tooPlain by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(cascadeColors.ground).safeDrawingPadding().padding(CascadeSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.l),
        ) {
            Text("Take the reference photo", style = CascadeType.screenTitle, color = cascadeColors.textPrimary)
            Text(
                if (tooPlain) "That is too plain to match reliably. Fill the frame with something with more detail."
                else "Fill most of the frame with it. Your morning photo needs to look like this one.",
                style = CascadeType.body,
                color = if (tooPlain) cascadeColors.status.warn else cascadeColors.textSecondary,
            )
            if (failed) {
                Text("The camera could not be opened.", style = CascadeType.body, color = cascadeColors.status.warn)
            } else {
                PhotoCamera(camera, onUnavailable = { failed = true }, modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).clip(CascadeShapes.card))
            }
            PrimaryButton(
                if (busy) "Saving" else "Take the photo",
                {
                    busy = true
                    scope.launch {
                        val photo = camera.take(context)
                        val signature = photo?.let { withContext(Dispatchers.Default) { PhotoHash.signature(PhotoGrids.reference(it)) } }
                        busy = false
                        when {
                            photo == null -> failed = true
                            signature == null -> tooPlain = true
                            else -> onTaken(withContext(Dispatchers.IO) { PhotoStore.save(context, photo) }, signature)
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = !busy && !failed,
            )
            SecondaryButton("Cancel", onClose, Modifier.fillMaxWidth())
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
