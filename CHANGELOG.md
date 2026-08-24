# Changelog — XON Trader

Format based on [Keep a Changelog](https://keepachangelog.com/), versioned with [SemVer](https://semver.org/).

## [0.2.9] — 2026-08-24

### Added
- **7 public keyless market providers** — Binance, Bybit, Kraken, Coinbase Exchange, OKX, Bitfinex and KuCoin: catalog, rankings, klines and live WebSocket per exchange. No registration or API key for any of them.
- **Per-coin source picker** — in the coin detail, choose which exchange feeds that coin (or "Auto" for best-available with failover). The watchlist resolves each pair to its effective provider, so a coin overridden to Bybit shows Bybit data, badge and chart even when stored under another source.
- **Settings → "Market data source"** — default provider chips (Auto + 7), same section as price alerts.
- **Per-provider markets tab** — chip row (Auto / 7 exchanges) to pick the ranking source; rankings load on demand while the screen is active, with a manual refresh button.

### Fixed
- **KuCoin `last:null` crash** — some public APIs return JSON `null` for numeric fields; JSON coercion (`coerceInputValues`) now maps them to defaults.
- **Coin detail header with canonical symbols** — `BTCUSDT/USDT · Coinbase`-style headers no longer double up the base; the header now shows `BTC/USDT · <source>`.

### Verified
- Emulator E2E green: F3 (all 4 new providers end-to-end) and F4 (multi-provider UI: per-coin override, override-aware watchlist, settings chips, no crashes).
- Battery: in **Save** mode the WebSockets close when the app goes to background and reopen on return; **Realtime** keeps them open via the foreground service.

## [0.2.8] — 2026-08-24

### Fixed
- **Battery gap in Save mode**: the price WebSocket opened by the UI (markets list / chart) stayed connected in background while the process lived, draining battery and network. Now, in Save mode, the WebSocket closes on background and reopens on foreground (live UI resumes by itself). Realtime mode unchanged: the foreground service keeps the connection alive even from background.
- Verified on emulator (24/08): SAVER background→disconnect, refocus→reconnect; REALTIME background→no disconnect, refocus→no spurious reconnect.

### Notes
- Releases 0.2.1→0.2.7 (beta feedback fixes, widget, chart, card-grid UI) are documented in `PLAN.md` §8–§11.

## [0.2.0-beta1] — 2026-08-22

First complete beta: all phases F0–F6 of the plan are implemented.

### Added
- **Live markets (F1)**: full Binance pair list with realtime price and 24h change via WebSocket, search, favorites, offline-first cache (Room).
- **Coin detail (F2)**: interactive candlestick/line/area chart (pinch-zoom, OHLC crosshair, volumes), 13 timeframes (1m → 1M), MA 7/25/99, EMA 12/26, Bollinger overlays, **RSI** and **MACD** sub-charts, 24h stats.
- **Price alerts (F3)**: 4 threshold types (above/below price, +% / −% 24h), single or repeatable with note, notifications with "Disable" quick action and direct chart deep-link, **Realtime** mode (foreground service, ~1s) or **Save** mode (15-min poll), auto-start on boot, anti-kill guide per phone brand.
- **Home-screen widgets (F4)**: 2×1 price and 4×2 watchlist (Glance), refresh every 15 min and on every app open, deep link to the chart.
- **Polish (F5)**: biometric/PIN app lock, JSON backup/restore of favorites+alerts, light/dark/system theme.

### Notes
- Market data from public Binance endpoints (`data-api.binance.vision`) — no registration, no key.
- Informational app: not financial advice.
- License **GPL-3.0**.

## [0.1.0-alpha1] — 2026-08-22

- F0: project scaffold, reproducible Docker build, GitHub Actions CI, XON brand (#4C3DFF→#E6007E).
