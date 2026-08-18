# tsteps

[![CI](https://github.com/fiorenzobrioni/tsteps/actions/workflows/android-ci.yml/badge.svg)](https://github.com/fiorenzobrioni/tsteps/actions/workflows/android-ci.yml)
![License](https://img.shields.io/badge/license-GPL--3.0-79c0ff?labelColor=10141a)
![minSdk](https://img.shields.io/badge/minSdk-33-a5d6ff?labelColor=10141a)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-ffa657?labelColor=10141a)
![Network](https://img.shields.io/badge/network-none-2ea043?labelColor=10141a)

**A step counter that thinks it is a git repo.**

Every day is a commit. Your steps are the added lines, a walk is a diff hunk with
its time range in the `@@` header, the daily goal is a CI check that passes or
fails, and your streak is a contribution graph. The word "commit" means exactly
what it says: showing up every day.

tsteps is the second app of the t-series, after
[tweather](https://github.com/fiorenzobrioni/tweather) ("a weather app that thinks
it is a code editor"). Same fake-editor interface, same Obsidian Syntax theme, same
JetBrains Mono everywhere: the two apps look like two files open in the same editor.

> **Status: under construction.** The skeleton builds and the plan is written.
> `VISION.md` is the product spec, `PLANNING.md` is the phased implementation log.

---

## The idea

The bottom bar has four tabs, and each one opens a file rather than a screen.

### `steps_data.json`: today

The working tree. The step count ticks live while you watch, because the file is
being written all day. The `//` comments are the status channel: sensor state,
estimate disclaimers and errors read like compiler messages, not toasts.

```json
{
  "date": "2026-08-18",
  "steps": {
    "count": 8432,
    "goal": 10000,
    "check": "▓▓▓▓▓▓▓▓▓▓▓▓▓░░░ 84%"
  },
  "movement": {
    "distance_km": 6.1,
    "active_min": 74,
    "active_kcal": 327
  },
  "hourly": "▁▁▂▅▇▇▃▁▁▂▅▃▁▁",
  "sessions": [
    { "time": "09:32", "type": "walk", "min": 46, "steps": 4820, "km": 3.4 }
  ],
  "streak_days": 6
}
```

Switch to imperial and the keys change too, to `distance_mi`. A JSON file should
not lie about its units. Calories are hidden until you give the app your weight:
an estimate without its input is not shown, it is invented.

### `steps_history.diff`: the log

One commit per day, committed at midnight by the system. Today sits on top as
uncommitted changes. Expand a day and the walks appear as hunks; personal records
are tags pinned to their commit.

```diff
commit 4f82a1c  (tag: best-week)
Author: you@tsteps.app
Date:   Sun Aug 17

    11,204 steps · 8.3 km · 96 min
    ✓ goal check passed (11,204 ≥ 10,000)

@@ 09:12..10:03 @@ walk
+ 5,120 steps · 3.6 km · 51 min
```

The goal check is factual, never guilt-driven: a red ✗ states a number, and if no
goal is set no check runs at all. The app is fully functional without one.

### `stats.md`: the contribution graph

Your movement as a GitHub-style heatmap, rendered as a markdown file: twelve weeks
of green squares, the current and longest streak, 7 and 30 day averages, and the
records table. Intensity buckets are relative to your own history, not to a
universal 10,000.

### `settings.config`: the settings

Booleans flip on tap, numbers open a terminal prompt, the trailing hint tells you
the allowed values. Resetting is a command with a two-tap confirm:
`$ git restore settings.config`.

### `$ tsteps track`: a walk, live

The glowing FAB starts a manual session, which runs as a terminal process: one
transcript line per minute, pause with `^Z`, stop with `^C`. The closed session
becomes a hunk in today's diff.

---

## The widget

`tsteps --today`: a terminal window on the home screen, built on the widget
architecture proven in tweather. It resizes one line at a time, from a glanceable
steps count to the full transcript with goal bar, distance, streak and last walk.
It never polls on its own, and if the sensor pipeline stops it says `# sensor off`
instead of presenting a frozen number as live.

---

## Data

There is no data section with API tables this time, and that is the point:

| What | Source |
| --- | --- |
| Steps | the phone's hardware step counter (`TYPE_STEP_COUNTER`) |
| Distance | steps × stride length, estimated from your height or set manually |
| Active calories | MET estimate from weight and active time, clearly labeled |
| Network | **none. The app does not request the INTERNET permission.** |

Your movement history never leaves the device. Export (JSON/CSV) and Health
Connect interop are planned phases; both stay under your control.

---

## Build

Requires JDK 21. No signing setup, no API key, no accounts: clone and build.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests, JVM only (Robolectric)
./gradlew :app:lintDebug
```

The release build is minified by R8 and unsigned by default. To get an installable
one for testing:

```bash
./gradlew :app:assembleRelease -PsignReleaseWithDebugKey
```

That flag signs it with the debug keystore committed in `keystore/` (alias
`tsteps-debug`). The keystore is in the repo on purpose, so debug builds from CI
and from any machine share one signature and can update an existing install. It is
not a release key, and the opt-in flag exists so a store artifact can never be
produced with it by accident.

CI runs the tests and lint before the builds, so a red suite never produces an
installable artifact.

---

## Architecture

Kotlin 2.2, Jetpack Compose with Material 3, single module, no DI framework,
same stack as tweather minus the networking (there is none).

| Concern | Choice |
| --- | --- |
| Dependency injection | a hand-rolled ServiceLocator |
| Settings, sensor anchors | DataStore |
| Days and sessions | Room (never pruned: a decade of days is tiny) |
| Background work | one WorkManager job for the midnight rollover |
| Live tracking | a foreground service, alive only during `$ tsteps track` |

`PLANNING.md` is the phased implementation log: every decision, and every
deviation from the vision, is recorded there with the reason.

---

## Design

The theme is **Obsidian Syntax**, inherited verbatim from tweather (token set in
its `obsidian_syntax/DESIGN.md`). Dracula and Monokai ship as alternate profiles,
switchable at runtime.

| | |
| --- | --- |
| Keys | `#79c0ff` |
| Strings | `#a5d6ff` |
| Numbers, booleans | `#ffa657` |
| Comments, punctuation | `#8b949e` |
| Additions / deletions | `#2ea043` / `#f85149` |
| Background | `#10141a` |

JetBrains Mono everywhere, a 4px baseline grid, no drop shadows: depth comes from
1px borders. Nothing is circular, the FAB included; what sets it apart is its glow.
The app icon is the series brand mark: two footprints mid-stride between curly
braces, where tweather keeps its cloud.

English and Italian, following the system per-app language. The rule is that
"code" stays English (JSON keys, filenames, `//` comments, terminal output) while
the chrome and the data values are translated.

---

## License

[GPL-3.0](LICENSE) © 2026 Fiorenzo Brioni

[JetBrains Mono](https://www.jetbrains.com/lp/mono/) under the SIL Open Font License.
