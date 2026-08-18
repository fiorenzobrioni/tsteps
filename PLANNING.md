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

- [ ] `CodeBlockContainer` (blocco con bordo 1px, header-tab col nome file, collapse `▾`/`▸`)
- [ ] `CodeCanvas` (canvas del file: gutter numeri riga, indent 20px, guide verticali)
- [ ] `JsonSyntax` + `MarkdownSyntax` (tokenizzazione e colori; verificare che coprano sparkline/blocchi `▓░` come stringhe)
- [ ] `EditorTab` + `EditorNavBar` (tab file in alto, bottom bar 56dp con indicatore 2px)
- [ ] `TerminalStatusBar` (barra 28dp, divisori `|`)
- [ ] `TerminalInput` (prompt `>` con cursore `_` lampeggiante)
- [ ] `GlowFab` (FAB rettangolare col glow — qui l'icona è `▶`, non refresh)
- [ ] `TreeViewItem` se servirà (valutare: tsteps potrebbe non avere tree view)
- [ ] Import dei test Compose corrispondenti (Robolectric) e adattamento
- [ ] Nav bar: 4 destinazioni Editor / Log / Stats / Settings (glifi Material: data_object, history/commit, insights, terminal — decidere in fase)

## Fase 2 — Layer sensore e dominio

Il cuore tecnico. Nessuna UI in questa fase: tutto testabile su JVM.

- [ ] Permission `ACTIVITY_RECOGNITION` nel manifest + richiesta runtime contestuale (spiegazione nel canale `//` prima del prompt di sistema)
- [ ] `StepSensorReader`: `TYPE_STEP_COUNTER` (cumulativo da boot, batched) con flush esplicito; rilevazione assenza sensore → stato d'errore leggibile (`// E: no step sensor on this device`)
- [ ] **Anchor di continuità**: DataStore con (boot id, ultimo valore cumulativo, timestamp) per sopravvivere a riavvii e reset del contatore — il classico bug dei contapassi, con test dedicati (riavvio, riavvio doppio, valore che decresce)
- [ ] **Rollover di mezzanotte**: job WorkManager che chiude il giorno → riga Room `day_summary`, esegue il goal check, aggiorna streak/tag; test per DST e cambio timezone
- [ ] Room: tabelle `day_summary` e `session` (nessun pruning: anni di giorni pesano nulla)
- [ ] Stima distanza: passi × falcata (falcata da `height_cm` × 0.415, override manuale); stima kcal: MET camminata × peso × ore attive, **nascosta senza peso**
- [ ] Minuti attivi: finestre con cadenza significativa (soglia da tarare su device); niente doppio conteggio con le sessioni
- [ ] Aggregato orario per la sparkline (14 bucket 06→20, configurabile poi)
- [ ] `ServiceLocator` a mano (come tweather: Hilt costerebbe più di quel che rende)

## Fase 3 — Schermata principale (`steps_data.json`)

- [ ] Rendering JSON live con i componenti Fase 1: `date`, `steps.count` che ticka in tempo reale, `goal` + barra `▓░` come stringa, `movement.*`, `hourly` sparkline, `sessions` (vuoto per ora), `streak_days`
- [ ] Chiavi oneste con le unità: `distance_km` ↔ `distance_mi` al cambio unità (regola tweather)
- [ ] Canale `//`: stato sensore, hint estimate (`// estimated from stride length`), errori come messaggi da compiler
- [ ] Status bar: `⎇ main`, data, `Last commit: ieri 23:59`
- [ ] FAB `▶` presente ma disabilitato con `// coming soon` finché la Fase 6 non arriva (o nascosto — decidere)

## Fase 4 — Navigazione e Impostazioni (`settings.config`)

- [ ] Navigation Compose con le 4 route; stato workspace (tab attiva) in DataStore come tweather
- [ ] `settings.config` con i pattern di tweather: boolean flip al tap, valori che ciclano, input terminale per i numeri, hint `//` col range
- [ ] Sezioni: `[goal]` (daily_steps, 0 = check disattivo), `[profile]` (weight_kg, height_cm — opzionali, con effetto dichiarato), `[units]`, `[theme]` (3 profili runtime), `[notifications]` (placeholder per Fase 8)
- [ ] `$ git restore settings.config` con conferma two-tap
- [ ] SettingsStore DataStore + test

## Fase 5 — Storico (`steps_history.diff`)

- [ ] Vista log: oggi come "changes not yet committed" in testa, poi un commit per giorno (hash finto stabile, author `you@tsteps.app`, messaggio `N steps · X km · Y min`)
- [ ] Goal check per giorno: `✓ goal check passed (…)` / `✗ goal check failed (…)` — solo se il goal era attivo quel giorno; tono fattuale
- [ ] Espansione giorno → diff: righe `+` per le metriche; le sessioni arriveranno come hunk in Fase 6
- [ ] Separatori settimana `--- week NN ---` con totale e delta vs settimana precedente
- [ ] Tag sui record (`tag: best-day`) calcolati dal dominio
- [ ] Riuso del pattern diff di tweather (`ForecastDiff`/`SnapshotDiff` come riferimento, non import diretto: il dominio è diverso)

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
