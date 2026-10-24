package com.cascade.alarm.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.data.stats.ChallengeStats
import com.cascade.alarm.data.stats.StatsRules
import com.cascade.alarm.ui.stats.StatsContent
import com.cascade.alarm.ui.stats.StatsText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class StatsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.now()

    private fun event(type: EventType, secondsAgo: Long, alarm: String = "a", meta: String? = null) =
        AlarmEventEntity(alarmId = alarm, type = type, timestamp = now.minusSeconds(secondsAgo), metadata = meta)

    @Test
    fun aWeekWithRingsShowsTotalsTimesAndChallenges() {
        val stats = StatsRules.compute(
            listOf(
                event(EventType.FIRED, 600),
                event(EventType.SNOOZED, 590),
                event(EventType.FIRED, 300),
                event(EventType.DISMISSED, 252, meta = """{"challenge":"Math","misses":"1"}"""),
                event(EventType.CHEAT_DETECTED, 280, meta = """{"reason":"left_screen"}"""),
                event(EventType.WAKE_CHECK_PASSED, 100),
            ),
            now, 7,
        )
        var period = 0
        compose.setContent { StatsContent(stats, { period = it }, {}) }
        compose.onNodeWithTag("total-Rang").assertExists()
        compose.onNodeWithText("Usually 48 s from the first sound to Dismiss").assertExists()
        compose.onNodeWithText("Sums").assertExists()
        compose.onNodeWithText("1 solved · 1 wrong answer each").assertExists()
        val scroller = compose.onNode(hasScrollAction())
        scroller.performScrollToNode(hasText("1 answered, 0 missed and rang again"))
        compose.onNodeWithText("Left the alarm screen while it rang: once").assertExists()
        compose.onNodeWithTag("period-30").performClick()
        assertEquals(30, period)
    }

    @Test
    fun noRingsSaysSo() {
        compose.setContent { StatsContent(StatsRules.compute(emptyList(), now, 7), {}, {}) }
        compose.onNodeWithTag("stats-empty").assertExists()
    }

    @Test
    fun wordsForDurationsAndChallenges() {
        assertEquals("48 s", StatsText.duration(Duration.ofSeconds(48)))
        assertEquals("2 min 5 s", StatsText.duration(Duration.ofSeconds(125)))
        assertEquals("3 min", StatsText.duration(Duration.ofMinutes(3)))
        assertEquals("4 solved, 1 given up · 0.6 wrong answers each", StatsText.challengeLine(ChallengeStats("Math", 4, 1, 3)))
        assertEquals("2 solved · no wrong answers", StatsText.challengeLine(ChallengeStats("Steps", 2, 0, 0)))
    }
}
