package com.cascade.alarm.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cascade.alarm.data.StarterPlan
import com.cascade.alarm.ui.onboarding.OnboardingContent
import com.cascade.alarm.ui.reliability.ReliabilityCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@Config(qualifiers = "w411dp-h914dp")
@RunWith(RobolectricTestRunner::class)
class OnboardingScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val permissions = listOf(
        ReliabilityCheck("Notifications", passed = true, blocker = true, consequence = "n", fixIntent = null),
        ReliabilityCheck("Full-screen alarms", passed = false, blocker = true, consequence = "Without it the alarm shows only as a notification.", fixIntent = null),
    )

    private fun next() = compose.onNodeWithTag("onboarding-next").performClick()

    @Test
    fun welcomeThenPermissionsThenAFirstGroup() {
        val allowed = mutableListOf<String>()
        var created: StarterPlan? = null
        compose.setContent {
            OnboardingContent(permissions, is24Hour = false, onAllow = { allowed += it.title }, onCreate = { created = it }, onSkip = {})
        }
        compose.onNodeWithText("Alarms that get harder").assertExists()
        next()
        compose.onNodeWithText("Step 2 of 3").assertExists()
        compose.onNodeWithTag("allow-Notifications").assertDoesNotExist()
        compose.onNodeWithTag("allow-Full-screen alarms").performClick()
        assertEquals(listOf("Full-screen alarms"), allowed)
        compose.onNodeWithText("Continue anyway").performClick()

        compose.onNodeWithText("Your first group").assertExists()
        compose.onNodeWithTag("starter-count-4").performClick()
        compose.onNodeWithTag("starter-step-15 min").performClick()
        compose.onNodeWithText("Rings at 6:30 AM, 6:45 AM, 7:00 AM, 7:15 AM on weekdays.", substring = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("starter-create"))
        compose.onNodeWithTag("starter-create").performClick()
        val plan = created!!
        assertEquals(4, plan.count)
        assertEquals(LocalTime.of(7, 15), plan.times.last())
        assertEquals(StarterPlan.WEEKDAYS, plan.days)
    }

    @Test
    fun skippingFromTheStartMakesNothing() {
        var skipped = 0
        var created = false
        compose.setContent { OnboardingContent(permissions, false, {}, { created = true }, { skipped++ }) }
        compose.onNodeWithTag("onboarding-skip").performClick()
        assertEquals(1, skipped)
        assertTrue(!created)
    }

    @Test
    fun withNothingToAllowThereAreTwoSteps() {
        compose.setContent { OnboardingContent(emptyList(), false, {}, {}, {}) }
        compose.onNodeWithText("Step 1 of 2").assertExists()
        next()
        compose.onNodeWithText("Your first group").assertExists()
    }
}
