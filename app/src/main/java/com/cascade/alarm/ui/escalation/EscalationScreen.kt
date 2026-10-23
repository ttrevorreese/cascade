package com.cascade.alarm.ui.escalation

import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.ui.ChallengeText
import com.cascade.alarm.ui.alarm.ChallengeEditor
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.camera.StepSensor
import com.cascade.alarm.ui.camera.hasCameraPermission
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.components.rememberPermissionRequest
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import com.cascade.alarm.ui.theme.rampStopFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EscalationState(val group: AlarmGroupEntity? = null, val alarmsOn: Int = 0, val draft: EscalationPolicy = EscalationPolicy())

@HiltViewModel
class EscalationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])
    private val mutable = MutableStateFlow(EscalationState())
    val state: StateFlow<EscalationState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val group = repository.observeGroup(groupId).first()
            val alarms = repository.observeAlarms(groupId).first()
            mutable.value = EscalationState(group, alarms.count { it.isEnabled }, group?.escalationPolicy ?: EscalationPolicy())
        }
    }

    fun edit(policy: EscalationPolicy) {
        mutable.value = mutable.value.copy(draft = policy)
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        repository.setEscalationPolicy(groupId, state.value.draft)
        onDone()
    }
}

@Composable
fun EscalationScreen(onDone: () -> Unit, viewModel: EscalationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val group = state.group ?: return
    EscalationContent(
        groupName = group.name,
        alarmsOn = state.alarmsOn,
        policy = state.draft,
        onChange = viewModel::edit,
        onSave = { viewModel.save(onDone) },
        onCancel = onDone,
    )
}

/**
 * The escalation ladder (SPEC §7.4, DESIGN.md §6): a 5dp gradient rail down the left with the
 * rungs beside it. Each rung shows its challenge, the alarms it covers and a five-bar difficulty
 * meter; the selected rung gets a 2dp outline in its own ramp colour and opens for editing.
 */
