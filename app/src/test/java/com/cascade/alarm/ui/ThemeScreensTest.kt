package com.cascade.alarm.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.cascade.alarm.data.GroupSummary
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.ui.appearance.AppearanceContent
import com.cascade.alarm.ui.appearance.GroupThemeContent
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.home.HomeContent
import com.cascade.alarm.ui.theme.CascadeTheme
import com.cascade.alarm.ui.theme.CascadeThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class ThemeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun scrollTo(tag: String) = compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))

    @Test
    fun aFreeThemeAppliesAtOnceAndALockedOneOnlyExplainsItself() {
        val picked = mutableListOf<String>()
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = false, price = "$3.99",
                onTheme = { picked += it.id }, onAnimation = {}, onBuy = {}, onRestore = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("theme-forest").assertIsSelected()
        // Landscape is open first, as Forest's family; Tide is part of the pack.
        compose.onNodeWithTag("theme-tide").performClick()
        compose.onNodeWithTag("purchase-sheet").assertExists()
        compose.onNodeWithText("Tide is part of Cascade Plus", substring = true).assertExists()
        compose.onNodeWithText("Unlock all themes for $3.99").assertExists()
        assertTrue("a locked theme is never applied on trial", picked.isEmpty())
    }

    @Test
    fun allShowsEveryFamilyUnderItsOwnHeading() {
        val picked = mutableListOf<String>()
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = true, price = null,
                onTheme = { picked += it.id }, onAnimation = {}, onBuy = {}, onRestore = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("family-all").performScrollTo().performClick()
        CascadeThemes.families.forEach { family ->
            scrollTo("family-section-${family.id}")
            compose.onNodeWithTag("family-section-${family.id}").assertExists()
        }
        // Themes from different families are all reachable without switching chips.
        scrollTo("theme-night")
        compose.onNodeWithTag("theme-night").performClick()
        scrollTo("theme-forest")
        compose.onNodeWithTag("theme-forest").performClick()
        assertEquals(listOf("night", "forest"), picked)
    }

    @Test
    fun familiesSwitchTheGridAndNightIsFree() {
        val picked = mutableListOf<String>()
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = false, price = null,
                onTheme = { picked += it.id }, onAnimation = {}, onBuy = {}, onRestore = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("family-afterDark").performScrollTo().performClick()
        compose.onNodeWithTag("theme-night").performClick()
        assertEquals(listOf("night"), picked)
        compose.onNodeWithTag("family-quiet").performScrollTo().performClick()
        compose.onNodeWithText("These end pale", substring = true).assertExists()
    }

    @Test
    fun theUnlockButtonSitsAtTheBottomUntilThePackIsOwned() {
        var owned by mutableStateOf(false)
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = owned, price = null,
                onTheme = {}, onAnimation = {}, onBuy = {}, onRestore = {}, onBack = {},
            )
        }
        scrollTo("unlock-themes")
        compose.onNodeWithTag("unlock-themes").performClick()
        compose.onNodeWithText("Not available yet").assertExists()
        compose.onNodeWithTag("purchase-sheet").assertExists()
        owned = true
        compose.onNodeWithTag("unlock-themes").assertDoesNotExist()
    }

    @Test
    fun withPlusNotForSaleOnlyFreeThemesShowAndNothingIsLocked() {
        val shown = CascadeThemes.shownFamilies(forSale = false)
        assertTrue(shown.flatMap { it.themeIds }.all { CascadeThemes.byId(it).isFree })
        assertEquals(CascadeThemes.all.count { it.isFree }, shown.sumOf { it.themeIds.size })
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = false, price = null,
                onTheme = {}, onAnimation = {}, onBuy = {}, onRestore = {}, onBack = {}, forSale = false,
            )
        }
        compose.onNodeWithTag("theme-forest").assertExists()
        compose.onNodeWithTag("theme-tide").assertDoesNotExist()
        compose.onNodeWithTag("family-earth").assertDoesNotExist()
        compose.onNodeWithTag("unlock-themes").assertDoesNotExist()
        compose.onNodeWithTag("animation-stars").assertDoesNotExist()
    }

    @Test
    fun withoutPlusAnAnimationOnlyPlaysInThePreview() {
        val chosen = mutableListOf<String>()
        // Android's "Remove animations": the preview stands still, so the list can scroll and settle.
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = false, price = null,
                onTheme = {}, onAnimation = { chosen += it.key }, onBuy = {}, onRestore = {}, onBack = {},
            )
        }
        scrollTo("animation-stars")
        compose.onNodeWithText("Part of Cascade Plus. Pick one to see it here.").assertExists()
        compose.onNodeWithTag("animation-stars").performClick()
        compose.onNodeWithTag("ambient-stars", useUnmergedTree = true).assertExists()
        assertTrue("a locked animation is never saved", chosen.isEmpty())
    }

    @Test
    fun aGroupCanFollowTheAppOrHaveItsOwnTheme() {
        var chosen by mutableStateOf<String?>("night")
        compose.setContent {
            GroupThemeContent("Gym Day", chosen, "forest", plusOwned = false, price = null, onTheme = { chosen = it }, onBuy = {}, onRestore = {}, onBack = {})
        }
        compose.onNodeWithTag("theme-night").assertIsSelected()
        compose.onNodeWithTag("follow-app-theme").performClick()
        assertNull(chosen)
        compose.onNodeWithText("Forest, and whatever it changes to").assertExists()
    }

    @Test
    fun anUnownedGroupThemeIsKeptAndSaysWhatShowsInstead() {
        compose.setContent {
            GroupThemeContent("Gym Day", "citrus", "night", plusOwned = false, price = null, onTheme = {}, onBuy = {}, onRestore = {}, onBack = {})
        }
        compose.onNodeWithTag("theme-locked").assertExists()
        compose.onNodeWithText("Citrus is part of Cascade Plus, which this phone does not have. This group uses Night until it is unlocked.").assertExists()
    }

    @Test
    fun aLightGroupOnADarkHomeKeepsTheAppSurfaceAndShowsASwatch() {
        val group = AlarmGroupEntity("g", "Gym Day", "#000000", "default", true, 0, Instant.EPOCH, themeId = "paper")
        val same = AlarmGroupEntity("h", "Night shift", "#000000", "default", true, 1, Instant.EPOCH, themeId = "night")
        val summaries = listOf(GroupSummary(group, 2, 2, null), GroupSummary(same, 1, 1, null))
        compose.setContent {
            CascadeTheme(CascadeThemes.byId("forest")) {
                HomeContent(summaries, {}, {}, { _, _ -> }, { _, _ -> }, {}, themeFor = { CascadeThemes.byId(it.themeId!!) })
            }
        }
        compose.onNodeWithTag("swatch-g", useUnmergedTree = true).assertExists()
        // Night is dark like Forest, so its card simply takes Night's surface: no swatch needed.
        compose.onNodeWithTag("swatch-h", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun ownershipIsKeptWhereTheFiringScreenCanReadIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = Entitlements(context)
        assertFalse(first.plusOwned.value)
        assertFalse(first.owns(CascadeThemes.byId("citrus")))
        assertTrue(first.owns(CascadeThemes.byId("night")))
        first.setPlusOwned(true)
        assertTrue(Entitlements(context).owns(CascadeThemes.byId("citrus")))
    }

    @Test
    fun withGroupThemesOffEveryCardStaysInTheAppTheme() {
        val group = AlarmGroupEntity("g", "Gym Day", "#000000", "default", true, 0, Instant.EPOCH, themeId = "paper")
        compose.setContent {
            CascadeTheme(CascadeThemes.byId("forest")) {
                HomeContent(listOf(GroupSummary(group, 2, 2, null)), {}, {}, { _, _ -> }, { _, _ -> }, {}, themeFor = null)
            }
        }
        compose.onNodeWithTag("swatch-g", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun appearanceSwitchesGroupThemesOnHome() {
        var on by mutableStateOf(true)
        compose.setContent {
            AppearanceContent(
                "forest", AnimationChoice.None, plusOwned = false, price = null,
                onTheme = {}, onAnimation = {}, onBuy = {}, onRestore = {}, onBack = {},
                groupThemesOnHome = on, onGroupThemesOnHome = { on = it },
            )
        }
        scrollTo("group-themes-on-home")
        compose.onNodeWithText("Each group's card shows its own theme").assertExists()
        compose.onNodeWithTag("group-themes-on-home").performClick()
        assertFalse(on)
        compose.onNodeWithText("Every card stays in Forest").assertExists()
    }

    @Test
    fun groupThemesOnHomeStartsOnAndIsKept() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = com.cascade.alarm.ui.settings.UiPreferences(context)
        assertTrue(first.groupThemesOnHome.value)
        first.setGroupThemesOnHome(false)
        assertFalse(com.cascade.alarm.ui.settings.UiPreferences(context).groupThemesOnHome.value)
    }
}
