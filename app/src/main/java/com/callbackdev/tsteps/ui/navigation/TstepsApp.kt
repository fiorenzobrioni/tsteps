package com.callbackdev.tsteps.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.callbackdev.tsteps.data.EditorSettings
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.ui.components.EditorNavBar
import com.callbackdev.tsteps.ui.components.EditorNavItems
import com.callbackdev.tsteps.ui.components.EditorOptions
import com.callbackdev.tsteps.ui.components.LocalEditorOptions
import com.callbackdev.tsteps.ui.log.LogScreen
import com.callbackdev.tsteps.ui.settings.SettingsScreen
import com.callbackdev.tsteps.ui.stats.StatsScreen
import com.callbackdev.tsteps.ui.steps.StepsScreen
import com.callbackdev.tsteps.ui.track.TrackOpenRequest
import com.callbackdev.tsteps.ui.track.TrackScreen
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import kotlinx.coroutines.flow.map

/**
 * App shell (tweather's pattern): NavHost above the editor-style bottom bar, one
 * destination per tab, each tab's stack saved and restored on switch.
 * `settings.config`'s editor section feeds every CodeCanvas in the app via
 * [LocalEditorOptions].
 */
object Routes {
    val Editor = EditorNavItems.Editor.route
    val Log = EditorNavItems.Log.route
    val Stats = EditorNavItems.Stats.route
    val Settings = EditorNavItems.Settings.route
}

/** The live-session process; navigated to, never a bottom-bar destination. */
private const val TrackRoute = "track"

@Composable
fun TstepsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val context = LocalContext.current
    val settingsStore = remember(context) { ServiceLocator.settingsStore(context) }
    val editorSettings by remember(settingsStore) { settingsStore.settings.map { it.editor } }
        .collectAsStateWithLifecycle(initialValue = EditorSettings())

    // The tracking notification's deep link: open the running process's buffer.
    // Guarded on an active session — a stale tap after ^C lands on the editor.
    val trackOpen by TrackOpenRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(trackOpen) {
        if (trackOpen) {
            TrackOpenRequest.consume()
            if (ServiceLocator.trackingManager(context).isActive) {
                navController.navigate(TrackRoute) { launchSingleTop = true }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            CompositionLocalProvider(
                LocalEditorOptions provides EditorOptions(
                    showLineNumbers = editorSettings.lineNumbers,
                    wordWrap = editorSettings.wordWrap
                )
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.Editor,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(Routes.Editor) {
                        StepsScreen(onOpenTrack = { navController.navigate(TrackRoute) })
                    }
                    composable(Routes.Log) { LogScreen() }
                    composable(Routes.Stats) {
                        StatsScreen(onOpenLog = { navController.navigateToTab(Routes.Log) })
                    }
                    composable(Routes.Settings) { SettingsScreen() }
                    // Not a tab: the live process opens over the editor's stack
                    // and pops back when it ends.
                    composable(TrackRoute) {
                        TrackScreen(onExit = { navController.popBackStack() })
                    }
                }
            }
            EditorNavBar(
                items = EditorNavItems.All,
                isSelected = { item ->
                    currentDestination?.hierarchy?.any { it.route == item.route } == true
                },
                onSelect = { navController.navigateToTab(it.route) }
            )
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


@Preview(showBackground = true, backgroundColor = 0xFF10141A)
@Composable
private fun TstepsAppPreview() {
    TstepsTheme { TstepsApp() }
}
