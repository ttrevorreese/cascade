package com.cascade.alarm.ui.reliability

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.cascade.alarm.data.reliability.OemGuidance

/**
 * One row of the Reliability Check (SPEC §4.3). A [blocker] stops alarms from working as they
 * should and earns a banner on Home; the rest is advice. [fixIntent] is null when there is
 * nothing to open; [needsRuntimeRequest] means the fix is the system permission dialog.
 */
data class ReliabilityCheck(
    val title: String,
    val passed: Boolean,
    val blocker: Boolean,
    val consequence: String,
    val fixIntent: Intent?,
    val needsRuntimeRequest: Boolean = false,
)

object ReliabilityChecks {
    fun check(context: Context): List<ReliabilityCheck> {
        val packageUri = "package:${context.packageName}".toUri()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    ReliabilityCheck(
                        title = "Notifications",
                        passed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED,
                        blocker = true,
                        consequence = "Without it the alarm sounds but cannot appear over the lock screen.",
                        fixIntent = null,
                        needsRuntimeRequest = true,
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(
                    ReliabilityCheck(
                        title = "Full-screen alarms",
                        passed = notifications.canUseFullScreenIntent(),
                        blocker = true,
                        consequence = "Without it the alarm shows only as a notification.",
                        fixIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri),
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    ReliabilityCheck(
                        title = "Alarms and reminders",
                        passed = alarms.canScheduleExactAlarms(),
                        blocker = true,
                        consequence = "Without it alarms can ring late.",
                        fixIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
                    ),
                )
            }
            add(
                ReliabilityCheck(
                    title = "Battery: unrestricted",
                    passed = power.isIgnoringBatteryOptimizations(context.packageName),
                    blocker = false,
                    consequence = "Some phone makers stop restricted apps, and their alarms with them. " +
                        "In the list that opens, find Cascade and choose Unrestricted (or Don't optimise).",
                    // The settings list, not the direct dialog: Play limits that to apps Doze
                    // breaks, and setAlarmClock() alarms already ring through Doze (SPEC §4.3).
                    fixIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    ReliabilityCheck(
                        title = "Keep running when unused",
                        // Android pauses apps that go unopened for months and removes their
                        // permissions, which would stop an alarm app that is set once and left.
                        passed = context.packageManager.isAutoRevokeWhitelisted,
                        blocker = false,
                        consequence = "Android may pause Cascade if you do not open it for a few months, and alarms would stop. " +
                            "On the page that opens, turn off \"Pause app activity if unused\".",
                        fixIntent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri),
                    ),
                )
            }
        }
    }

    /** App info, the fallback for any settings screen that does not exist on this phone. */
    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /** The first of [guidance]'s settings screens that exists on this phone, else app info. */
    fun makerSettings(context: Context, guidance: OemGuidance): Intent =
        guidance.screens
            .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: appSettings(context)
}
