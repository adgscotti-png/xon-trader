# XON Trader

Free, no-middleman Android crypto terminal: interactive charts, home-screen
widgets with live price and realtime alerts, fed by **7 public keyless market
providers** — Binance, Bybit, Kraken, Coinbase Exchange, OKX, Bitfinex and
KuCoin. No account, no API key, no backend, no telemetry.

Community successor of TabTrader. License GPL-3.0.

## Features

- **7 market providers in one app** — pick a source per coin (or "Auto" for the
  best available with automatic failover). No registration required for any of
  them.
- **Live only where it matters** — realtime prices flow on your watchlist
  (max 20 pairs, from different exchanges, updated in real time) and widgets.
  Market rankings load on demand while the screen is active; no background
  polling.
- **Battery-friendly** — in *Save* mode the sockets close when the app goes to
  background and reopen on return. *Realtime* mode keeps them alive through a
  foreground service for ~1s updates.
- **Per-provider markets** — switch the ranking source (Auto / 7 exchanges) with
  a chip row, pull rankings by hand or let them refresh on screen-active.
- **Interactive charts** — candlestick/line/area, 13 timeframes, MA/EMA/Bollinger
  overlays, RSI and MACD sub-charts, OHLC crosshair.
- **Price alerts** — threshold types (above/below price, ±% 24h), single or
  repeatable, notification with quick actions and deep link to the chart.
- **Home-screen widgets** — 2×1 price and 4×2 watchlist (Glance).
- **Privacy-first** — zero backend, zero analytics. Optional biometric/PIN app
  lock and JSON backup/restore.

## Build

Nothing needed on the host except **Docker** — the build is fully reproducible
from a pinned image:

```bash
./docker/build.sh                 # assembleDebug → app/build/outputs/apk/debug/
./docker/build.sh assembleRelease # signed R8 release → app/build/outputs/apk/release/
```

Release signing reads `keystore/release.properties` (excluded from git):
**back up the `keystore/` folder** — without it, future signed updates with the
same key are impossible.

First run pulls the builder image + Android SDK (~1 GB) into the persistent
volume; afterwards everything is warm.

- Builder image: `docker/builder.Dockerfile` (Temurin 21 + Gradle 8.9)
- SDK: volume `/opt/android-sdk`, installed by `docker/sdk-setup.sh`
- Stack: Kotlin 2.1 · AGP 8.7.3 · compileSdk 35 · minSdk 26 · Compose M3
- Multi-provider data via a clean **Port/Adapter** architecture (`MarketDataProvider`
  port + one adapter per exchange), canonical symbol model with per-exchange
  native formats mapped at the adapter boundary, per-provider rate limiters and
  circuit breakers.

## Releases

- 0.2.9 — 7 market providers + per-coin source picker + Auto failover
  (this release). See [`CHANGELOG.md`](CHANGELOG.md).
- History back to the 0.2.0 MVP in [`CHANGELOG.md`](CHANGELOG.md) and
  [`PLAN.md`](PLAN.md).

## License

GPL-3.0-only — see [`LICENSE`](LICENSE).
Copyright © 2026 Andrea Scotti / XON Trader.
