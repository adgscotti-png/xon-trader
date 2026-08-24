# XON Trader

Terminale crypto Android **gratuito e senza intermediari**: grafici, widget home
con prezzo live e alert realtime su dati di mercato Binance (endpoint pubblici,
nessuna chiave richiesta per l'uso base). Progetto successore community di
TabTrader. Licenza GPL-3.0.

## Stato

**0.2.0-beta1** — MVP completo: tutte le fasi del piano sono implementate.
Storico in [`CHANGELOG.md`](CHANGELOG.md), piano completo in [`PLAN.md`](PLAN.md).

| Fase | Contenuto | Stato |
|---|---|---|
| F0 | Ambiente build riproducibile + skeleton app | ✅ |
| F1 | Mercati, ricerca, watchlist (REST+WS Binance, cache Room) | ✅ |
| F2 | Grafico candele interattivo + indicatori (MA/EMA/BB, RSI/MACD) | ✅ |
| F3 | Alert engine locale + servizio realtime + notifiche con azioni | ✅ |
| F4 | Widget home Glance 2×1 e 4×2 + refresh WorkManager | ✅ |
| F5 | Blocco app biometrico, backup JSON, tema | ✅ |
| F6 | Beta pubblica: release firmata, changelog, CI | 🚧 serve repo GitHub remota |

## Come si costruisce

Non serve nulla installato sulla macchina oltre a **Docker** (l'ambiente è
controllato al 100% da un'immagine pinned):

```bash
./docker/build.sh                 # assembleDebug → app/build/outputs/apk/debug/
./docker/build.sh assembleRelease # release R8 firmata → app/build/outputs/apk/release/
```

La firma release legge `keystore/release.properties` (escluso dal git):
**fare backup della cartella `keystore/`** — senza di essa non si possono
pubblicare aggiornamenti firmati con la stessa chiave.

Prima esecuzione: scarica immagine builder + Android SDK (~1 GB) nel volume
persistente `../android-sdk`; le volte successive tutto è caldo.

- Immagine builder: `docker/builder.Dockerfile` (Temurin 21 + Gradle 8.9)
- SDK: volume `/opt/android-sdk`, installato da `docker/sdk-setup.sh`
- Stack: Kotlin 2.1 · AGP 8.7.3 · compileSdk 35 · minSdk 26 · Compose M3

## Architettura (prevista)

Single-module con layering rigoroso (`core/data/domain/ui`), estrazione moduli
Gradle quando servirà. Dati market: REST+WebSocket pubblici Binance
(default `data-api.binance.vision`, endpoint custom configurabile). Zero backend,
zero telemetria. Chiave API utente (opzionale, post-MVP portfolio): cifrata
Tink/Keystore, mai trasmesse fuori dal dispositivo.

## Licenza

GPL-3.0-only — vedi [`LICENSE`](LICENSE).
Copyright © 2026 Andrea Scotti / XON Trader.
