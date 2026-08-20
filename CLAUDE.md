# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

**tsteps** is an Android step counter / minimal movement tracker (Kotlin 2.2 / Jetpack Compose Material 3) whose entire UI mimics a code editor and a git repository: today is `steps_data.json` (the working tree), the history is `steps_history.diff` (one commit per day, walks as diff hunks, the daily goal as a CI check ✓/✗), stats are a `stats.md` with a GitHub-style contribution heatmap, and settings are a `settings.config`. Second app of the **t-series** after [tweather](https://github.com/fiorenzobrioni/tweather) — same theme, same components, same philosophy.

Source of truth:

- `VISION.md` — product spec: the series' soul inherited from tweather (§1), the git metaphor mapping (§2), every screen/file (§4), metrics (§5), decisions that diverge from the old draft (§6), sensor strategy (§7), MVP (§8). The early `VISION-DRAFT.md` was superseded and deleted (git history has it).
- `PLANNING.md` — the phased implementation plan with checkable steps. **Keep it updated as work progresses**, recording every decision and deviation with its reason (tweather's rule).
- The sibling repo `../tweather` — reference implementation for the editor kit (`ui/components/`), the widget architecture, the diff screens and all decisions already validated on device. Components are **copied and adapted** (package `com.callbackdev.tsteps`), never linked.

## Build and commands

Stack: Kotlin 2.2 + Compose (Material 3), Gradle 9.1 / AGP 8.13, version catalog in `gradle/libs.versions.toml`. Package/applicationId: `com.callbackdev.tsteps`. minSdk 33, compile/targetSdk 36. **No Retrofit/OkHttp and no INTERNET permission: tsteps has no network, by identity.** If a feature needs the network, it is not a tsteps feature.

- Build debug APK: `./gradlew :app:assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`)
- Unit tests: `./gradlew :app:testDebugUnitTest` — single class: `--tests "com.callbackdev.tsteps.SomeTest"`
- Lint: `./gradlew :app:lintDebug`
- Installable minified build: `./gradlew :app:assembleRelease -PsignReleaseWithDebugKey`
- On this machine there is no system JDK: prepend `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` to gradlew commands.

**Debug signing**: `keystore/debug.keystore` is intentionally committed (alias `tsteps-debug`, store/key password `android`) so debug APKs from CI and any machine share one signature. Do not regenerate it.

**CI**: `.github/workflows/android-ci.yml` runs on every push — unit tests, lint, then both APKs (tests *before* builds: a red suite never produces an installable artifact). Artifacts: `tsteps-debug-apk`, `tsteps-release-apk-testing-only`, `tsteps-release-mapping`.

**Release signing**: the real keystore lives OUTSIDE the repo (`C:\Fiorenzo\keys\tsteps-release.jks`); the `release` signingConfig is created only when the four `TSTEPS_KEYSTORE*` properties are all set — from `~/.gradle/gradle.properties` locally, from `ORG_GRADLE_PROJECT_*` env vars (GitHub Secrets) in CI — and wins whenever configured. On an unconfigured checkout the release build is unsigned by default; `-PsignReleaseWithDebugKey` signs it with the committed debug key so the minified build is installable for testing (R8 breakage shows up nowhere else) — opt-in so an unconfigured checkout can never produce an installable release by accident. `.github/workflows/release.yml` fires on `v*` tags: tests + lint, then the real-key APK published as a GitHub Release together with its R8 mapping.

## Design constraints (non-negotiable, inherited from tweather)

- **Typography**: JetBrains Mono everywhere, 4px baseline grid, 20px indent per nesting level. The home widget is the single exception (system `monospace` — CVE-2021-0567, launchers silently drop `@font/` in widget layouts).
- **Syntax colors** (Obsidian): keys `#79c0ff`, strings `#a5d6ff`, numbers/booleans `#ffa657`, comments/braces `#8b949e`, diff add `#2ea043`, del `#f85149`. Palette: background `#10141a`, surface `#181c22`, on-surface `#dfe2eb`, borders `#30363d`. Profiles: Obsidian/Dracula/Monokai, dark-only.
- **No drop shadows** — 1px borders + tonal stacking; the FAB's glow is the only exception, and the FAB is rectangular (4px radius, like everything).
- **Controls rendered as text**: booleans as tappable `true`/`false`, removal as `[rm]`, destructive actions as `$` commands with two-tap confirm, inputs as terminal prompts with blinking `_`. No native Material controls.
- **The file must not lie**: keys rename with units (`distance_km` ↔ `distance_mi`), estimates carry their `//` disclaimer, metrics missing an input are hidden, not invented (kcal without weight).
- **Localization rule**: "code" stays English (JSON keys, filenames, `//` comments, terminal output, commit/check lines); chrome and data values localize IT/EN. The `README.md` day tab is prose: fully localized.
- Session hunk headers use git range syntax: `@@ 09:12..10:03 @@ walk` (ASCII `..`, not a dash).

## Writing `README.md` (root file only)

**No em dashes (`—`) or en dashes (`–`) in the root `README.md`.** Rewrite the sentence rather than swapping in a hyphen: use a colon when the clause explains, a full stop when the thoughts are separate, parentheses for an aside. Same house style as tweather, deliberately scoped to that one file — every other file keeps normal punctuation.

## Domain notes

- Step source: hardware `TYPE_STEP_COUNTER` (cumulative since boot, batched); continuity anchors in DataStore survive reboots (see PLANNING Fase 2 — the classic pedometer bug, test it).
- Midnight rollover WorkManager job commits the day (Room `day_summary`), runs the goal check, fires notifications, repaints the widget. DST/timezone changes are test cases.
- Foreground service **only** during manual tracking (`$ tsteps track`); passive counting never runs a service.
- Battery is a feature: no polling, no continuous sensors, no GPS in the core product (maps are parked indefinitely — VISION §6.4).
