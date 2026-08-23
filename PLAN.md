# ADGENT TRADER — alternativa FOSS a TabTrader

**Piano di progetto — 2026-08-22 — claude · NOME APPROVATO da Andrea 22/08/2026**
`WORKING-ON: F6 beta pubblica — release firmata 0.2.0-beta1 (R8+keystore), changelog; manca solo repo GitHub remota per Releases (serve Andrea). F1-F5 complete e committate`

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
4. **Trademark** → risolto: nome **ADGENT TRADER** (decisione Andrea 22/08/2026), brand kit ADGENT (#4C3DFF→#E6007E), licenza GPL-3.0
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
