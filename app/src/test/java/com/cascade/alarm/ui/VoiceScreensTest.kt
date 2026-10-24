package com.cascade.alarm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.voice.NamedVoice
import com.cascade.alarm.data.voice.VoiceChoice
import com.cascade.alarm.data.voice.VoiceOption
import com.cascade.alarm.ui.alarm.AlarmEditorContent
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.settings.SettingsContent
import com.cascade.alarm.ui.voice.SpeechPreview
import com.cascade.alarm.ui.voice.VoiceContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class VoiceScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val alarm = AlarmEntity(
        id = "a", groupId = "g", positionInGroup = 0, hour = 6, minute = 10, daysOfWeek = emptySet(),
        label = null, isEnabled = true, volumeRampSeconds = 30, timeoutMinutes = 15,
    )

    @Test
    fun theEditorSaysWhatWillBeReadAndPlaysIt() {
        val heard = mutableListOf<String>()
        var draft by mutableStateOf(alarm)
        compose.setContent {
            AlarmEditorContent(
                draft = draft, isNew = false, onEdit = { draft = it(draft) }, onSave = {}, onDelete = {}, onCancel = {},
                onHear = { heard += it },
            )
        }
        val scroller = compose.onNode(hasScrollAction())
        scroller.performScrollToNode(hasTestTag("read-aloud"))
        compose.onNodeWithTag("read-aloud").assertIsOff().performClick()
        assertTrue(draft.readLabelAloud)
        compose.onNodeWithText("Label").performTextInput("Take your pills")
        scroller.performScrollToNode(hasTestTag("hear-label"))
        compose.onNodeWithText("Says \"It's 6:10 AM. Take your pills.\"", substring = true).assertExists()
        compose.onNodeWithTag("hear-label").performClick()
        assertEquals(listOf("It's 6:10 AM. Take your pills."), heard)
    }

    @Test
    fun theEditorExplainsAnEngineThatDidNotAnswer() {
        compose.setContent {
            AlarmEditorContent(
                draft = alarm.copy(readLabelAloud = true), isNew = false, onEdit = {}, onSave = {}, onDelete = {}, onCancel = {},
                previewStatus = SpeechPreview.Status.Failed,
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("hear-label"))
        compose.onNodeWithText("speech engine did not answer", substring = true).assertExists()
    }

    private fun named(name: String, title: String, engine: String = "com.google.android.tts", engineLabel: String = "Google") =
        NamedVoice(VoiceOption(engine, engineLabel, name, Locale.US, 400, false, false), title, "English (United States)")

    @Test
    fun thePickerListsVoicesByEngineAndChoosesHearsAndSetsSpeed() {
        val voices = listOf(named("en-us-x-a", "Voice 1"), named("en-us-x-b", "Voice 2"), named("SMTf00", "Voice 1", "com.samsung.SMT", "Samsung"))
        var choice by mutableStateOf(VoiceChoice())
        val heard = mutableListOf<String?>()
        compose.setContent {
            VoiceContent(
                voices = voices, choice = choice, status = SpeechPreview.Status.Idle,
                onChoose = { v -> choice = v?.let { VoiceChoice(it.option.engine, it.option.name, choice.rate, it.title) } ?: VoiceChoice(rate = choice.rate) },
                onRate = { choice = choice.copy(rate = it) },
                onHear = { heard += it?.option?.name },
                onMoreVoices = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("voice-default").assertExists()
        compose.onNodeWithText("Samsung").assertExists()
        compose.onNodeWithText("Voice 2").performClick()
        assertEquals("en-us-x-b", choice.voiceName)
        compose.onNodeWithTag("voice-en-us-x-a-hear").performClick()
        assertEquals(listOf<String?>("en-us-x-a"), heard)
        compose.onNodeWithTag("rate-Faster").performClick()
        assertEquals(1.2f, choice.rate)
        assertEquals("en-us-x-b", choice.voiceName)
    }

    @Test
    fun thePickerSaysWhenThereIsNothingButTheDefault() {
        compose.setContent {
            VoiceContent(emptyList(), VoiceChoice(), SpeechPreview.Status.Idle, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("No offline voices were found", substring = true).assertExists()
        compose.onNodeWithTag("voice-default").assertExists()
    }

    @Test
    fun settingsShowsTheVoiceAndOpensThePicker() {
        var opened = 0
        compose.setContent {
            SettingsContent(
                AnimationChoice.None, {}, {}, {},
                voice = VoiceChoice("g", "b", 0.8f, "Voice 2 · English (United States)"),
                onOpenVoice = { opened++ },
            )
        }
        compose.onNodeWithText("Voice 2 · English (United States) · Slower speed").assertExists()
        compose.onNodeWithTag("open-voice").performClick()
        assertEquals(1, opened)
    }
}
