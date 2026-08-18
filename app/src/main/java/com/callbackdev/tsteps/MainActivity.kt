package com.callbackdev.tsteps

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.ui.navigation.TstepsApp
import androidx.lifecycle.lifecycleScope
import com.callbackdev.tsteps.ui.theme.ThemeProfile
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.widget.TstepsWidgetUpdater
import com.callbackdev.tsteps.work.SyncScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Single owner of background-work reconciliation (tweather's pattern):
        // arms or cancels the sampling and rollover jobs based on permission and
        // sensor availability. The permission-request UI lands in Fase 3; until
        // it is granted this is a no-op that keeps zero jobs alive.
        SyncScheduler.reconcile(this)
        // The app is dark-only (see TstepsTheme), so the system bars must always
        // draw their icons light. enableEdgeToEdge()'s default is SystemBarStyle.auto,
        // which picks the appearance from the *system* dark-mode setting: on a phone
        // in light mode that would give dark icons over the Obsidian background — an
        // invisible status bar. Force the dark style on both bars instead.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val settingsStore = ServiceLocator.settingsStore(this)
        // Widget re-renders that no sync would trigger: theme, units, goal,
        // profile and opacity changes (tweather's foreground collector).
        lifecycleScope.launch {
            settingsStore.settings
                .map {
                    listOf(
                        it.themeProfileName, it.units, it.dailyGoalSteps,
                        it.weightKg, it.heightCm, it.widgetOpacityPct
                    )
                }
                .distinctUntilChanged()
                .collect { TstepsWidgetUpdater.updateAll(this@MainActivity) }
        }
        setContent {
            // Theme switches at runtime with settings.config's "active_profile"
            val profile by remember {
                settingsStore.settings.map { ThemeProfile.fromName(it.themeProfileName) }
            }.collectAsStateWithLifecycle(initialValue = ThemeProfile.Obsidian)
            TstepsTheme(profile = profile) {
                TstepsApp()
            }
        }
    }
}
