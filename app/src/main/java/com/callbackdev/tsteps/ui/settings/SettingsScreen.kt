package com.callbackdev.tsteps.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callbackdev.tsteps.BuildConfig
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.SettingsRanges
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.healthconnect.AndroidHealthConnectGateway
import com.callbackdev.tsteps.healthconnect.HcAvailability
import com.callbackdev.tsteps.healthconnect.HcPermissions
import com.callbackdev.tsteps.healthconnect.HcSectionStatus
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeCanvas
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.EditorTabs
import com.callbackdev.tsteps.ui.components.StatusBarDivider
import com.callbackdev.tsteps.ui.components.SyntaxText
import com.callbackdev.tsteps.ui.components.TerminalInput
import com.callbackdev.tsteps.ui.components.TerminalStatusBar
import com.callbackdev.tsteps.ui.components.WidgetLine
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.components.keyOpenLine
import com.callbackdev.tsteps.ui.components.punctLine
import com.callbackdev.tsteps.ui.components.stringValueLine
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import com.callbackdev.tsteps.ui.theme.ThemeProfile
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Everything the settings file can change, bundled for [buildSettingsLines]. */
class SettingsActions(
    val onLineNumbers: (Boolean) -> Unit,
    val onWordWrap: (Boolean) -> Unit,
    val onDailyCommit: (Boolean) -> Unit,
    val onGoalCheck: (Boolean) -> Unit,
    val onDailyGoal: (Int) -> Unit,
    val onAutoDetect: (Boolean) -> Unit,
    val onHealthConnect: (Boolean) -> Unit,
    val onWeight: (Double?) -> Unit,
    val onHeight: (Int?) -> Unit,
    val onToggleUnits: () -> Unit,
    val onToggleSessionMetric: () -> Unit,
    val onThemeProfile: (String) -> Unit,
    val onCycleWidgetOpacity: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onReset: () -> Unit
)

/** The three numbers edited through a terminal input instead of cycling. */
internal enum class NumericField { GOAL, WEIGHT, HEIGHT }

/** Status of the `notifications` block's dynamic `//` line (tweather's states). */
enum class NotifLineState {
    /** Both toggles off — nothing will ever post. */
    Disabled,

    /** At least one toggle on and the permission granted. */
    Armed,

    /** Toggles on but no permission; tap requests it. */
    MissingPermission,

    /** Permission permanently denied; tap opens the system app settings. */
    DeniedPermanently
}

