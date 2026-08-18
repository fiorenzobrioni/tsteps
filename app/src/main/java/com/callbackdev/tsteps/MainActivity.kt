package com.callbackdev.tsteps

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.callbackdev.tsteps.ui.SkeletonScreen
import com.callbackdev.tsteps.ui.theme.TstepsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app is dark-only (see TstepsTheme), so the system bars must always
        // draw their icons light. enableEdgeToEdge()'s default is SystemBarStyle.auto,
        // which picks the appearance from the *system* dark-mode setting: on a phone
        // in light mode that would give dark icons over the Obsidian background — an
        // invisible status bar. Force the dark style on both bars instead.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            TstepsTheme {
                SkeletonScreen()
            }
        }
    }
}
