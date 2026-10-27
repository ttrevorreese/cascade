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
    fun bedtimeReminderIsOffUntilTurnedOnAndThenPicksTheSleep() {
        var on by mutableStateOf(false)
        var sleep by mutableStateOf(480)
        compose.setContent { SettingsContent(AnimationChoice.None, {}, {}, {}, bedtimeOn = on, bedtimeSleep = sleep, onBedtime = { o, s -> on = o; sleep = s }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("bedtime"))
        compose.onNodeWithTag("bedtime-450").assertDoesNotExist()
        compose.onNodeWithTag("bedtime").performClick()
        assertEquals(true, on)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("bedtime-450"))
        compose.onNodeWithTag("bedtime-450").performClick()
        assertEquals(450, sleep)
        compose.onNodeWithText("7½ h before the next alarm", substring = true).assertExists()
    }

    @Test
    fun feedbackThroughTheFormKeepsTheAddressOff() {
        compose.setContent { SettingsContent(AnimationChoice.None, {}, {}, {}, usesForm = true) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("feedback-other"))
        compose.onNodeWithText("cascadealarmgroup.app@gmail.com", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Opens a short form", substring = true).assertExists()
    }

    @Test
    fun theFormLinkCarriesTheKindAndThePhone() {
        val uri = com.cascade.alarm.ui.settings.Feedback.formUri(
            com.cascade.alarm.ui.settings.Feedback.Kind.PROBLEM, "1.0.0", "16", "Samsung SM-S908U",
            url = "https://docs.google.com/forms/d/e/abc/viewform",
        )!!
        assertEquals("pp_url", uri.getQueryParameter("usp"))
        org.junit.Assert.assertTrue(uri.toString().startsWith("https://docs.google.com/forms/d/e/abc/viewform?"))
        org.junit.Assert.assertNull(com.cascade.alarm.ui.settings.Feedback.formUri(com.cascade.alarm.ui.settings.Feedback.Kind.IDEA, url = ""))
        assertEquals("Bug report", com.cascade.alarm.ui.settings.Feedback.Kind.PROBLEM.formChoice)
    }

    @Test
    fun feedbackOffersThreeKindsAndSaysWhenThereIsNoEmailApp() {
        val sent = mutableListOf<com.cascade.alarm.ui.settings.Feedback.Kind>()
        var noEmailApp by mutableStateOf(false)
        compose.setContent { SettingsContent(AnimationChoice.None, {}, {}, {}, onFeedback = { sent += it }, noEmailApp = noEmailApp) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("feedback-other"))
        compose.onNodeWithText("cascadealarmgroup.app@gmail.com", substring = true).assertExists()
        compose.onNodeWithTag("feedback-idea").performClick()
        compose.onNodeWithTag("feedback-problem").performClick()
        assertEquals(listOf(com.cascade.alarm.ui.settings.Feedback.Kind.IDEA, com.cascade.alarm.ui.settings.Feedback.Kind.PROBLEM), sent)
        noEmailApp = true
        compose.onNodeWithText("There is no email app on this phone", substring = true).assertExists()
    }

    @Test
    fun feedbackMessagesAskTheRightQuestionAndNameThePhone() {
        val body = com.cascade.alarm.ui.settings.Feedback.body(com.cascade.alarm.ui.settings.Feedback.Kind.PROBLEM, "1.0.0", "16", "Samsung SM-S908U")
        org.junit.Assert.assertTrue(body.startsWith("What happened"))
        org.junit.Assert.assertTrue(body.endsWith("Cascade 1.0.0 · Android 16 · Samsung SM-S908U"))
        val uri = com.cascade.alarm.ui.settings.Feedback.mailto(com.cascade.alarm.ui.settings.Feedback.Kind.IDEA)
        assertEquals("mailto", uri.scheme)
        org.junit.Assert.assertTrue(uri.toString().startsWith("mailto:cascadealarmgroup.app@gmail.com?subject=Cascade%20feature%20request"))
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
