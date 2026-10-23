package com.cascade.alarm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.data.challenge.MathProblems
import com.cascade.alarm.data.challenge.MemorySequence
import com.cascade.alarm.data.challenge.challengeSeed
import com.cascade.alarm.engine.ring.RingingAlarm
import com.cascade.alarm.ui.alarm.ChallengeEditor
import com.cascade.alarm.ui.ring.AlarmScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

// A real phone's size: the keypad must fit without scrolling.
@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class ChallengeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val scheduled = Instant.parse("2026-10-22T10:00:00Z")

    private fun ringing(challenge: ChallengeConfig?, startedAt: Instant = Instant.now()) = RingingAlarm(
        alarmId = "a", label = null, scheduledFor = scheduled, deadline = Instant.now().plusSeconds(900),
        position = 0, total = 3, challenge = challenge, startedAt = startedAt,
    )

    private fun type(answer: Int) {
        answer.toString().forEach { compose.onNodeWithContentDescription(it.toString()).performClick() }
        compose.onNodeWithContentDescription("Check answer").performClick()
    }

    @Test
    fun theSumsMustBeSolvedToDismiss() {
        val config = ChallengeConfig.Math(Difficulty.EASY, 2)
        val problems = MathProblems.generate(challengeSeed("a", scheduled), config.difficulty, config.problemCount)
        var dismissed = 0
        val ducks = mutableListOf<Boolean>()
        compose.setContent { AlarmScreen(ringing(config), onDismiss = { dismissed++ }, onDuck = { ducks += it }) }

        compose.onNodeWithText("Solve 2 sums to dismiss.").assertExists()
        compose.onNodeWithText("Start the sums").performClick()
        compose.onNodeWithText("Sum 1 of 2").assertExists()
        compose.onNodeWithText("${problems[0].text} =").assertExists()
        type(problems[0].answer + 1)
        compose.onNodeWithText("Not quite. Try again.").assertExists()
        assertEquals(0, dismissed)
        type(problems[0].answer)
        compose.onNodeWithText("Sum 2 of 2").assertExists()
        type(problems[1].answer)
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(true, ducks.first())
    }

    @Test
    fun giveUpAppearsAfterThreeMissesAndIsConfirmedFirst() {
        val config = ChallengeConfig.Math(Difficulty.EASY, 1)
        val answer = MathProblems.generate(challengeSeed("a", scheduled), config.difficulty, 1).single().answer
        var gaveUp = 0
        compose.setContent { AlarmScreen(ringing(config), onDismiss = {}, onGiveUp = { gaveUp++ }) }
        compose.onNodeWithText("Start the sums").performClick()
        repeat(2) { type(answer + 1) }
        compose.onNodeWithText("Give up").assertDoesNotExist()
        type(answer + 1)
        compose.onNodeWithText("Recorded in your history").assertExists()
        compose.onNodeWithText("Give up").performClick()
        compose.onNodeWithText("Give up on this alarm?").assertExists()
        compose.onNodeWithText("Keep going").performClick()
        assertEquals(0, gaveUp)
        compose.onNodeWithText("Give up").performClick()
        compose.onAllNodesWithText("Give up")[1].performClick() // the dialog's confirm button
        assertEquals(1, gaveUp)
    }

    @Test
    fun giveUpIsOfferedAfterThreeMinutesWithoutAnyMisses() {
        compose.setContent {
            AlarmScreen(ringing(ChallengeConfig.Math(), startedAt = Instant.now().minusSeconds(181)), onDismiss = {})
        }
        compose.onNodeWithText("Start the sums").performClick()
        compose.onNodeWithText("Give up").assertExists()
    }

    @Test
    fun thePhraseHasToBeTypedNotPasted() {
        var dismissed = 0
        compose.setContent { AlarmScreen(ringing(ChallengeConfig.TypePhrase("kettle on")), onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Show the phrase").performClick()
        compose.onNodeWithText("kettle on").assertExists()
        // A paste lands as one big edit and is refused.
        compose.onNodeWithTag("phrase-entry").performTextReplacement("kettle on")
        compose.waitForIdle()
        assertEquals(0, dismissed)
        "Kettle On".forEach { compose.onNodeWithTag("phrase-entry").performTextInput(it.toString()) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun aCodeChallengeWithoutCameraAccessAsksForSumsInstead() {
        compose.setContent { AlarmScreen(ringing(ChallengeConfig.BarcodeScan("cascade:1", "Bathroom mirror")), onDismiss = {}) }
        compose.onNodeWithText("Solve 3 sums to dismiss.").assertExists()
        compose.onNodeWithText("Start the sums").assertExists()
    }

    @Test
    fun theEditorPicksAndConfiguresAChallenge() {
        var challenge by mutableStateOf<ChallengeConfig>(ChallengeConfig.None)
        compose.setContent { ChallengeEditor(challenge, { challenge = it }) }
        compose.onNodeWithTag("challenge-math").performClick()
        assertEquals(ChallengeConfig.Math(), challenge)
        compose.onNodeWithText("Hard").performClick()
        compose.onNodeWithText("More").performClick()
        assertEquals(ChallengeConfig.Math(Difficulty.HARD, 4), challenge)
        compose.onNodeWithTag("challenge-phrase").performClick()
        compose.onNodeWithText("Leave empty for four random words each time.").assertExists()
        compose.onNodeWithTag("challenge-none").performClick()
        assertEquals(ChallengeConfig.None, challenge)
    }

    @Test
    fun theMemorySequenceIsShownThenRepeated() {
        val config = ChallengeConfig.Memory(4)
        val sequence = MemorySequence.generate(challengeSeed("a", scheduled), 4)
        val names = listOf("Circle", "Square", "Triangle", "Diamond")
        var dismissed = 0
        compose.setContent { AlarmScreen(ringing(config), onDismiss = { dismissed++ }) }
        compose.onNodeWithText("Repeat a sequence of 4 to dismiss.").assertExists()
        compose.onNodeWithText("Show the sequence").performClick()
        compose.onNodeWithText("Watch the sequence.").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Repeat it: 0 of 4").assertExists()
        // A wrong first pad replays the sequence.
        compose.onNodeWithContentDescription(names[(sequence[0] + 1) % 4]).performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Not quite. Watch again.").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Repeat it: 0 of 4").assertExists()
        sequence.forEach { compose.onNodeWithContentDescription(names[it]).performClick() }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun stepsWithoutAStepSensorAskForSumsInstead() {
        // Robolectric has no step counter, like some phones.
        compose.setContent { AlarmScreen(ringing(ChallengeConfig.Steps(30)), onDismiss = {}) }
        compose.onNodeWithText("Solve 3 sums to dismiss.").assertExists()
    }

    @Test
    fun aPhotoWithoutCameraAccessAsksForSumsInstead() {
        val config = ChallengeConfig.PhotoMatch("p", signature = "ab".repeat(136), label = "Toothbrush")
        compose.setContent { AlarmScreen(ringing(config), onDismiss = {}) }
        compose.onNodeWithText("Solve 3 sums to dismiss.").assertExists()
    }

    @Test
    fun theEditorOffersTheM3bChallenges() {
        var challenge by mutableStateOf<ChallengeConfig>(ChallengeConfig.None)
        compose.setContent { ChallengeEditor(challenge, { challenge = it }) }
        compose.onNodeWithTag("challenge-memory").performClick()
        compose.onNodeWithText("More").performClick()
        assertEquals(ChallengeConfig.Memory(6), challenge)
        compose.onNodeWithTag("challenge-steps").performClick()
        compose.onNodeWithText("More").performClick()
        assertEquals(ChallengeConfig.Steps(30), challenge)
        compose.onNodeWithText("This phone has no step counter, so this alarm asks for sums instead.").assertExists()
        compose.onNodeWithTag("challenge-photo").performClick()
        compose.onNodeWithText("Take the reference photo").assertExists()
    }
}
