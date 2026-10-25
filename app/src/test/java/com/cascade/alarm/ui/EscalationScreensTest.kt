package com.cascade.alarm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.ui.alarm.AlarmEditorContent
import com.cascade.alarm.ui.alarm.AlarmEditorState
import com.cascade.alarm.ui.escalation.EscalationContent
import com.cascade.alarm.ui.group.GroupContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class EscalationScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val ladder = EscalationPolicy(listOf(ChallengeConfig.Math(Difficulty.EASY, 2), ChallengeConfig.TypePhrase(), ChallengeConfig.Steps(30)))

    private fun alarm(id: String, position: Int, hour: Int = 6, minute: Int = position * 10) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = position, hour = hour, minute = minute,
        daysOfWeek = DayOfWeek.entries.take(5).toSet(), label = null, isEnabled = true, volumeRampSeconds = 30, timeoutMinutes = 15,
    )

    @Test
    fun anEmptyLadderOffersTheSuggestedOne() {
        var policy by mutableStateOf(EscalationPolicy())
        compose.setContent { EscalationContent("Workday Wake", alarmsOn = 4, policy = policy, onChange = { policy = it }, onSave = {}, onCancel = {}) }
        compose.onNodeWithText("No rungs yet", substring = true).assertExists()
        compose.onNodeWithText("Use the suggested ladder").performClick()
        assertEquals(Escalation.SUGGESTED, policy)
        compose.onNodeWithText("Rung 1 · Alarm 1").assertExists()
        compose.onNodeWithText("2 easy sums").assertExists()
        compose.onNodeWithText("Rung 4 · Alarm 4").assertExists()
    }

    @Test
    fun rungsShowWhatTheyCoverAndHowHardTheyAre() {
        compose.setContent { EscalationContent("Workday Wake", alarmsOn = 5, policy = ladder, onChange = {}, onSave = {}, onCancel = {}) }
        compose.onNodeWithText("Rung 3 · Alarms 3 to 5").assertExists()
        // Easy sums and a phrase both rate 1; steps rate 3.
        compose.onAllNodesWithContentDescription("Difficulty 1 of 5", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Difficulty 3 of 5", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aSelectedRungCanBeChangedMovedAndRemoved() {
        var policy by mutableStateOf(ladder)
        var saved = 0
        compose.setContent { EscalationContent("Workday Wake", 3, policy, { policy = it }, { saved++ }, {}) }
        compose.onNodeWithTag("rung-0").performClick()
        compose.onNodeWithText("Hard").performClick()
        assertEquals(ChallengeConfig.Math(Difficulty.HARD, 2), policy.steps[0])
        compose.onNodeWithText("Later").performClick()
        assertEquals(ChallengeConfig.TypePhrase(), policy.steps[0])
        assertEquals(ChallengeConfig.Math(Difficulty.HARD, 2), policy.steps[1])
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(ChallengeConfig.TypePhrase(), ChallengeConfig.Steps(30)), policy.steps)
        compose.onNodeWithTag("save-escalation").performClick()
        assertEquals(1, saved)
    }

    @Test
    fun theGroupShowsEachAlarmsChallengeAndHasNoMoveButtons() {
        val group = AlarmGroupEntity("g", "Workday Wake", "#000000", "default", true, 0, Instant.EPOCH, escalationPolicy = ladder)
        val alarms = listOf(alarm("a", 0), alarm("b", 1), alarm("c", 2))
        compose.setContent {
            GroupContent(
                group = group, alarms = alarms,
                onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> },
                onShiftWithFirstChange = {}, onShift = {}, onWakeCheckChange = { _, _ -> },
            )
        }
        compose.onNodeWithTag("challenge-a", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Type a phrase", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("3 rungs, from 2 easy sums to walk 30 steps").assertExists()
        // The clock sets the order (SPEC §7.2), so there is nothing to move by hand.
        compose.onNodeWithContentDescription("Move alarm 1 later in the sequence").assertDoesNotExist()
    }

    @Test
    fun theEditorSaysWhenANewTimeMovesTheAlarm() {
        val siblings = listOf(alarm("a", 0).copy(hour = 6, minute = 0), alarm("c", 2).copy(hour = 6, minute = 20))
        val opened = AlarmEditorState(draft = alarm("b", 1).copy(hour = 6, minute = 10), siblings = siblings, policy = ladder).placed()
        assertEquals(1, opened.place)
        assertEquals(ladder.steps[1], opened.ladderRung)
        val later = opened.copy(startPlace = opened.place, draft = opened.draft!!.copy(minute = 30)).placed()
        assertEquals(2, later.place)
        assertEquals(ladder.steps[2], later.ladderRung)
        compose.setContent {
            AlarmEditorContent(
                draft = later.draft!!, isNew = false, onEdit = {}, onSave = {}, onDelete = {}, onCancel = {},
                ladderRung = later.ladderRung, moved = later.place to later.total,
            )
        }
        compose.onNodeWithText("At this time it becomes alarm 3 of 3, with that alarm's challenge.").assertExists()
    }

    @Test
    fun theFirstAlarmKeepsItsPlaceWhenTheRestMoveWithIt() {
        val siblings = listOf(alarm("b", 1).copy(hour = 6, minute = 10))
        val draft = alarm("a", 0).copy(hour = 6, minute = 30)
        assertEquals(0, AlarmEditorState(draft = draft, siblings = siblings, policy = ladder, restFollow = true).placed().place)
        assertEquals(1, AlarmEditorState(draft = draft, siblings = siblings, policy = ladder).placed().place)
    }

    @Test
    fun theAlarmEditorFollowsTheLadderUntilToldOtherwise() {
        var draft by mutableStateOf(alarm("a", 1))
        compose.setContent {
            AlarmEditorContent(
                draft = draft, isNew = false, onEdit = { draft = it(draft) }, onSave = {}, onDelete = {}, onCancel = {},
                ladderRung = ChallengeConfig.TypePhrase(),
            )
        }
        compose.onNodeWithText("From the ladder: Type four random words.").assertExists()
        compose.onNodeWithTag("follow-ladder").assertIsOn().performClick()
        // Its own challenge starts as a copy of the rung.
        assertEquals(ChallengeConfig.TypePhrase(), draft.challengeOverride)
        compose.onNodeWithTag("follow-ladder").assertIsOff()
        compose.onNodeWithTag("challenge-none").performClick()
        assertEquals(ChallengeConfig.None, draft.challengeOverride)
        compose.onNodeWithTag("follow-ladder").performClick()
        assertEquals(null, draft.challengeOverride)
    }

    @Test
    fun aWalkingRungSaysWhenStepsCannotBeCounted() {
        var policy by mutableStateOf(EscalationPolicy())
        compose.setContent { EscalationContent("Workday Wake", 4, policy, { policy = it }, {}, {}) }
        compose.onNodeWithText("Use the suggested ladder").performClick()
        // Robolectric has no step counter, like some phones.
        compose.onNodeWithText("This phone has no step counter, so walking rungs ask for sums instead.").assertExists()
    }
}
