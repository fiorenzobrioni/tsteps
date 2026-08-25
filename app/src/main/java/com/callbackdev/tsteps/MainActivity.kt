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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.ui.navigation.TstepsApp
import com.callbackdev.tsteps.ui.settings.SettingsOpenRequest
import com.callbackdev.tsteps.ui.track.TrackOpenRequest
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.callbackdev.tsteps.tracking.TrackingService
import com.callbackdev.tsteps.ui.theme.ThemeProfile
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.widget.TstepsWidgetUpdater
import com.callbackdev.tsteps.work.SyncScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * The tracking notification deep-links here (SINGLE_TOP: a running activity
     * gets onNewIntent instead of a rebuild — both paths must route the extra).
     */
    private fun routeLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TrackingService.EXTRA_OPEN_TRACK, false) == true) {
            TrackOpenRequest.request()
        }
        // Health Connect's rationale/privacy intents (Fase 12): the explanation
        // lives in settings.config's health_connect section — open it there.
        when (intent?.action) {
            "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE",
            Intent.ACTION_VIEW_PERMISSION_USAGE -> SettingsOpenRequest.request()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeLaunchIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        routeLaunchIntent(intent)
        // Single owner of background-work reconciliation (tweather's pattern):
        // arms or cancels the sampling and rollover jobs based on permission and
        // sensor availability. The permission-request UI lands in Fase 3; until
        // it is granted this is a no-op that keeps zero jobs alive.
        SyncScheduler.reconcile(this)
        // Fase 17: decides once whether this install predates `$ tsteps init`, and
        // must land before the shell can tell a first run from a returning user. An
        // app that holds the permission, or that has ever anchored a counter reading,
        // has been answering the question by itself and is never asked again.
        lifecycleScope.launch {
            val used = SyncScheduler.hasPermission(this@MainActivity) ||
                ServiceLocator.trackerStateStore(this@MainActivity).read() != null
            ServiceLocator.firstRunStore(this@MainActivity).migrate(used)
        }
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
                .collect { TstepsWidgetUpdater.updateAllSafely(this@MainActivity) }
        }
        // Leaving the app is the moment the widget goes back on show, and the
        // whole session the live listener has been ingesting readings the widget
        // never heard about: the collector above only fires on a settings change,
        // and its one shot at launch races that first ingest. Without this the
        // widget kept the last worker's number until the next 15-minute pass —
        // the app told you the truth and did not pass it on.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                TstepsWidgetUpdater.updateAllDetached(applicationContext)
            }
        })
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
