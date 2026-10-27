package com.cascade.alarm.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.cascade.alarm.BuildConfig

/**
 * Feedback and feature requests (owner request, 2026-10-25). Cascade cannot use the internet, so
 * this opens a web page or app that can: the feedback form in the browser once [FORM_URL] is set
 * (2026-10-26: the developer's address stays private, and a script on the form emails them with
 * an emoji per kind; setup in docs/feedback-form.md), else the user's email app.
 */
object Feedback {
    const val ADDRESS = "cascadealarmgroup.app@gmail.com"

    /**
     * The Google Form's link, from its "Get pre-filled link" (ending in /viewform), and the ids of
     * its "What is it about?" and "Phone" questions. Empty until the form exists: email is used.
     */
    const val FORM_URL = ""
    const val FORM_KIND_ENTRY = ""
    const val FORM_PHONE_ENTRY = ""

    val usesForm: Boolean get() = FORM_URL.isNotEmpty()

    /** [formChoice] must match the form's option word for word: the script picks the emoji by it. */
    enum class Kind(val key: String, val title: String, val detail: String, val subject: String, val prompt: String, val formChoice: String) {
        IDEA(
            "idea", "Suggest a feature", "Something Cascade should do", "Cascade feature request",
            "What would you like Cascade to do, and when would you use it?", "Feature request",
        ),
        PROBLEM(
            "problem", "Report a problem", "Something that went wrong, or an alarm that did not ring right", "Cascade problem",
            "What happened, and what did you expect? If an alarm was involved, roughly when did it ring?", "Bug report",
        ),
        OTHER("other", "Anything else", "Questions, thanks, complaints", "Cascade feedback", "", "Something else"),
    }

    /** "Cascade 1.0.0 · Android 16 · Samsung SM-S908U": helps with problems, and is easy to delete. */
    fun phoneLine(appVersion: String, android: String, phone: String) = "Cascade $appVersion · Android $android · $phone"

    /** The form with the kind and the phone filled in; null until [FORM_URL] is set. */
    fun formUri(kind: Kind, appVersion: String = BuildConfig.VERSION_NAME, android: String = Build.VERSION.RELEASE, phone: String = phone(), url: String = FORM_URL): Uri? {
        if (url.isEmpty()) return null
        return Uri.parse(url).buildUpon()
            .appendQueryParameter("usp", "pp_url")
            .apply { if (FORM_KIND_ENTRY.isNotEmpty()) appendQueryParameter("entry.$FORM_KIND_ENTRY", kind.formChoice) }
            .apply { if (FORM_PHONE_ENTRY.isNotEmpty()) appendQueryParameter("entry.$FORM_PHONE_ENTRY", phoneLine(appVersion, android, phone)) }
            .build()
    }

    /** The message as the email app shows it; the phone details help with problems and are easy to delete. */
    fun body(kind: Kind, appVersion: String, android: String, phone: String): String = buildString {
        if (kind.prompt.isNotEmpty()) append(kind.prompt).append("\n\n\n\n")
        append("—\n")
        append(phoneLine(appVersion, android, phone))
    }

    fun mailto(kind: Kind): Uri = Uri.parse(
        "mailto:$ADDRESS?subject=${Uri.encode(kind.subject)}&body=${Uri.encode(body(kind, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, phone()))}",
    )

    /** Opens the form, or else the email app; false when the phone has neither. */
    fun send(context: Context, kind: Kind): Boolean {
        formUri(kind)?.let { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                // No browser: email still works.
            }
        }
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
