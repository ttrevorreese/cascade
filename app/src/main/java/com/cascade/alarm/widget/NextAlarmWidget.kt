package com.cascade.alarm.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.cascade.alarm.MainActivity
import com.cascade.alarm.R
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.ui.RingsInNotice
import com.cascade.alarm.ui.TimeText
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.ThemeResolution
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget shows (owner request, 2026-10-26): the next ring of any group that is on. */
data class WidgetState(
    val group: AlarmGroupEntity,
    val at: Instant,
    /** A regular ring, so "Skip next" applies; snoozes and wake-up checks cannot be skipped here. */
    val skippable: Boolean,
) {
    companion object {
        fun of(groups: List<AlarmGroupEntity>, alarms: List<AlarmEntity>): WidgetState? {
            val armed = groups.filter { it.isArmed && it.id != SystemTestAlarm.GROUP_ID }.associateBy { it.id }
            val (alarm, group) = alarms.mapNotNull { a -> armed[a.groupId]?.let { a to it } }
                .filter { (a, _) -> a.nextTriggerAt != null }
                .minByOrNull { (a, _) -> a.nextTriggerAt!! } ?: return null
            val at = alarm.nextTriggerAt!!
            return WidgetState(group, at, skippable = at != alarm.oneTimeTriggerAt && at != alarm.wakeCheckAt)
        }
    }
}

@Singleton
class NextAlarmWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CascadeDatabase,
    private val ui: UiPreferences,
    private val entitlements: Entitlements,
    private val time: TimeSource,
) {
    private val prefs get() = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun update() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NextAlarmWidget::class.java))
        if (ids.isEmpty()) return
        val state = WidgetState.of(db.alarmGroupDao().all(), db.alarmDao().all())
        val confirming = confirming()?.takeIf { state != null && it.first == state.group.id && it.second == state.at }
        manager.updateAppWidget(ids, views(state, confirming != null))
    }

    /** First tap on Skip asks again; a second tap on the same ring within [CONFIRM_WINDOW] skips. */
    fun confirming(): Pair<String, Instant>? {
        val group = prefs.getString(CONFIRM_GROUP, null) ?: return null
        val at = Instant.ofEpochMilli(prefs.getLong(CONFIRM_AT, 0))
        val asked = Instant.ofEpochMilli(prefs.getLong(CONFIRM_ASKED, 0))
        return if (Duration.between(asked, time.now()) <= CONFIRM_WINDOW) group to at else null
    }

    fun ask(groupId: String, at: Instant) =
        prefs.edit().putString(CONFIRM_GROUP, groupId).putLong(CONFIRM_AT, at.toEpochMilli()).putLong(CONFIRM_ASKED, time.now().toEpochMilli()).apply()

    fun clearAsk() = prefs.edit().remove(CONFIRM_GROUP).remove(CONFIRM_AT).remove(CONFIRM_ASKED).apply()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Redraws once the confirm window is over; if the process dies first, a stale label only asks again. */
    fun revertLater() {
        scope.launch {
            delay(CONFIRM_WINDOW.toMillis() + 500)
            try {
                if (confirming() == null) update()
            } catch (e: Exception) {
                EngineLog.e("widget revert failed", e)
            }
        }
    }

    private fun views(state: WidgetState?, confirming: Boolean): RemoteViews {
        val colors = ThemeResolution.app(ui.themeId.value, entitlements::owns).colors
        val is24Hour = DateFormat.is24HourFormat(context)
        val zone = ZoneId.systemDefault()
        return RemoteViews(context.packageName, R.layout.widget_next_alarm).apply {
            setInt(R.id.widget_bg, "setColorFilter", colors.surface.toArgb())
            setTextColor(R.id.widget_label, colors.textSecondary.toArgb())
            setTextColor(R.id.widget_time, colors.textPrimary.toArgb())
            setTextColor(R.id.widget_detail, colors.textSecondary.toArgb())
            setTextColor(R.id.widget_skip, colors.textPrimary.toArgb())
            setOnClickPendingIntent(R.id.widget_root, openApp())
            if (state == null) {
                setTextViewText(R.id.widget_time, "—")
                setTextViewText(R.id.widget_detail, context.getString(R.string.widget_nothing))
                setViewVisibility(R.id.widget_skip, View.GONE)
            } else {
                val local = state.at.atZone(zone)
                setTextViewText(R.id.widget_time, TimeText.time(local.hour, local.minute, is24Hour))
                setTextViewText(R.id.widget_detail, "${TimeText.day(state.at, zone)} · ${state.group.name}")
                setViewVisibility(R.id.widget_skip, if (state.skippable) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_skip, context.getString(if (confirming) R.string.widget_skip_confirm else R.string.widget_skip))
                setOnClickPendingIntent(R.id.widget_skip, skipIntent(state))
            }
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun skipIntent(state: WidgetState) = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, NextAlarmWidget::class.java)
            .setAction(NextAlarmWidget.ACTION_SKIP)
            .putExtra(NextAlarmWidget.EXTRA_GROUP, state.group.id)
            .putExtra(NextAlarmWidget.EXTRA_AT, state.at.toEpochMilli()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        val CONFIRM_WINDOW: Duration = Duration.ofSeconds(6)
        private const val PREFS = "widget"
        private const val CONFIRM_GROUP = "confirm_group"
        private const val CONFIRM_AT = "confirm_at"
        private const val CONFIRM_ASKED = "confirm_asked"
    }
}

/**
 * Home-screen widget: the next alarm, and Skip (tap twice: one stray tap must not cost a morning).
 * Undo is in the app, on the group's screen.
 */
class NextAlarmWidget : AppWidgetProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun widgetUpdater(): NextAlarmWidgetUpdater
        fun alarmRepository(): AlarmRepository
        fun ringsInNotice(): RingsInNotice
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP) return super.onReceive(context, intent)
        val groupId = intent.getStringExtra(EXTRA_GROUP) ?: return
        val at = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_AT, 0))
        val entry = EntryPointAccessors.fromApplication(context, Entry::class.java)
        val updater = entry.widgetUpdater()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (updater.confirming() == (groupId to at)) {
                    updater.clearAsk()
                    if (entry.alarmRepository().skipNext(groupId)) entry.ringsInNotice().show(groupId)
                    updater.update()
                } else {
                    updater.ask(groupId, at)
                    updater.update()
                    // Back to "Skip next" if the second tap does not come. Not inside goAsync: taps
                    // reach the widget one at a time, so waiting here would hold the second tap back.
                    updater.revertLater()
                }
            } catch (e: Exception) {
                EngineLog.e("widget skip failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun refresh(context: Context) {
        val updater = EntryPointAccessors.fromApplication(context, Entry::class.java).widgetUpdater()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updater.update()
            } catch (e: Exception) {
                EngineLog.e("widget update failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SKIP = "com.cascade.alarm.widget.SKIP"
        const val EXTRA_GROUP = "group"
        const val EXTRA_AT = "at"
    }
}
