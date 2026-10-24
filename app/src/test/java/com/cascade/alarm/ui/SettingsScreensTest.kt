package com.cascade.alarm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cascade.alarm.data.NewAlarmDefaults
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.settings.SettingsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class SettingsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaultsForNewGroupsChangeInPlace() {
        var defaults by mutableStateOf(NewAlarmDefaults())
        compose.setContent {
            SettingsContent(AnimationChoice.None, {}, {}, {}, defaults = defaults, onDefaults = { defaults = it })
        }
        val scroller = compose.onNode(hasScrollAction())
        scroller.performScrollToNode(hasTestTag("default-snooze"))
        compose.onNodeWithTag("default-snooze").assertIsOn()
        compose.onNodeWithTag("default-snooze-9").performClick()
        assertEquals(9, defaults.snoozeMinutes)
        scroller.performScrollToNode(hasTestTag("default-timeout-30"))
        compose.onNodeWithTag("default-timeout-30").performClick()
        assertEquals(30, defaults.timeoutMinutes)
        scroller.performScrollToNode(hasTestTag("default-read-aloud"))
        compose.onNodeWithTag("default-read-aloud").performClick()
        assertEquals(true, defaults.readLabelAloud)
    }

    @Test
    fun exportSaysWhatItSavesAndThenHowItWent() {
        var exports = 0
        var result by mutableStateOf<String?>(null)
        compose.setContent {
            SettingsContent(AnimationChoice.None, {}, {}, {}, onExport = { exports++ }, exportResult = result)
        }
        val scroller = compose.onNode(hasScrollAction())
        scroller.performScrollToNode(hasTestTag("export"))
        compose.onNodeWithText("Nothing is sent anywhere.", substring = true).assertExists()
        compose.onNodeWithTag("export").performClick()
        assertEquals(1, exports)
        result = "Saved."
        compose.onNodeWithText("Saved.").assertExists()
    }
}
