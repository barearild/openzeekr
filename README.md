# OpenZeekr

A clean-room Android companion app for a **Zeekr (overseas / EU) vehicle** — remote
control over the Geely/ECARX TSP cloud, and the groundwork for the BLE digital-key
channel (proximity unlock and remote parking).

Built from independent reverse-engineering of the author's **own** vehicle and app,
for interoperability and research.

> ⚠️ **Authorized use only.** Use this only with a Zeekr account and vehicle that
> **you own or are explicitly authorized to access.** This is a research tool, not a
> way to access cars that aren't yours.

## Credits & thanks 🙏

This project stands on the shoulders of the community that mapped the Zeekr/Geely
cloud first. Huge thanks to:

- **[Wysie/zeekr_key_extractor](https://github.com/Wysie/zeekr_key_extractor)** — extracting the per-region app keys.
- **[Fryyyyy/zeekr_homeassistant](https://github.com/Fryyyyy/zeekr_homeassistant)** — the login / HMAC / vehicle-list cloud flow.
- **[mescon/zeekr-7x-home-assistant](https://github.com/mescon/zeekr-7x-home-assistant)** — the 3.0.x EU signing recipe and the runtime-key insight.

OpenZeekr reimplements the cloud layer independently (Kotlin/Android) and adds the
BLE / digital-key research, but the account and signing groundwork was inspired by
their work. Following their convention, **no decrypted app secrets are published here.**

## What it does

| Area | Status |
|------|--------|
| **Cloud remote control** (lock/unlock, climate, engine start, charge, windows, flash/horn, sentry mode, …) | ✅ Implemented — full command catalog → `PUT /remote-control/vehicle/telematics/{vin}` with `X-SIGNATURE` HMAC |
| **Vehicle status** | ✅ Implemented |
| **Sentry footage list / request-upload** | ✅ Implemented (cloud `sentinel-monitoring-service`) |
| **Sentry live view** | 🟡 Token fetch works; rendering needs the RTC provider SDK (not yet identified) |
| **Remote parking (RPA/RSPA)** | 🟡 Flow, opcodes, 500 ms heartbeat, challenge auto-answer hook, RSSI stream — all real; **transmit is gated on the DK session** |
| **Proximity unlock/lock (RSSI)** | 🟡 Ranging is real (live BLE scan, EMA-smoothed, hysteresis + signal-loss watchdog); the unlock/lock action rides the DK session |
| **Digital-key lock/unlock + DK BLE session** (handshake, AES-GCM/CMAC, RPA challenge) | 🔴 **Placeholder** (`PlaceholderDkSession`) — the DK handshake crypto is still being reversed |

The DK pieces are written against a real `DkSession` interface, so dropping in a
working handshake implementation lights up lock/unlock and remote parking without
touching the UI or controllers.

## 🔑 Getting your own keys (required — none are shipped)

OpenZeekr **never** contains any account secret. To talk to the cloud you supply
**your own** keys, extracted from **your own** app install. There are six values,
in two groups:

**1. Static, per-region keys** — `hmac_access_key`, `hmac_secret_key`,
`password_public_key`. Extract these from the Zeekr APK with
**[Wysie/zeekr_key_extractor](https://github.com/Wysie/zeekr_key_extractor)**
(`--region EU` / `SEA` / `LA`).

**2. Runtime keys** — `prod_secret` (the `X-SIGNATURE` signing key), `vin_key`,
`vin_iv`. These are assembled at runtime and aren't plain strings in the APK, so
read them from **your own** running app with a dynamic-instrumentation tool (e.g.
Frida) by observing the standard crypto primitives it initializes
(`javax.crypto.Mac` / `SecretKeySpec` / `IvParameterSpec`). See the projects above
for the current recipe per app version.

Then put them into the app (nothing is compiled in):

- **Settings** screen → paste each value, or
- **Import JSON** in the same shape as [`secrets.example.json`](secrets.example.json).

They're stored in `EncryptedSharedPreferences` on-device only.

```jsonc
// secrets.example.json — fill with YOUR OWN extracted values
{ "hmac_access_key":"", "hmac_secret_key":"", "password_public_key":"",
  "prod_secret":"", "vin_key":"", "vin_iv":"",
  "email":"", "password":"", "vin":"" }
```

## Proximity unlock / walk-away lock

Phone-side policy (we decide from RSSI, then issue an explicit DK command — not the
car-side `DKB` switch). Configure on the **Controls** screen:

- **Unlock at ≥ −65 dBm** (default) — closer = higher/less-negative RSSI.
- **Lock at ≤ −85 dBm** (default) — farther = lower RSSI. The gap is hysteresis so
  it doesn't flap at the boundary.
- Signal-loss watchdog: NEAR and no advertisement for 8 s → walked-away → lock.
- Optionally pin the vehicle's BLE MAC; blank ranges the strongest advertiser.

RSSI is exponentially smoothed (α=0.4). Zone transitions fire once: FAR→NEAR
unlocks, NEAR→FAR locks. (Actuation is live once the DK session is implemented.)

## Project structure

```
config/   SecretsConfig + ConfigStore (encrypted, import/export)
net/      Signing (X-SIGNATURE), interceptors, Retrofit TspApi, models
remote/   Command catalog (serviceIds) + repositories (auth, control, sentry)
ble/      DkSession (+placeholder), DkBleManager (GATT scaffold), DkLockController
ble/rpa/  RpaOpcodes + RpaController (heartbeat / challenge / flow)
ui/       Compose screens: Controls, Parking, Sentry, Settings
```

## Build

Open the folder in **Android Studio (Koala or newer)** and let it sync, or from the CLI:

```bash
./gradlew assembleDebug
```

Create `local.properties` with your SDK path (Android Studio does this for you):

```
sdk.dir=/path/to/Android/Sdk
```

## Signing (`X-SIGNATURE`)

`X-SIGNATURE = base64(HMAC(prod_secret, stringToSign))`, where `stringToSign` is:

```
<x-api* headers, lowercased name:value, sorted, \n-joined>
<query sorted by key, k=v joined by &>
<hex MD5 of body, or "">
<HTTP METHOD>
<url path>
```

`X-TIMESTAMP` is a plain header and is **not** part of the signed string. Default
HMAC is SHA-1; switch to SHA-256 in Settings if the gateway rejects it.

## Roadmap — making DK functional

1. Reverse the DK BLE handshake → implement `DkSession.establish()` (session key,
   IV, CMAC-key derivation).
2. Extract the RPA challenge-answer grid → `DkSession.answerChallenge`.
3. Fill the DK GATT service/characteristic UUIDs + frame layout in `DkBleManager`.

## License

MIT — see [LICENSE](LICENSE). Provided as-is, for authorized research on your own
vehicle. Not affiliated with Zeekr, Geely, or ECARX.
