# PLANNING.md — Piano di realizzazione tsteps

Piano di sviluppo per **tsteps**, contapassi Android (Kotlin + Jetpack Compose) con UI in stile code editor + repository git (tema "Obsidian Syntax", ereditato da tweather). Ogni passo è smarcabile: `[ ]` da fare → `[x]` completato.

Riferimenti: `VISION.md` (identità, metafora, schermate, metriche — sostituisce il vecchio `VISION-DRAFT.md`, rimosso dal repo e recuperabile dalla history), il repo gemello [tweather](https://github.com/fiorenzobrioni/tweather) come sorgente dei componenti UI e delle decisioni già validate (design system in `obsidian_syntax/DESIGN.md`, log decisioni in `PLANNING.md` di tweather).

Regola del piano (ereditata da tweather): ogni decisione e ogni deviazione dalla vision va registrata qui **con la motivazione**, nella fase in cui è stata presa.

---

## Fase 0 — Setup progetto

- [x] Repository git con remote https://github.com/fiorenzobrioni/tsteps.git, licenza GPL-3.0, `.gitignore` Android/Kotlin (copiato da tweather)
- [x] Progetto Android (scheletro Compose), package/applicationId `com.callbackdev.tsteps`
- [x] Gradle (Kotlin DSL) identico a tweather: Gradle 9.1, AGP 8.13, Kotlin 2.2.20, Compose BOM 2025.08, Material 3, minSdk 33, target/compileSdk 36, version catalog `gradle/libs.versions.toml`
- [x] Dipendenze base = quelle di tweather **meno Retrofit/OkHttp**: tsteps non ha rete (nessuna permission INTERNET — è un punto di identità, vedi VISION §3.1). Room/WorkManager/DataStore/Navigation già nel catalogo, attivate dalle fasi che le usano
- [x] Font **JetBrains Mono** (400/500/600/700) copiati in `res/font`
- [x] Tema portato da tweather in `ui/theme/` (Color, Theme, Type, Shape, Depth, SyntaxColors): 3 profili Obsidian/Dracula/Monokai, dark-only, rename `TweatherTheme` → `TstepsTheme`
- [x] Keystore debug condiviso committato `keystore/debug.keystore` (alias `tsteps-debug`, password `android`) — stessa filosofia di tweather: APK debug di CI e macchine diverse si aggiornano senza reinstallare
- [x] CI GitHub Actions `.github/workflows/android-ci.yml` (copiata da tweather, versione rivista ago 2026): test e lint **prima** delle build, artifact APK debug + release minificata debug-signed su flag + mapping R8
- [x] Icona launcher: parentesi graffe e tick colorati identici a tweather, nuvola sostituita da **due impronte di piedi a metà passo** nello stesso trattamento fill `#2B4D73` / stroke `#79C0FF`; icona status bar monocromatica `ic_stat_tsteps` già pronta per la fase notifiche
- [x] `MainActivity` minima: splash screen (brand mark), edge-to-edge con barre forzate scure (stesso fix di tweather per i telefoni in light mode), placeholder `SkeletonScreen` che disegna uno `steps_data.json` statico con gutter e colori sintassi — il primo build ha già la faccia di tsteps
- [x] Primo unit test (`ThemeProfileTest`) per esercitare la toolchain JVM; `robolectric.properties` (sdk 35, graphics NATIVE) copiato per i test Compose futuri
- [x] Verifica: `testDebugUnitTest` + `assembleDebug` + `lintDebug` + `assembleRelease -PsignReleaseWithDebugKey` verdi in locale
- [x] Primo commit e push (`3cf60b1`); CI verde sul remote al primo run (test, lint, APK debug + release minificata, mapping R8)

## Fase 1 — Editor kit (import da tweather)

I componenti riusabili di tweather sono già a tema e già testati: si importano, non si reinventano. Adattare package e togliere le dipendenze dal dominio meteo.

- [x] `CodeBlockContainer` (blocco con bordo 1px, header-tab col nome file, collapse `▾`/`▸`)
- [x] `CodeCanvas` (canvas del file: gutter numeri riga, indent 20px, `EditorOptions` incluse — line numbers e word wrap arriveranno in settings alla Fase 4)
- [x] `JsonSyntax` + `MarkdownSyntax`: verificato con un test nuovo che sparkline (`▁▂▅▇`) e barre goal (`▓░`) tokenizzano come normali stringhe JSON
- [x] `EditorTab` + `EditorNavBar` (tab file in alto, bottom bar 56dp con indicatore 2px)
- [x] `TerminalStatusBar` (barra 28dp, divisori `|`)
- [x] `TerminalInput` (prompt `>` con cursore `_` lampeggiante)
- [x] `GlowFab` — glifo di default cambiato da refresh a **`▶`** (l'unico verbo col glow di tsteps è avviare una camminata, non aggiornare)
- [x] `TreeViewItem` **non importato** (deciso): tsteps non ha né avrà una tree view; se mai servisse si riprende da tweather
- [x] Test Compose importati e adattati: `CodeCanvasTest`, `EditorTabsTest`, `JsonSyntaxTest` (+ test sparkline), `MarkdownSyntaxTest` — 25 test verdi
- [x] Nav bar: 4 destinazioni con route pulite `editor`/`log`/`stats`/`settings` (niente legacy "explorer") e glifi: `data_object` (il `{}` di steps_data.json), `commit` (il log È un git log), `insights` (stats.md), `code` (settings.config). Label IT: "Stats" resta inglese — "Statistiche" non sta nella label-sm senza troncarsi
- [x] Shell `TstepsApp` provvisoria: bottom bar + placeholder per tab (`// <file> — not yet written`), **senza** Navigation Compose — il NavHost con stack per-tab è lavoro della Fase 4; la shell serve a esercitare i componenti su device da subito

## Fase 2 — Layer sensore e dominio

Il cuore tecnico. Nessuna UI in questa fase: tutto testabile su JVM (72 test totali a fine fase).

- [x] Permission `ACTIVITY_RECOGNITION` nel manifest (unica permission dell'app — niente INTERNET, mai). **Deviazione registrata**: la richiesta runtime con spiegazione nel canale `//` è UI e slitta alla Fase 3; fino ad allora `SyncScheduler.reconcile` è un no-op senza permesso (zero job = zero batteria)
- [x] `StepSensorReader`: campiona `TYPE_STEP_COUNTER` (register → flush → un valore → unregister, mai listener permanenti in background); `isAvailable` per il canale d'errore; conversione timestamp elapsed→wall clock; `BOOT_COUNT` catturato con ogni lettura
- [x] **Anchor di continuità** (`TrackerState` in DataStore `tracker_state`, store dedicato — `git restore settings.config` non deve toccare la continuità dei passi): transizioni pure in `StepTracker.advance` con test per riavvio, doppio riavvio, contatore che decresce senza riavvio (reset HAL), orologio spostato indietro, primissima lettura (àncora senza inventare un giorno di passi)
- [x] **Attribuzione temporale** `StepAttribution`: il delta si spalma sui bucket (data, ora) locali in proporzione al tempo — un batch letto alle 00:10 accredita due date. Zone come parametro puro: DST 23h/25h e cambio timezone sono unit test, non sorprese da device. Clamp a 48h sugli intervalli enormi (spalmare una settimana sarebbe falsa precisione)
- [x] **Rollover di mezzanotte**: `RolloverWorker` one-shot auto-rischedulante su `Rollover.nextMidnightMillis` (DST-safe, testato su Europe/Rome 23h/25h) + **rete di sicurezza**: ogni `step-sync` committa i giorni finiti, così un telefono che dorme oltre mezzanotte committa alla prima lettura del mattino. Commit insert-only e idempotente: i due chiamanti possono correre liberamente
- [x] Room `TstepsDatabase` v1: `hourly_steps` (bucket orari, la materia prima del giorno vivo), `day_summary` (il commit: **scritto una volta, mai aggiornato** — distanza/kcal congelate col profilo del giorno, `goalSteps`/`goalMet` snapshot del check; il peso cambiato dopo non riscrive la storia), `session` (schema pronto, logica in Fase 6/11)
- [x] Stime in `domain/Estimates`: falcata = altezza × 0.415 (default 0.72 m dichiarato), distanza = passi × falcata, kcal = MET 3.3 × peso × ore attive, **null senza peso** (nascosta, non inventata)
- [x] Minuti attivi **derivati** dai bucket orari (`min(60, passi_ora/100)`) — **deviazione registrata**: le "finestre di cadenza" per-minuto richiederebbero un listener permanente (foreground service), vietato dalla filosofia batteria; il campionamento non le può osservare. I minuti veri arrivano con le sessioni tracciate (Fase 6). Niente doppio storage: derivato = mai incoerente
- [x] Aggregato orario per la sparkline: i bucket sono tutte le 24 ore; la finestra 06→20 è una scelta di rendering della Fase 3
- [x] Goal check (`PASSED`/`FAILED`/`SKIPPED` — senza goal il check non gira, non fallisce) e streak (`Streaks.current`/`longest`) puri, **calcolati in lettura, mai persistiti**: nessuno stato di streak da corrompere
- [x] `SettingsStore` minimale (goal default **0 = off**, niente meccaniche di colpa senza opt-in; peso/altezza null; unità; profilo tema) — la UI e le sezioni restanti sono Fase 4
- [x] `ServiceLocator` a mano (pattern tweather) con `overrideForTests`; `SyncScheduler.reconcile` chiamato da `MainActivity` (unico owner della riconciliazione job)
- [x] Test: `StepTrackerTest` (6), `StepAttributionTest` (9, incluse DST), `EstimatesTest` (5), `GoalStreaksTest` (6), `RolloverTest` (5), `TrackerStateStoreTest` (3), `SettingsStoreTest` (4), `StepRepositoryTest` (9, Robolectric + Room in-memory: ancoraggio, accumulo, mezzanotte, commit congelato/idempotente/mai-oggi, riavvio) — worker lasciati sottili apposta (colla su repository già testato)

## Fase 3 — Schermata principale (`steps_data.json`)

- [x] Rendering live con i componenti Fase 1: `date`, `steps.count` che ticka in tempo reale, `goal` + barra `▓░` come stringa (`StepsGlyphs.goalBar`: barra al massimo piena, la percentuale dice la verità oltre il 100), `movement.*`, `hourly` sparkline (`▁▂▃▄▅▆▇█`, finestra 06..20, scala relativa all'ora più attiva del giorno), `sessions: []`, `streak_days`
- [x] **Ticking live**: il listener del sensore vive esattamente quanto la subscription della UI (`channelFlow` + `WhileSubscribed`): schermo visibile = stream attivo con ingest conflated ogni 2s; schermo via = listener deregistrato in secondi. Interfaccia `StepSource` estratta dal reader per testare il flusso con letture sintetiche
- [x] Chiavi oneste con le unità: `distance_km` ↔ `distance_mi` (valore convertito E chiave rinominata, regola tweather)
- [x] Documento costruito a mano (`StepsDocument`, non `buildJsonLines`): serve ciò che il builder generico volutamente non fa — hint `//` in coda alle righe valore, comando `$` tappabile nello stato d'errore, chiavi che appaiono/spariscono col dato. Senza goal: né `goal`, né `check`, né `streak_days`. Senza peso: `// active_kcal: set profile.weight_kg to enable` al posto del numero (nascosto, non inventato)
- [x] Canale `//`: stato sensore ed errori da compiler (`// E: no step sensor on this device`); **richiesta permesso in-file** (chiude la deviazione della Fase 2): `// E: ACTIVITY_RECOGNITION permission not granted` + riga tappabile `$ tsteps grant activity-recognition` → dialog di sistema → `reconcile()` arma i job; ricontrollo del permesso a ogni resume (concessioni/revoche da impostazioni di sistema, in entrambe le direzioni). Negli stati d'errore il file mostra `"steps": null` — un null onesto, mai uno zero finto
- [x] Status bar: `⎇ main | <data> | sensor: OK/off/ERR` (off = permesso mancante, ERR = sensore assente, in rosso) a sinistra; `Last commit: <data>` a destra (localizzata, "commit" resta gergo git)
- [x] FAB `▶` **presente ma disabilitato** (deciso col committente): grigio comment, senza glow; al tap risponde con un commento transiente in testa al file (`// $ tsteps track — coming soon`, 4s) — il modo dell'editor di dire "non ancora", niente toast
- [x] Mezzanotte con app aperta: la data del working tree si ri-valuta ogni 30s (`Clock` iniettabile nei test)
- [x] Test (24 nuovi, 96 totali): `StepsGlyphsTest` (scala sparkline, rumore notturno fuori finestra, barra goal 0/50/100/128%), `StepsDocumentTest` (colori token, hint, rinomina imperiale, omissioni oneste, comando grant cliccabile), `StepsViewModelTest` (Robolectric: tick live end-to-end su Room reale, transizioni permesso, NO_SENSOR > NO_PERMISSION, streak/last-commit dalla storia), `StepsScreenTest` (Compose: rendering, grant tappabile, commento del FAB, chiave imperiale)

## Fase 4 — Navigazione e Impostazioni (`settings.config`)

- [x] Navigation Compose con le 4 route (pattern tweather: NavHost + `navigateToTab` con `saveState`/`restoreState` per stack per-tab); Log e Stats restano placeholder finché le Fasi 5 e 8 non li scrivono. **Chiarimento**: il "workspace DataStore" di tweather persiste la tab file dell'editor (JSON vs README), che qui non esiste ancora — arriva con la Fase 7, non serviva ora
- [x] `settings.config` nel formato serie (corpo JSON con commenti `//`, come tweather — la bozza INI a sezioni della VISION è superata: componenti riusati e coerenza di serie valgono più della variazione): boolean flip al tap, `units.system` e `theme.active_profile` ciclano, `available_profiles` con `// active` e attivazione diretta al tap
- [x] **Input terminale per i numeri liberi** (goal, peso, altezza): al tap la riga valore diventa un prompt (`TerminalInput` con focus automatico) con `[esc]` per annullare; Done valida con range espliciti (`SettingsRanges`: goal 0..100000, peso 20..300, altezza 100..250), errore transiente `// ERROR: expected …` in rosso; **submit vuoto = clear** (stato di prima classe: è ciò che nasconde le kcal e torna alla falcata 0.72 m); i valori assenti si leggono `null` JSON in grigio comment
- [x] Sezioni: `editor` (line_numbers/word_wrap → `LocalEditorOptions` per tutti i CodeCanvas via shell), `goal`, `profile`, `units`, `theme`, `notifications` (placeholder onesto `// nothing to configure yet`), `about` (versione, licenza e credits tappabili verso i siti)
- [x] `// Last modified:` timestamp ISO che appare alla prima modifica (pattern tweather); `$ git restore settings.config` con conferma two-tap (arma per 4s, hint rosso) — **non tocca l'anchor del contapassi**: il reset della config non deve mai perdere la continuità dei passi
- [x] `SettingsStore` esteso (sezione editor, stamp last-modified su ogni edit, `resetToDefaults`) + tema runtime in `MainActivity` (il profilo cambia live da settings, pattern tweather)
- [x] Test (21 nuovi, 117 totali): `SettingsInputTest` (parse puro: vuoto=clear, virgola decimale, range, interi obbligatori), `SettingsStoreTest` esteso (editor, stamping, reset totale), `SettingsScreenTest` (flip, cicli, input end-to-end con errore/esc/clear, reset two-tap, placeholder), `TstepsNavigationTest` (shell reale con WorkManager di test)

## Fase 5 — Storico (`steps_history.diff`)

- [x] Vista log: oggi in testa come `# On branch main / # Changes not yet committed (today)` con il riassunto del working tree (`#   8,432 steps · 6.1 km · 74 min`), poi un commit per giorno: hash finto **stabile e deterministico** (`CommitHash`: SHA-1 di "tsteps:<data>" troncato a 7 hex — stessa data, stesso hash, su ogni device per sempre), `Author: you@tsteps.app`, `Date:` con giorno localizzato, messaggio `N steps · X km · Y min · Z kcal` (numeri formattati per locale, kcal solo se c'era il peso)
- [x] Goal check per giorno, fattuale e coi numeri: `✓ goal check passed (11,204 ≥ 10,000)` verde / `✗ goal check failed (…)` rosso — **assente** se quel giorno il goal non era attivo (snapshot `goalSteps`/`goalMet` del commit, mai ricalcolato col goal di oggi)
- [x] Espansione al tap sull'header → diff del giorno: `--- a/steps_data.json +++ b/steps_data.json`, hunk header `@@ <data> @@` in key-blue, metriche come righe `+` verdi con tinta di sfondo e gutter colorato (stile identico ai diff di tweather); le chiavi rispettano le unità (`distance_km` ↔ `distance_mi`). Le sessioni arriveranno come hunk `@@ hh:mm..hh:mm @@` in Fase 6
- [x] Separatori settimana ISO: `--- week 34 · 52,340 steps (+2,340 vs week 33) ---`, delta colorato diff-add/del — la settimana come diff; totali dai soli giorni committati (oggi sta nella sezione uncommitted, non nei totali)
- [x] Tag sui record: `(tag: best-day)` in arancio (il giallo-tag di git nella nostra palette) sul commit col massimo di passi, pareggi al più recente (`Records.bestDay`, calcolato in lettura — `longest-walk` arriverà con le sessioni)
- [x] Status bar: `⎇ main | N commits` a sinistra, `HEAD → <hash>` a destra (`none` senza commit); empty state onesto `// no commits yet — the first day commits at midnight`
- [x] Pattern diff di tweather riusato come stile (colori, tinte, gutter), non come codice: il dominio è diverso, confermato
- [x] Test (22 nuovi, 139 totali): `CommitHashTest` (stabilità, formato, unicità), `RecordsTest`, `LogDocumentTest` (uncommitted, commit completo, check nei tre stati, espansione con chiavi imperiali, separatori con delta ±, tag, toggle), `LogViewModelTest` (Room reale: stato completo, best-day, toggle), `LogScreenTest` (rendering, tap sull'header, diff espanso, status bar)

## Fase 6 — Sessioni manuali (`$ tsteps track`)

- [ ] FAB `▶` → schermata processo: transcript che appende una riga al minuto (tempo, passi, km, velocità), `^C` stop con conferma, `^Z`/`fg` pausa/riprendi
- [ ] Foreground service **solo durante il tracking** (unica eccezione alla regola no-servizi), notifica = la riga di comando del processo
- [ ] Persistenza `session` in Room; la sessione chiusa appare come hunk `@@ HH:MM–HH:MM @@ walk` nel diff di oggi e nell'array `sessions` del JSON
- [ ] Dettaglio sessione: durata, passi, distanza, velocità media **o** passo medio (preferenza in settings), cadenza media
- [ ] Tipi: `walk` e `other` — stop (running/cycling parcheggiati, VISION §6.7)
- [ ] Niente doppio conteggio: i passi della sessione sono un sottoinsieme del totale giornaliero, mai una somma

## Fase 7 — README.md del giorno (seconda tab dell'editor)

- [ ] Tab `README.md` accanto a `steps_data.json` (workspace DataStore per la tab attiva, pattern tweather Fase 10)
- [ ] Markdown source evidenziato, **completamente localizzato** (è prosa): `## Today`, `## Status` (goal, streak), `## Week` (tabella compatta)
- [ ] Alimentato stateless dal dominio

## Fase 8 — Stats (`stats.md`)

- [ ] Heatmap contributi ultime 12 settimane: glifi `□ ▪ ■` su 4–5 intensità di verde, bucket **relativi alla distribuzione dell'utente** (non al 10k assoluto)
- [ ] Streak corrente e massima (streak = giorni consecutivi col check verde; senza goal, sezione assente — niente colpevolizzazione non richiesta)
- [ ] Medie 7/30 giorni (passi, distanza, minuti attivi) in tabella markdown
- [ ] Tabella `tags` (best-day, longest-walk, best-week) con link al commit (tap → log posizionato sul giorno)

## Fase 9 — Notifiche

- [ ] Permission `POST_NOTIFICATIONS` runtime (path unico, minSdk 33) + `ic_stat_tsteps` (già in repo)
- [ ] `daily_commit`: al rollover di mezzanotte (o prima apertura successiva), corpo in stile terminale: il messaggio di commit del giorno chiuso
- [ ] `goal_check`: una sola notifica al passaggio del check, mai ripetuta (edge-triggered come le regole di tweather)
- [ ] Tutte disattivabili singolarmente in `settings.config`; nessuna notifica motivazionale/promozionale, mai
- [ ] Valutare (non promesso): "Weather CI" di tweather come modello per regole utente future — solo se emergerà una domanda reale

## Fase 10 — Widget home screen (`tsteps --today`)

Si eredita l'architettura completa del widget di tweather (la fase più rifinita di quel progetto — riusare anche le lezioni: breakpoint misurati non stimati, un gradino per riga, niente glifi decorativi che rubano spazio, testo 15sp).

- [ ] RemoteViews con sizes-map API 31+, tier a righe: passi → barra goal → dist/attivi → streak → `# last walk`
- [ ] Font `monospace` di sistema (stessa deviazione documentata di tweather, CVE-2021-0567)
- [ ] Opacità sfondo configurabile, palette dal profilo tema attivo
- [ ] Repaint su lettura passi, rollover e cambi tema/unità/goal; nessun polling proprio
- [ ] Stato degradato: `# sensor off` se la pipeline sensore è ferma (permission revocata / sensore assente) — mai un numero congelato spacciato per vivo
- [ ] Preview del picker + `widget_info` con `updatePeriodMillis=0`

## Fase 11 — Rilevamento automatico sessioni

Volutamente tardi: prima il modello sessione deve essere validato dal tracking manuale (VISION §6.3).

- [ ] Rilevazione conservativa di camminate continue dai delta del contatore (finestre di cadenza sostenuta ≥ N minuti); i falsi positivi sono peggio delle camminate perse
- [ ] Le sessioni auto appaiono come hunk con marcatore (`@@ … @@ walk (auto)`)
- [ ] Modifica/eliminazione sessione (`[rm]` con conferma; edit dei bordi orari come input terminale)
- [ ] Taratura su device reale prima di dichiararla finita

## Fase 12 — Health Connect

- [ ] Write: passi giornalieri + sessioni; Read: passi da altre sorgenti con **dedup per origine** (mai doppio conteggio, VISION §7)
- [ ] Permessi HC spiegati in chiaro prima della richiesta
- [ ] Nessun data type oltre quelli delle metriche esistenti: HC è interoperabilità, non espansione di scope

## Fase 13 — Export dati

- [ ] `$ tsteps export` in settings: JSON (giorni + sessioni) e CSV in Downloads via MediaStore — nessuna permission storage necessaria
- [ ] Formato documentato nel README (i dati sono dell'utente)

## Fase 14 — Restyling pre-v1 e Release

- [ ] Passata di coerenza design su tutte le schermate (l'equivalente della Fase 11b di tweather): niente deriva dai vincoli, spazi, scala font di sistema, TalkBack
- [ ] Review batteria completa su device (profilare i flush del sensore)
- [ ] Edge case checklist verificata uno a uno: sensore assente o temporaneamente indisponibile, permission negate/revocate, riavvio (singolo e doppio), cambio timezone e DST, giorno senza dati, dati duplicati, sessioni incomplete (crash/kill durante il tracking), profilo incompleto, battery saver
- [ ] Keystore di release reale (sostituisce il flag debug-sign), versioning, store listing
- [ ] Screenshot per il README (`docs/screenshots/`)

---

## Note trasversali

- **Vincoli di design non negoziabili** (vedi `CLAUDE.md` e VISION §1.2): solo JetBrains Mono (eccetto widget), griglia 4px, indent 20px, niente ombre (bordi 1px + glow del FAB), raggio 4px ovunque, controlli renderizzati come testo, emoji come icone nel testo.
- **Regola l10n**: il "codice" resta inglese (chiavi JSON, filenames, `//` comments, output terminale, hash, check CI); chrome e valori-dato localizzati IT/EN. `README.md` (la tab) è prosa: localizzata per intero.
- **Niente rete**: se una feature futura chiede la permission INTERNET, non è una feature di tsteps.
- **Ordine**: Fasi 1–2 sono il fondamento; la 2 può procedere in parallelo alla 1. Le fasi 3–5 dipendono da 1–2. La 6 sblocca la 11. Widget (10) dopo che dominio e settings sono stabili.
- **Import da tweather**: i componenti si copiano adattando il package, mai linkando il repo; ogni divergenza che emerge (bug fixati qui, migliorie) va valutata per il backport a tweather.
- Aggiornare questo file smarcando i passi completati e annotando le deviazioni dalla VISION con la motivazione.
