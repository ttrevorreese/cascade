package com.cascade.alarm.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.cascade.alarm.BuildConfig

/**
 * Feedback and feature requests (owner request, 2026-10-25). Cascade cannot use the internet, so
 * this opens the user's own email app with a message to the developer that they can read and edit
 * before sending, or not send at all.
 */
object Feedback {
    const val ADDRESS = "cascadealarmgroup.app@gmail.com"

    enum class Kind(val key: String, val title: String, val detail: String, val subject: String, val prompt: String) {
        IDEA(
            "idea", "Suggest a feature", "Something Cascade should do", "Cascade feature request",
            "What would you like Cascade to do, and when would you use it?",
        ),
        PROBLEM(
            "problem", "Report a problem", "Something that went wrong, or an alarm that did not ring right", "Cascade problem",
            "What happened, and what did you expect? If an alarm was involved, roughly when did it ring?",
        ),
        OTHER("other", "Anything else", "Questions, thanks, complaints", "Cascade feedback", ""),
    }

    /** The message as the email app shows it; the phone details help with problems and are easy to delete. */
    fun body(kind: Kind, appVersion: String, android: String, phone: String): String = buildString {
        if (kind.prompt.isNotEmpty()) append(kind.prompt).append("\n\n\n\n")
        append("—\n")
        append("Cascade $appVersion · Android $android · $phone")
    }

    fun mailto(kind: Kind): Uri = Uri.parse(
        "mailto:$ADDRESS?subject=${Uri.encode(kind.subject)}&body=${Uri.encode(body(kind, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, phone()))}",
    )

    /** Opens the email app; false when the phone has none. */
    fun send(context: Context, kind: Kind): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, mailto(kind)).apply {
            // Some email apps read the extras rather than the mailto query.
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, kind.subject)
            putExtra(Intent.EXTRA_TEXT, body(kind, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, phone()))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun phone() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}
