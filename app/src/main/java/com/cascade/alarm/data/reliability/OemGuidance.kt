package com.cascade.alarm.data.reliability

/**
 * What to change on phones whose makers stop background apps (SPEC §4.3, dontkillmyapp.com).
 * The settings screens move between OS versions, so every deep link is a candidate that is checked
 * before it is offered, with the app's own settings page as the fallback.
 */
data class OemGuidance(
    val maker: String,
    val steps: List<String>,
    /** Candidate settings screens as package/class names, most specific first. */
    val screens: List<Pair<String, String>>,
    /** dontkillmyapp.com page for this maker, for the full and current story. */
    val moreUrl: String,
)

object OemGuidances {
    fun forDevice(manufacturer: String, brand: String): OemGuidance? {
        val name = "$manufacturer $brand".lowercase()
        return when {
            "samsung" in name -> SAMSUNG
            listOf("xiaomi", "redmi", "poco").any { it in name } -> XIAOMI
            listOf("huawei", "honor").any { it in name } -> HUAWEI
            listOf("oppo", "realme", "oneplus").any { it in name } -> OPPO
            "vivo" in name -> VIVO
            else -> null
        }
    }

    private val SAMSUNG = OemGuidance(
        maker = "Samsung",
        steps = listOf(
            "Settings > Apps > Cascade > Battery: choose Unrestricted.",
            "Settings > Battery > Background usage limits > Never sleeping apps: add Cascade.",
            "If Cascade ever shows up under Sleeping apps there, remove it.",
        ),
        screens = listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        ),
        moreUrl = "https://dontkillmyapp.com/samsung",
    )

    private val XIAOMI = OemGuidance(
        maker = "Xiaomi",
        steps = listOf(
            "Settings > Apps > Manage apps > Cascade > Autostart: on.",
            "In the same place, Battery saver: No restrictions.",
            "Lock Cascade in Recents (pull down on its card) so cleaning does not close it.",
        ),
        screens = listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        moreUrl = "https://dontkillmyapp.com/xiaomi",
    )

    private val HUAWEI = OemGuidance(
        maker = "Huawei",
        steps = listOf(
            "Settings > Battery > App launch > Cascade: switch to Manage manually.",
            "Turn on Auto-launch, Secondary launch and Run in background.",
        ),
        screens = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        moreUrl = "https://dontkillmyapp.com/huawei",
    )

    private val OPPO = OemGuidance(
        maker = "Oppo, Realme and OnePlus",
        steps = listOf(
            "Settings > Apps > Cascade > Battery usage: allow background activity, or choose Don't optimise.",
            "Settings > Apps > Auto launch: allow Cascade.",
        ),
        screens = listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        ),
        moreUrl = "https://dontkillmyapp.com/oneplus",
    )

    private val VIVO = OemGuidance(
        maker = "Vivo",
        steps = listOf(
            "i Manager > App manager > Autostart: allow Cascade.",
            "Settings > Battery > Background power consumption: allow Cascade.",
        ),
        screens = listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        moreUrl = "https://dontkillmyapp.com/vivo",
    )
}
