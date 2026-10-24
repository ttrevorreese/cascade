package com.cascade.alarm.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.stats.ChallengeStats
import com.cascade.alarm.data.stats.Stats
import com.cascade.alarm.data.stats.StatsRules
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(events: AlarmEventDao, private val time: TimeSource) : ViewModel() {
    private val period = MutableStateFlow(StatsRules.PERIODS.first())
    private val loaded = MutableStateFlow<List<AlarmEventEntity>?>(null)

    /** Null while the log is read. */
    val stats: StateFlow<Stats?> = combine(loaded, period) { list, days ->
        list?.let { StatsRules.compute(it, time.now(), days) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val longest = StatsRules.PERIODS.max().toLong()
            loaded.value = events.since(time.now().minus(Duration.ofDays(longest + 1)))
        }
    }

    fun setPeriod(days: Int) {
        period.value = days
    }
}

@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    StatsContent(stats, viewModel::setPeriod, onBack)
}

/** SPEC §7.8: how the mornings went, from Cascade's own log on this phone. */
@Composable
fun StatsContent(stats: Stats?, onPeriod: (Int) -> Unit, onBack: () -> Unit) {
    val c = cascadeColors
    Scaffold(containerColor = c.ground, topBar = { CascadeTopBar("Stats", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.m),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatsRules.PERIODS.forEach { days ->
                        FilterChip(
                            selected = stats?.days == days,
                            onClick = { onPeriod(days) },
                            label = { Text("$days days") },
                            modifier = Modifier.testTag("period-$days"),
                        )
                    }
                }
            }
            when {
                stats == null -> item { Text("Reading the log…", style = CascadeType.body, color = c.textSecondary) }
                stats.isEmpty -> item {
                    Text(
                        "Nothing yet. These fill in as your alarms ring.",
                        style = CascadeType.body,
                        color = c.textSecondary,
                        modifier = Modifier.testTag("stats-empty"),
                    )
                }
                else -> {
                    item { Totals(stats) }
                    item { DismissCard(stats) }
                    if (stats.challenges.isNotEmpty()) item { ChallengesCard(stats.challenges) }
                    if (stats.wakeChecksPassed + stats.wakeChecksFailed > 0) {
                        item {
                            Card("Wake-up checks") {
                                Line("${stats.wakeChecksPassed} answered, ${stats.wakeChecksFailed} missed and rang again")
                            }
                        }
                    }
                    if (stats.leftScreen + stats.poweredOff > 0) {
                        item {
                            Card("Getting around the alarm") {
                                if (stats.leftScreen > 0) Line("Left the alarm screen while it rang: ${times(stats.leftScreen)}")
                                if (stats.poweredOff > 0) Line("Turned the phone off while it rang: ${times(stats.poweredOff)}")
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Worked out on this phone from Cascade's own log. None of it leaves the phone.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = CascadeSpace.m),
                )
            }
        }
    }
}

@Composable
private fun Totals(stats: Stats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Total("Rang", stats.rang, Modifier.weight(1f))
        Total("Snoozed", stats.snoozed, Modifier.weight(1f))
        Total("Gave up", stats.gaveUp, Modifier.weight(1f))
        Total("Missed", stats.missed, Modifier.weight(1f))
    }
}

@Composable
private fun Total(label: String, value: Int, modifier: Modifier) {
    val c = cascadeColors
    Surface(shape = CascadeShapes.medium, color = c.surface, modifier = modifier.testTag("total-$label")) {
        Column(Modifier.padding(vertical = CascadeSpace.m, horizontal = CascadeSpace.s)) {
            Text("$value", style = CascadeType.homeNextAlarm, color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(label, style = CascadeType.body, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DismissCard(stats: Stats) {
    val c = cascadeColors
    Card("Time to dismiss") {
        Line(stats.medianToDismiss?.let { "Usually ${StatsText.duration(it)} from the first sound to Dismiss" } ?: "No alarm was dismissed in this time")
        val longest = stats.byDay.mapNotNull { it.medianToDismiss }.maxOrNull()
        if (longest != null) {
            val bar = c.textPrimary
            val empty = c.textSecondary.copy(alpha = 0.3f)
            val described = stats.byDay.joinToString("; ") { day ->
                "${day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}: " + (day.medianToDismiss?.let(StatsText::duration) ?: "none")
            }
            Canvas(
                Modifier.fillMaxWidth().height(96.dp).padding(top = CascadeSpace.m).semantics { contentDescription = described },
            ) {
                val slot = size.width / stats.byDay.size
                val width = (slot * 0.6f).coerceAtMost(28.dp.toPx())
                stats.byDay.forEachIndexed { i, day ->
                    val x = slot * i + (slot - width) / 2
                    val share = day.medianToDismiss?.let { it.toMillis().toFloat() / longest.toMillis().coerceAtLeast(1) } ?: 0f
                    val h = if (share > 0f) (size.height * share).coerceAtLeast(4.dp.toPx()) else 3.dp.toPx()
                    drawRoundRect(
                        color = if (share > 0f) bar else empty,
                        topLeft = Offset(x, size.height - h),
                        size = Size(width, h),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
            if (stats.byDay.size <= 7) {
                Row(Modifier.fillMaxWidth()) {
                    stats.byDay.forEach { day ->
                        Text(
                            day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = CascadeType.body,
                            color = c.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChallengesCard(challenges: List<ChallengeStats>) {
    val c = cascadeColors
    Card("Challenges") {
        challenges.forEach { stat ->
            Column(Modifier.padding(vertical = CascadeSpace.xs).testTag("challenge-${stat.kind}")) {
                Text(StatsText.challenge(stat.kind), style = CascadeType.rowLabel, color = c.textPrimary)
                Text(StatsText.challengeLine(stat), style = CascadeType.body, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    val c = cascadeColors
    Surface(shape = CascadeShapes.medium, color = c.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CascadeSpace.m), verticalArrangement = Arrangement.spacedBy(CascadeSpace.xs)) {
            Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
            content()
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = CascadeType.body, color = cascadeColors.textSecondary)
}

private fun times(n: Int) = if (n == 1) "once" else "$n times"

object StatsText {
    /** "48 s", "2 min 5 s", "3 min". */
    fun duration(d: Duration): String {
        val seconds = d.seconds
        if (seconds < 60) return "$seconds s"
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (rest == 0L) "$minutes min" else "$minutes min $rest s"
    }

    /** Logged type names in the words the editor uses (ChallengeText.short). */
    fun challenge(kind: String): String = when (kind) {
        "Math" -> "Sums"
        "TypePhrase" -> "Type a phrase"
        "BarcodeScan" -> "Scan a code"
        "PhotoMatch" -> "Match a photo"
        "Steps" -> "Walk"
        "Memory" -> "Repeat a sequence"
        else -> kind
    }

    /** "4 solved, 1 given up · 0.5 wrong answers each". */
    fun challengeLine(stat: ChallengeStats): String {
        val outcome = listOfNotNull(
            "${stat.solved} solved",
            if (stat.gaveUp > 0) "${stat.gaveUp} given up" else null,
        ).joinToString(", ")
        val misses = when {
            stat.misses == 0 -> "no wrong answers"
            else -> String.format(Locale.US, "%.1f", stat.missesPerTry).removeSuffix(".0").let { n ->
                if (n == "1") "1 wrong answer each" else "$n wrong answers each"
            }
        }
        return "$outcome · $misses"
    }
}
