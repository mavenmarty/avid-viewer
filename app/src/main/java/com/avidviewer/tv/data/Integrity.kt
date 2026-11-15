package com.avidviewer.tv.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.avidviewer.tv.BuildConfig
import java.security.MessageDigest

/**
 * Checks that the running app is still signed with Avid Viewer's release key — i.e. that nobody has
 * taken the APK, re-signed it with their own key and shipped it as "Avid Viewer".
 *
 * The expected certificate hash is baked in at build time ([BuildConfig.EXPECTED_SIG_SHA256], set in
 * app/build.gradle.kts from the keystore). Re-signing an already-built APK changes the real signature
 * but not that baked-in constant, so a tampered copy no longer matches.
 *
 * Fail-open by design: when the expected hash is blank (debug, `next` builds without the keystore, and
 * the Robolectric tests) or the signature can't be read, [isGenuine] returns true. The point is to deter
 * casual repackaging, not to brick a legitimate TV over an unexpected platform quirk. Genuine updates are
 * signed with the same key, so the self-updater is unaffected.
 */
object Integrity {

    /** True unless we can positively tell the app was re-signed with a different key. */
    fun isGenuine(ctx: Context): Boolean {
        val expected = BuildConfig.EXPECTED_SIG_SHA256.lowercase()
        if (expected.isBlank()) return true
        val actual = runCatching { signingCertHashes(ctx) }.getOrNull() ?: return true
        if (actual.isEmpty()) return true
        return actual.any { it == expected }
    }

    /** SHA-256 of each certificate the app is currently signed with (hex, lower-case, no separators). */
    private fun signingCertHashes(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return emptyList()
            // History covers a key that was rotated with Android's key-rotation; current signer(s) otherwise.
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
    }
}
