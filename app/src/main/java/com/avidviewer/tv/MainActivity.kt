package com.avidviewer.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.C

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.avidviewer.tv.data.CrashLog.install(this, runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        coil.Coil.setImageLoader(com.avidviewer.tv.ui.Images.imageLoader(applicationContext))
        val genuine = com.avidviewer.tv.data.Integrity.isGenuine(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Surface)) {
                if (genuine) AppRoot(vm) else TamperBlock()
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Nov 21, 2026: the start-up screen gives the TV a head start; remote keys wait until it closes.
        if (vm.startSplash) return true
        // #22: a key that makes Compose follow a focus link to something not on screen threw "FocusRequester is not
        // initialized" and closed the app. Now it's noted for crash reports and the press is used up instead.
        val handled = try { super.dispatchKeyEvent(event) } catch (e: IllegalStateException) {
            if (e.message?.contains("FocusRequester is not initialized") != true) throw e
            com.avidviewer.tv.data.CrashLog.note(this, "Focus target missing on ${vm.screen.name.lowercase()}; key ignored")
            vm.focusRescue++
            true
        }
        // A remote arrow or OK that reached nothing because nothing is highlighted: put the highlight back.
        if (!handled && event.action == android.view.KeyEvent.ACTION_DOWN && !vm.anyFocus && event.keyCode in rescueKeys) vm.focusRescue++
        return handled
    }

    private val rescueKeys = setOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
    )

    override fun onStop() {
        super.onStop()
        com.avidviewer.tv.data.CrashLog.leftNormally(this)
        com.avidviewer.tv.data.CrashLog.stopWatching()
        // Leaving the app (Home button or Back) stops playback so it doesn't run in the background.
        vm.stop()
        vm.onAppExit()
    }

    override fun onStart() {
        super.onStart()
        // On screen: if the app is closed before onStop, the next launch reports it (CrashLog.adoptUncleanExit).
        com.avidviewer.tv.data.CrashLog.inUse(this)
        if (android.os.Build.FINGERPRINT != "robolectric") com.avidviewer.tv.data.CrashLog.watchMainThread(this) { vm.screen.name.lowercase() }
        vm.resetIdle()
        vm.onAppOpened()
        if (vm.player.mediaItemCount > 0) {
            vm.player.prepare()
            vm.player.play()
        }
    }

    override fun onDestroy() {
        if (isFinishing) vm.onAppExit()
        super.onDestroy()
    }
}

/** Shown in place of the app when it has been re-signed with a key that isn't Avid Viewer's (see Integrity). */
@Composable
private fun TamperBlock() {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This copy of Avid Viewer isn’t genuine", fontSize = 24.sp, textAlign = TextAlign.Center)
            Text(
                "It was signed by someone other than Avid Viewer, so it won’t run. " +
                    "Install the official app from the Avid Viewer GitHub Releases page.",
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
