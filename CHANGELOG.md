# Changelog — XON Trader

Format based on [Keep a Changelog](https://keepachangelog.com/), versioned with [SemVer](https://semver.org/).

## [0.3.1] — 2026-08-25

### Changed
- **Compact market cards** — the coin cards in the Markets grid drop the "24h H / 24h L / Vol" footer row (those stats are already on the coin detail), so more pairs fit per screen; card height shrinks from 150dp to 120dp.
- **5m chart timeframe** — a new 5-minute candle interval sits between 1m and 15m on every exchange (Binance, Bybit, Kraken, Coinbase, OKX, Bitfinex, KuCoin), and the default chart timeframe is now 15m instead of 1h.

### Fixed
- **Gainers/Losers rankings started from the wrong place** — right after launch the market cache grows from a few watchlist pairs to the full market, and the grid kept its scroll anchored to the previously first-visible coin, hiding the newly inserted rows above it. Result: the Losers tab looked like it "started from the bottom" (stablecoins / mid-list pairs) instead of the real biggest losers, and Gainers the same in reverse. The grid now resets to the top whenever the ranking changes (filter/provider switch or a new top-ranked pair), so both tabs always open on the actual best/worst movers.

### Verified
- Emulator E2E green (25/08): no "24h H/L" in the markets grid; 5m chip present on the chart and default 15m confirmed (96 × 15m candles loaded); Losers opens on the real biggest loser (HEMI −22%) and Gainers on the real biggest gainer (ONG +42%), both lists monotonic.

## [0.3.0] — 2026-08-25

### Added
- **Batch alert creation** — in the alert editor, turn one starting price into a ladder of 2–20 alerts at a fixed price step above it (e.g. 20 alerts every $1,000 from $90,000). Each alert is independent and editable; the editor previews the generated scale live.
- **Alert levels on the chart** — a thin green line marks each active price alert's threshold on the candle chart, with the level labelled in the gutter. Levels are excluded from the chart's auto-fit, so an alert far from the current price (e.g. 120k) stays hidden until prices get close.
- **Realtime feed survives reboot on Android 15** — the foreground service now uses the `specialUse` type, which Android 15 allows to start from `BOOT_COMPLETED`. Before, the boot receiver crashed with `ForegroundServiceStartNotAllowedException` (dataSync), so "alerts even with the app closed" silently broke after a phone restart on Android 15.

### Fixed
- **Favorite added from a non-default exchange kept the wrong source** — e.g. adding SLX from Kraken showed it in Favorites as OKX because the default provider overrode the stored choice. The stored source now wins unless explicitly overridden or that source is down.
- **Widget tap opened the last screen** — tapping a widget now deep-links straight to the tapped instrument's chart (cold and warm start) instead of restoring the previously open screen; re-tapping the same coin while it is already open does not stack a duplicate screen.

### Verified
- Emulator E2E green (25/08): 20-alert ladder (90,000→109,000), chart alert lines inside/outside the visible range with auto-fit unchanged, SLX added from Kraken resolves to Kraken in Favorites, widget deep link on cold and warm start, and a full device reboot with Realtime mode on starts the price feed with no crash.

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