@Composable
fun EscalationContent(
    groupName: String,
    alarmsOn: Int,
    policy: EscalationPolicy,
    onChange: (EscalationPolicy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = cascadeColors
    val context = LocalContext.current
    val rungs = policy.steps
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    fun update(steps: List<ChallengeConfig>) = onChange(EscalationPolicy(steps))
    // Rungs need their permissions asked for here, at setup, never at alarm time (SPEC §6.8).
    val camera = rememberPermissionRequest(Manifest.permission.CAMERA) { hasCameraPermission(context) }
    val steps = rememberPermissionRequest(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null,
    ) { StepSensor.hasPermission(context) }
    val hasStepCounter = remember { StepSensor.hasSensor(context) }
    val walks = rungs.any { it is ChallengeConfig.Steps }
    val usesCamera = rungs.any { it is ChallengeConfig.BarcodeScan || it is ChallengeConfig.PhotoMatch }
    val useSuggested = {
        update(Escalation.SUGGESTED.steps)
        if (hasStepCounter && !steps.granted) steps.request()
    }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            CascadeTopBar("Escalation", onBack = onCancel) {
                TextButton(onClick = onSave, modifier = Modifier.testTag("save-escalation")) { Text("Save escalation") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
        ) {
            item {
                Text(groupName, style = CascadeType.rowLabel, color = c.textSecondary)
                Text(
                    "Each alarm gets the rung for its place in the sequence, counting only alarms that are on. " +
                        "The last rung repeats for any alarms after it. An alarm's own challenge overrides its rung.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = CascadeSpace.xs, bottom = CascadeSpace.l),
                )
            }
            if (rungs.isEmpty()) {
                item {
                    Text(
                        "No rungs yet. Alarms in this group are dismissed with one tap, unless one has its own challenge.",
                        style = CascadeType.body,
                        color = c.textPrimary,
                        modifier = Modifier.padding(bottom = CascadeSpace.l),
                    )
                    PrimaryButton("Use the suggested ladder", useSuggested, Modifier.fillMaxWidth())
                }
            }
            if (walks && !hasStepCounter) {
                item { PermissionProblem("This phone has no step counter, so walking rungs ask for sums instead.") }
            } else if (walks && !steps.granted) {
                item {
                    PermissionProblem("Walking rungs need step counting. Without it they ask for sums instead.", "Allow step counting", steps.request)
                }
            }
            if (usesCamera && !camera.granted) {
                item {
                    PermissionProblem("Scan and photo rungs need the camera. Without it they ask for sums instead.", "Allow the camera", camera.request)
                }
            }
            itemsIndexed(rungs) { index, rung ->
                val color = c.ramp(rampStopFor(index, rungs.size))
                RungRow(
                    index = index,
                    rungs = rungs.size,
                    alarmsOn = alarmsOn,
                    rung = rung,
                    color = color,
                    previous = if (index > 0) c.ramp(rampStopFor(index - 1, rungs.size)) else null,
                    next = if (index < rungs.lastIndex) c.ramp(rampStopFor(index + 1, rungs.size)) else null,
                    selected = selected == index,
                    onClick = { selected = if (selected == index) null else index },
                ) {
                    ChallengeEditor(rung, { changed -> update(rungs.toMutableList().apply { set(index, changed) }) }, showIntro = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s), modifier = Modifier.padding(top = CascadeSpace.m)) {
                        if (index > 0) {
                            SecondaryButton("Earlier", {
                                update(rungs.toMutableList().apply { add(index - 1, removeAt(index)) })
                                selected = index - 1
                            }, small = true)
                        }
                        if (index < rungs.lastIndex) {
                            SecondaryButton("Later", {
                                update(rungs.toMutableList().apply { add(index + 1, removeAt(index)) })
                                selected = index + 1
                            }, small = true)
                        }
                        SecondaryButton("Remove", {
                            update(rungs.toMutableList().apply { removeAt(index) })
                            selected = null
                        }, small = true)
                    }
                }
            }
            if (rungs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(CascadeSpace.l))
                    SecondaryButton("Add a rung", {
                        // A new top rung starts as a copy of the hardest so far, ready to make harder.
                        update(rungs + rungs.last())
                        selected = rungs.size
                    }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun RungRow(
    index: Int,
    rungs: Int,
    alarmsOn: Int,
    rung: ChallengeConfig,
    color: Color,
    previous: Color?,
    next: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    editor: @Composable () -> Unit,
) {
    val c = cascadeColors
    val covers = Escalation.alarmsFor(index, rungs, alarmsOn)
    val coverage = when {
        covers == null -> "No alarm uses this rung yet"
        covers.first == covers.last -> "Alarm ${covers.first}"
        else -> "Alarms ${covers.first} to ${covers.last}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // The rail: 5dp, one continuous gradient through the rungs' ramp stops.
                val x = RAIL_COLUMN.toPx() / 2
                val w = 5.dp.toPx()
                val mid = 36.dp.toPx()
                val top = if (previous != null) lerp(previous, color, 0.5f) else color
                val bottom = if (next != null) lerp(color, next, 0.5f) else color
                val end = if (next != null) size.height else mid
                val start = if (previous != null) 0f else mid
                val brush = Brush.verticalGradient(listOf(top, color, bottom), start, end)
                // Square where rows meet, so the rail reads as one line; round only its two ends.
                drawRect(brush, Offset(x - w / 2, start), Size(w, end - start))
                if (previous == null) drawCircle(color, w / 2, Offset(x, start))
                if (next == null) drawCircle(color, w / 2, Offset(x, end))
            }
            .padding(vertical = CascadeSpace.s),
    ) {
        Box(Modifier.width(RAIL_COLUMN))
        Surface(
            onClick = onClick,
            shape = CascadeShapes.medium,
            color = c.surface,
            border = if (selected) BorderStroke(2.dp, color) else null,
            modifier = Modifier.weight(1f).testTag("rung-$index"),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rung ${index + 1} · $coverage", style = CascadeType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    DifficultyMeter(Escalation.difficulty(rung), color)
                }
                Text(ChallengeText.full(rung), style = CascadeType.rowLabel, color = c.textPrimary, modifier = Modifier.padding(top = 4.dp))
                if (selected) {
                    Spacer(Modifier.height(CascadeSpace.m))
                    editor()
                }
            }
        }
    }
}

/** A problem first, in a bordered warning card with its action (DESIGN.md §6). */
@Composable
private fun PermissionProblem(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = cascadeColors
    Surface(
        shape = CascadeShapes.medium,
        color = c.surface,
        border = BorderStroke(1.5.dp, c.status.warn),
        modifier = Modifier.fillMaxWidth().padding(bottom = CascadeSpace.m),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            Text(text, style = CascadeType.body, color = c.textPrimary)
            if (action != null) SecondaryButton(action, onAction, small = true)
        }
    }
}

/** Five bars, rising in height as well as filling, so the level reads without colour (DESIGN.md §9). */
@Composable
private fun DifficultyMeter(level: Int, color: Color) {
    val c = cascadeColors
    Canvas(Modifier.size(width = 36.dp, height = 18.dp).semantics { contentDescription = "Difficulty $level of 5" }) {
        val bar = 5.dp.toPx()
        val gap = (size.width - bar * 5) / 4
        for (i in 0 until 5) {
            val h = size.height * (i + 2) / 6
            drawRoundRect(
                if (i < level) color else c.line,
                Offset(i * (bar + gap), size.height - h),
                Size(bar, h),
                CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

private val RAIL_COLUMN = 24.dp
