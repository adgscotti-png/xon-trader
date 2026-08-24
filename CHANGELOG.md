# Changelog — ADGENT Trader

Formato basato su [Keep a Changelog](https://keepachangelog.com/), versionamento [SemVer](https://semver.org/).

## [0.2.8] — 2026-08-24

### Corretto
- **Gap batteria in modalità Risparmio**: il WebSocket dei prezzi, aperto dalla UI
  (lista mercati / grafico), restava collegato anche con l'app in background fino a
  quando il processo viveva, consumando batteria e rete. Ora, in modalità Risparmio,
  al passaggio in background il WebSocket viene chiuso e al ritorno in foreground
  riaperto (la UI live riparte da sola). In modalità Realtime nessun cambiamento:
  il foreground service continua a mantenere la connessione anche da background.
- Verificato su emulatore (24/08): SAVER background→disconnect, refocus→reconnect;
  REALTIME background→nessun disconnect, refocus→nessun reconnect spurio.

### Note
- Le release 0.2.1→0.2.7 (fix feedback beta, widget, grafico, UI card grid) sono
  documentate in `PLAN.md` §8–§11.

## [0.2.0-beta1] — 2026-08-22

Prima beta completa: tutte le fasi F0–F6 del piano sono implementate.

### Aggiunto
- **Mercati live (F1)**: lista completa coppie Binance con prezzo e variazione 24h in tempo reale via WebSocket, ricerca, preferiti, cache offline-first (Room).
- **Dettaglio coin (F2)**: grafico candele/linea/area interattivo (pinch-zoom, crosshair OHLC, volumi), 13 timeframe (1m → 1M), overlay MA 7/25/99, EMA 12/26, Bollinger, sub-chart **RSI** e **MACD**, statistiche 24h.
- **Avvisi prezzo (F3)**: 4 tipi di soglia (sopra/sotto prezzo, +% / −% 24h), avvisi singoli o ripetibili con nota, notifiche con azione rapida "Disattiva" e apertura diretta del grafico, modalità **Realtime** (servizio foreground, ~1s) o **Risparmio** (poll 15 min), avvio automatico al boot, guida anti-kill per marca telefono.
- **Widget home screen (F4)**: widget prezzo 2×1 e watchlist 4×2 (Glance), refresh ogni 15 min e ad ogni apertura app, deep link al grafico.
- **Polish (F5)**: blocco app con biometria/PIN, backup/ripristino preferiti+avvisi in JSON, tema chiaro/scuro/sistema.

### Note
- Dati di mercato da endpoint pubblici Binance (`data-api.binance.vision`) — nessuna registrazione, nessuna chiave.
- App informativa: non costituisce consulenza finanziaria.
- Licenza **GPL-3.0**.

## [0.1.0-alpha1] — 2026-08-22

- F0: scaffold progetto, ambiente build Docker ripetibile, CI GitHub Actions, brand ADGENT (#4C3DFF→#E6007E).