/**
 * Settings screen: the fake file `settings.config` (tweather's format — a JSON
 * body with `//` comments). Booleans flip on tap, the units and theme strings
 * cycle, and the three free numbers (goal, weight, height) open an in-file
 * terminal input: the value line becomes a prompt with `[esc]` to cancel. An
 * empty submit clears the optional profile values — "empty" is a first-class
 * state here, it's what hides kcal and falls back to the default stride.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val activity = LocalActivity.current

    // POST_NOTIFICATIONS — tweather's state machine, verbatim in spirit.
    // Re-check on every resume so a grant or a revocation made in the system
    // settings is reflected as soon as we're back.
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasNotifPermission = remember(permissionEpoch) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    var notifDeniedPermanently by remember { mutableStateOf(false) }
    // A toggle the user flipped on before granting: applied right after the grant
    var pendingNotifToggle by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionEpoch++
        if (granted) {
            notifDeniedPermanently = false
            pendingNotifToggle?.invoke()
            pendingNotifToggle = null
        } else {
            pendingNotifToggle = null
            if (activity?.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS
                ) == false
            ) {
                notifDeniedPermanently = true
            }
        }
    }
    // The system-settings detour has no result callback: every resume is the
    // return path. A grant applies the toggle the user had flipped; any other
    // return clears the pending, so a stale toggle can never fire much later.
    LaunchedEffect(permissionEpoch) {
        if (hasNotifPermission) pendingNotifToggle?.invoke()
        pendingNotifToggle = null
    }
    val anyNotifOn = settings.notifications.dailyCommit || settings.notifications.goalCheck
    val notifState = when {
        !anyNotifOn -> NotifLineState.Disabled
        hasNotifPermission -> NotifLineState.Armed
        notifDeniedPermanently -> NotifLineState.DeniedPermanently
        else -> NotifLineState.MissingPermission
    }

    // Health Connect (Fase 12): availability is a cheap local check, granted
    // permissions one IPC — both refreshed on the same resume epochs (grants
    // and revokes happen on HC's own screens while tsteps is paused).
    var hcStatus by remember { mutableStateOf(HcSectionStatus()) }
    LaunchedEffect(permissionEpoch) {
        hcStatus = HcPermissions.sectionStatus(AndroidHealthConnectGateway(context))
    }
    val hcLauncher = rememberLauncherForActivityResult(HcPermissions.requestContract()) { granted ->
        permissionEpoch++
        // Whatever subset the user granted is what the sync will do; nothing
        // granted leaves the toggle off — the default is preserved.
        if (granted.isNotEmpty()) viewModel.setHealthConnectSync(true)
    }

    /** Turning a notification toggle ON without the permission asks for it first. */
    fun gated(setter: (Boolean) -> Unit): (Boolean) -> Unit = { enabled ->
        if (enabled && !hasNotifPermission) {
            pendingNotifToggle = { setter(true) }
            if (notifDeniedPermanently) {
                context.openAppSystemSettings()
            } else {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            setter(enabled)
        }
    }

    SettingsScreen(
        settings = settings,
        notifState = notifState,
        hcStatus = hcStatus,
        onHcLine = {
            when (hcStatus.availability) {
                HcAvailability.UPDATE_REQUIRED -> uriHandler.openUri(
                    "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
                )
                else -> hcLauncher.launch(HcPermissions.ALL)
            }
        },
        onNotifLine = {
            when (notifState) {
                NotifLineState.MissingPermission ->
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                NotifLineState.DeniedPermanently -> context.openAppSystemSettings()
                else -> Unit
            }
        },
        actions = SettingsActions(
            onLineNumbers = viewModel::setLineNumbers,
            onWordWrap = viewModel::setWordWrap,
            onDailyCommit = gated(viewModel::setNotifDailyCommit),
            onGoalCheck = gated(viewModel::setNotifGoalCheck),
            onDailyGoal = viewModel::setDailyGoalSteps,
            onAutoDetect = viewModel::setAutoDetectSessions,
            onHealthConnect = { enabled ->
                when {
                    !enabled -> viewModel.setHealthConnectSync(false)
                    hcStatus.anyGranted -> viewModel.setHealthConnectSync(true)
                    // The section above the toggle IS the plain-language
                    // explanation; the request follows it, never precedes it.
                    else -> hcLauncher.launch(HcPermissions.ALL)
                }
            },
            onWeight = viewModel::setWeightKg,
            onHeight = viewModel::setHeightCm,
            onToggleUnits = viewModel::toggleUnits,
            onToggleSessionMetric = viewModel::toggleSessionMetric,
            onThemeProfile = viewModel::setThemeProfile,
            onCycleWidgetOpacity = viewModel::cycleWidgetOpacity,
            onOpenUrl = uriHandler::openUri,
            onReset = viewModel::resetToDefaults
        )
    )
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    actions: SettingsActions,
    notifState: NotifLineState = NotifLineState.Armed,
    onNotifLine: () -> Unit = {},
    hcStatus: HcSectionStatus = HcSectionStatus(availability = HcAvailability.AVAILABLE),
    onHcLine: () -> Unit = {},
    canvasState: LazyListState = rememberLazyListState()
) {
    val syntax = TstepsTheme.syntax
    val resources = LocalContext.current.resources

    // In-file numeric editing: which line is a prompt right now, and its text.
    var editing by rememberSaveable { mutableStateOf<NumericField?>(null) }
    var editValue by rememberSaveable { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inputError) {
        if (inputError != null) {
            delay(4_000)
            inputError = null
        }
    }

    // Two-tap confirm for the reset command; disarms by itself after a few seconds.
    var resetArmed by remember { mutableStateOf(false) }
    LaunchedEffect(resetArmed) {
        if (resetArmed) {
            delay(4_000)
            resetArmed = false
        }
    }

    fun startEdit(field: NumericField) {
        editing = field
        inputError = null
        editValue = when (field) {
            NumericField.GOAL ->
                settings.dailyGoalSteps.takeIf { it > 0 }?.toString() ?: ""
            NumericField.WEIGHT -> settings.weightKg?.let { formatWeight(it) } ?: ""
            NumericField.HEIGHT -> settings.heightCm?.toString() ?: ""
        }
    }

    fun submitEdit() {
        val field = editing ?: return
        when (val result = parseNumericInput(field, editValue)) {
            is NumericInput.Value -> {
                when (field) {
                    NumericField.GOAL -> actions.onDailyGoal(result.value.toInt())
                    NumericField.WEIGHT -> actions.onWeight(result.value)
                    NumericField.HEIGHT -> actions.onHeight(result.value.toInt())
                }
                editing = null
            }
            NumericInput.Cleared -> {
                when (field) {
                    // An empty goal reads as "no goal", which is goal 0.
                    NumericField.GOAL -> actions.onDailyGoal(0)
                    NumericField.WEIGHT -> actions.onWeight(null)
                    NumericField.HEIGHT -> actions.onHeight(null)
                }
                editing = null
            }
            is NumericInput.Invalid -> inputError = result.error
        }
    }

    val lines = buildSettingsLines(
        settings = settings,
        syntax = syntax,
        actions = actions,
        notifState = notifState,
        notifLabel = resources.getString(R.string.cd_grant_notifications),
        onNotifLine = onNotifLine,
        hcStatus = hcStatus,
        hcGrantLabel = resources.getString(R.string.cd_grant_health),
        onHcLine = onHcLine,
        changeLabel = { key -> resources.getString(R.string.cd_change_setting, key) },
        openLabel = { name -> resources.getString(R.string.cd_open_link, name) },
        editing = editing,
        editValue = editValue,
        onEditValue = { editValue = it },
        onStartEdit = ::startEdit,
        onSubmitEdit = ::submitEdit,
        onCancelEdit = { editing = null },
        cancelLabel = resources.getString(R.string.cd_cancel_edit),
        inputError = inputError,
        resetArmed = resetArmed,
        resetLabel = resources.getString(
            if (resetArmed) R.string.cd_confirm_reset else R.string.cd_reset_settings
        ),
        onResetLine = {
            if (resetArmed) {
                resetArmed = false
                actions.onReset()
            } else {
                resetArmed = true
            }
        }
    )
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            EditorTabs(
                fileNames = listOf("settings.config"),
                activeIndex = 0,
                onSelect = {}
            )
            CodeCanvas(
                lines = lines,
                state = canvasState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            )
            TerminalStatusBar {
                Text("⎇ config")
                StatusBarDivider()
                Text("rw")
                Spacer(Modifier.weight(1f))
                Text("UTF-8")
            }
        }
    }
}

