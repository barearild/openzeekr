# OpenZeekr

> 🚧 **Not usable yet — work in progress, no support.** This is an experimental,
> incomplete research project, provided **as-is with no warranty and no support**
> (no issues, no help, no guarantees it works or is safe to use). Nothing here is
> confirmed working end-to-end; expect breakage and changes without notice. Use
> entirely at your own risk.

A clean-room Android companion app for a **Zeekr (overseas / EU) vehicle** — remote
control over the Geely/ECARX TSP cloud, and a working **offline BLE digital key**
(lock/unlock at the car), with proximity unlock and remote-parking research on the
same digital-key channel.

Built from independent reverse-engineering of the author's **own** vehicle and app,
for interoperability and research.

> ⚠️ **Authorized use only.** Use this only with a Zeekr account and vehicle that
> **you own or are explicitly authorized to access.** This is a research tool, not a
> way to access cars that aren't yours.

> 🌍 **EU only, and only lightly tested.** Everything has ever only been pointed at
> the **EU** TSP server with an **EU** vehicle and account. Other regions are
> unlikely to work as-is: **some endpoints, hosts, region and project-id values are
> currently hardcoded for EU inside the app.** Non-EU support is not implemented.

## Credits & thanks 🙏

This project stands on the shoulders of the community that mapped the Zeekr/Geely
cloud first. Huge thanks to:

