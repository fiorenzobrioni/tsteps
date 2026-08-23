# VISION.md — tsteps

> **A step counter that thinks it is a git repo.**
> Every day is a commit. Your steps are the added lines.

tsteps is the second app of the **t-series** (after [tweather](https://github.com/fiorenzobrioni/tweather)): real, single-purpose Android apps whose entire UI mimics a code editor and a terminal. This document fixes the soul of the series as inherited from tweather (§1), explains how the metaphor deepens in the step-counter domain (§2), and specifies the product (§3 onward). It absorbs and supersedes the early `VISION-DRAFT.md` (removed from the repo; retrievable from git history).

---

## 1. The soul inherited from tweather

These are the findings of the tweather review — the parts that *are* the series, and that tsteps adopts wholesale. They are not styling suggestions; they are the identity.

### 1.1 The editor is the interface, not a skin

Every screen is a **fake file** behind a bottom tab bar. Data is rendered as syntax-highlighted source (JSON, markdown, diff, config) with a line-number gutter, editor tabs, and a terminal status bar. There are no cards, no lists-with-icons, no Material widgets wearing a dark theme. If a piece of UI cannot be expressed as something a code editor or a terminal would show, it does not ship.

Corollaries that tweather proved on device:

- **Controls are text.** Booleans are tappable `true`/`false` values. Removal is `[rm]`. Destructive actions are `$` shell commands with a two-tap confirm (`$ git restore settings.config`). Inputs are terminal prompts with a blinking `_` cursor. Checkboxes are `[x]`/`[ ]`.
- **The comment channel.** `//` comments are the loading, error and hint channel. A failure reads like a compiler message, not a toast.
- **Icons are emoji inside the text** (`☀️`, `👣`, `🔥`), never image assets in the body.
- **The file must not lie.** In tweather, switching to Fahrenheit renames the keys (`temp_c` → `temp_f`). Estimates are labeled as estimates. Stale data says `# stale` instead of posing as current. Honesty of the fake file is a hard rule.

### 1.2 Design system: Obsidian Syntax (non-negotiable)

Full token set in tweather's `obsidian_syntax/DESIGN.md`; the Compose implementation is already ported to `app/src/main/java/com/callbackdev/tsteps/ui/theme/`.

- **JetBrains Mono everywhere.** 4px baseline grid, 20px indent per nesting level. Single sanctioned exception: home-widget layouts use system `monospace` (CVE-2021-0567 — launchers silently drop `@font/` in widget contexts).
- **Syntax colors** (Obsidian profile): keys `#79c0ff`, strings `#a5d6ff`, numbers/booleans `#ffa657`, comments/punctuation `#8b949e`, diff add `#2ea043`, diff del `#f85149`.
- **Core palette**: background `#10141a`, surface container `#181c22`, on-surface `#dfe2eb`, borders `#30363d`.
- **No drop shadows.** Depth = 1px borders + tonal stacking. The FAB's glow is the only shadow-like effect, and the FAB is rectangular like everything else — nothing in an editor is round. 4px corner radius on every element.
- **Theme profiles**: Obsidian (default), Dracula, Monokai — switchable at runtime, dark-only. No light theme exists in the series.

### 1.3 The localization rule

English and Italian via the system per-app language picker (minSdk 33). The split is semantic: **code stays English** — JSON keys, filenames, `//` comments, terminal output, commit hashes and CI checks. **Chrome and data values are localized** — navigation labels, accessibility text, day names, session type names. A `README.md` fake file is prose, so it localizes fully, headings included.

### 1.4 Engineering ethos

- Single module, no DI framework (hand-rolled `ServiceLocator`), Kotlin 2.2 + Compose M3, version catalog.
- DataStore for settings/state, Room for history, one shared WorkManager periodic job — never one job per feature.
- JVM-only test suite (Robolectric for Compose), runs in CI **before** any APK is built: a red suite must never produce an installable artifact.
- Committed shared debug keystore; release build minified by R8, unsigned by default, debug-signable only via explicit flag.
- `PLANNING.md` is a phased log with checkable steps where every decision and deviation is recorded **with its reason**. Battery cost is a design input, not an afterthought.

---

## 2. The metaphor upgrade: from editor to repository

tweather's git layer was a stretch: every fetch became a "commit", and the commits were frequent, machine-made, meaningless individually. In a step counter the metaphor lands exactly:

| git concept | tsteps meaning |
| --- | --- |
| working tree | **today** — the day being written, changing under your feet |
| commit | **a completed day**, committed at midnight |
| added lines (`+`) | **steps** — what you produced that day |
| diff hunk `@@ 09:32..10:18 @@` | **an activity session** (a walk), with its time range as the hunk header |
| commit log | **the history** — one commit per day, newest first |
| CI check (✓ / ✗) | **the daily goal** — the day's build passes or fails |
| commit streak / contribution graph | **the streak** and the month-at-a-glance activity heatmap |
| `git tag` | **personal records** (best day, longest walk) pinned to their commit |
| a running process (`^C` to stop) | **a manually tracked walk**, live |
| `git diff last_week` | **week-over-week comparison** — a real diff in `week.diff`, the Log's second file (§4.2) |

And the word **commit** gains its double meaning: the app is about committing — showing up every day. A streak is literally a commit streak.

One rule keeps this healthy: **the metaphor serves the data, never the reverse.** No git concept gets a screen unless it answers a real user question ("how much did I move?", "when?", "am I consistent?").

---

## 3. Product identity

### 3.1 What tsteps is

A minimal Android step counter and everyday-movement tracker. It answers, at a glance and honestly:

- How many steps today? How far, how long, roughly how many active kcal?
- Did I take an actual walk, and what was it like?
- How consistent have I been — this week, this month?

**Product promise:** *everything you want to know about your daily movement, nothing you don't need.* The phone is enough; no wearable, no account, and — stronger than tweather can claim — **no network**. The app does not request the INTERNET permission at all. Data never leaves the device except when the user explicitly exports it.

### 3.2 What tsteps is not

Not a medical app, not a health dashboard, not a nutrition/sleep/heart tracker, not a sports computer, not a coach, not a social network. Every future feature request is tested against this list. (Inherited intact from the draft — it was its best section.)

### 3.3 Product principles

1. **Minimal by default.** Every feature justifies its presence; if it serves few users and complicates the main file, it is optional or deferred.
2. **Passive first.** Useful without ever pressing Start. Manual tracking exists for when a walk deserves its own hunk.
3. **Battery is a feature.** Hardware step counter, batched sensor reads, no continuous GPS, no polling. A pedometer that drains the battery is a contradiction.
4. **Honest numbers.** Estimates say so (`// estimated from stride length`), precision is never faked (`6.1 km`, not `6.137 km`), and missing inputs disable a metric instead of inventing it.
5. **No forced gamification.** The goal is a CI check, not a guilt machine. Neutral language always: `✗ goal check failed (6,412 < 8,000)` states a fact; the app never says "you're falling behind!". No goal set → no check run, and the app is fully functional.
6. **Local first.** Room + DataStore. Export is a file the user owns. Health Connect is an interoperability door, not a cloud.

---

## 4. The files (screens)

Four tabs, like tweather: **Editor / Log / Stats / Settings**. Each opens a file.

### 4.1 `steps_data.json` — today (main screen)

The working tree. The step count ticks live while you watch — the file is being written all day.

```json
{
  "date": "2026-08-18",                  // ← today = uncommitted changes
  "steps": {
    "count": 8432,
    "goal": 10000,
    "check": "▓▓▓▓▓▓▓▓▓▓▓▓▓░░░ 84%"
  },
  "movement": {
    "distance_km": 6.1,                  // estimated from stride length
    "active_min": 74,
    "active_kcal": 327                   // MET estimate, needs profile.weight
  },
  "hourly": "▁▁▂▅▇▇▃▁▁▂▅▃▁▁",            // 06 → 20, one glyph per hour
  "sessions": [
    { "time": "09:32", "type": "walk", "min": 46, "steps": 4820, "km": 3.4 }
  ],
  "streak_days": 6
}
```

- **No goal set is a state, not a hole**: the `steps` block still carries the key, as an explicit `"goal": null,  // tap to set 8000` that is tappable and sets it. The offer is the whole opt-in — no check, no bar and no streak exist until it is taken (§3.3.5 stays intact: the app never imposes a goal, it just stops hiding the one it would suggest). See §6.11 for why a silent install-time default was rejected.
- Imperial switch renames keys, tweather-style: `distance_km` → `distance_mi`.
- The hourly sparkline is the draft's "activity timeline", terminal-ified: it answers *when was I active* in one line of glyphs.
- `//` comments carry sensor state: `// sensor: TYPE_STEP_COUNTER (hardware, batched)` or the graceful failure `// E: no step sensor on this device`.
- Tapping a session opens its detail (the hunk view, §4.2).
- **FAB** (glowing, rectangular): `▶` — starts a manual session (§4.5). tweather's FAB re-runs the fetch; tsteps' FAB starts the walk. Each app gets exactly one glowing verb.
- **Second editor tab: `README.md`** — the day as localized prose (markdown source), like tweather's city README: `## Today` summary, `## Status` (goal state with the same completion percentage the JSON's `check` bar shows, streak), `## Week` compact table closed by a totals line in the shape of the `## Today` summary, and `## Records` — the same three all-time tags `stats.md` pins in a table, told as sentences (a third pipe table on this page would be the stats screen wearing prose). Records come from committed days only, like any tag on a commit. Fully localized, headings included.

### 4.2 `steps_history.diff` — the log

The git log made real. One commit per day, committed at midnight by the system. Today sits on top as uncommitted changes.

```diff
# On branch main — changes not yet committed (today)
#   8,432 steps · 6.1 km · 74 min

commit 4f82a1c  (tag: best-week)
Author: you@tsteps.app
Date:   Sun Aug 17

    11,204 steps · 8.3 km · 96 min · 421 kcal
    ✓ goal check passed (11,204 ≥ 10,000)

@@ 09:12..10:03 @@ walk
+ 5,120 steps · 3.6 km · 51 min
@@ 17:40..18:11 @@ walk
+ 2,904 steps · 2.1 km · 31 min

commit c91d3ae
Date:   Sat Aug 16

    4,113 steps · 2.9 km · 33 min
    ✗ goal check failed (4,113 < 10,000)
```

- Collapsed by default to the commit lines; a day expands into its diff, where **sessions are hunks** with the time range as the `@@` header. Passive movement outside sessions is the context lines.
- CI checks (✓ green / ✗ red) run only if a goal is set; the tone is factual, never scolding.
- Records are `tag:` refs on their commit (`tag: best-day`).
- Week boundaries render as `--- week 34 ---` separators with the week total and a `+/-` delta vs the previous week — the week as a diff, as promised.

**Second editor tab: `week.diff`** — `git diff last_week` made literal, the last row of §2's table to get a file of its own. The log's week separators carry the steps delta in passing; this is the whole comparison, one hunk per metric with the change in the hunk header's context slot:

```diff
$ git diff @{last.week}

--- a/week 33   aug 10..16   7/7 days
+++ b/week 34   aug 17..23   2/7 days

// week 34 is still being written: 2 of 7 days so far

@@ steps @@  -39,000  -80%
- 49,000
+ 10,000

@@ distance_km @@  -28.1
- 34.9
+ 6.8

@@ goal_checks @@  -6
- ✓✓✓✓✓✓✓  7/7
+ ✓·  1/1
```

Three rules keep it honest: **nothing is pro-rated** (both headers carry their day count, a week in progress says so in the comment channel, and the totals stay the totals); **the `-` side is the week immediately before**, empty or not — promoting an older week because the real one is blank would lie about what was compared, so an empty previous week renders as a stated absence; and **only steps carry a percentage**, since distance and active minutes are linear in steps and three near-identical percentages would imply three measurements. Today rides the current side from the working tree with a skipped check: the check runs at midnight, and claiming a result before it has run is the one lie this file could tell.

### 4.3 `stats.md` — the contribution graph

The screen the git metaphor unlocks — the one no mainstream health app has. Your movement as a GitHub-style contribution heatmap, rendered as a markdown file.

```markdown
# stats.md

## contributions (last 12 weeks)

Mon  ▪ ▪ ■ ■ ▪ □ ■ ■ ■ □ ■ ■
Wed  ■ □ ▪ ■ ■ ▪ ■ □ ▪ ■ ■ ▪
Fri  □ ■ ■ ▪ ■ ■ □ ■ ■ ■ ▪ ■
Sun  ■ ▪ □ ■ ▪ ■ ■ ▪ ■ □ ■ ■
         jun         jul         aug

## streak

current: 6 days       longest: 19 days

## averages (30d)

| metric   | value  |
| -------- | ------ |
| steps    | 8,120  |
| distance | 5.9 km |
| active   | 68 min |

## totals

since 2026-04-02: **412,309 steps** · 291.4 km · 58 h

## tags

| tag           | value         | date       |
| ------------- | ------------- | ---------- |
| best-day      | 14,823 steps  | 2026-07-12 |
| longest-walk  | 92 min        | 2026-06-28 |
```

Intensity buckets (□ ▪ ■ in 4–5 green shades) are relative to the user's own distribution, not to an absolute 10k. Streaks and tags are informational — nothing flashes, nothing is lost dramatically. `## totals` is the repo's own size (`git log --shortstat` for a body): it sums what the app already measured — committed days plus today's live working tree — and infers no new fact about the user, which is what keeps it on the right side of §3.2.

### 4.4 `settings.config` — the settings

tweather's pattern verbatim: values cycle or open terminal inputs on tap, trailing `//` hints give the allowed range, reset is `$ git restore settings.config` with two-tap confirm.

```ini
[goal]
daily_steps = 10000            // 0 disables the CI check
                               // at 0 the hint names the suggested 8000 instead

[profile]
weight_kg = 78                 // only for kcal estimate; empty hides kcal
height_cm = 175                // only for stride estimate
stride_cm = 78                 // measured stride; overrides height_cm

[units]
system = "metric"              // metric | imperial

[theme]
active_profile = "obsidian"    // obsidian | dracula | monokai

[notifications]
daily_commit = true            // day summary when the day is committed
goal_check = true              // one notification when the check passes

$ git restore settings.config
```

### 4.5 `$ tsteps track` — the live session

Not a file: a **running process**. Started from the FAB, it opens a terminal transcript that appends a line per minute; the notification (required by the foreground service that keeps sensors alive) shows the same command line. Stopping is the shell's own verb: **`^C`**, rendered as a key you tap (with confirm). Pause is `^Z`, resume is `fg`.

```
$ tsteps track walk
tracking… (^C to stop)

00:00  start                    09:32
05:00  512 steps    0.4 km
10:00  1,096 steps  0.8 km
        …
24:18  2,431 steps  1.7 km  4.2 km/h

[ ^Z ]                    [ ^C ]
```

On `^C` the session is closed and appears as a new hunk in today's diff. Session detail (opened from `steps_data.json` or the log) shows the full metrics: duration, steps, distance, avg speed **or** pace (one, chosen in settings), avg cadence. No map, no route — see §6.

### 4.6 `tsteps --today` — the widget

A terminal window on the home screen, inheriting tweather's entire widget architecture (RemoteViews sizes-map, one line per tier step, measured breakpoints, configurable opacity, system monospace). Content:

```
$ tsteps --today                    👣
steps:    8,432 / 10,000
check:    ▓▓▓▓▓▓▓▓▓▓▓▓▓░░░ 84%
dist:     6.1 km      active: 74 min
streak:   6 days
# last walk: 09:32 (46 min)
```

It repaints on step-count reads and at midnight rollover; it never runs its own polling. Unlike tweather there is no network to go stale — but if the sensor pipeline stops delivering (permission revoked, sensor gone), the widget says so (`# sensor off`) instead of showing a frozen number as live.

---

## 5. Metrics

The full metric set, with placement. "Core" appears in `steps_data.json`; "session" appears only in session detail; "stats" lives in `stats.md`.

| metric | placement | notes |
| --- | --- | --- |
| steps (day) | core | hardware `TYPE_STEP_COUNTER`, the one number that must always work |
| distance | core | steps × stride; stride from `profile.stride_cm` if measured, else derived from height, else a labeled average; honest `//` comment |
| active minutes | core | minutes containing meaningful step cadence; no "move ring" theatrics |
| active kcal | core | MET estimate; **hidden until weight is set** — never invented |
| hourly sparkline | core | the "when" of the day in one line |
| goal + check | core, log | one goal, steps only (MVP); CI semantics |
| sessions | core, log | auto-detected (later phase) + manual; hunks in the day's diff |
| avg speed / pace | session | one of the two, user preference; km/h default for walking |
| cadence | session | avg steps/min |
| streak | core, stats | consecutive goal-met days; absent if no goal — no guilt without opt-in |
| heatmap | stats | 12-week contribution graph, relative intensity buckets |
| averages 7/30d | stats | steps, distance, active min |
| totals | stats | steps, distance, active time since the first commit — a sum, never a new inference |
| records | stats, log | `tag:` refs — best-day, longest-walk, best-week |

Deliberately absent: floors climbed, VO₂ estimates, "fitness age", intensity zones, move/exercise/stand rings, calories-vs-food, and anything requiring a heart rate.

---

## 6. Decisions that diverge from VISION-DRAFT.md

Recorded here so the reasoning survives:

1. **All screen sketches replaced.** The draft's centered-big-number dashboard is exactly what the series is not; the hero number lives inside the JSON (and big on the widget).
2. **Health Connect out of the MVP.** The draft listed it as MVP item 11 while its own phase plan put it at Phase 4 — the phase plan was right. The phone sensor is the MVP source of truth; HC lands later as read/write interop with dedup.
3. **Automatic session detection out of the MVP.** Conservative detection is genuinely hard (the draft says so itself); manual sessions ship first and validate the session model, auto-detection follows.
4. **GPS routes and the map are parked indefinitely.** A map cannot be rendered inside a terminal without breaking the aesthetic, and route tracking drags the product toward the sports-computer category the draft itself excludes. If GPS ever arrives it will be as better distance/speed for a tracked session plus a **GPX file export** — a route as a file fits the metaphor; a map screen does not.
5. **Elevation/barometer parked** with GPS.
6. **Intensity classification (light/moderate/vigorous) dropped.** A fuzzy three-level taxonomy adds vocabulary without answering a user question cadence doesn't already answer.
7. **Running/cycling modes parked.** Session types stay `walk` and `other` until reality demands more.
8. **The widget is promoted** from "future feature" to an early phase: a step counter's most common interaction is a glance, and the series already owns a proven widget stack.
9. **Added: contribution heatmap, tags-as-records, week diffs, streak-as-commit-streak** — the features the git metaphor makes natural, absent from the draft.
10. **Added: data export** (`$ tsteps export` → JSON/CSV in Downloads) — a local-first app owes the user their data.
11. **An install-time default goal was rejected; the file offers one instead.** A goal written at install would make every day the user did not choose it fail a check they never opted into — precisely the guilt machine §3.3.5 forbids. But a goal buried in `settings.config` also hides the check, the bar and the streak from anyone who never opens that file, which is most of the metaphor. The resolution keeps both: the stored default stays 0, and `steps_data.json` renders `"goal": null` as a tappable offer (§4.1). 8,000 rather than 10,000 as the suggested number — 10,000 is a 1965 pedometer's brand name, while the step-count cohorts put the mortality-benefit plateau around 6,000–8,000/day for older adults and 8,000–10,000 for younger ones. 8,000 is the one figure inside the evidence for the whole adult range.
12. **`week.diff` promoted from a separator to a file.** The week-over-week comparison was in §2's table from the start and shipped as one number on the log's week separators — a steps delta glimpsed while scrolling past. That is a hint, not the comparison; the Log grows a second tab, exactly what §1.2's one-element tab strip was built to allow. What the file must never do is make the two sides look commensurable when they are not: the day counts are on both headers and no total is ever scaled.
13. **`stride_m` added to the export.** The archive carried `distance_m` but not the factor that produced it, so a reader could not tell a day measured at 0.78 m from one guessed at the 0.72 m fallback, nor recompute after measuring their own stride. It is derived from the row itself (`distance / steps`) rather than stored a second time: the distance *is* the product, so there is no copy that can drift. Schema bumped to 2.
14. **Sex/gender was considered for the profile and rejected.** It would change nothing tsteps computes: active kcal is `MET × weight × time`, which has no sex term, and the anthropometric stride factors differ by ~0.5% (0.415 vs 0.413 of height) — far below the noise floor of a step-derived distance. Using it meaningfully would mean a BMR/TDEE model, which needs age too and lands squarely in the health-dashboard category §3.2 excludes. A profile field that implies a personalization it does not deliver is the same lie as a fake unit. The honest way to a better distance was already in §5 and merely unimplemented: `profile.stride_cm`.

---

## 7. Sensor & data strategy

- **`TYPE_STEP_COUNTER`** (hardware, cumulative since boot, batched) is the primary source, read via periodic flushes — no wake locks, no foreground service for passive counting. `ACTIVITY_RECOGNITION` runtime permission, requested in context with a plain explanation.
- **Reboot handling**: the counter resets at boot; persisted anchors (boot count + last cumulative value) reconstruct continuity. This is the classic pedometer bug — it gets its own tests.
- **Midnight rollover**: a scheduled job closes the day, writes the `DaySummary` row, runs the goal check, fires the `daily_commit` notification, updates the widget. DST and timezone changes are test cases, not surprises.
- **Fallback**: no step sensor → the app says so in the comment channel and degrades to manual sessions via accelerometer-free timing; it never shows a broken dashboard.
- **Foreground service only during manual tracking**, with the `$ tsteps track` transcript as its notification. Killed at `^C`.
- **Storage**: Room (`day_summary`, `session` tables — pruned never; a decade of days is trivially small), DataStore for settings and sensor anchors.
- **No network. No INTERNET permission.** The CI badge for this in the README is the product's proudest line.
- **Health Connect (later phase)**: write sessions + daily steps, read external data with source dedup, never expanding scope because a data type exists.

---

## 8. MVP

1. Passive step counting (hardware sensor, reboot-safe, midnight rollover)
2. `steps_data.json` with steps, distance, active minutes, kcal (if weight set), hourly sparkline, goal bar
3. `settings.config` — goal, profile, units, theme profiles, reset
4. `steps_history.diff` — day commits with goal checks (sessions arrive with Fase sessions)
5. Manual sessions (`$ tsteps track`) with session detail
6. `README.md` day tab
7. `stats.md` — heatmap, streak, averages, tags
8. Widget `tsteps --today`
9. IT/EN localization, per-app language

Deferred: auto-detection, Health Connect, export, Wear OS (probably never), GPS (parked).

## 9. Success criteria

The first release succeeds if a user can install it, grant one permission, and: see today's steps instantly; trust the numbers (and know which are estimates); find yesterday without thinking; take a walk with `track` and read it back as a hunk; check their consistency on the heatmap; and never once think about battery. The deeper test, inherited from the draft: **does adding a feature make the app better, or just bigger?**

> **North star:** steps, time, distance and the walks you actually took — in a file that never lies, in a repo you commit to every day.
