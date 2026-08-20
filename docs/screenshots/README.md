# Screenshots used by the README

Real captures from a device, not mockups. Same convention as the sibling repo
[tweather](https://github.com/fiorenzobrioni/tweather/tree/main/docs/screenshots).

Captured with the phone in Italian, which is why the chrome reads `Impostazioni`
while the JSON keys, the filenames, the terminal output and the git headers stay
English. That split is the app's l10n rule, not an artifact of the screenshots. The
one deliberate exception is `main-md.jpg`: the `README.md` tab is prose, so there
everything translates, headings included.

| File | What it shows |
| --- | --- |
| `main-json.jpg` | Editor tab, `steps_data.json` — today as highlighted JSON: goal check bar, hourly sparkline, a session with `[rm]`, the glowing FAB |
| `main-md.jpg` | Editor tab, `README.md` — the same day as localized prose: `## Oggi`, `## Stato`, the walks and week tables |
| `main-recording.jpg` | the Editor during `$ tsteps track walk` — a live session as a terminal process, `[ ^Z pause ]` / `[ ^C stop ]`, `proc:` status bar |
| `log.jpg` | Log tab, `steps_history.diff` — today as uncommitted changes, a week separator, committed days with goal checks, a `best-day` tag and a diff hunk |
| `stats.jpg` | Stats tab, `stats.md` — the 12-week contribution graph, streaks, the averages and tags tables, `ro` in the status bar |
| `settings.jpg` | Settings tab, `settings.config` — editor toggles, goal, auto-detect, profile, units, theme profiles |
| `widget.jpg` | the home-screen widget, cropped — a terminal window running `tsteps --today` |

The `main-*.jpg` triple is deliberate: same screen three times, because the tab bar
and the FAB are what turn one screen into three states (the JSON, its prose twin,
and a live tracking session). That is the shot each of them exists to show.

JPEG on purpose, not an oversight. These are already-lossy captures, so re-encoding
them as PNG would produce a lossless copy of a lossy image: PNG's size with JPEG's
quality. The rule is never to *re-encode*: if a future capture comes off the device
as PNG, keep it PNG.
