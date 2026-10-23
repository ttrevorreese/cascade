package com.cascade.alarm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.engine.ring.RingSound
import com.cascade.alarm.ui.group.GroupContent
import com.cascade.alarm.ui.sound.SoundContent
import com.cascade.alarm.ui.sound.volumeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class SoundScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(
        draft: () -> RingSound,
        onChange: (RingSound) -> Unit = {},
        problem: String? = null,
        onPick: () -> Unit = {},
        onPreview: () -> Unit = {},
        onSave: () -> Unit = {},
    ) = compose.setContent {
        SoundContent(
            groupName = "Workday Wake", draft = draft(), previewing = false, importing = false, problem = problem,
            alarmVolume = 9 to 15, onChange = onChange, onPickFile = onPick, onTogglePreview = onPreview, onSave = onSave, onCancel = {},
        )
    }

    private fun scrollTo(tag: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))

    @Test
    fun choosingAToneSelectsIt() {
        var draft by mutableStateOf(RingSound())
        content({ draft }, onChange = { draft = it })
        compose.onNodeWithTag("tone-classic").assertExists()
        compose.onNodeWithText("Classic").assertExists()
        scrollTo("tone-klaxon")
        compose.onNodeWithText("Klaxon").performClick()
        assertEquals(SoundConfig.BuiltIn("klaxon"), draft.sound)
    }

    @Test
    fun theSelectedToneReadsAsSelected() {
        content({ RingSound(SoundConfig.BuiltIn("dawn")) })
        compose.onNodeWithText("Dawn").assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("Dawn").and(androidx.compose.ui.test.isSelectable()), useUnmergedTree = false)
            .assertIsSelected()
        compose.onNode(androidx.compose.ui.test.hasText("Classic").and(androidx.compose.ui.test.isSelectable()))
            .assertIsNotSelected()
    }

    @Test
    fun aPickedFileShowsByNameAndCanBeReplaced() {
        var picks = 0
        content({ RingSound(SoundConfig.DeviceAudio("abc.mp3", "Morning song.mp3")) }, onPick = { picks++ })
        scrollTo("pick-file")
        compose.onNodeWithText("Morning song.mp3").assertExists()
        compose.onNodeWithText("Choose another file").performClick()
        assertEquals(1, picks)
    }

    @Test
    fun aRefusedFileSaysWhy() {
        content({ RingSound() }, problem = "That file is over 20 MB. Pick a shorter one.")
        scrollTo("pick-file")
        compose.onNodeWithText("That file is over 20 MB. Pick a shorter one.").assertExists()
    }

    @Test
    fun theRampAndStartShowTheirValuesAndMoveInSteps() {
        var draft by mutableStateOf(RingSound(rampSeconds = 45, startPercent = 15))
        content({ draft }, onChange = { draft = it })
        scrollTo("start-percent")
        compose.onNodeWithText("Reaches full volume after 45 s").assertExists()
        compose.onNodeWithText("Starts at 15% of full volume").assertExists()
        compose.onNodeWithTag("ramp-seconds").performSemanticsAction(SemanticsActions.SetProgress) { it(57f) }
        assertEquals(55, draft.rampSeconds)
        compose.onNodeWithTag("start-percent").performSemanticsAction(SemanticsActions.SetProgress) { it(99f) }
        assertEquals(60, draft.startPercent)
        compose.onNodeWithText("Reaches full volume after 55 s").assertExists()
    }

    @Test
    fun previewAndSaveAreReachable() {
        var previews = 0
        var saves = 0
        content({ RingSound() }, onPreview = { previews++ }, onSave = { saves++ })
        compose.onNodeWithText("Play a preview").performClick()
        compose.onNodeWithTag("save-sound").performClick()
        assertEquals(1 to 1, previews to saves)
    }

    @Test
    fun theVolumeNoteExplainsAMutedAlarmStream() {
        assertTrue(volumeNote(0 to 15).contains("turns it up to a third"))
        assertEquals("Full volume is your phone's alarm volume, now 9 of 15.", volumeNote(9 to 15))
    }

    @Test
    fun theGroupScreenSummarisesTheSoundAndOpensIt() {
        val group = AlarmGroupEntity(
            "g", "Workday Wake", "#000000", "default", true, 0, Instant.EPOCH,
            sound = SoundConfig.BuiltIn("chimes"), rampSeconds = 50,
        )
        val alarm = AlarmEntity(
            id = "a", groupId = "g", positionInGroup = 0, hour = 6, minute = 0,
            daysOfWeek = DayOfWeek.entries.take(5).toSet(), label = null, isEnabled = true, timeoutMinutes = 15,
        )
        var opened = 0
        compose.setContent {
            GroupContent(
                group = group, alarms = listOf(alarm), onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> }, onMoveAlarm = { _, _ -> }, onShiftWithFirstChange = {}, onShift = {},
                onWakeCheckChange = { _, _ -> }, onOpenSound = { opened++ },
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("change-sound"))
        compose.onNodeWithText("Chimes, full volume after 50 s").assertExists()
        compose.onNodeWithTag("change-sound").performClick()
        assertEquals(1, opened)
    }
}
