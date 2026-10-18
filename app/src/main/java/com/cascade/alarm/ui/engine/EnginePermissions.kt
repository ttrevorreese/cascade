package com.cascade.alarm.ui.engine

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** One row of the M1 permission checklist. [fixIntent] is null when there is nothing to open. */
data class PermissionCheck(
    val title: String,
    val granted: Boolean,
    val consequence: String,
    val fixIntent: Intent?,
    val needsRuntimeRequest: Boolean = false,
)

object EnginePermissions {
    fun check(context: Context): List<PermissionCheck> {
        val packageUri = "package:${context.packageName}".toUri()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    PermissionCheck(
                        title = "Notifications",
                        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED,
                        consequence = "Without it the alarm sounds but cannot appear over the lock screen.",
                        fixIntent = null,
                        needsRuntimeRequest = true,
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(
                    PermissionCheck(
                        title = "Full-screen alarms",
                        granted = notifications.canUseFullScreenIntent(),
                        consequence = "Without it the alarm shows only as a notification.",
                        fixIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri),
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    PermissionCheck(
                        title = "Alarms & reminders",
                        granted = alarms.canScheduleExactAlarms(),
                        consequence = "Without it alarms can be late.",
                        fixIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
                    ),
                )
            }
            add(
                PermissionCheck(
                    title = "Battery: unrestricted",
                    granted = power.isIgnoringBatteryOptimizations(context.packageName),
                    consequence = "Recommended. Some manufacturers (incl. Samsung) may stop restricted apps.",
                    fixIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                ),
            )
        }
    }
}
