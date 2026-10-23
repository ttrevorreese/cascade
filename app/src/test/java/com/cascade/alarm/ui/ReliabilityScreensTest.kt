package com.cascade.alarm.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cascade.alarm.data.GroupSummary
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.reliability.Incident
import com.cascade.alarm.data.reliability.IncidentCause
import com.cascade.alarm.data.reliability.OemGuidances
import com.cascade.alarm.data.reliability.TestResult
import com.cascade.alarm.ui.home.HomeContent
import com.cascade.alarm.ui.reliability.ReliabilityCheck
import com.cascade.alarm.ui.reliability.ReliabilityContent
import com.cascade.alarm.ui.reliability.ReliabilityUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class ReliabilityScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(title: String, passed: Boolean, blocker: Boolean) =
        ReliabilityCheck(title, passed, blocker, "$title consequence.", fixIntent = null)

    private val checks = listOf(
        check("Notifications", passed = true, blocker = true),
        check("Battery: unrestricted", passed = false, blocker = false),
        check("Full-screen alarms", passed = false, blocker = true),
    )

    private fun reliability(state: ReliabilityUiState = ReliabilityUiState(), onFix: (ReliabilityCheck) -> Unit = {}, onTest: () -> Unit = {}) =
        compose.setContent {
            ReliabilityContent(
                checks = checks, guidance = OemGuidances.forDevice("samsung", "samsung"), state = state,
                onFix = onFix, onOpenMakerSettings = {}, onReadMore = {}, onTest = onTest, onShareLog = {}, onOpenRinging = {}, onBack = {},
            )
        }

    /** Clicks the button labelled [label] inside the card tagged [tag]. */
    private fun clickIn(tag: String, label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag))).performClick()

    @Test
    fun problemsComeFirstWithTheirFixAndPassesStayQuiet() {
        val fixed = mutableListOf<String>()
        reliability(onFix = { fixed += it.title })
        compose.onNodeWithText("2 things can stop your alarms from working properly.").assertExists()
        compose.onNodeWithTag("problem-Full-screen alarms").assertExists()
        compose.onNodeWithTag("pass-Notifications").assertExists()
        compose.onNodeWithText("On Samsung phones").assertExists()
        clickIn("problem-Full-screen alarms", "Fix this")
        assertEquals(listOf("Full-screen alarms"), fixed)
    }

    @Test
    fun theTestLineSaysWhatHappened() {
        val due = Instant.now().minus(1, ChronoUnit.HOURS)
        var tests = 0
        reliability(ReliabilityUiState(lastTest = TestResult.Rang(due, Duration.ofMillis(400))), onTest = { tests++ })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ring a test alarm in 1 minute"))
        compose.onNodeWithText("Last test: rang on time", substring = true).assertExists()
        compose.onNodeWithText("0.4 s late", substring = true).assertExists()
        compose.onNodeWithText("Ring a test alarm in 1 minute").performClick()
        assertEquals(1, tests)
    }

    @Test
    fun aTestThatNeverRangSaysSo() {
        reliability(ReliabilityUiState(lastTest = TestResult.Missed(Instant.now())))
        compose.onNodeWithText("did not ring. See the problems above.", substring = true).assertExists()
    }

    private val group = AlarmGroupEntity("g", "Workday Wake", "#000000", "default", true, 0, Instant.EPOCH)

    @Test
    fun homeBannersOnlyBlockersAndDotsAdvice() {
        compose.setContent {
            HomeContent(
                listOf(GroupSummary(group, 1, 1, null)), {}, {}, { _, _ -> }, { _, _ -> }, {},
                blockers = listOf(check("Full-screen alarms", passed = false, blocker = true)),
                adviceOutstanding = true,
            )
        }
        compose.onNodeWithText("Full-screen alarms is off").assertExists()
        compose.onNodeWithContentDescription("Advice waiting", useUnmergedTree = true).assertExists()
    }

    @Test
    fun homeShowsNoBannerWhenOnlyAdviceIsOutstanding() {
        compose.setContent {
            HomeContent(listOf(GroupSummary(group, 1, 1, null)), {}, {}, { _, _ -> }, { _, _ -> }, {}, adviceOutstanding = true)
        }
        compose.onNodeWithTag("blocker-banner").assertDoesNotExist()
    }

    @Test
    fun missedAndLateAlarmsExplainThemselvesUntilAcknowledged() {
        val yesterday = Instant.now().minus(1, ChronoUnit.DAYS)
        val acknowledged = mutableListOf<String>()
        var opened = 0
        compose.setContent {
            HomeContent(
                emptyList(), {}, { opened++ }, { _, _ -> }, { _, _ -> }, {},
                incidents = listOf(
                    Incident.Missed("m", "a", yesterday, IncidentCause.APP_STOPPED),
                    Incident.Late("l", "a", yesterday, IncidentCause.PHONE_OFF, Duration.ofMinutes(7)),
                ),
                onAcknowledge = { acknowledged += it },
            )
        }
        compose.onNodeWithText("alarm yesterday did not ring.", substring = true).assertExists()
        compose.onNodeWithText("Android stopped Cascade, probably to save battery.").assertExists()
        compose.onNodeWithText("rang 7 minutes late.", substring = true).assertExists()
        compose.onNodeWithText("The phone was starting up.").assertExists()
        // Only the miss Android caused leads to the Reliability Check.
        compose.onNodeWithText("Check reliability").performClick()
        assertEquals(1, opened)
        clickIn("incident-m", "Got it")
        assertEquals(listOf("m"), acknowledged)
    }

    @Test
    fun aSoundThatFellBackSaysWhatPlayedInstead() {
        val yesterday = Instant.now().minus(1, ChronoUnit.DAYS)
        compose.setContent {
            HomeContent(
                emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {},
                incidents = listOf(Incident.SoundFailed("s", "a", yesterday, "file_missing", "classic")),
                onAcknowledge = {},
            )
        }
        compose.onNodeWithText("alarm yesterday could not play its sound.", substring = true).assertExists()
        compose.onNodeWithText("Its audio file is missing, so it played Classic instead. Choose its group's sound again.").assertExists()
        compose.onNodeWithText("Check reliability").assertDoesNotExist()
    }
}
