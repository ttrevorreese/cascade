package com.cascade.alarm.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.cascade.alarm.data.GroupSummary
import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.engine.ring.RingingAlarm
import com.cascade.alarm.ui.home.HomeContent
import com.cascade.alarm.ui.ring.AlarmScreen
import com.cascade.alarm.ui.settings.SettingsContent
import com.cascade.alarm.ui.components.AnimationChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The accessibility pass (M7, 2026-10-24): labels, headings, and the alarm screen at large text. */
@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val group = AlarmGroupEntity("g", "Workday Wake", "#000000", "default", true, 0, Instant.EPOCH)

    @Test
    @Config(qualifiers = "w411dp-h914dp")
    fun aGroupSwitchSaysWhichGroupItSwitches() {
        compose.setContent { HomeContent(listOf(GroupSummary(group, 1, 1, null)), {}, {}, { _, _ -> }, { _, _ -> }, {}) }
        compose.onNodeWithTag("armed-g").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Workday Wake")))
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp")
    fun screenTitlesAreHeadings() {
        compose.setContent { SettingsContent(AnimationChoice.None, {}, {}, {}) }
        compose.onNodeWithText("Settings").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("For new groups").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    /** A small phone with the largest system font: Dismiss and Snooze must still be on screen. */
    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun theAlarmScreenKeepsItsButtonsOnScreenAtDoubleTextSize() {
        val ringing = RingingAlarm(
            alarmId = "a", label = "Take the bins out before the lorry comes", scheduledFor = Instant.EPOCH,
            deadline = Instant.now().plusSeconds(600), position = 0, total = 4, snooze = SnoozeState.Offer(5, 3),
        )
        compose.setContent { AlarmScreen(ringing, onDismiss = {}) }
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Snooze 5 min").assertIsDisplayed()
        compose.onNodeWithContentDescription("Alarm 1 of 4").assertIsDisplayed()
    }
}
