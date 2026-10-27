package com.cascade.alarm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.cascade.alarm.data.GroupSummary
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.engine.ring.RingingAlarm
import com.cascade.alarm.ui.alarm.AlarmEditorContent
import com.cascade.alarm.ui.ring.AlarmScreen
import com.cascade.alarm.ui.group.GroupContent
import com.cascade.alarm.ui.home.HomeContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val group = AlarmGroupEntity("g", "Workday Wake", "#000000", "default", true, 0, Instant.EPOCH)

    private fun alarm(id: String, position: Int, hour: Int, enabled: Boolean = true) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = position, hour = hour, minute = 0,
        daysOfWeek = DayOfWeek.entries.take(5).toSet(), label = null, isEnabled = enabled,
        volumeRampSeconds = 30, timeoutMinutes = 15,
    )

    @Test
    fun homeSortsGroupsAndOnlyYourOrderHasArrows() {
        val two = listOf(
            GroupSummary(group, alarmCount = 1, enabledCount = 1, nextRing = null),
            GroupSummary(group.copy(id = "h", name = "Gym Day", sortOrder = 1), alarmCount = 1, enabledCount = 1, nextRing = null),
        )
        var sort by mutableStateOf(com.cascade.alarm.data.GroupSort.CUSTOM)
        compose.setContent { HomeContent(two, {}, {}, { _, _ -> }, { _, _ -> }, {}, sort = sort, onSort = { sort = it }) }
        compose.onNodeWithContentDescription("Move Gym Day up").assertExists()
        compose.onNodeWithTag("group-sort").performScrollTo().performClick()
        compose.onNodeWithTag("sort-time").performClick()
        assertEquals(com.cascade.alarm.data.GroupSort.TIME, sort)
        compose.onNodeWithText("Earliest first").assertExists()
        compose.onNodeWithContentDescription("Move Gym Day up").assertDoesNotExist()
    }

    @Test
    fun homeCanListGroupsThatAreOnFirst() {
        val two = listOf(
            GroupSummary(group, alarmCount = 1, enabledCount = 1, nextRing = null),
            GroupSummary(group.copy(id = "h", name = "Gym Day", sortOrder = 1), alarmCount = 1, enabledCount = 1, nextRing = null),
        )
        var onFirst by mutableStateOf(false)
        compose.setContent { HomeContent(two, {}, {}, { _, _ -> }, { _, _ -> }, {}, onFirst = onFirst, onOnFirst = { onFirst = it }) }
        compose.onNodeWithTag("group-sort").performScrollTo().performClick()
        compose.onNodeWithTag("sort-on-first").performClick()
        assertEquals(true, onFirst)
        compose.onNodeWithText("Your order, on first").assertExists()
        compose.onNodeWithContentDescription("Move Gym Day up").assertDoesNotExist()
    }

    @Test
    fun aDuplicateIsNamedBeforeItIsMade() {
        var made: String? = null
        compose.setContent {
            com.cascade.alarm.ui.group.GroupContent(
                group = group, alarms = listOf(alarm("a", 0, 6)),
                onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> }, onShiftWithFirstChange = {}, onShift = {}, onWakeCheckChange = { _, _ -> },
                onDuplicate = { made = it },
            )
        }
        compose.onNodeWithTag("duplicate-group").performClick()
        compose.onNodeWithText("Name the copy").assertExists()
        compose.onNodeWithTag("name-field").assertTextContains("Workday Wake copy")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(null, made)

        compose.onNodeWithTag("duplicate-group").performClick()
        compose.onNodeWithTag("name-field").performTextReplacement("Weekend Wake")
        compose.onNodeWithTag("name-confirm").performClick()
        assertEquals("Weekend Wake", made)
    }

    @Test
    fun homeShowsEmptyStateThenGroups() {
        var groups by mutableStateOf<List<GroupSummary>?>(emptyList())
        compose.setContent { HomeContent(groups, {}, {}, { _, _ -> }, { _, _ -> }, {}) }
        compose.onNodeWithText("No groups yet", substring = true).assertExists()

        groups = listOf(GroupSummary(group.copy(isArmed = false), alarmCount = 2, enabledCount = 2, nextRing = null))
        compose.onNodeWithText("Workday Wake").assertExists()
        compose.onNodeWithText("Off · 2 alarms").assertExists()
        compose.onNodeWithTag("armed-g").assertIsOff()
        // A disarmed group loses its ramp preview.
        compose.onNodeWithText("2 steps").assertDoesNotExist()
        compose.onNodeWithText("Nothing scheduled").assertExists()
    }

    @Test
    fun homeShowsTheArmedGroupsScheduleRampAndNextAlarm() {
        val next = Instant.parse("2026-10-19T10:00:00Z")
        val summary = GroupSummary(
            group, alarmCount = 3, enabledCount = 3, nextRing = next,
            firstTime = LocalTime.of(6, 0), lastTime = LocalTime.of(6, 35), days = DayOfWeek.entries.take(5).toSet(),
        )
        compose.setContent { HomeContent(listOf(summary), {}, {}, { _, _ -> }, { _, _ -> }, {}) }
        compose.onNodeWithText("Next alarm").assertExists()
        compose.onNodeWithText("6:00 AM – 6:35 AM · Weekdays").assertExists()
        compose.onNodeWithText("3 steps").assertExists()
        compose.onNodeWithContentDescription("Move Workday Wake up").assertIsNotEnabled()
    }

    @Test
    fun homeMasterSwitchReportsTheNewState() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val summary = GroupSummary(group, 1, 1, null)
        compose.setContent { HomeContent(listOf(summary), {}, {}, { id, on -> toggles += id to on }, { _, _ -> }, {}) }
        compose.onNodeWithTag("armed-g").assertIsOn().performClick()
        assertEquals(listOf("g" to false), toggles)
    }

    @Test
    fun homeCreatesANamedGroup() {
        val created = mutableListOf<String>()
        compose.setContent { HomeContent(emptyList(), {}, {}, { _, _ -> }, { _, _ -> }, { created += it }) }
        compose.onNodeWithText("New group").performClick()
        compose.onNodeWithText("Name").performTextInput("Gym Day")
        compose.onNodeWithText("Create group").performClick()
        assertEquals(listOf("Gym Day"), created)
    }

    @Test
    fun groupListsAlarmsInOrderAndExplainsADisarmedGroup() {
        compose.setContent {
            GroupContent(
                group = group.copy(isArmed = false),
                alarms = listOf(alarm("a", 0, 6), alarm("b", 1, 6, enabled = false)),
                onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> }, onShiftWithFirstChange = {}, onShift = {}, onWakeCheckChange = { _, _ -> },
            )
        }
        compose.onNodeWithText("1 of 2 · Weekdays").assertExists()
        compose.onNodeWithText("2 of 2 · Weekdays").assertExists()
        compose.onNodeWithTag("group-armed").assertIsOff()
        // Own switches are shown as they are, even while the group is off.
        compose.onNodeWithTag("alarm-enabled-a").assertIsOn()
        compose.onNodeWithTag("alarm-enabled-b").assertIsOff()
        compose.onNodeWithText("Group is off").assertExists()
    }

    @Test
    fun groupShiftControlsReportTheirChanges() {
        val shifts = mutableListOf<Long>()
        var shiftWithFirst: Boolean? = null
        compose.setContent {
            GroupContent(
                group = group, alarms = listOf(alarm("a", 0, 6)),
                onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> }, onShiftWithFirstChange = { shiftWithFirst = it }, onShift = { shifts += it }, onWakeCheckChange = { _, _ -> },
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("group-shift-with-first"))
        compose.onNodeWithTag("group-shift-with-first").assertIsOn().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(false, shiftWithFirst)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("+5 min"))
        compose.onNodeWithText("+5 min").performClick()
        compose.onNodeWithText("−5 min").performClick()
        assertEquals(listOf(5L, -5L), shifts)
    }

    @Test
    fun wakeCheckSettingTurnsOnAndPicksADelay() {
        val changes = mutableListOf<Pair<Boolean, Int>>()
        compose.setContent {
            var current by remember { mutableStateOf(group) }
            GroupContent(
                group = current, alarms = listOf(alarm("a", 0, 6)),
                onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> }, onShiftWithFirstChange = {}, onShift = {},
                onWakeCheckChange = { enabled, delay ->
                    changes += enabled to delay
                    current = current.copy(wakeCheckEnabled = enabled, wakeCheckDelayMinutes = delay)
                },
            )
        }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasTestTag("group-wake-check"))
        compose.onNodeWithText("No check after the last alarm").assertExists()
        // The tiny Robolectric window leaves these at the scroll edge, so invoke their clicks directly.
        compose.onNodeWithTag("group-wake-check").assertIsOff().performSemanticsAction(SemanticsActions.OnClick)
        list.performScrollToNode(hasText("5 min"))
        compose.onNodeWithText("5 min").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(true to 3, true to 5), changes)
    }

    @Test
    fun deletingAGroupNeedsConfirmation() {
        var deleted = 0
        compose.setContent {
            GroupContent(
                group, emptyList(), {}, {}, {}, {}, onDelete = { deleted++ },
                onAlarmEnabledChange = { _, _ -> }, onShiftWithFirstChange = {}, onShift = {}, onWakeCheckChange = { _, _ -> },
            )
        }
        compose.onNodeWithText("Delete").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete Workday Wake?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
    }

    @Test
    fun editorTogglesDaysAndSaves() {
        var saved = 0
        lateinit var latest: AlarmEntity
        compose.setContent {
            var draft by remember { mutableStateOf(alarm("a", 0, 6).copy(daysOfWeek = emptySet())) }
            latest = draft
            AlarmEditorContent(
                draft = draft, isNew = true,
                onEdit = { transform -> draft = transform(draft) },
                onSave = { saved++ }, onDelete = {}, onCancel = {},
            )
        }
        // performClick doesn't wait for recomposition; `latest` is a plain variable, so sync first.
        fun tap(day: DayOfWeek): Set<DayOfWeek> {
            compose.onNodeWithTag("day-$day").performClick()
            compose.waitForIdle()
            return latest.daysOfWeek
        }
        assertEquals(setOf(DayOfWeek.MONDAY), tap(DayOfWeek.MONDAY))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), tap(DayOfWeek.FRIDAY))
        assertEquals(setOf(DayOfWeek.FRIDAY), tap(DayOfWeek.MONDAY))
        compose.onNodeWithTag("save").performClick()
        assertEquals(1, saved)
        assertTrue("new alarms have nothing to delete", compose.onAllNodesWithTextCount("Delete alarm") == 0)
    }

    @Test
    fun settingATimeSwitchesAnAlarmThatRangOnceBackOn() {
        lateinit var latest: AlarmEntity
        compose.setContent {
            var draft by remember { mutableStateOf(alarm("a", 0, 6, enabled = false).copy(daysOfWeek = emptySet())) }
            latest = draft
            AlarmEditorContent(draft = draft, isNew = false, onEdit = { transform -> draft = transform(draft) }, onSave = {}, onDelete = {}, onCancel = {})
        }
        compose.onNodeWithTag("time-step5").performClick()
        compose.waitForIdle()
        assertTrue(latest.isEnabled)
        assertEquals(LocalTime.of(6, 5), LocalTime.of(latest.hour, latest.minute))
    }

    @Test
    fun theTimeCanBeTypedOrNudgedByMinutes() {
        lateinit var latest: AlarmEntity
        compose.setContent {
            var draft by remember { mutableStateOf(alarm("a", 0, 6)) }
            latest = draft
            AlarmEditorContent(draft = draft, isNew = false, onEdit = { transform -> draft = transform(draft) }, onSave = {}, onDelete = {}, onCancel = {})
        }
        fun time() = LocalTime.of(latest.hour, latest.minute)
        compose.onNodeWithTag("time").performTextReplacement("715")
        compose.waitForIdle()
        assertEquals(LocalTime.of(7, 15), time())
        compose.onNodeWithTag("time-step10").performClick()
        compose.onNodeWithTag("time-step-1").performClick()
        compose.waitForIdle()
        assertEquals(LocalTime.of(7, 24), time())
        compose.onNodeWithTag("time").assertTextContains("7:24")
        compose.onNodeWithTag("time-pm").performClick()
        compose.waitForIdle()
        assertEquals(LocalTime.of(19, 24), time())
        compose.onNodeWithTag("time").performTextReplacement("6:30 am")
        compose.waitForIdle()
        assertEquals(LocalTime.of(6, 30), time())
        // Half-typed is not a time: the last good one stands and the box says so.
        compose.onNodeWithTag("time").performTextReplacement("6:7")
        compose.onNodeWithTag("time-invalid").assertExists()
        assertEquals(LocalTime.of(6, 30), time())
    }

    @Test
    fun theGroupCanSkipPauseOrMoveItsNextMorningAndGoBack() {
        val calls = mutableListOf<String>()
        var current by mutableStateOf(group)
        compose.setContent {
            GroupContent(
                group = current, alarms = listOf(alarm("a", 0, 6).copy(nextTriggerAt = Instant.parse("2026-10-19T10:00:00Z"))),
                onBack = {}, onOpenAlarm = {}, onArmedChange = {}, onRename = {}, onDelete = {},
                onAlarmEnabledChange = { _, _ -> }, onShiftWithFirstChange = {}, onShift = {}, onWakeCheckChange = { _, _ -> },
                onSkipNext = { calls += "skip" },
                onPauseUntil = { calls += "pause $it" },
                onMoveNext = { time, done -> calls += "move $time"; done(com.cascade.alarm.engine.schedule.GroupTiming.MoveResult.InThePast) },
                onResumeSchedule = { calls += "resume" },
                now = Instant.parse("2026-10-19T02:00:00Z"),
            )
        }
        compose.onNodeWithTag("next-morning").assertTextContains("Next ring", substring = true)
        compose.onNodeWithTag("resume-schedule").assertDoesNotExist()
        compose.onNodeWithTag("skip-next").performClick()
        assertEquals(listOf("skip"), calls)
        // A move into the past keeps the dialog open with the reason.
        compose.onNodeWithTag("move-next").performClick()
        compose.onNodeWithTag("move-time").performTextReplacement("530")
        compose.onNodeWithTag("move-confirm").performClick()
        compose.onNodeWithTag("move-problem").assertExists()
        assertEquals("move 05:30", calls.last())
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("pause-until").performClick()
        compose.onNodeWithTag("pause-confirm").performClick()
        assertTrue(calls.last(), calls.last().startsWith("pause 2026-10-"))
        // Skipped: the screen says so and offers the way back.
        current = group.copy(skipUntil = java.time.LocalDateTime.of(2099, 10, 19, 6, 0, 1))
        compose.onNodeWithTag("next-morning").assertTextContains("Skipped.", substring = true)
        compose.onNodeWithTag("resume-schedule").performClick()
        assertEquals("resume", calls.last())
    }

    private fun ringing(position: Int, total: Int, wakeCheck: Boolean = false) = RingingAlarm(
        alarmId = "a", label = "Gym bag", scheduledFor = Instant.EPOCH, deadline = Instant.now().plusSeconds(60),
        isWakeCheck = wakeCheck, position = position, total = total,
    )

    @Test
    fun firingScreenSaysWhereInTheSequenceItIs() {
        var current by mutableStateOf(ringing(0, 4))
        compose.setContent { AlarmScreen(current, onDismiss = {}) }
        compose.onNodeWithText("Alarm 1 of 4. More to come.").assertExists()
        compose.onNodeWithContentDescription("Alarm 1 of 4").assertExists()
        compose.onNodeWithText("Gym bag").assertExists()
        current = ringing(3, 4)
        compose.onNodeWithText("Last one.").assertExists()
        compose.onNodeWithText("Dismiss").assertIsEnabled()
    }

    @Test
    fun firingScreenAsksTheWakeUpCheck() {
        compose.setContent { AlarmScreen(ringing(2, 3, wakeCheck = true), onDismiss = {}) }
        compose.onNodeWithText("Are you awake?").assertExists()
        compose.onNodeWithText("I'm awake").assertExists()
        compose.onNodeWithText("Alarm rings again in", substring = true).assertExists()
    }

    @Test
    fun firingScreenOffersSnoozeBelowDismissAndNoneOnTheLast() {
        var snoozed = 0
        var current by mutableStateOf(ringing(0, 3).copy(snooze = com.cascade.alarm.data.SnoozeState.Offer(5, 2)))
        compose.setContent { AlarmScreen(current, onDismiss = {}, onSnooze = { snoozed++ }) }
        compose.onNodeWithText("Snooze 5 min").performClick()
        assertEquals(1, snoozed)
        compose.onNodeWithText("2 snoozes left").assertExists()
        current = ringing(2, 3).copy(snooze = com.cascade.alarm.data.SnoozeState.NotOnLast)
        compose.onNodeWithText("Last one.").assertExists()
        compose.onNodeWithText("No snooze.").assertExists()
        compose.onNodeWithText("Snooze 5 min").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
