package com.avidviewer.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * "Report a bug": the address, plus the details worth including, read from this TV.
 * Never includes playlists, provider addresses, logins or keys.
 */
object BugReport {
    const val ADDRESS = "bugs@avidviewer.app"

    fun device(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        val fireOs = if (maker.equals("Amazon", ignoreCase = true)) fireOsFor(Build.VERSION.SDK_INT)?.let { " · $it" } ?: "" else ""
        return "$name · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})$fireOs"
    }

    /** Fire OS versions by the Android level they're built on. */
    fun fireOsFor(sdk: Int): String? = when {
        sdk >= 30 -> "Fire OS 8"
        sdk >= 28 -> "Fire OS 7"
        sdk >= 25 -> "Fire OS 6"
        sdk >= 22 -> "Fire OS 5"
        else -> null
    }

    /** First lines of the saved crash report (the error itself), if there is one. */
    fun crashSummary(crash: String?): String? = crash?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }
        ?.dropWhile { !it.contains("Exception") && !it.contains("Error") }?.take(3)?.joinToString("\n")?.take(400)?.ifBlank { null }

    fun details(appVersion: String, crash: String?): List<Pair<String, String>> = listOfNotNull(
        "App version" to appVersion,
        "Device" to device(),
        crashSummary(crash)?.let { "Last crash" to it },
    )

    fun subject(appVersion: String) = "Avid Viewer bug report ($appVersion)"

    fun body(appVersion: String, crash: String?): String =
        "What happened:\n\n\nSteps to reproduce:\n1. \n\n" +
            details(appVersion, crash).joinToString("\n") { (k, v) -> "$k: $v" }

    /** A mailto link with the subject and details filled in (used for the QR code and the email button). */
    fun mailto(appVersion: String, crash: String?): String =
        "mailto:$ADDRESS?subject=" + Uri.encode(subject(appVersion)) + "&body=" + Uri.encode(body(appVersion, crash))

    fun emailIntent(appVersion: String, crash: String?): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse(mailto(appVersion, crash))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Most TVs have no email app; the button only shows when one is installed. */
    fun canEmail(context: Context): Boolean = runCatching {
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS")).resolveActivity(context.packageManager) != null
    }.getOrDefault(false)
}
