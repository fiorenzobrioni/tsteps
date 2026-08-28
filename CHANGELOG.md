# Changelog

All notable changes to tsteps are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- The app now speaks Italian where it is talking to you, and keeps English where it
  is showing you the file. What decides is the register, not the punctuation around
  it: JSON keys, filenames, `$` commands, git chrome and the goal check lines stay
  English, while every comment line that is a sentence follows the reader. A line
  can hold both, and then the tokens stay put and the words around them move
  (`// ERROR: esportazione fallita — Downloads is not writable`). This replaces the
  old rule that kept every `//` comment English, which mistook the channel for the
  register and left the app more English than `git` itself: under `LANG=it_IT`,
  `git status` says "Sul branch main" and still never translates `commit`.
- The `$ tsteps track` controls say `[ ^Z pausa ]` and `[ ^C ferma ]` in Italian.
  The shell glyph is the token and never moves; the word beside it is there to
  explain the glyph, so it has to be in a language the reader has.
- A failed export now says what happened in your own language and prints the
  system's error after it as the evidence: `// ERROR: esportazione fallita — …`.
  What broke usually arrives as an errno that nothing can translate.

### Added

- `$ tsteps init`: the first run now explains why the app needs the physical-activity
  permission and asks for it there, instead of leaving a cold system dialog to do it.
  Skipping is a real answer — you land on the document that already says the counter
  is off and offers the grant command. Installs that are already counting never see
  it.
- `HELP.md`, a second file behind the Settings tab bar: what the four tabs are, what
  the borrowed words mean (commit, working tree, diff, check, branch), where the
  numbers come from and why the app looks like this. Written for someone who does not
  read `git` for a living, and fully localized. A one-off `// new here? open HELP.md`
  line at the top of the editor points at it once and goes away as soon as the file
  has been opened.

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
