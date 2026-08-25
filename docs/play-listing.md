# XON Trader — Google Play listing (pronto per la submission)

> Prep 25/08/2026 — account Play richiesto da Andrea; tutto il resto pronto qui.
> Requisito chiave: app NUOVA → targetSdk 36 (obbligatorio dal 31/08/2026,
> estensione Play Console al 01/11/2026). Build di riferimento: `bundleRelease`.

## 1. Identità

| Campo | Valore |
|---|---|
| App name | **XON Trader** |
| Package | `com.adgent.trader` |
| Short description (80 char) | `Crypto market data for 7 exchanges: charts, alerts, watchlist — no ads, no account.` |
| Category | Finance |
| Type | App |
| Pricing | Free |
| Contains ads | No |
| In-app purchases | No |

## 2. Full description (EN — draft)

**XON Trader** is a fast, free and open-source market tracker for crypto on 7
exchanges — no account, no ads, no data collection.

**Multi-exchange market data**
- Live prices and 24h change for Binance, Bybit, Kraken, Coinbase, OKX,
  Bitfinex and KuCoin — all keyless, straight from each exchange's public API.
- Auto mode merges every exchange into one feed; each card shows the exchange
  the price comes from.
- Gainers / Losers rankings that sort by real 24h movement.

**Charts**
- Candles, line and area charts with 1m/5m/15m/1h/4h/1d/1w timeframes.
- Indicators: MA, EMA, Bollinger Bands, RSI, MACD.
- Works offline-first: candles are cached on device.

**Alerts & watchlist**
- Price alerts above/below a level, with batch ladder creation, saved on device.
- Favorite pairs, starred and always available; home-screen widgets.

**Privacy first**
- No account, no registration, no analytics, no advertising SDKs. Your data
  never leaves your phone. Open source on GitHub.

## 3. Privacy & data safety (Play Console)

- **Data safety form**: "Your app doesn't collect or share any user data."
  All answers = **No** (no account, no location, no personal info, no
  diagnostics, no ads). Only network activity = fetching public market data
  from exchange APIs (this is the app's core function, not user data).
- **Policy URL**: use the GitHub repo README URL
  (`https://github.com/adgscotti-png/xon-trader`) or a simple privacy note —
  since no data is collected, a short statement is enough.
- **IARC / content rating**: questionnaire → Finance/Utility, no violence,
  no user interaction on the rating. Expected "Everyone" / 3+.
- **Target audience**: all; no child-directed (no COPPA signals).

## 4. Assets

| Asset | Required | Status |
|---|---|---|
| App icon 512×512 | yes | ✅ rebrand XON (fulmine + wordmark) già in `app/src/main/res` |
| Feature graphic 1024×500 | yes | ⏳ da generare dal brand kit (XON su navy) |
| Phone screenshots (min 2, ideal 6-8) | yes | ✅ da `shots/` — candidates: `f032/01-markets.png`, `f032/02-auto.png`, `f6-07-btc-chart-nolines.png`, `f8-release-chart.png`, alert editor, widget |
| App bundle `.aab` | yes | ⏳ `bundleRelease` (0.3.3, targetSdk 36) |
| Promo video (30s) | optional | skip |
| Privacy policy URL | yes | repo README |
| Support/contact | yes | email / GitHub issues |

## 5. Testing track (prima della release)

1. Upload AAB nella **testing track (internal)**.
2. Verifica Play App Signing: caricare la chiave di **upload** (= keystore di
   release esistente), Play gestisce la firma finale. MAI cambiare keystore.
3. Prova via link per tester (più dispositivi) → smoke minimo (markets + chart).
4. Produzione: release aperta con rollout 20% per i primi giorni.

## 6. Versions

- 0.3.2: GitHub Releases (APK firmato, targetSdk 35) — FOSS via repo.
- 0.3.3 (Play): stessa base + `compileSdk/targetSdk = 36`, `.aab` con
  `versionCode 15`. La versione Play può divergere dalla GitHub.

## 7. Firma & keystore (regole)

- Keystore di release in `keystore/release.properties` (**fuori git**).
- Play App Signing: la chiave caricata è quella di **upload**; la chiave finale
  resta in mano a Play. Se si perde il keystore → si perde l'upload key.
- Backup del keystore = **responsabilità di Andrea** (già evidenziato).
