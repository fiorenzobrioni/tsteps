# Changelog

All notable changes to tsteps are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- The home widget now repaints when you leave the app. The whole time the app is
  open its live listener has been ingesting readings the widget never heard about;
  it used to keep the last background sample's number until the next 15-minute
  pass.
- The widget's ↻ now reads the counter inside the tap instead of queueing the read
  for WorkManager, where an expedited request degrades to an ordinary job once the
  quota is spent — on a phone whose owner rarely opens the app, that meant minutes.
  While the read is in flight the glyph turns into `…`, so a tap that found nothing
  new is no longer indistinguishable from a tap that went nowhere.
- A failing sync pass no longer skips the widget repaint, cancels the 15-minute
  sampler, or (at midnight) drops the next rollover appointment. Health Connect's
  IPC can throw, and one throw used to take all three with it.
- A counter sample now waits for the hardware FIFO flush it already asked for and
  keeps the newest reading, instead of taking the first value handed out at
  registration — which on a batching step counter can be minutes old.

## [1.0.0] — 2026-08-20

First release. Everything below is new.

### The repo

- Four tabs behind a bottom navigation bar, each a fake file: `steps_data.json`
  (today as highlighted JSON — goal check bar, hourly sparkline, sessions),
  `steps_history.diff` (one commit per day, today on top as uncommitted changes,
  walks as diff hunks, records as tags), `stats.md` (the 12-week contribution
  graph, streaks, averages, records table) and `settings.config` (settings as an
  editable config file).
- A second editor tab, `README.md`: the same day as fully localized prose — totals,
  the day's build badge (`## Stato`, neutral words, never guilt), the walks table
  and the week at a glance.
- Obsidian Syntax design system shared with tweather: JetBrains Mono everywhere,
  1px borders instead of shadows, controls rendered as text — booleans flip on tap,
  destructive actions are `$` commands with a two-tap confirm. Theme profiles:
  Obsidian, Dracula, Monokai.

### The steps

- The phone's hardware step counter, and nothing else: **no INTERNET permission**,
  no accounts, no Play Services. Distance and active calories are estimates and
  say so; calories stay hidden until a weight exists to compute them from.
- Live tracking: the FAB runs `$ tsteps track` as a terminal process — one
  transcript line per minute, `^Z` to pause, `^C` to stop; the closed session
  becomes a hunk in today's diff. Opt-in auto-detection turns sustained walking
  cadence into `(auto)` sessions with honest approximate boundaries, at zero extra
  sensing cost.
- Daily goal as a CI check that passes or fails on facts; streaks and a
  GitHub-style heatmap bucketed against your own history, not a universal 10,000.
- Home-screen widget: a terminal window running `tsteps --today`, resizing one
  line at a time, honest about a stopped sensor (`# sensor off`).
- Health Connect interop, opt-in and off by default: hourly steps and sessions
  written to the on-device store, other apps' counts shown per source, never
  summed into yours.
- Export from `settings.config`: `$ tsteps export --json` / `--csv` into
  Downloads via MediaStore (no storage permission). Stable units written into the
  keys, missing data as `null` (never zero), today included and flagged
  `"committed": false`.
- Fully localized, Italian and English, via the system per-app language picker:
  JSON keys, filenames and terminal output stay English like real code; prose and
  data values translate.

[1.0.0]: https://github.com/fiorenzobrioni/tsteps/releases/tag/v1.0.0
