package com.avidviewer.tv.data

import android.os.Build

/**
 * "Report a bug": the details worth including, read from this TV. Reports go to avidviewer.app (no email since Nov 20, 2026).
 * Never includes playlists, provider addresses, logins or keys.
 */
object BugReport {
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
}
