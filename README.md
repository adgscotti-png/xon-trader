# ADGENT TRADER

Terminale crypto Android **gratuito e senza intermediari**: grafici, widget home
con prezzo live e alert realtime su dati di mercato Binance (endpoint pubblici,
nessuna chiave richiesta per l'uso base). Progetto successore community di
TabTrader. Licenza GPL-3.0.

## Stato

Fase F0 — scaffold ambiente di build + scheletro Compose. Piano completo in
[`PLAN.md`](PLAN.md).

| Fase | Contenuto | Stato |
|---|---|---|
| F0 | Ambiente build riproducibile + skeleton app | ✅ |
| F1 | Mercati, ricerca, watchlist (REST Binance + cache) | ⏳ |
| F2 | Grafico candele interattivo custom | ⏳ |
| F3 | WebSocket realtime + alert engine locale | ⏳ |
| F4 | Widget home (Glance, 4 formati) | ⏳ |
| F5 | Settings, sicurezza, i18n, polish | ⏳ |
| F6 | Beta pubblica | ⏳ |

## Come si costruisce

Non serve nulla installato sulla macchina oltre a **Docker** (l'ambiente è
controllato al 100% da un'immagine pinned):

```bash
./docker/build.sh                 # assembleDebug → app/build/outputs/apk/debug/
./docker/build.sh assembleRelease # release R8 (firma: TODO keystore)
```

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
Copyright © 2026 Andrea Scotti / ADGENT.
