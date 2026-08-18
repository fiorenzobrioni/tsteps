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

- [x] FAB `▶` (attivo, col glow, solo con sensore+permesso OK — negli stati d'errore niente FAB, spiega il documento) → avvia il service e apre la **schermata processo**: transcript con una riga per minuto attivo (`05:00  512 steps  0.4 km`), pausa/riprendi come `^Z`/`fg` del terminale (marcati nel transcript), `^C` con conferma two-tap (status line rossa `// tap ^C again to stop`), riga live `24:18  2,431 steps · 1.8 km · 4.3 km/h` con tick a 1s; il tipo (`walk`/`other`) è il token editabile della riga comando, cicla al tap
- [x] **Foreground service solo durante il tracking** (`TrackingService`, type `health`, l'unica eccezione sanzionata alla regola no-servizi): tiene il listener del sensore vivo a schermo spento, alimenta il `TrackingManager` (singleton, stato osservato dalla UI) e **ingesta le stesse letture nella pipeline giornaliera** — uno stream, due viste, niente doppio conteggio per costruzione. Notifica = la riga di comando del processo (titolo `$ tsteps track walk`, corpo = riga live, canale IMPORTANCE_LOW silenzioso); permessi `FOREGROUND_SERVICE_HEALTH` + `POST_NOTIFICATIONS` dichiarati (la **richiesta** runtime della notifica resta in Fase 9: senza grant il transcript non va nella tendina, il service gira comunque — gate esplicito su `notify()`)
- [x] Dominio puro `LiveSessionTracker`: passi in pausa scartati (appartengono al giorno, non alla sessione), reboot mid-session gestito con la regola dello StepTracker, durata attiva = wall time meno pause (anche quella in corso); `SessionMetrics`: velocità/passo/cadenza **null sotto soglia** (30s / 50m — nascosto, non inventato)
- [x] Persistenza: **Room v2** con migrazione 1→2 (`session.activeMillis`, test in stile tweather: DB v1 costruito a mano con la DDL esatta, aperto con Room v2 — le righe del device del committente sopravvivono); a `^C` la sessione si scrive una volta con cadenza media e distanza dalla falcata **snapshot all'avvio** (il profilo di quel momento, onestà del commit)
- [x] La sessione chiusa appare: come **hunk `@@ 09:32..10:18 @@ walk`** con riga `+` verde nella sezione uncommitted del log (e nei giorni committati espansi), e nell'array `sessions` di `steps_data.json` come oggetto inline che **si espande in place** al tap nel dettaglio completo
- [x] Dettaglio sessione in-file: start/end/type/active_min/steps/distanza + **velocità media O passo medio** (`units.session_metric = "speed" | "pace"` in settings.config, cicla al tap; chiavi oneste `avg_speed_kmh`↔`avg_speed_mph`, `avg_pace_min_km`↔`avg_pace_min_mi`) + cadenza media
- [x] Tipi: `walk` e `other` — confermato stop a due (running/cycling parcheggiati, VISION §6.7)
- [x] Niente doppio conteggio: strutturale — i passi sessione sono una finestra etichettata sullo stesso contatore cumulativo del giorno, mai sommati a nulla
- [x] `UnitFormat` condiviso (km/mi, velocità, pace label, orari) per i renderer nuovi
- [x] **Rifiniture da feedback su device (committente):** i controlli nudi `[ ^Z ]`/`[ ^C ]` non si spiegavano da soli — ora portano **glifo E parola** (`[ ^Z pause ]` ↔ `[ fg resume ]`, `[ ^C stop ]`) su target da FAB (56dp, bordo 1px, raggio 4px); lo stop è il verbo primario (più largo) e **armato prende l'unico glow della schermata**, rosso diff-delete. Aggiunta la **top bar** che mancava: la stessa strip delle altre schermate ma con etichetta da terminale `$ tsteps track` (VS Code mostra i terminali integrati come tab; il `$` la marca come tab di processo, non file) — la schermata senza chrome superiore sembrava di un'altra app
- [x] Test (33 nuovi, 172 totali): `LiveSessionTrackerTest` (pause, reboot, durate, idempotenza), `SessionMetricsTest` (soglie null-over-invented, pace per km e per miglio), `TrackingManagerTest` (start→stop persistito con stride snapshot, pause escluse, transcript, doppio start no-op), `TrackDocumentTest` (buffer completo, ^Z/fg colorati, riga live speed/pace, conferma ^C), `TstepsDatabaseMigrationTest`, più estensioni a StepsDocument/LogDocument/StepsScreen test (sessioni inline/espanse, hunk, FAB nuovo) e `TrackScreenTest` (tab, controlli, two-tap)

## Fase 7 — README.md del giorno (seconda tab dell'editor)

Fatta dopo la Fase 8, su scelta del committente.

- [x] Tab `README.md` accanto a `steps_data.json`: `WorkspaceStore` portato da tweather (DataStore `workspace` dedicato — è stato dell'editor, non una chiave di settings: `git restore settings.config` non deve chiudere la tab), selezione persistita, scroll separato per file
- [x] `StepsReadme`: il giorno in prosa, **completamente localizzato** (titoli inclusi — è prosa, la regola keys-stay-English non si applica). Titolo = data estesa localizzata (`# Martedì 18 agosto 2026`); `## Oggi` con i passi in grassetto e le kcal solo col peso; `## Stato` = il build badge del giorno: progresso goal in parole neutre (`8.432 passi su 10.000 · ne mancano 1.568` — mai colpevolizzazione), `✓` al raggiungimento, streak, e i problemi sensore come blockquote `>` di warning; `## Camminate` (solo se ci sono sessioni); `## Settimana` con gli ultimi 7 giorni — **oggi in grassetto e vivo dal working tree**, i giorni non tracciati come `—` (dato mancante, non zero); footer corsivo `*Calcolato sul dispositivo · N giorni committati*`
- [x] **Tabelle incolonnate** con la convenzione fresca di tweather (Fase 11c, richiesta esplicita del committente): `MarkdownTable` portato tal quale — colonne paddate alla cella più larga, marker `---:` veri sulle colonne numeriche, emoji (se mai serviranno) sul bordo destro della cella. **Refactor anche di stats.md**: le tabelle averages e tags ora passano dallo stesso `markdownTable`, un'unica convenzione in tutta l'app
- [x] Alimentato stateless dal dominio: `StepsUiState` estesa con `history` (giorni committati) per la tabella settimana e il footer
- [x] Test (15 nuovi, 212 totali): `WorkspaceStoreTest` (portato), `StepsReadmeTest` (titolo localizzato, prosa con grassetti, kcal assente senza peso, stato nei quattro casi goal/no-goal/no-permission/no-sensor, tabella camminate rettangolare con `---:`, settimana a 7 righe con oggi in grassetto e `—` sui buchi, footer), `StepsScreenTest` esteso (due tab, switch, README renderizzato)

## Fase 8 — Stats (`stats.md`)

Fatta prima della Fase 7 su scelta del committente (le due fasi sono indipendenti).

- [x] **Heatmap contributi ultime 12 settimane** (`domain/Heatmap` puro): griglia ISO Monday-first che termina con la settimana di oggi, 7 righe × 12 colonne di celle `■` in 4 intensità di verde (alpha 0.30/0.55/0.78/1.0), `·` per i giorni a zero, **vuoto** per i giorni non ancora accaduti (non zeri finti). Bucket **relativi alla distribuzione dell'utente**: quartili dei soli giorni non-zero della finestra (upper-bound: un solo giorno attivo è il massimo di quell'utente, non un puntino spento; i mostri fuori finestra non distorcono la scala). **Oggi entra vivo nella griglia** come cella del working tree. Etichette giorno lun/mer/ven/dom localizzate, riga mesi sotto le colonne che ne iniziano uno
- [x] Streak corrente e massima (`## streak`, da `Streaks` sui check committati) — **sezione assente senza goal**, non azzerata: niente colpevolizzazione non richiesta
- [x] Medie 7/30 giorni (`domain/Averages`): media sui **giorni che hanno un commit** — un giorno non tracciato è dato mancante, non uno zero che annacqua la media; finestra vuota = niente riga. Tabella markdown allineata (il mono rende l'allineamento gratis), distanza nelle unità correnti
- [x] Tabella `## tags`: `best-day`, `longest-walk` (nuovo `Records.longestWalk`, max minuti attivi) e `best-week` (nuovo `Records.bestWeek`, somma per settimana ISO) — **le righe best-day e longest-walk sono link al commit**: tap → tab Log col giorno espanso e il commit scrollato in vista (`LogFocus`, canale hand-rolled: la back-stack entry del Log viene ripristinata, non ricreata, quindi un nav-argument non la raggiungerebbe — stessa ragione del ⎇ di tweather). best-week non è cliccabile: non ha un singolo commit
- [x] Rendering: markdown source evidenziato come ogni file della serie (`buildMarkdownLines` per headings/tabelle/footer corsivo), heatmap colorata a mano (un tokenizer markdown non ha nozione di intensità); footer onesto `*computed on read from N committed days*`; status bar `⎇ main | ro` (un file di statistiche si calcola, non si edita) + `N days`
- [x] Empty state onesto: griglia comunque visibile (con la cella di oggi viva), poi `// nothing committed yet — records and averages appear with the first day's commit`
- [x] Test (25 nuovi, 197 totali): `HeatmapTest` (geometria, futuri vuoti, quartili relativi, giorno solitario = max, finestra che ignora i mostri antichi, etichette mesi), `AveragesTest` (media sui giorni con dato, bordo finestra, null su vuoto), `RecordsTest` esteso (longest-walk, best-week ISO), `StatsDocumentTest` (documento riga per riga: heatmap 7 righe + mesi, cella verde a intensità giusta, streak solo con goal, tabelle, tag cliccabili coi commit giusti, imperiale, empty state, footer), `StatsViewModelTest` (Room reale: griglia con oggi vivo, record, medie, streak on/off), `StatsScreenTest`, `LogViewModelTest` esteso (jump da stats: giorno espanso + focus consumato)

## Fase 9 — Notifiche

- [x] Permission `POST_NOTIFICATIONS` runtime col pattern completo di tweather nella sezione `notifications` di settings.config: riga di stato dinamica (`// rides the midnight rollover and the step sync` armata / `// notifications disabled` / errori rossi tappabili per permesso mancante o negato-per-sempre → dialog o detour alle impostazioni di sistema con ritorno gestito al resume), toggle **gated** (accendere senza permesso chiede prima il permesso e applica il toggle solo al grant, pending azzerato su ogni altro ritorno). Il grant rende visibile anche la notifica del tracking (Fase 6, già gated su `notify()`)
- [x] `daily_commit` (default on): il messaggio di commit del giorno chiuso, postato **da chi committa** — rollover di mezzanotte o safety-net del primo sync del mattino (`commitDaysBefore` ora ritorna i giorni committati DA QUEL passaggio: il no-op non notifica). Titolo = chrome localizzato (`👣 Giorno committato — 11.204 passi`), corpo = output terminale inglese (hash, riga metriche, check line colata dal commit). Canale IMPORTANCE_LOW **silenzioso**: spesso posta a mezzanotte, e un riepilogo che sveglia è esattamente il rumore che la VISION vieta. Backlog multi-giorno (telefono spento una settimana) → solo il giorno più recente: un riepilogo, non sette
- [x] `goal_check` (default on): **edge-triggered, una volta al giorno** — `GoalWatcher` valuta nei due punti dove i passi arrivano in background (sync periodico e minute-tick del tracking service; mai dal foreground: l'utente sta guardando il numero), dedup per data in un DataStore dedicato `notif_state` (stato macchina, non settings: `git restore` non ri-spara nulla), marcato PRIMA del post (un crash costa una notifica, mai un doppione), si riarma a mezzanotte col cambio data. Corpo: check line + streak (se >1) + `$ tsteps log --today`. Canale IMPORTANCE_DEFAULT: un evento al giorno vale un ping
- [x] Tutte disattivabili singolarmente; canali separati così le impostazioni di sistema possono zittirne uno senza l'altro; nessuna notifica motivazionale/promozionale, mai
- [x] "Weather CI" come modello per regole utente: valutato e **rinviato** — con due soli eventi possibili (commit e check) un motore di regole sarebbe struttura senza domanda; si riapre solo se emergerà un bisogno reale
- [x] Test (10 nuovi, 222 totali): `StepsNotificationsTest` (contenuti puri: titolo localizzato, corpo terminale con hash/check, omissioni oneste, streak solo >1), `GoalWatcherTest` (attraversamento → un post solo, ri-valutazione silenziosa, sotto soglia armato, goal 0/toggle off inerti), `NotificationStateStoreTest`, `StepRepositoryTest` esteso (ritorno solo-nuovi-commit), `SettingsScreenTest` esteso (toggle notifiche, riga di stato armata, errore rosso tappabile)

## Fase 10 — Widget home screen (`tsteps --today`)

Architettura del widget di tweather ereditata per intero, lezioni comprese — e il piano l'aveva promesso: breakpoint misurati, un gradino per riga, niente glifi che rubano spazio, testo a 15sp.

- [x] **Content builder puro** (`WidgetContentBuilder`): transcript ordinato per utilità — `steps: 8,432 / 10,000`, `check: ▓▓▓▓▓▓▓▓░░ 84%` (barra a 10 celle in verde-prompt, "emerald = active states" del DESIGN), `dist`, `active`, `kcal` (solo col peso), `streak` (solo con goal e >0), `# last walk: 09:32 (46 min)`, `# last_sync: HH:mm` — il tier taglia dal fondo, mai riordina. Prompt `you@tsteps:~$ cat steps_data.json`; emoji 👣 costante (è il brand, non un dato). Stati onesti: `# sensor off — open tsteps` in rosso (permesso revocato/sensore assente), `# no data yet` prima del primo campione — mai zeri finti
- [x] **Marcatore stale** (>45 min dall'ultimo campione = 3 periodi di sync, tolleranza Doze): la riga `# last_sync` diventa rossa; sui tier troppo corti per averla, `# stale` cavalca la riga steps (la regola Temp-line di tweather). Il sync worker **ridisegna anche quando il campione fallisce** — senza, il marcatore non comparirebbe proprio nello scenario che deve segnalare
- [x] **Renderer** con sizes-map API 31+ (l'host risceglie il tier a ogni resize, senza round-trip), un gradino per riga 4→8, colori per token via `ForegroundColorSpan` (ParcelableSpan, sopravvive all'IPC). **La scala si ferma a 8**: il transcript massimo di tsteps ha 8 righe, e il test misurato ha bocciato subito i pioli 9–11 ereditati da tweather (11 righe lì) — un gradino che niente può riempire promette solo altezza sprecata. Costanti misurate, non stimate: il test binary-search sull'altezza minima reale di ogni gradino (con margine deliberato ~2dp/riga per il monospace OEM) resta di guardia
- [x] Tier SMALL (striscia glanceable): 👣 + conteggio grande + **la barra goal come label** (progresso a colpo d'occhio; senza goal, `steps today` in plain — il grigio comment non regge il contrasto su Dracula/Monokai, lezione tweather)
- [x] Layout portati (FrameLayout + fill/border ImageView, title bar LinearLayout coi due box da 48dp larghi ma alti quanto la barra, `monospace` di sistema per CVE-2021-0567 — deviazione documentata ereditata); drawable con Obsidian *nel* drawable (l'initialLayout dell'host non passa dal bind) e il `<solid>` trasparente load-bearing nel bordo (il bug GradientDrawable di tweather, tenuto fixato e **testato sui pixel**: fill al 50% davvero semitrasparente, cornice cava ma presente e nel colore tema)
- [x] Opacità sfondo `widget.bg_opacity_pct` in settings.config (100/85/70/50 ciclabili, solo sul fill, bordo sempre opaco)
- [x] **Niente polling** (`updatePeriodMillis=0`): ridisegni da sync worker, rollover, minute-tick del tracking (una camminata a schermo spento è quando il widget serve di più), collector settings di MainActivity (tema/unità/goal/profilo/opacità), onUpdate del provider; ↻ = un campione expedited (`step-sync-manual`, KEEP anti tap-spam). Provider con goAsync **nullable** (il crash di tweather, tenuto fixato) e resize deliberatamente non gestito (la sizes-map esiste apposta)
- [x] **Niente configurazione**: tsteps ha una sola sorgente dati — il giorno — quindi niente da pinnare, niente WidgetConfigActivity, niente stores per-instance (più semplice del genitore, di proposito); preview del picker statica con colori Obsidian hardcoded
- [x] **Rifinitura da feedback su device (committente):** i nomi dei campi del transcript passano alla **maiuscola iniziale** (`Steps:`, `Check:`, `Dist:`…) come nel widget di tweather — i due widget convivono sulla stessa home e devono leggersi come fratelli; il minuscolo aveva senso per le chiavi JSON dentro l'app, ma il widget è la finestra di terminale, non il file. I commenti `#` restano minuscoli (sono commenti)
- [x] Test (20 nuovi, 242 totali): `WidgetContentBuilderTest` (transcript completo e ordinato, budget che taglia dal fondo, omissioni oneste, stale su entrambe le vie, sensor-off/no-data, small tier, imperiale), `WidgetRendererTest` (bind reale via `apply`, colori token attraverso l'IPC, slot nascosti, **pixel test** dei due layer di sfondo, **ogni tier sta nel proprio breakpoint**, **binary search sui gradini** — il test che ha già pagato: ha trovato i 3 pioli morti)

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