/** Parsed outcome of a numeric prompt submit. */
internal sealed interface NumericInput {
    data class Value(val value: Double) : NumericInput
    data object Cleared : NumericInput
    data class Invalid(val error: String) : NumericInput
}

/** Pure so the ranges and the empty-clears rule are unit-testable. */
internal fun parseNumericInput(field: NumericField, raw: String): NumericInput {
    val text = raw.trim().replace(',', '.')
    if (text.isEmpty()) return NumericInput.Cleared
    val number = text.toDoubleOrNull()
        ?: return NumericInput.Invalid("// ERROR: not a number")
    return when (field) {
        NumericField.GOAL ->
            if (number == number.toInt().toDouble() &&
                number.toInt() in SettingsRanges.GOAL_STEPS
            ) {
                NumericInput.Value(number)
            } else {
                NumericInput.Invalid(
                    "// ERROR: expected ${SettingsRanges.GOAL_STEPS.first}..${SettingsRanges.GOAL_STEPS.last}"
                )
            }
        NumericField.WEIGHT ->
            if (number in SettingsRanges.WEIGHT_KG) {
                NumericInput.Value(number)
            } else {
                NumericInput.Invalid(
                    "// ERROR: expected ${SettingsRanges.WEIGHT_KG.start.toInt()}..${SettingsRanges.WEIGHT_KG.endInclusive.toInt()} kg"
                )
            }
        NumericField.HEIGHT ->
            if (number == number.toInt().toDouble() &&
                number.toInt() in SettingsRanges.HEIGHT_CM
            ) {
                NumericInput.Value(number)
            } else {
                NumericInput.Invalid(
                    "// ERROR: expected ${SettingsRanges.HEIGHT_CM.first}..${SettingsRanges.HEIGHT_CM.last} cm"
                )
            }
    }
}