- **[Wysie/zeekr_key_extractor](https://github.com/Wysie/zeekr_key_extractor)** — extracting the per-region app keys.
- **[Fryyyyy/zeekr_homeassistant](https://github.com/Fryyyyy/zeekr_homeassistant)** — the login / HMAC / vehicle-list cloud flow.
- **[mescon/zeekr-7x-home-assistant](https://github.com/mescon/zeekr-7x-home-assistant)** — the 3.0.x EU signing recipe and the runtime-key insight.

OpenZeekr reimplements the cloud layer independently (Kotlin/Android) and adds the
BLE / digital-key research, but the account and signing groundwork was inspired by
their work. Following their convention, **no decrypted app secrets are published here.**

## Support this work 💛

This comes with **no support** — but a *lot* of time, tooling and late nights went
into reverse-engineering it and bringing it this far. If it's useful to you, a small
token of appreciation is genuinely welcome (never expected):

**→ [revolut.me/emilimpd](https://revolut.me/emilimpd)**

## What it does

Legend: ✅ working & verified · 🟡 built but **unverified** (may or may not work) · 🔴 not working yet.

| Area | Status |
|------|--------|
| **Account login** (idaas → TSP bearer) | ✅ Working (EU) — logs in and obtains the TSP bearer token |
| **DK BLE digital key — pair + lock/unlock** | ✅ **Working & verified at the car.** Clean-room handshake (cert exchange → ECDH → AES-128-GCM session) + control opcodes `0x110`/`0x111`; locks and unlocks over BLE, no native libs |
| **DK cloud provisioning** (enrol our own keypair → key-info) | ✅ Working — provisions OpenZeekr's own digital key and pairs to the car |
| **Cloud remote control** (lock/unlock, climate, engine, charge, windows, flash/horn, sentry, …) | ✅ **Core verified** — commands accepted (`000000`) via the stock body shape + `X-SIGNATURE`; some commands are vehicle-state gated (e.g. refused at low battery SOC) so remain effectively unverified |
| **Unified quick action** (BLE if a DK session is connected, else cloud) | ✅ Working |
| **Foreground service** (keeps the DK BLE session connected; approach unlock/lock) | 🟡 Two-stage policy: low-power hardware-filtered passive scan → background connect on approach → clean disconnect on walk-away; keep-alive suspended while proximity owns the link — **pending at-car tuning** |
| **Proximity unlock / walk-away lock (RSSI)** | 🟡 Passive `SCAN_MODE_LOW_POWER` trip-wire, then aggressive connected-GATT RSSI; unlock at a user threshold (floored −65 dBm), auto-lock at unlock−5; connect-before-unlock, clean disconnect after lock — **pending at-car tuning** |
| **Vehicle status** | ✅ **Working (EU)** — single GET (`vehicle/status/latest?latest=false&target=new`), tolerant JSON map (lock/doors/SOC/range/climate/odometer/tyres/location). Handles this platform's quirks: SOC lives in `chargeLevel` (not blank `stateOfCharge`); charging derived from live `chargeIAct×chargeUAct` kW (the `isCharging` flag is wrong) |
| **Sentry footage / live view** | 🔴 **CN-only — not available on EU (or any overseas) gateway.** `sentinel-monitoring-service` is unrouted (gateway 404 `00A01`); our request is byte-identical to stock, so it's a server-side regional gap, not a client bug. **Hidden in the app for now; back-burner** — see below |
| **Remote parking (RPA/RSPA)** | 🟡 Fully wired — flow, opcodes, 500 ms dead-man heartbeat, challenge auto-answer, RSSI stream, **and the AES-CMAC frame trailer + ECIES `cmacKey` unwrap** (reversed from the native lib, offline unit-tested). At the car the request is still NAK'd (`0x100a` cmdMatchErr → sequencing / must be armed in-car) — **pending at-car** |

The digital-key handshake and lock/unlock are real and verified; RPA rides the same
`DkSession` and is byte-complete pending an at-car session where remote parking is
armed. See `CMAC_FINDINGS.md` (local) for the RPA crypto derivation.

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
unlocks, NEAR→FAR locks — issuing **real DK lock/unlock** over the working BLE
session. (A lower-power hardware-filtered scan + background-connect policy is WIP.)

## Project structure

```
config/   SecretsConfig + ConfigStore (encrypted, import/export)
net/      Signing (X-SIGNATURE), interceptors, Retrofit TspApi, models
remote/   Command catalog (serviceIds) + repositories (auth, control, sentry)
ble/      DkSession (+placeholder), DkBleManager (GATT scaffold), DkLockController
ble/rpa/  RpaOpcodes + RpaController (heartbeat / challenge / flow)
ui/       Compose screens: Controls, Parking, Key (DK setup), Settings
          (Sentry screen exists but its tab is hidden — see back-burner note)
```

> Note: region, base URL and project-id are EU defaults baked into
> `SecretsConfig`/`ZeekrConst` — non-EU use would need these made configurable.

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

`X-TIMESTAMP` is a plain header and is **not** part of the signed string. The TSP
gateway uses **HMAC-SHA-256** (key = `prod_secret`); this is the EU recipe and is
what the app currently assumes.

## Roadmap

Done: ✅ DK BLE handshake + session-key/IV derivation · ✅ digital-key lock/unlock at
the car · ✅ GATT UUIDs + frame layout · ✅ RPA challenge-answer grid · ✅ RPA AES-CMAC
trailer + ECIES `cmacKey` unwrap (offline-validated) · ✅ vehicle status (EU) · ✅
first-run onboarding · ✅ two-stage low-power proximity policy.

Next:

1. **Remote parking at the car** — confirm the arming/sequencing (the request is
   currently NAK'd `0x100a` cmdMatchErr — likely must be armed on the head unit) and
   capture a live golden vector to close the CMAC loop end-to-end.
2. **Proximity at the car** — tune the connect band / actuation thresholds on real
   approach/walk-away runs.

### Back-burner: Sentry / Sentinel (footage + live view) — CN-only

The `sentinel-monitoring-service` backend is **not deployed on the EU (or any
overseas) API gateway** — a live request returns HTTP 404 `00A01` ("no route
registered"). Our path, query and signing are **byte-identical to the stock app**
(verified against both `com.zeekr.overseas` 3.0.7 and `com.zeekr.global` 1.6.3 —
same interface class, same host table, same call-sites), so this is a **server-side
regional gap, not a client bug**. All five endpoints share one service prefix, so
footage list, snapshots and live view fall together. Auth is region-scoped (the
bearer token's issuer is the EU inner gateway; the VIN is registered EU-side), so an
EU account can't simply borrow another region's gateway. The feature is therefore
**hidden in the app for now and not testable on this vehicle** — parked here until a
workaround surfaces (e.g. a gateway that both routes the service and accepts the
credentials). Client code + reversed schemas are kept in `SENTRY_ENDPOINT_FINDINGS.md`
for when that day comes.

## License

MIT — see [LICENSE](LICENSE). Provided as-is, for authorized research on your own
vehicle. Not affiliated with Zeekr, Geely, or ECARX.
