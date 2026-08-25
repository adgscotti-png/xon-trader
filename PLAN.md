# XON TRADER — alternativa FOSS a TabTrader

**Piano di progetto — 2026-08-22 — claude · NOME APPROVATO da Andrea 22/08/2026**
`WORKING-ON: 0.3.2 RELEASED (tag v0.3.2, asset APK verificato). ORA: Google Play prep (account richiesto da Andrea 25/08): bump compileSdk/targetSdk 36 + bundleRelease AAB + test AVD xontest36 + feature graphic 1024×500; listing già pronto in docs/play-listing.md`

---

## 1. Cosa è TabTrader (ricerca 22/08/2026)

TabTrader (Tabtrader B.V., Amsterdam, package `com.tabtrader.android`) è un terminale
crypto multi-exchange per Android/iOS/Web, v7.6.3 attuale.

**Stato reale del progetto** (verificato online oggi):
- Acquisito il **21/02/2025** da Trader Acquisition Corp. (gruppo Echo Base Holdings).
- La stampa lo dà **ancora attivo** (aggiornamenti app fino ad aprile 2026), MA:
  - gli exchange supportati sono scesi da **20+ a 6** nell'ultima build ("6 exchanges, pro charts, alerts, wallet" è il nuovo claim);
  - le funzioni chiave sono dietro abbonamento **Pro / ULTRA**;
  - il sito tabtrader.com risulta **irraggiungibile** dal nostro ambiente e la scheda
    Google Play risponde **404** (possibile delisting o blocco regionale — da verificare
    con un telefono reale).
- Conclusione: l'impressione di "progetto abbandonato" della community è fondata anche
  se non c'è uno shut-down formale annunciato. Il vuoto da riempire è reale.

### Funzionalità NON-trading da replicare (scope di riferimento)
| Funzione | Dettaglio TabTrader | Nel nostro MVP |
|---|---|---|
| Watchlist + screener | liste multiple, ticker card live, sparkline | ✅ F1 |
| Grafici | candele, 30+ indicatori, Heikin Ashi, disegni Fibonacci | ✅ base (candele/area/linea, MA/EMA/BB/RSI/MACD/volume) — disegni post-MVP |
| Alert prezzo | illimitati, persistenza, note, colori, alert tecnici (RSI/MACD/EMA) | ✅ prezzo sopra/sotto + % variazione; alert tecnici post-MVP |
| Widget home screen | Android+iOS, scelta strumento/exchange | ✅ 4 formati (vedi §4) |
| Portfolio | sync account exchange multipli | ⏳ read-only con chiave Binance propria (post-MVP); holdings manuali |
| News | feed aggregato | ⏳ RSS post-MVP |
| Sicurezza app | PIN/biometria/2FA | ✅ biometria/PIN locale |
| Wallet Solana, trading, arbitraggio | — | ❌ fuori scope (come richiesto) |

Screenshot reali salvati in `research/shot1-6.jpg`: tema navy scuro, watchlist con
icona+sparkline+prezzo+% change, bottom nav a 3 tab, wallet (che NOI NON facciamo).

---

## 2. Fattibilità: SÌ, e più semplice del previsto

**Punto chiave che cambia tutto:** i dati di mercato Binance sono **PUBBLICI**.
- REST `https://api.binance.com/api/v3/...` (ticker24h, klines, exchangeInfo, depth)
  e mirror ufficiale solo-market-data `https://data-api.binance.vision`
- WebSocket pubblico `wss://stream.binance.com:9443/stream?streams=btcusdt@miniTicker/kline_1m/...`
  (mirror: `wss://data-stream.binance.vision`)
- **Nessuna API key richiesta** per prezzi, grafici, ticker → widget e alert funzionano
  senza alcuna configurazione per l'utente.

