package com.avidviewer.tv.data

import android.app.Application
import android.content.Context
import android.util.Base64
import app.talsec.rasp.security.api.Talsec
import app.talsec.rasp.security.api.TalsecConfig
import app.talsec.rasp.security.api.ThreatListener
import com.avidviewer.tv.BuildConfig

/**
 * What freeRASP found on this TV, kept on the TV (SharedPreferences "security-log").
 *
 * Each kind of finding is counted once per app start, with when it was first and last seen. [ALERTS] are the
 * findings worth an email to bugs@avidviewer.app (rooted TV, hooking, a tampered or re-packaged app, a debugger
 * on a release build, an emulator, an app cloner); the rest ([INFO]: developer options, ADB, installed outside
 * a store) are normal on a Fire TV that sideloads the app, so they are only kept here.
 *
 * Nothing about what the viewer watches, their playlists or their logins is in it.
 */
class SecurityLog(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("security-log", Context.MODE_PRIVATE)
    private val seenThisRun = mutableSetOf<String>()

    data class Finding(val kind: String, val count: Int, val first: Long, val last: Long)

    /** Notes a finding; true when it's an alert not sent yet. */
    @Synchronized
    fun record(kind: String): Boolean {
        if (!seenThisRun.add(kind)) return false
        val now = clock()
        val e = prefs.edit()
            .putInt("$kind.count", prefs.getInt("$kind.count", 0) + 1)
            .putLong("$kind.last", now)
        if (!prefs.contains("$kind.first")) e.putLong("$kind.first", now)
        val alert = kind in ALERTS && !prefs.getBoolean("$kind.sent", false)
        if (alert) e.putBoolean("$kind.pending", true)
        e.apply()
        return alert
    }

    fun findings(): List<Finding> = (ALERTS + INFO).mapNotNull { k ->
        val n = prefs.getInt("$k.count", 0)
        if (n == 0) null else Finding(k, n, prefs.getLong("$k.first", 0), prefs.getLong("$k.last", 0))
    }

    /** Alerts found and not emailed yet. */
    fun pending(): List<String> = ALERTS.filter { prefs.getBoolean("$it.pending", false) }

    var lastSent: Long
        get() = prefs.getLong("lastSent", 0)
        private set(v) { prefs.edit().putLong("lastSent", v).apply() }

    /** After an email: those alerts aren't sent again (their counts keep going up). */
    fun markSent(kinds: List<String>) {
        val e = prefs.edit()
        kinds.forEach { e.putBoolean("$it.sent", true).remove("$it.pending") }
        e.apply()
        lastSent = clock()
    }

    /** One line, e.g. "Security: rooted device, hooking framework". */
    fun summary(kinds: List<String> = pending()): String =
        "Security: " + kinds.joinToString(", ") { LABELS[it] ?: it }

    /** Every finding, for the email: kind, how many app starts it was seen in, first and last time. */
    fun text(): String {
        val fmt = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
        return findings().joinToString("\n") {
            "${LABELS[it.kind] ?: it.kind}${if (it.kind in ALERTS) "" else " (info)"}: seen in ${it.count} app start(s), first ${fmt.format(java.util.Date(it.first))}, last ${fmt.format(java.util.Date(it.last))}"
        }
    }

    companion object {
        val ALERTS = listOf("root", "hooks", "tampered", "debugger", "emulator", "obfuscation", "cloned", "automation", "bootloader")
        val INFO = listOf("unofficialStore", "devMode", "adb", "noPasscode", "noSecureHardware", "systemVpn")
        val LABELS = mapOf(
            "root" to "rooted device", "hooks" to "hooking framework (Frida, Xposed…)", "tampered" to "tampered or re-signed app",
            "debugger" to "debugger attached", "emulator" to "emulator", "obfuscation" to "release build not obfuscated",
            "cloned" to "app cloned / multiple instances", "automation" to "automation tool driving the app", "bootloader" to "unlocked bootloader",
            "unofficialStore" to "installed outside a known store", "devMode" to "developer options on", "adb" to "ADB on",
            "noPasscode" to "no screen lock", "noSecureHardware" to "no secure hardware", "systemVpn" to "VPN on",
        )
    }
}

/**
 * freeRASP (Talsec) runtime checks, started from [com.avidviewer.tv.AvidViewerApp].
 *
 * Only on a properly signed release: the expected certificate comes from [BuildConfig.EXPECTED_SIG_SHA256] (read from
 * the keystore at build time, as for [Integrity]); when it's blank (debug builds, `next` builds without the keystore,
 * the Robolectric tests) freeRASP isn't started, so it can't report the build server as "tampered".
 *
 * Findings are only recorded ([SecurityLog]); the app keeps running. [Integrity] already refuses a re-signed copy.
 * Alerts are emailed through the crash relay at most once a day ([com.avidviewer.tv.ui.AppViewModel]), and Talsec's
 * own weekly report and portal go to [WATCHER_MAIL].
 */
object SecurityMonitor {
    /** Talsec's security reports and portal login. Public already (the bug address shown in the app). */
    const val WATCHER_MAIL = "bugs@avidviewer.app"
    private const val PACKAGE = "com.avidviewer.tv" // a constant, as Talsec asks (not context.packageName)

    /** Installers that are expected: Amazon Appstore, Google Play, the Downloader app, and the app's own updater. */
    private val STORES = arrayOf("com.amazon.venezia", "com.android.vending", "com.esaba.downloader", PACKAGE)

    @Volatile var log: SecurityLog? = null; private set
    @Volatile var started = false; private set

    fun start(app: Application) {
        if (android.os.Build.FINGERPRINT == "robolectric") return
        val hex = BuildConfig.EXPECTED_SIG_SHA256
        if (hex.isBlank()) return
        val log = SecurityLog(app).also { this.log = it }
        runCatching {
            val config = TalsecConfig.Builder(PACKAGE, arrayOf(hexToBase64(hex)))
                .watcherMail(WATCHER_MAIL)
                .supportedAlternativeStores(STORES)
                .prod(!BuildConfig.DEBUG)
                .killOnBypass(false)
                .build()
            ThreatListener(threats(log), deviceState(log)).registerListener(app)
            Talsec.start(app, config)
            started = true
        }.onFailure { android.util.Log.w("AvidViewer", "freeRASP didn't start: $it") }
    }

    /** "ab01…" (64 hex digits, as in BuildConfig) → the Base64 Talsec wants. */
    fun hexToBase64(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun threats(log: SecurityLog) = object : ThreatListener.ThreatDetected() {
        override fun onPrivilegedAccess() { log.record("root") }
        override fun onHooks() { log.record("hooks") }
        override fun onAppIntegrity() { log.record("tampered") }
        override fun onDebug() { log.record("debugger") }
        override fun onSimulator() { log.record("emulator") }
        override fun onObfuscationIssues() { log.record("obfuscation") }
        override fun onMultiInstance() { log.record("cloned") }
        override fun onAutomation() { log.record("automation") }
        override fun onBootloader() { log.record("bootloader") }
        override fun onUnofficialStore() { log.record("unofficialStore") }
    }

    private fun deviceState(log: SecurityLog) = object : ThreatListener.DeviceState() {
        override fun onDevMode() { log.record("devMode") }
        override fun onAdbEnabled() { log.record("adb") }
        override fun onPasscode() { log.record("noPasscode") }
        override fun onSecureHardwareNotAvailable() { log.record("noSecureHardware") }
        override fun onSystemVpn() { log.record("systemVpn") }
    }
}
