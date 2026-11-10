package com.avidviewer.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Surface)) {
                AppRoot(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home button or Back) stops playback so it doesn't run in the background.
        vm.stop()
        vm.onAppExit()
    }

    override fun onStart() {
        super.onStart()
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
