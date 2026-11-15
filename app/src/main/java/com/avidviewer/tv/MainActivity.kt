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

    // ---- Backup & restore: storage permission and the file picker ----
    private var permissionThen: ((Boolean) -> Unit)? = null
    private val permission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        permissionThen?.invoke(ok); permissionThen = null
    }
    private var pickThen: ((String?) -> Unit)? = null
    private fun delivered(uri: android.net.Uri?) {
        val then = pickThen; pickThen = null
        then?.invoke(uri?.let { runCatching { com.avidviewer.tv.data.BackupFiles.readUri(this, it) }.getOrNull() })
    }
    private val openDocument = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { delivered(it) }
    private val getContent = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { delivered(it) }

    private fun handles(intent: android.content.Intent) = intent.resolveActivity(packageManager) != null

    private val backupHost = object : AppViewModel.BackupHost {
        override fun requestStorage(write: Boolean, then: (Boolean) -> Unit) {
            val p = if (write) android.Manifest.permission.WRITE_EXTERNAL_STORAGE else android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) { then(true); return }
            permissionThen = then
            runCatching { permission.launch(p) }.onFailure { permissionThen = null; then(false) }
        }

        override fun canPick() =
            handles(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")) ||
                handles(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"))

        override fun pickFile(then: (String?) -> Unit) {
            pickThen = then
            val opened = handles(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")) &&
                runCatching { openDocument.launch(arrayOf("*/*")) }.isSuccess
            if (!opened && runCatching { getContent.launch("*/*") }.isFailure) { pickThen = null; then(null) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.backupHost = backupHost
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
        val handled = super.dispatchKeyEvent(event)
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
        // Leaving the app (Home button or Back) stops playback so it doesn't run in the background.
        vm.stop()
        vm.onAppExit()
    }

    override fun onStart() {
        super.onStart()
        // On screen: if the app is closed before onStop, the next launch reports it (CrashLog.adoptUncleanExit).
        com.avidviewer.tv.data.CrashLog.inUse(this)
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