internal fun formatWeight(weightKg: Double): String = "%.1f".format(Locale.ROOT, weightKg)

private fun buildSettingsLines(
    settings: AppSettings,
    syntax: SyntaxColors,
    actions: SettingsActions,
    notifState: NotifLineState,
    notifLabel: String,
    onNotifLine: () -> Unit,
    hcStatus: HcSectionStatus,
    hcGrantLabel: String,
    onHcLine: () -> Unit,
    changeLabel: (String) -> String,
    openLabel: (String) -> String,
    editing: NumericField?,
    editValue: String,
    onEditValue: (String) -> Unit,
    onStartEdit: (NumericField) -> Unit,
    onSubmitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    cancelLabel: String,
    inputError: String?,
    resetArmed: Boolean,
    resetLabel: String,
    onResetLine: () -> Unit
): List<CanvasLine> = buildList {
    add(commentLine("// tsteps Configuration File", syntax))
    settings.lastModifiedEpochSeconds?.let { epoch ->
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epoch))
        add(commentLine("// Last modified: $stamp", syntax))
    }
    add(punctLine("{", 0, syntax))

    add(keyOpenLine("editor", 1, syntax))
    add(boolLine("line_numbers", settings.editor.lineNumbers, comma = true,
        hint = "// click to toggle", syntax = syntax,
        onClickLabel = changeLabel("line_numbers")) {
        actions.onLineNumbers(!settings.editor.lineNumbers)
    })
    add(boolLine("word_wrap", settings.editor.wordWrap, comma = false, syntax = syntax,
        onClickLabel = changeLabel("word_wrap")) {
        actions.onWordWrap(!settings.editor.wordWrap)
    })
    add(punctLine("},", 1, syntax))

    add(keyOpenLine("goal", 1, syntax))
    addNumericLine(
        key = "daily_steps",
        renderedValue = settings.dailyGoalSteps.toString(),
        isNull = false,
        comma = false,
        hint = "// 0 disables the check",
        field = NumericField.GOAL,
        syntax = syntax,
        changeLabel = changeLabel,
        editing = editing,
        editValue = editValue,
        onEditValue = onEditValue,
        onStartEdit = onStartEdit,
        onSubmitEdit = onSubmitEdit,
        onCancelEdit = onCancelEdit,
        cancelLabel = cancelLabel,
        inputError = inputError
    )
    add(punctLine("},", 1, syntax))

    // Fase 11: opt-in inference. Off is the default and means genuinely off —
    // no samples recorded, no detection ran. The hint says what turning it on
    // buys and how honest it can be about boundaries.
    add(keyOpenLine("sessions", 1, syntax))
    add(boolLine("auto_detect", settings.autoDetectSessions, comma = false,
        hint = "// infers walks from the counter, ~15 min grid", syntax = syntax,
        onClickLabel = changeLabel("auto_detect")) {
        actions.onAutoDetect(!settings.autoDetectSessions)
    })
    add(punctLine("},", 1, syntax))

    add(keyOpenLine("profile", 1, syntax))
    addNumericLine(
        key = "weight_kg",
        renderedValue = settings.weightKg?.let { formatWeight(it) } ?: "null",
        isNull = settings.weightKg == null,
        comma = true,
        hint = "// empty hides active_kcal",
        field = NumericField.WEIGHT,
        syntax = syntax,
        changeLabel = changeLabel,
        editing = editing,
        editValue = editValue,
        onEditValue = onEditValue,
        onStartEdit = onStartEdit,
        onSubmitEdit = onSubmitEdit,
        onCancelEdit = onCancelEdit,
        cancelLabel = cancelLabel,
        inputError = inputError
    )
    addNumericLine(
        key = "height_cm",
        renderedValue = settings.heightCm?.toString() ?: "null",
        isNull = settings.heightCm == null,
        comma = false,
        hint = "// empty uses the 0.72 m stride",
        field = NumericField.HEIGHT,
        syntax = syntax,
        changeLabel = changeLabel,
        editing = editing,
        editValue = editValue,
        onEditValue = onEditValue,
        onStartEdit = onStartEdit,
        onSubmitEdit = onSubmitEdit,
        onCancelEdit = onCancelEdit,
        cancelLabel = cancelLabel,
        inputError = inputError
    )
    add(punctLine("},", 1, syntax))

    add(keyOpenLine("units", 1, syntax))
    add(
        stringValueLine(
            "system",
            if (settings.units == UnitsSystem.METRIC) "metric" else "imperial",
            comma = true,
            syntax = syntax,
            hint = "// metric | imperial",
            onClickLabel = changeLabel("system"),
            onClick = actions.onToggleUnits
        )
    )
    add(
        stringValueLine(
            "session_metric",
            if (settings.sessionMetric == SessionMetric.SPEED) "speed" else "pace",
            comma = false,
            syntax = syntax,
            hint = "// speed | pace",
            onClickLabel = changeLabel("session_metric"),
            onClick = actions.onToggleSessionMetric
        )
    )
    add(punctLine("},", 1, syntax))

    add(keyOpenLine("theme", 1, syntax))
    add(
        stringValueLine(
            "active_profile", settings.themeProfileName, comma = true, syntax = syntax,
            onClickLabel = changeLabel("active_profile"),
            onClick = {
                val entries = ThemeProfile.entries
                val current = ThemeProfile.fromName(settings.themeProfileName)
                actions.onThemeProfile(entries[(entries.indexOf(current) + 1) % entries.size].name)
            }
        )
    )
    add(keyOpenLine("available_profiles", 2, syntax, bracket = "["))
    ThemeProfile.entries.forEachIndexed { i, profile ->
        val isActive = profile.name == settings.themeProfileName
        add(
            CodeLine(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.string)) { append("\"${profile.name}\"") }
                    if (i != ThemeProfile.entries.lastIndex) {
                        withStyle(SpanStyle(color = syntax.comment)) { append(",") }
                    }
                    if (isActive) {
                        withStyle(SpanStyle(color = syntax.comment)) { append("  // active") }
                    }
                },
                indent = 3,
                onClick = { actions.onThemeProfile(profile.name) },
                onClickLabel = changeLabel("active_profile")
            )
        )
    }
    add(punctLine("]", 2, syntax))
    add(punctLine("},", 1, syntax))

    add(keyOpenLine("widget", 1, syntax))
    add(
        CodeLine(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = syntax.key)) { append("\"bg_opacity_pct\"") }
                withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
                withStyle(SpanStyle(color = syntax.number)) {
                    append(settings.widgetOpacityPct.toString())
                }
                withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                    append("  // 100 | 85 | 70 | 50")
                }
            },
            indent = 2,
            onClick = actions.onCycleWidgetOpacity,
            onClickLabel = changeLabel("bg_opacity_pct")
        )
    )
    add(punctLine("},", 1, syntax))

    add(keyOpenLine("notifications", 1, syntax))
    add(notifStatusLine(notifState, syntax, notifLabel, onNotifLine))
    add(boolLine("daily_commit", settings.notifications.dailyCommit, comma = true,
        hint = "// the closed day's commit message", syntax = syntax,
        onClickLabel = changeLabel("daily_commit")) {
        actions.onDailyCommit(!settings.notifications.dailyCommit)
    })
    add(boolLine("goal_check", settings.notifications.goalCheck, comma = false,
        hint = "// once per day, when the check passes", syntax = syntax,
        onClickLabel = changeLabel("goal_check")) {
        actions.onGoalCheck(!settings.notifications.goalCheck)
    })
    add(punctLine("},", 1, syntax))

    // Fase 12: Health Connect interop, opt-in and default off. These comments
    // are the plain-language explanation required BEFORE any permission request
    // — and where the HC rationale intents land.
    add(keyOpenLine("health_connect", 1, syntax))
    add(commentLine("// on-device interop with other health apps — no network", syntax, indent = 2))
    add(commentLine("// writes: hourly steps + walk sessions · reads: their steps", syntax, indent = 2))
    add(commentLine("// external steps are shown, never added to yours", syntax, indent = 2))
    when (hcStatus.availability) {
        HcAvailability.UNAVAILABLE -> add(
            CodeLine(
                AnnotatedString(
                    "// E: Health Connect is not available on this device",
                    SpanStyle(color = syntax.diffDel)
                ),
                indent = 2
            )
        )
        HcAvailability.UPDATE_REQUIRED -> add(
            CodeLine(
                AnnotatedString(
                    "// E: Health Connect needs an update — tap to open",
                    SpanStyle(color = syntax.diffDel)
                ),
                indent = 2,
                onClick = onHcLine,
                onClickLabel = openLabel("Health Connect")
            )
        )
        HcAvailability.AVAILABLE -> {
            if (settings.healthConnect.sync) {
                if (hcStatus.anyGranted) {
                    val verbs = listOfNotNull(
                        "writes steps".takeIf { hcStatus.writeSteps },
                        "writes sessions".takeIf { hcStatus.writeSessions },
                        "reads other apps".takeIf { hcStatus.readSteps }
                    ).joinToString(" · ")
                    add(
                        CodeLine(
                            AnnotatedString(
                                "// connected: $verbs",
                                SpanStyle(color = syntax.comment.copy(alpha = 0.6f))
                            ),
                            indent = 2
                        )
                    )
                } else {
                    add(
                        CodeLine(
                            AnnotatedString(
                                "// ERROR: no permission granted — tap to grant",
                                SpanStyle(color = syntax.diffDel)
                            ),
                            indent = 2,
                            onClick = onHcLine,
                            onClickLabel = hcGrantLabel
                        )
                    )
                }
            }
            add(boolLine("sync", settings.healthConnect.sync, comma = false,
                hint = "// asks Health Connect first", syntax = syntax,
                onClickLabel = changeLabel("sync")) {
                actions.onHealthConnect(!settings.healthConnect.sync)
            })
        }
    }
    add(punctLine("},", 1, syntax))

    // Read-only About block; the license/credit lines open the related site.
    add(keyOpenLine("about", 1, syntax))
    add(stringValueLine("app_name", "tsteps", comma = true, syntax = syntax))
    add(stringValueLine("version", BuildConfig.VERSION_NAME, comma = true, syntax = syntax))
    add(stringValueLine("developer", "Callback Dev", comma = true, syntax = syntax))
    add(stringValueLine("copyright", "© 2026 Fiorenzo Brioni", comma = true, syntax = syntax))
    add(
        stringValueLine(
            "license", "GPL-3.0", comma = true, syntax = syntax,
            onClickLabel = openLabel("license"),
            onClick = { actions.onOpenUrl("https://www.gnu.org/licenses/gpl-3.0.html") }
        )
    )
    add(keyOpenLine("credits", 2, syntax))
    add(
        stringValueLine(
            "font", "JetBrains Mono", comma = false, syntax = syntax, indent = 3,
            hint = "// SIL OFL 1.1",
            onClickLabel = openLabel("JetBrains Mono"),
            onClick = { actions.onOpenUrl("https://www.jetbrains.com/lp/mono/") }
        )
    )
    add(punctLine("}", 2, syntax))
    add(punctLine("}", 1, syntax))

    add(punctLine("}", 0, syntax))

    // Terminal prompt below the buffer: factory reset as a git command. First tap
    // arms it (confirm hint in diff-deletion red), second tap runs it.
    add(punctLine("", 0, syntax))
    add(commentLine("// restore defaults (discards local changes):", syntax))
    add(
        CodeLine(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = syntax.comment)) { append("$ ") }
                append("git restore settings.config")
                if (resetArmed) {
                    withStyle(SpanStyle(color = syntax.diffDel)) {
                        append("  // tap again to confirm")
                    }
                }
            },
            indent = 0,
            onClick = onResetLine,
            onClickLabel = resetLabel
        )
    )
}