La chiave API Binance (quella dell'utente, come volevi) diventa quindi **opzionale** e
serve solo a:
1. **Portfolio reale**: saldo account via endpoint `/api/v3/account` (consigliata chiave
   READ-ONLY, mai permessi withdrawal) → sync automatico dei possedimenti;
2. limiti rate più alti (irrilevante per noi: 6000 weight/min per IP sono abbondanti);
3. funzioni future senza nuovo onboarding.

Architettura risultante: **ZERO backend**. L'app parla direttamente con Binance.
Niente server da pagare, niente FCM obbligatorio, nessun dato utente altrove,
la chiave non lascia mai il telefono. Costo di esercizio: 0 €, per sempre.

### Unico trade-off onesto: gli alert "in tempo reale"
Senza server push, l'allarme istantaneo richiede un processo locale:
- **Modalità Realtime** (default): foreground service con WebSocket → notifica in ~1s
  dal tick. Costo: notifica persistente discreta (standard Android) + batteria minima.
- **Modalità Risparmio**: WorkManager polling ogni 15 min (limite Android) → alert
  garantiti entro 15 min, zero notifica persistente.
L'app rileva kill OEM (Xiaomi/Huawei/ecc.) e mostra guida whitelist per marca
(dontkillmyapp). Questo è MIGLIORE di TabTrader, che ha lo stesso problema ma non lo
spiega (il loro helpcenter dice solo "metti la batteria in priorità").

---

## 3. Architettura & stack (best practice 2026)

**Principi:** offline-first (Room cache), un solo flusso dati reattivo (Flow),
modularizzazione Gradle, zero dipendenze proprietarie → idoneo F-Droid.

```
build-logic/            convention plugins
:app                    shell, navigation, DI wiring
:core:model             entità pure
:core:common            dispatcher, result, formattazione numeri
:core:network           Retrofit+kotlinx.serialization, WS manager, binance.vision default
:core:database          Room (klines cache, watchlist, alert rules)
:core:datastore         DataStore prefs + Tink AEAD su Keystore (chiave API)
:core:charts            renderer Canvas Compose riusabile + sparkline→Bitmap (widget)
:core:notifications     canali, builder azioni rapide
:feature:onboarding     benvenuto, permessi notifiche, (opz.) import chiave
:feature:markets        mercati, ricerca, top gainers/losers
:feature:coin           dettaglio + grafico interattivo + crea alert
:feature:alerts         lista/gestione alert
:feature:widgets        Glance widgets + config activity
:feature:settings       valuta, tema, lingua, export/import, batteria-OEM
:feature:portfolio      (post-MVP) manuale + sync read-only
```

- **Kotlin 2.x**, coroutines+Flow, **Compose + Material 3** (dynamic color Android 12+, dark AMOLED)
- **Widget: Glance 1.x** (genera AppWidget RemoteViews nativi)
- **DI: Hilt** · **Rete: OkHttp/Retrofit + kotlinx.serialization** · **WS custom manager** (riconnessione esponenziale+jitter, ping/pong, riconnessione forzata 24h Binance)
- **Grafico: renderer custom su Canvas Compose** (pinch-zoom, pan, crosshair, decimazione per 5k+ candele a 60fps). MPAndroidChart è datato; Vico non fa candele. Custom = migliore dell'originale ed è riutilizzato per le sparkline bitmap nei widget.
- **Foreground service tipo `dataSync`** (dichiarazione obbligatoria Android 14+) + WorkManager fallback
- **Chiave API**: mai in chiaro — AEAD (Tink) con chiave in **Android Keystore**; header `X-MBX-APIKEY`; schermata mostra i permessi richiesti (solo lettura); pulsante "revoca su Binance" con deep-link
- **Min SDK 26** (≈98% dispositivi), target SDK corrente Play (36 nel 2026)
- **Licenza consigliata: GPL-3.0** (impedisce fork proprietari: garanzia di continuità che la community chiede dopo l'abbandono)

### Qualità & CI (GitHub Actions)
ktlint + detekt + test unitari (JUnit5/Turbine/MockK) + Paparazzi screenshot test +
Gradle Managed Devices (emulatori API 26/30/35) + build R8 firmata da secrets +
release APK su GitHub Releases (compatibile Obtainium) + Play closed testing.
Zero telemetria; crash report opzionale ACRA self-hosted.

---

## 4. Le due killer feature in dettaglio

### Widget home (Glance)
| Formato | Contenuto |
|---|---|
| 1×1 | prezzo + % change di 1 pair |
| 2×1 | prezzo grande + sparkline |
| 2×2 | prezzo + mini-grafico interattivo-ish + high/low 24h |
| 4×2 | lista watchlist (4-6 righe) con sparkline |

- Refresh configurabile per widget: 15min (WorkManager) oppure realtime se servizio attivo (throttle 60s/widget per batteria)
- Tema: sistema / chiaro / scuro / trasparente; click → deep link alla coin
- Sparkline renderizzata da `:core:charts` su Bitmap condiviso

### Alert engine
- Regole in Room: `above/below`, `% change 24h`, una-tantum o ripetibile (con cooldown anti-spam 60s/regola), note+colore (come TabTrader)
- Valutazione sul flusso WS quando Realtime; fallback poll WorkManager altrimenti
- Notifica high-importance con azioni: **Disattiva · Posticipa 1h · Apri grafico**
- Recupero stato al boot (allarmi già scattati durante il fermo)
- Canali: Prezzi / Sistema / Test; permesso POST_NOTIFICATIONS richiesto in onboarding (API 33+)

---

## 5. Toolchain da installare (ambiente dev)

| Strumento | Versione/scopo |
|---|---|
| JDK | Temurin 21 |
| Android Studio | ultima stable 2025.x+ |
| Android SDK | platform 36, build-tools recenti, cmdline-tools, platform-tools |
| Emulatori | system-image Google APIs x86_64: API 26, 30, 35, 36 |
| scrcpy | test su telefono fisso via USB |
| Gradle wrapper 9.x + AGP corrente, Kotlin 2.2.x (compose compiler nel plugin K2) |
| ktlint, detekt, Paparazzi | qualità + screenshot test |
| GitHub Actions + secrets firma release | CI/CD |
| (opz.) fastlane supply | pubblicazione Play track interne |

Sul server scotti non serve nulla: si sviluppando in repo dedicato (propongo
`/workspace/tabtrader-alt/` qui o una macchina con Android SDK) e CI fa il resto.

---

## 6. Roadmap

| Fase | Durata | Deliverable |
|---|---|---|
| **F0 scaffold** | 1-2 gg | repo, CI verde, moduli, design tokens, licenza |
| **F1 mercati** | 1 sett | lista mercati, ricerca, top movers, watchlist persistente, dati REST+cache |
| **F2 grafici** | 1-2 sett | dettaglio coin, grafico custom interattivo, timeframe 1m→1M, indicatori base, conversione fiat |
| **F3 alert+realtime** | 1 sett | WS service, regole, notifiche con azioni, modalità Realtime/Risparmio, guida OEM |
| **F4 widget** | 3-4 gg | 4 formati Glance, configuratore, temi, deep link |
| **F5 polish** | 1 sett | settings complete, biometria, import chiave cifrata, export/import JSON, IT/EN, accessibility |
| **F6 beta pubblica** | — | GitHub Releases + Play closed testing, feedback community, hardening OEM battery |

MVP utilizzabile: **~5-6 settimane part-time**. Post-MVP: portfolio read-only (chiave),
news RSS, alert tecnici (RSI/MACD), disegni grafico, multi-exchange (Bybit/OKX/Kraken
pubblici), F-Droid, Wear OS tile.

## 7. Rischi & mitigazioni
1. **Kill process OEM** → doppia modalità + guida per-marca + test su Xiaomi reale
2. **Geo-blocco binance.com** (UK/US) → default `binance.vision`, setting endpoint custom (`api.binance.us`)
3. **Policy Play finance** → disclaimer "non è consulenza finanziaria"; app informativa ok
4. **Trademark** → risolto: nome **XON TRADER** (decisione Andrea 24/08/2026, già "XON Trader"), brand kit (#4C3DFF→#E6007E), licenza GPL-3.0
5. **Binance cambia API** → layer `:core:network` isolato + contract test + version pin

---
*Fonti ricerca: play/apkpure/apkcombo listing, helpcenter tabtrader, stampa acquisizione
(schede salvate in conversazione 22/08/2026). Screenshot: research/*.jpg*

---

## 8. Ondata F7 — fix feedback beta (23/08/2026)

Test su device reale superato (debug **e** release: rischio R8 archiviato). Tre
segnalazioni di Andrea, tre aree di intervento:

### 1. Alert muti → permesso POST_NOTIFICATIONS
**Causa #1**: da Android 13 il permesso notifiche va chiesto a runtime; senza,
il sistema scarta ogni notifica in silenzio. Ora:
- `ui/notifications/NotifPermission.kt` — stato permesso reattivo (si aggiorna a ogni resume), richiesta dialog + fallback impostazioni di sistema.
- Banner di avviso in testa alla schermata Avvisi finché il permesso manca.
- Richiesta contestuale al salvataggio del primo avviso e alla creazione dal grafico.
- Impostazioni → sezione **Notifiche**: stato permesso + **notifica di prova** (`Notifications.notifyTest`, canale Test).

### 2. Crash grafico su pan + gesture riscritte
`detectTransformGestures` consumava tutti gli eventi in conflitto col pan/crosshair
(crash su pan con pinch parallelo). Rewrite completo di `CandleChart.kt`:
- gesture manuale `awaitEachGesture`: 1 dito = pan + crosshair, 2 dita = pinch zoom ancorato;
- `ChartWindow.clamp()` — mai range di coercion vuoti (fonte del crash con serie corte);
- **pressione prolungata (450ms, haptic)** → linea prezzo trascinabile → barra conferma Sopra/Sotto → avviso creato al volo dal grafico (`CoinDetailViewModel.quickAlert`, toast conferma);
- rendering: etichette assi senza sovrapposizioni, fit barre sullo schermo, path sicuri su serie corte.

### 3. Widget configurabili per istanza
- `WidgetConfigActivity` (+ `android:configure` nei manifest widget): simbolo fisso o automatico (ticker), righe 3-6 (watchlist), dimensione testo 5 preset, formato numero Auto/Completo/Senza virgole/Approssimato, toggle variazione 24h e ora aggiornamento, intervallo refresh 15min→6h (WorkManager `UPDATE` senza resettare la finestra).
- Pulsantino **↻ refresh immediato** sui widget (`WidgetRefreshAction`, bypass TTL).
- Il worker valuta ora anche gli alert su dati REST freschi: rete di sicurezza se il servizio realtime è fermo (modalità Risparmio / killer OEM).
- Cleanup config alla rimozione del widget (`onDeleted`).

**Release 0.2.1** (versionCode 3).

## 9. Ondata F8 — feedback beta round 2 (23/08/2026)

Cinque segnalazioni di Andrea su 0.2.1, tutte affrontate in un'unica ondata.

### 1. Widget: configurazione non applicata (CRITICO)
- **Causa radice**: `WidgetConfigActivity` salvava e chiamava `onClose(true)` dentro
  `rememberCoroutineScope` → il finish() cancellava lo scope PRIMA che
  `updateAll` finisse e prima che RESULT_OK venisse consegnato; il sistema
  scartava il widget piazzato e alla riaggiunta l'id nuovo ripartiva dai
  default (ETH / formato AUTO). Coincide con entrambi i sintomi riportati.
- **Fix**: setResult+finish immediati al tap su "Save and apply"; ridisegno
  widget su `appContainer.appScope` (scope dell'app, sopravvive all'activity).
- **Fallback errato eliminato**: simbolo configurato vince SEMPRE — cache →
  fetch live immediato (`refreshTickers(listOf(sym), force=true)`) → segnaposto.
  Mai più un'altra moneta al posto di quella scelta (`fetchRowLive`).
- **Formati numero corretti** (`Format.kt` riscritto): WHOLE = intero senza
  decimali ("97400"), COMPACT = approssimato ("97.4k"), FULL/AUTO con decimali.

### 2. Ricerca mercati su TUTTO il catalogo Binance
- `MarketsViewModel`: catalogo completo coppie USDT attive da `exchangeInfo`
  (già in Room via `ensureSymbols`) usato come fonte ricerca; debounce 120ms;
  ranking prefissi→contiene; max 30 risultati; prezzi dei risultati mancanti
  scaricati in batch (riga con prezzo "—" se offline).

### 3. Grafico: legenda fuori dall'area candele
- `ChartLayout` con bande riservate: top 34px (tooltip OHLC) + gutter destro
  misurato sui label prezzo + fascia assiale sotto 32px; volume sotto ancora.
- Etichette prezzo right-aligned nel gutter (mai sopra le candele), tag prezzo
  live/alert/crosshair nel gutter colorato, fallback compat se il formato pieno
  non entra; griglia solo sull'area plot.

### 4. UI completamente in inglese
- Tutte le schermate tradotte (Mercati/Alerts/Settings, editor avvisi, dettaglio
  coin, chart confirm bar, canali notifica, prompt biometrico, config widget,
  strings.xml). Formattazione numeri US (Locale.US).

### 5. Crash backup esporta/ripristino
- Launch SAF protetti (`safeLaunch`) con toast su errore picker OEM;
  MIME concreti invece di wildcard. **Verificato su emulatore (23/08, E2E 0.2.2)**:
  sia Export (`CreateDocument`) sia Restore (`OpenDocument`) aprono il picker
  senza crash. ⚠️ Nota verifica: i bottoni sono **sotto la piega** in Settings
  (~y>2127) — lo script deve scrollare prima del tap; coordinate reali dopo lo
  scroll: Export≈(257,1438), Restore≈(654,1438). La prima bozza dello script
  tap-pava a (300,1560)/(800,1560) mancando i bottoni (falso "export rotto").

**Release 0.2.2** (versionCode 4).

## 10. Ondata UI CARD GRID — grafica a caselle (23/08/2026)

Su richiesta di Andrea con reference in `research/ui-ref/` (TabTrader-1.jpg:
griglia 2-per-riga; WhatsApp: scheda grafico più pulita). Solo presentazione:
ViewModels, dati, navigazione e interazioni invariati.

### 1. Mercati: lista → griglia di card 2-per-riga
- `LazyColumn` → `LazyVerticalGrid(GridCells.Fixed(2))`. Ogni card (Surface
  surfaceContainerLow, raggio 18dp, h 150dp): badge moneta 34dp, nome+simbolo
  (stella dorata se preferito), sparkline, prezzo 17sp, badge variazione %,
  mini-stat in fondo: **24h H** (verde) / **24h L** (rosso) / **Vol**.
- Interazioni identiche: tap → dettaglio, long-press → toggle preferito.
- Ricerca → stessa griglia con **toggle stella esplicito** in alto a destra
  (come la vecchia riga di ricerca, comportamento preservato).

### 2. Dettaglio coin: scheda grafico più pulita
- Prezzo + badge variazione spostati DENTRO la card del grafico (header).
- Sostituita la griglia di 3 mini-card con **una card "24h statistics"**:
  barra range High/Low con marker bianco del prezzo corrente (gradiente brand)
  + volume 24h compatto. Tutti i dati già nello state (high/low/vol/price).
- Grafico 320→300dp per far respirare le card sotto.

### 3. Avvisi / Impostazioni / Tema
- Avvisi: badge moneta (CoinBadge) al posto della campanella; raggio 16dp.
- Impostazioni: sezioni-card raggio 18dp (allineate).
- Tema dark: ramp superfici AMOLED esplicita (surfaceContainerLow→Highest).

### 4. Fix compile latente
`MarketsViewModel.ensureCatalog()` ritornava `catalogJob` (var privata) come
`Job`: con compilazione incrementale passava, a compile pulito falliva
(smart-cast non applicabile). Ora `return viewModelScope.launch{...}.also{ catalogJob = it }`.

**Verifica E2E emulatore (23/08, 0.2.3)**: 11 shot, no FATAL, app viva.
Griglia ✓ scroll ✓ ricerca+stella ✓ grafico+stats ✓ alerts ✓ settings ✓
long-press preferito ✓ toggle stella ricerca ✓. Script: `scripts/emu-verify-023.sh`
(+ `scripts/emu-check-fav.sh` per le interazioni preferito). Coordinate card
prima riga ≈ (276,506); nav bar: alerts (540,2330), settings (900,2330).

**Release 0.2.3** (versionCode 5), stesso cert (upgrade in-place).

## 11. Ondata fix widget CRITICO + grafico (23/08/2026) — release 0.2.4

### 1. Root cause del "widget che sovrascrive i precedenti" (CRITICO)
**Sintomo Andrea**: l'ultimo widget creato funziona ma trasforma i precedenti —
selezioni BTC su uno e anche quello che mostrava ETH diventa BTC; addirittura
anche il widget watchlist (favoriti) viene "sovrascritto".

**Diagnosi (deterministica, emulatore + bytecode)**:
1. `WidgetConfigStore.save()` avvolge TUTTO in `runCatching` → errori silenziosi.
2. `WidgetConfig` NON era `@Serializable` → `json.encodeToString(config)` chiamava
   il fallback runtime `SerializersKt.noCompiledSerializer()` → lanciava
   `SerializationException` a runtime, **inghiottita dal runCatching**.
3. Risultato: **nessuna configurazione è mai stata salvata**. Ogni widget ticker
   era in modalità Automatica → tutti mostravano la STESSA moneta
   (`rows.firstOrNull()` = primo preferito / top volume). Da qui la percezione
   "creo BTC → tutti diventano BTC", incluse le watchlist (stessa sorgente dati).
4. Nota: F7 era stata verificata "per review del diff", NON E2E → il bug è
   passato inosservato. La lezione: `runCatching` + serializzazione = verificare
   SEMPRE la persistenza reale.

**Fix**:
- `@Serializable` su `WidgetConfig` e su `NumberFormatMode` (Enum esplicito).
- Verificato: `WidgetConfig$$serializer.class` generato; prefs ora contengono
  `ticker_8={symbol:ETHUSDT}`, `watchlist_9={rows:4}`, `ticker_10={symbol:BTCUSDT}`
  in parallelo (chiavi distinte per istanza).
- **Repro on-device (activity DEBUG-only, `app/src/debug/`)** con AppWidgetHost:
  3 widget reali (ticker ETH → watchlist → ticker BTC) → dopo il passo 3 il
  primo mostra ancora ETH. Isolamento per-istanza CONFERMATO sul device.

### 2. Fix secondario: truncation watchlist (Glance 10 figli)
Logcat rivelava `GlanceAppWidget: Truncated Column container from 13 to 10
elements` — Glance limita a 10 figli per contenitore. Con righe >~4 il widget
watchlist perdeva righe in silenzio (peggio una volta che le config funzionano).
Fix: righe annidate in una `Column` interna → mai più troncature.

### 3. Grafico: etichette tempo sotto le candele
Prima: stesso stile 19sp delle etichette prezzo → troppo grandi, si
sovrapponevano ai bordi (clamp). Ora: stile dedicato 12sp, centrate nella fascia
asse, guardia anti-sovrapposizione (`lastTimeLabelEnd` salta la label se finisce
sulla precedente).

### 4. Grafico: header scheda completa
Aggiunto **orologio live** (`HH:mm:ss · dd/MM/yy`, aggiornato ogni secondo)
sotto il badge variazione, accanto al prezzo → scheda grafico con ultimo prezzo,
variazione e ora corrente.

### 5. Infra: debug keystore persistente
`build.sh` non montava `~/.android` → AGP rigenerava il debug keystore a ogni
build → ogni APK debug aveva firma diversa → `adb install -r` falliva
(INSTALL_FAILED_UPDATE_INCOMPATIBLE). Fix: volume `android-home` per
`/root/.android`.

### Verifica (23/08, 0.2.4)
- Repro on-device: prefs con 3 chiavi distinte + widget A=ETH dopo creazione C=BTC.
- Grafico: header con clock + asse tempi piccolo e leggibile. Screenshot in
  `shots/repro/`.
- **Release 0.2.4** (versionCode 6), stesso cert (upgrade in-place).

## 12. Ondata fix gap batteria WS-in-background (24/08/2026) — release 0.2.8

### Problema
In modalità **Risparmio** il WebSocket (`!miniTicker@arr` su
`data-stream.binance.vision`) resta collegato finché il processo vive: aperto dalla
UI (`MarketsViewModel`/`CoinDetailViewModel` → `ensureLive()`), non veniva mai
chiuso quando l'app andava in background (nessun foreground service in SAVER) →
batteria e rete consumate per nulla. In REALTIME il problema non esiste: il
foreground service tiene il WS e lo gestisce.

### Fix
- Nuova dipendenza `androidx.lifecycle:lifecycle-process` (2.8.7, già in catalogo).
- Nuova classe `core/service/WsLifecycleController` (DefaultLifecycleObserver su
  `ProcessLifecycleOwner`):
  - cache della modalità dati (`settingsRepo.settings` collect, seed sincrono
    in `attach()` con `runBlocking { first() }` per chiudere la finestra prima
    della prima emissione DataStore);
  - `onStop` (background) + modalità SAVER → `ws.disconnect()` (chiusura
    **sincrona**: nessuna coroutine, nessuna race col ritorno in foreground);
  - `onStart` (foreground) → `ws.connect()` SOLO se eravamo stati noi a chiudere
    (`closedForBackground`): nessun reconnect spurio, e il passaggio
    REALTIME→SAVER dalle impostazioni (che già ferma il service) non riapre il WS;
  - REALTIME → mai toccato.
- Wiring: istanza in `AppContainer`, `attach()` in `TraderApp.onCreate()`
  (`ProcessLifecycleOwner` è inizializzato da startup prima di `Application.onCreate`).
- Il worker widget (15 min, REST) resta la rete di sicurezza per gli alert in SAVER:
  indipendente dal WS, nessuna modifica.

### Verifica (24/08, emulatore API 35, logcat temporaneo poi rimosso)
- SAVER: launch → `connect()` (UI live) · background → `onStop mode=SAVER` →
  `disconnect()` · refocus → `onStart closedForBackground=true` → `connect()`.
- REALTIME (switch impostazioni, service attivo): background → `onStop mode=REALTIME`
  → **nessun** disconnect · refocus → nessun reconnect spurio.
- Debug + release firmati (stesso cert `6b924345...`), R8 ok.

**Release 0.2.8** (versionCode 10).

## 13. Ondata REBRAND XON TRADER (24/08/2026) — dentro release 0.2.8

Decisione Andrea 24/08/2026: nome finale **"XON Trader"** (da **XONIC TRADER**,
richiama la velocità giocando con "sonic": risponde solo agli user need, "è un
lampo"). Rinomina anche progetto e repo, così ci si capisce quando se ne parla
e su GitHub.

### Cosa è cambiato
- **Nome app** "XON Trader" ovunque (strings.xml, widget label/preview,
  AppLock, notifiche, Settings, widget diagnostics, README, CHANGELOG, PLAN).
- **Icona nuova** dall'icon set di Andrea (`xon_trader_icon.zip`): fulmine +
  wordmark "XON TRADER" bianco su nero, adaptive foreground 5 densità
  (FRAC 0.60, mark sorgente x[79,942] y[340,683]). Generata da
  `/tmp/gen_xon_icon.js` (decoder/encoder PNG filter-aware, alpha=luminance).
- **Lingua grafici in inglese**: `CandleChart.kt` CANDLES→**Candles**,
  LINEA→**Line**, AREA→**Area**; timeframes `Models.kt` D1 "1g"→**"1d"**,
  W1 "1s"→**"1w"** (erano le abbreviazioni italiane giorno/settimana);
  AppLock "Sblocca"→**"Unlock"**.
- **Cartella** `/workspace/adgent-trader` → `/workspace/xon-trader`
  (== host `/home/andrea/projects/xon-trader`).
- **GitHub** repo `adgscotti-png/adgent-trader` → `adgscotti-png/xon-trader`
  (API PATCH, 200 OK; vecchio nome redirige). Remote `.git/config` aggiornato.
- **Builder docker** `adgent-trader-builder:1.0` → `xon-trader-builder:1.0`
  (build.sh HOST_REPO aggiornato); build verde 59s.
- Backup export: `adgent-trader-backup-*.json` → `xon-trader-backup-*.json`.

### NON cambiato (per scelta, upgrade-in-place)
Package/identità **`com.adgent.trader`** tenuto: deep link `adgent://coin/`,
tema `Theme.AdgentTrader`, db Room `adgent_trader.db`, proguard, script
emulatore `PKG=com.adgent.trader`, classi `WidgetConfigActivity` ecc. → chi ha
la 0.2.7 installata fa upgrade in-place senza reinstallare, senza rompere
deep-link/theme/db. Eventuale rinomina package = ondata separata + migrazione.

### Verifica (24/08, emulatore API 35)
- aapt: `application-label:'XON Trader'`, icon adaptive `ic_launcher.xml`.
- UI dump grafico: timeframes `1m 15m 1h 4h 1d 1w 1M` + mode `Candles/Line/Area`.
- Sweep stringhe: nessun residuo italiano user-visible (tutti i match rimasti
  sono commenti dev-facing).
- Shots: `shots/xon-rebrand-{chart,markets}.png`.

**Release 0.2.8** (versionCode 10) — ⏳ **non ancora pushato**: Andrea ha chiesto
di aspettare (altri cambi prima della pubblicazione).

## 14. Ondata 0.3.1 — UI Andrea (25/08/2026) — RELEASE

4 modifiche richieste da Andrea sulla UX:

### 1. Card Markets più corte (più coppie per schermata)
Rimossa la riga footer `24h H / 24h L / Vol` (dati già nel coin detail),
altezza card `150dp → 120dp`, rimossi import morti (`Format`, `MarketGreen`,
`MarketRed`) e la composable `CardStat`.

### 2. Timeframe grafico 5m + default 15m
- `Timeframe.M5("5m", 300)` tra M1 e M15 (`Models.kt`).
- `intervalFor` per tutti e 7 i provider (`Timeframes.kt`): Binance `5m`,
  Bybit `5`, Kraken `5`, Coinbase `300`, OKX `5m`, Bitfinex `5m`, KuCoin `5min`.
- `maxAgeMs` cache klines: `M5 -> 5L` (`MarketDataRepository.kt`).
- Default grafico: `H1 → M15` (verificato: 96 candele 15m = 24h).

### 3. FIX CRITICO: Gainers/Losers "partono dal basso"
**Sintomo**: dopo l'avvio la tab Losers mostrava una fascia a metà lista
(stablecoin, ~0%) invece dei veri peggiori — come se partisse dal basso.
**Root cause**: la `LazyVerticalGrid` ancora lo scroll alla **key del primo
item visibile**. Quando la cache cresce da parziale (solo le 5 coppie della
watchlist di default) a mercato pieno (~120 righe) — cosa che succede 30-40s
dopo il primo avvio perché `bootstrap` aspetta `ensureCatalog` (exchangeInfo
grande) poi `refreshTopMarket` — la key del primo item si sposta da indice 0
a ~indice 10 nella lista ordinata e Compose la tiene in cima al viewport,
nascondendo le righe appena inserite SOPRA. Non era un bug di ordinamento:
`applyFilter` ordinava correttamente.
**Fix**: `rememberLazyGridState` + `LaunchedEffect(resetKey) { scrollToItem(0) }`,
dove `resetKey = filter:provider:firstRowKey` — la griglia torna in cima a ogni
cambio di ranking (filtro/provider o nuovo primo risultato). Applicato anche
alla ricerca (`resetKey = "search:firstRowKey"`).
**Verifica**: LOSERS apre su HEMI −22%, GAINERS su ONG +42%, entrambe monotone.

### Verifica (25/08, emulatore API 35, E2E 2 run + smoke release)
- `scripts/emu-verify-031.sh` (debug): card senza `24h H/L`, chip `1m 5m 15m 1h
  4h 1d 1w`, default 15m confermato dal db klines, LOSERS/GAINERS ordine reale.
- `scripts/emu-smoke-release-031.sh` (release): installa `app-release.apk`,
  `versionName 0.3.1 / versionCode 13`, LOSERS apre su −21.86%, app viva.
- **Release live**: commit `68524da`, tag `v0.3.1`,
  `github.com/adgscotti-png/xon-trader/releases/tag/v0.3.1`, asset
  `xon-trader-0.3.1.apk` (3,265,000 byte) scaricato e verificato byte-identico
  (sha256 `56c099ae…`) all'APK locale.

## 15. Rilascio store: Google Play (item ④) — CHECKLIST

Distribuzione FOSS via GitHub Releases finora. Per Play serve un processo
dedicato. Requisito chiave verificato 25/08/2026 su
`support.google.com/googleplay/android-developer/answer/11926878`:

> **New apps e updates devono targetizzare Android 16 (API 36) dal 31/08/2026**
> (estensione a 01/11/2026 su richiesta dal Play Console). Le app già
> esistenti devono restare almeno su API 35 fino al 31/08/2026.

→ XON Trader è un'**app NUOVA** per Play: la prima submission deve puntare
**API 36** (o usare l'estensione al 01/11/2026). L'APK attuale è `targetSdk 35`.

### Stato prep (25/08, account Play richiesto)
- ✅ `platforms;android-36` + `build-tools;36.0.0` installati; system-image
  `android-36;google_apis;x86_64` scaricata; **AVD `xontest36` creato**.
- ✅ **`docs/play-listing.md`**: listing EN (short + full description), privacy
  "zero dati", data safety form, IARC, assets checklist (feature graphic 1024×500
  ⏳ da generare), testing track, Play App Signing/upload key.
- ⏳ **Prossimo**: bump `compileSdk/targetSdk = 36` in `app/build.gradle.kts` +
  `bundleRelease` → `.aab`; test su AVD `xontest36` (edge-to-edge obbligatorio,
  predictive back, 16 KB page size per il so relativo).

### Checklist
1. **Account**: Play Console (pagamento 25$ una tantum), progetto/developer name.
2. **App bundle**: sostituire `assembleRelease` con `bundleRelease` (`.aab` è
   l'unico formato accettato). Keystore: usare lo stesso per sempre (Play App
   Signing: caricare la chiave di upload, Play tiene la signing finale).
3. **targetSdk 36**: installare platform-36, bump `compileSdk/targetSdk = 36`,
   verificare su AVD android-36 (edge-to-edge, predictive back), aggiornare
   E2E di conseguenza.
4. **Scheda Play**: titolo/descrizione/screenshots (usare gli shot degli E2E:
   markets, chart), feature graphic 1024×500, icona 512 (rebrand XON già pronto).
5. **Privacy**: dichiarazione per le API usate — app **senza raccolta dati**
   (nessun account, nessun analytics): selezionare "Non raccogliere dati";
   eventuale Policy URL.
6. **Content rating** (questionario IARC), Data safety form.
7. **Test**: aprire il tracking (Open/Internal/Closed) e caricarvi prima la
   `.aab` di prova → far testare la beta a un gruppo ristretto prima del
   rilascio pubblico (meglio Closed testing: XON Trader è per trader attivi).
8. **Produzione**: promotion track → review Google (1-7 gg di solito).
9. **Versioning**: mantenere `versionCode` crescente (13 → 14…).
10. **FOSS**: il codice è GPL-3.0; Play non ha obiezioni, ma per F-Droid
    servirebbe una build riproducibile (repo "obtainium" come alternativa).
