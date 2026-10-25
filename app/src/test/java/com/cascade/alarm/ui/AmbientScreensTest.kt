package com.cascade.alarm.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.cascade.alarm.ui.components.AmbientAnimation
import com.cascade.alarm.ui.components.AmbientBackdrop
import com.cascade.alarm.ui.components.AmbientCanvas
import com.cascade.alarm.ui.components.LocalAnimationChoice
import com.cascade.alarm.ui.home.HomeContent
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.appearance.AppearanceContent
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeTheme
import com.cascade.alarm.ui.theme.CascadeThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theBackdropShowsTheChosenAnimationForTheThemeInEffect() {
        // The animations run forever; a manual clock keeps the test from chasing frames.
        compose.mainClock.autoAdvance = false
        var choice by mutableStateOf<AnimationChoice>(AnimationChoice.MatchTheme)
        var theme by mutableStateOf("forest")
        compose.setContent {
            CascadeTheme(CascadeThemes.byId(theme)) {
                CompositionLocalProvider(LocalAnimationChoice provides choice) {
                    AmbientBackdrop { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {}) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("ambient-drift").assertExists()
        // "Match the theme" follows whatever theme is in effect, a group's included.
        theme = "night"
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("ambient-stars").assertExists()
        choice = AnimationChoice.Fixed(AmbientAnimation.SNOW)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("ambient-snow").assertExists()
        choice = AnimationChoice.None
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("ambient-snow").assertDoesNotExist()
    }

    @Test
    fun nothingPlaysWhereNothingProvidesAnAnimation() {
        compose.setContent { AmbientBackdrop { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {}) } }
        AmbientAnimation.entries.forEach { compose.onNodeWithTag("ambient-${it.key}").assertDoesNotExist() }
    }

    @Test
    fun everyAnimationDrawsForAWhile() {
        compose.mainClock.autoAdvance = false
        var animation by mutableStateOf(AmbientAnimation.entries.first())
        compose.setContent { AmbientCanvas(animation, Modifier.fillMaxSize()) }
        AmbientAnimation.entries.forEach {
            animation = it
            repeat(3) { compose.mainClock.advanceTimeBy(700) }
            compose.onNodeWithTag("ambient-${it.key}").assertExists()
        }
    }

    @Test
    fun everyThemeFamilyHasAnAnimationDecided() {
        assertEquals(CascadeThemes.families.map { it.id }.toSet(), AmbientAnimation.FAMILIES)
        assertEquals(AmbientAnimation.DRIFT, AmbientAnimation.forFamily("landscape"))
        assertNull("Clarity keeps still", AmbientAnimation.forFamily("clarity"))
    }

    @Test
    fun aChoiceResolvesAgainstTheTheme() {
        assertEquals(AmbientAnimation.DRIFT, AnimationChoice.MatchTheme.resolve("forest"))
        assertEquals(AmbientAnimation.STARS, AnimationChoice.MatchTheme.resolve("night"))
        assertNull(AnimationChoice.MatchTheme.resolve("beacon"))
        assertEquals(AmbientAnimation.RAIN, AnimationChoice.Fixed(AmbientAnimation.RAIN).resolve("beacon"))
        assertNull(AnimationChoice.None.resolve("forest"))
        listOf(AnimationChoice.MatchTheme, AnimationChoice.None, AnimationChoice.Fixed(AmbientAnimation.GLINTS)).forEach {
            assertEquals(it, AnimationChoice.fromKey(it.key))
        }
        assertEquals(AnimationChoice.MatchTheme, AnimationChoice.fromKey("from-a-newer-version"))
    }

    @Test
    fun theChoiceIsKeptAndStartsAtMatchTheTheme() {
        val first = UiPreferences(context)
        assertEquals(AnimationChoice.MatchTheme, first.animation.value)
        assertEquals(CascadeThemes.default.id, first.themeId.value)
        first.setAnimation(AnimationChoice.Fixed(AmbientAnimation.EMBERS))
        first.setTheme("night")
        val again = UiPreferences(context)
        assertEquals(AnimationChoice.Fixed(AmbientAnimation.EMBERS), again.animation.value)
        assertEquals("night", again.themeId.value)
    }

    @Test
    fun turningTheOldSwitchOffCarriesOverAsNone() {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("background_motion", false).commit()
        assertEquals(AnimationChoice.None, UiPreferences(context).animation.value)
    }

    @Test
    fun homeOpensSettings() {
        var opened = 0
        compose.setContent { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, {}, onOpenSettings = { opened++ }) }
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun appearanceNamesTheThemesAnimationAndPreviewsTheChoice() {
        // Android's "Remove animations": the preview stands still, so the list can scroll and settle.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var choice by mutableStateOf<AnimationChoice>(AnimationChoice.MatchTheme)
        compose.setContent { appearance(choice = choice, onAnimation = { choice = it }) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("animation-theme"))
        compose.onNodeWithText("Forest is in the Landscape family: drift.").assertExists()
        compose.onNodeWithTag("ambient-drift").assertExists()

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("animation-snow"))
        compose.onNodeWithText("Snow").performClick()
        assertEquals(AnimationChoice.Fixed(AmbientAnimation.SNOW), choice)

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("animation-none"))
        compose.onNodeWithText("None").performClick()
        assertEquals(AnimationChoice.None, choice)
    }

    @Test
    fun clarityExplainsWhyItKeepsStill() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent { appearance(themeId = "beacon") }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("animation-theme"))
        compose.onNodeWithText("Beacon is in the Clarity family: no animation, to keep it easy to read.").assertExists()
        compose.onNodeWithText("No animation").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun appearance(themeId: String = "forest", choice: AnimationChoice = AnimationChoice.MatchTheme, onAnimation: (AnimationChoice) -> Unit = {}) =
        AppearanceContent(themeId, choice, plusOwned = true, price = null, onTheme = {}, onAnimation = onAnimation, onBuy = {}, onRestore = {}, onBack = {})
}