/**
 * A free-number line. Closed: `"weight_kg": 78.0,  // hint` (tap to edit; a null
 * value renders as JSON null in comment gray). Open: the value swaps for a
 * terminal input with `[esc]` to cancel, plus a transient `// ERROR:` line when
 * the submit doesn't parse.
 */
private fun MutableList<CanvasLine>.addNumericLine(
    key: String,
    renderedValue: String,
    isNull: Boolean,
    comma: Boolean,
    hint: String,
    field: NumericField,
    syntax: SyntaxColors,
    changeLabel: (String) -> String,
    editing: NumericField?,
    editValue: String,
    onEditValue: (String) -> Unit,
    onStartEdit: (NumericField) -> Unit,
    onSubmitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    cancelLabel: String,
    inputError: String?
) {
    if (editing != field) {
        add(
            CodeLine(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
                    withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
                    withStyle(
                        SpanStyle(color = if (isNull) syntax.comment else syntax.number)
                    ) { append(renderedValue) }
                    if (comma) withStyle(SpanStyle(color = syntax.comment)) { append(",") }
                    withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                        append("  $hint")
                    }
                },
                indent = 2,
                onClick = { onStartEdit(field) },
                onClickLabel = changeLabel(key)
            )
        )
        return
    }
    add(
        WidgetLine(
            indent = 2,
            measureText = "\"$key\": 00000000  [esc]  slack"
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyntaxText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
                        withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
                    }
                )
                Box(Modifier.width(120.dp)) {
                    TerminalInput(
                        value = editValue,
                        onValueChange = { text ->
                            onEditValue(text.filter { it.isDigit() || it == '.' || it == ',' }.take(8))
                        },
                        prompt = "",
                        placeholder = if (isNull) "" else renderedValue,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onSubmitEdit() }),
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                }
                Text(
                    text = "[esc]",
                    style = MaterialTheme.typography.bodySmall,
                    color = syntax.comment,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(role = Role.Button, onClickLabel = cancelLabel) {
                            onCancelEdit()
                        }
                )
            }
        }
    )
    if (inputError != null) {
        add(
            CodeLine(
                AnnotatedString(inputError, SpanStyle(color = syntax.diffDel)),
                indent = 2
            )
        )
    }
}

