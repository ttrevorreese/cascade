package com.cascade.alarm.data

/**
 * How Home lists groups (owner request, 2026-10-25). [CUSTOM] is the order the user set with the
 * arrows; it is kept while another order is showing, so switching back finds it unchanged.
 */
enum class GroupSort(val key: String, val title: String) {
    CUSTOM("custom", "Your order"),
    TIME("time", "Earliest first"),
    MODIFIED("modified", "Recently changed"),
    ;

    fun sort(groups: List<GroupSummary>): List<GroupSummary> = when (this) {
        CUSTOM -> groups.sortedBy { it.group.sortOrder }
        // By the first alarm's time of day; a group with no alarms goes last.
        TIME -> groups.sortedWith(
            compareBy<GroupSummary>({ it.firstTime == null }, { it.firstTime }, { it.group.sortOrder }),
        )
        MODIFIED -> groups.sortedWith(compareByDescending<GroupSummary> { it.group.updatedAt }.thenBy { it.group.sortOrder })
    }

    companion object {
        val DEFAULT = CUSTOM

        fun byKey(key: String?): GroupSort = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
