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
import com.callbackdev.tsteps.ui.settings.SettingsOpenRequest
import com.callbackdev.tsteps.ui.settings.SettingsScreen
import com.callbackdev.tsteps.ui.stats.StatsScreen
import com.callbackdev.tsteps.ui.steps.StepsScreen
import com.callbackdev.tsteps.ui.track.TrackOpenRequest
import com.callbackdev.tsteps.ui.track.TrackScreen
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.callbackdev.tsteps.data.FirstRun
import com.callbackdev.tsteps.data.FirstRunStore
import com.callbackdev.tsteps.ui.init.InitScreen
import com.callbackdev.tsteps.work.SyncScheduler
import kotlinx.coroutines.launch
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

/**
 * Decides between `$ tsteps init` and the workspace — see [FirstRunStore.state]. The
 * [FirstRun.Unknown] branch draws an empty surface on purpose: the legacy check is
 * one DataStore read away, and guessing "pending" for that frame would flash a setup
 * screen at someone who has been using the app for months.
 */
@Composable
fun TstepsApp() {
    val context = LocalContext.current
    val firstRunStore = remember(context) { ServiceLocator.firstRunStore(context) }
    val firstRun by remember(firstRunStore) { firstRunStore.state }
        .collectAsStateWithLifecycle(initialValue = FirstRun.Unknown)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (firstRun) {
            FirstRun.Unknown -> Unit
            FirstRun.Pending -> FirstRunSetup(firstRunStore)
            FirstRun.Done -> Workspace()
        }
    }
}

/** The state around [InitScreen]: the permission the app cannot count without. */
@Composable
private fun FirstRunSetup(firstRunStore: FirstRunStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // A grant flips the "should the background jobs exist" answer
            SyncScheduler.reconcile(context)
            scope.launch { firstRunStore.markInitDone() }
        } else {
            permissionDenied = true
        }
    }
    InitScreen(
        onGrant = { permission.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
        onSkip = { scope.launch { firstRunStore.markInitDone() } },
        permissionDenied = permissionDenied
    )
}

@Composable
private fun Workspace() {
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

    // Health Connect's rationale intents land on the settings tab, where the
    // health_connect section carries the explanation (Fase 12).
    val settingsOpen by SettingsOpenRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) {
            SettingsOpenRequest.consume()
            navController.navigateToTab(Routes.Settings)
        }
    }

    // The editor's HELP.md hint asks for a file on another tab: the flag rides
    // across the tab switch, the Settings screen consumes it (Fase 17).
    var openHelp by rememberSaveable { mutableStateOf(false) }

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
                        StepsScreen(
                            onOpenTrack = { navController.navigate(TrackRoute) },
                            onOpenHelp = {
                                openHelp = true
                                navController.navigateToTab(Routes.Settings)
                            }
                        )
                    }
                    composable(Routes.Log) { LogScreen() }
                    composable(Routes.Stats) {
                        StatsScreen(onOpenLog = { navController.navigateToTab(Routes.Log) })
                    }
                    composable(Routes.Settings) {
                        SettingsScreen(
                            openHelp = openHelp,
                            onHelpOpened = { openHelp = false }
                        )
                    }
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