/**
 * The notifications block's dynamic status line (tweather's pattern). Error
 * states are tappable: grant, or the system app settings permanently denied.
 */
private fun notifStatusLine(
    state: NotifLineState,
    syntax: SyntaxColors,
    onClickLabel: String,
    onClick: () -> Unit
): CodeLine {
    val (text, color) = when (state) {
        NotifLineState.Disabled ->
            "// notifications disabled" to syntax.comment.copy(alpha = 0.6f)
        NotifLineState.Armed ->
            "// rides the midnight rollover and the step sync" to
                syntax.comment.copy(alpha = 0.6f)
        NotifLineState.MissingPermission ->
            "// ERROR: notifications permission missing — tap to grant" to syntax.diffDel
        NotifLineState.DeniedPermanently ->
            "// ERROR: denied — open system settings" to syntax.diffDel
    }
    val clickable = state == NotifLineState.MissingPermission ||
        state == NotifLineState.DeniedPermanently
    return CodeLine(
        text = AnnotatedString(text, SpanStyle(color = color)),
        indent = 2,
        onClick = onClick.takeIf { clickable },
        onClickLabel = onClickLabel.takeIf { clickable }
    )
}

/** Permanently denied permissions can only be granted back from the app's page. */
private fun android.content.Context.openAppSystemSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
    )
}

/**
 * `"word_wrap": false,  // hint` — a plain [CodeLine] (so it word-wraps like any
 * other line) whose whole line toggles the boolean on tap.
 */
private fun boolLine(
    key: String,
    value: Boolean,
    comma: Boolean,
    syntax: SyntaxColors,
    hint: String? = null,
    onClickLabel: String? = null,
    onToggle: () -> Unit
): CodeLine = CodeLine(
    text = buildAnnotatedString {
        withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
        withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
        withStyle(SpanStyle(color = syntax.number)) { append(value.toString()) }
        if (comma) withStyle(SpanStyle(color = syntax.comment)) { append(",") }
        if (hint != null) {
            withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                append("  $hint")
            }
        }
    },
    indent = 2,
    onClick = onToggle,
    onClickLabel = onClickLabel
)

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 900)
@Composable
private fun SettingsScreenPreview() {
    TstepsTheme {
        SettingsScreen(
            settings = AppSettings(dailyGoalSteps = 10_000, weightKg = 78.0, heightCm = 175),
            actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141A, heightDp = 900)
@Composable
private fun SettingsScreenDefaultsPreview() {
    TstepsTheme {
        SettingsScreen(
            settings = AppSettings(),
            actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        )
    }
}
