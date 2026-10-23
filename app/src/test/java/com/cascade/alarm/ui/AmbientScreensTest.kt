package com.cascade.alarm.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cascade.alarm.ui.components.AmbientBackdrop
import com.cascade.alarm.ui.components.LocalBackgroundMotion
import com.cascade.alarm.ui.home.HomeContent
import com.cascade.alarm.ui.settings.SettingsContent
import com.cascade.alarm.ui.settings.UiPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class AmbientScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDotsShowOnlyWhileTheSettingIsOn() {
        // The dots animate forever; a manual clock keeps the test from chasing frames.
        compose.mainClock.autoAdvance = false
        var on by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalBackgroundMotion provides on) {
                AmbientBackdrop { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {}) }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("ambient-dots").assertExists()
        on = false
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("ambient-dots").assertDoesNotExist()
    }

    @Test
    fun theDotsAreOffWhereNothingProvidesTheSetting() {
        compose.setContent { AmbientBackdrop { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {}) } }
        compose.onNodeWithTag("ambient-dots").assertDoesNotExist()
    }

    @Test
    fun homeOpensSettings() {
        var opened = 0
        compose.setContent { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {}, onOpenSettings = { opened++ }) }
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun settingsSwitchesTheAnimation() {
        var on by mutableStateOf(true)
        compose.setContent { SettingsContent(on, { on = it }, {}) }
        compose.onNodeWithTag("background-motion").assertIsOn()
        compose.onNodeWithText("Slow floating dots", substring = true).assertExists()
        compose.onNodeWithTag("background-motion").performClick()
        assertFalse(on)
        compose.onNodeWithTag("background-motion").assertIsOff()
        compose.onNodeWithText("No floating dots").assertExists()
    }

    @Test
    fun theAnimationIsOnByDefaultAndTheChoiceIsKept() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = UiPreferences(context)
        assertTrue(first.backgroundMotion.value)
        first.setBackgroundMotion(false)
        assertFalse(UiPreferences(context).backgroundMotion.value)
    }
}
