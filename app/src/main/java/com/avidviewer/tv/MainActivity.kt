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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
