package com.cascade.alarm.ui.theme

/**
 * The one place a theme is chosen (DESIGN.md §11.1): `group.themeId ?: appThemeId ?: Forest`.
 * An id that is unknown (a typo, a theme from a newer version) or not owned (a refund, a restore
 * that has not finished) is skipped silently, never blocking anything: the firing screen always
 * gets a theme.
 */
object ThemeResolution {
    fun resolve(groupThemeId: String?, appThemeId: String?, owns: (ThemeSpec) -> Boolean): ThemeSpec =
        usable(groupThemeId, owns) ?: usable(appThemeId, owns) ?: CascadeThemes.default

    /** The app theme alone, for screens that always use it (Settings, Stats, Reliability, Appearance). */
    fun app(appThemeId: String?, owns: (ThemeSpec) -> Boolean): ThemeSpec = resolve(null, appThemeId, owns)

    /** Whether the group's own choice is set but will not show, so its editor can say why. */
    fun groupThemeLocked(groupThemeId: String?, owns: (ThemeSpec) -> Boolean): Boolean =
        groupThemeId != null && CascadeThemes.find(groupThemeId)?.let { !owns(it) } == true

    private fun usable(id: String?, owns: (ThemeSpec) -> Boolean): ThemeSpec? =
        id?.let(CascadeThemes::find)?.takeIf { runCatching { owns(it) }.getOrDefault(it.isFree) }
}
