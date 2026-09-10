# Project Condition Report

**As of: 11 September 2026, 01:58 IST**
Build: `BLE-Link-v1-2026-09-11.apk` · commit `946db00` · repo `P-Dinesh-dev/ble-coded-phy-link` (private)

---

## 1. What the app can do right now

A **two-phone, Bluetooth-only, text-only messenger**. No pairing, no internet, no SIM, no extra hardware.

| Capability | State |
|---|---|
| Send and receive text between two Android phones over BLE | ✅ Working |
| **Bit-exact delivery** — the received text is identical or nothing is shown | ✅ Verified by test |
| Encrypted with a shared passphrase (AES-256-GCM) | ✅ Working |
| Automatic delivery confirmation (`✓ delivered`) | ✅ Working |
| Survives heavy packet loss without breaking | ✅ Verified to 95% loss |
| Live link readout — pkt/s, RSSI, PHY, granted TX power | ✅ On main screen |
| Continuous idle beacon so RSSI/pkt-s stay live with no message sent | ✅ Working |
| CSV logging of every second + every event, for range analysis | ✅ Working |
| Phase 0 handset diagnostic screen | ✅ Working |
| Unicode / Telugu / emoji | ✅ Verified |

**It does not do:** voice, images, files, group chat, multi-hop relay, or iPhone. Text only, two phones, point to point.

---

## 2. How far does it work?

### Honest answer: **verified only at ~5 m so far. Nothing beyond that has been tested.**

No distance test has been run. Every range figure below is a **model prediction**, not a measurement.

**What has actually been measured on hardware:**

| Measured | Value |
|---|---|
| Coded PHY confirmed active | `primary=CODED` ✅ |
| Granted TX power | **−1 to +1 dBm** (varies; see §4) |
| RSSI at ~5 m, phones flat on a surface | −55 to −70 dBm |
| Packets/sec received | 4–7 (of ~10 advertised) |
| Extended advertising supported | `true`, maxAdv 1650 bytes |

The −70 dBm reading at 5 m matched the two-ray propagation model's prediction of −72 dBm to within 2 dB, which is the one piece of evidence that the range model is behaving.

### Predicted range (NOT measured)

Link budget ≈ **96–98 dB**. Two-ray ground-reflection model, both phones at height *h*:

| Distance | h = 1.5 m (standing) | h = 2.5 m (compound wall) | h = 3 m |
|---|---|---|---|
| **300 m** | +4 to +6 dB — thin | **+13 to +15 dB — comfortable** | +16 to +18 dB |
| **400 m** | −1 to +1 dB — knife edge | +8 to +10 dB | +11 to +13 dB |
| **500 m** | −5 to −3 dB ✗ | +4 to +6 dB — thin | +7 to +9 dB |

**Working target: 300 m, with both phones raised to ~2.5 m and clear line of sight.**

Two hard conditions:
1. **Line of sight is required.** One exterior concrete wall costs 10–20 dB — the entire margin. It will not work through a building.
2. **Height matters more than anything else.** 1.5 m → 2.5 m is worth **+8.9 dB**, which is more than the whole app gains from Coded PHY in cluttered propagation. A compound wall, parapet or gate pillar is not optional.

---

## 3. What is completed

### Phase 0 — Handset viability ✅ **PASSED ON HARDWARE**
Confirmed the phone genuinely does Coded PHY, not just claims to. `isLeCodedPhySupported()` is unreliable across Android devices, so this had to be proven per-handset. Result: `primary=CODED`, real packets received.

### Phase 1 + 3 — Measurement logging ✅ **BUILT, awaiting field data**
One CSV logger serves both, written to
`Android/data/com.dinesh.codedphyprobe/files/link_log.csv`
```
time,label,event,pps,rssi,phy,granted_tx,sent,delivered
```
Events: `TX`, `RX`, `ACK`, `BADTAG`, `MARK`. A distance-label field stamps each row so a range walk can be analysed afterwards.

### Phase 2 — Messaging protocol ✅ **BUILT + 10/10 TESTS PASSING**
Connectionless extended advertising only — no GATT connection, so a signal fade costs one packet instead of tearing down the link. There is nothing to reconnect and nothing to time out.

Pipeline: `text → compress (only if smaller) → AES-256-GCM once → fragment → advertise`

- 24-byte packets: `sender | msgId | totalLen | esi | 20-byte symbol`
- Encrypt-then-fragment: one 16-byte tag per *message*, not per packet
- Round-robin symbol rotation, ACK-terminated, 30 s timeout
- Idle beacon keeps the link readable without sending anything

### UI ✅ **FIXED AND VERIFIED ON DEVICE**
Rebuilt after finding it was unusable: `targetSdk 37` forces edge-to-edge, so the diagnostics were rendering underneath the system status bar and were invisible. Confirmed fixed by screenshot and view-hierarchy dump on the actual phone.

---

## 4. Open questions

1. **RX sensitivity is assumed at −102 dBm and unverified.** With only ~97 dB of budget this is the largest unknown — a 5 dB error either way flips the 400 m result. Resolved by walking out in 25 m steps and finding the RSSI at 50% packet delivery.
2. **Granted TX power is inconsistent: the probe reported +1 dBm, the messenger reports −1 dBm.** Same request. Either the controller reports achieved rather than requested power, or it varies with radio state. Worth watching — 2 dB is real at these margins.
3. **Can more than +1 dBm be granted?** `AdvertisingSetParameters.TX_POWER_HIGH` is *defined* as +1 dBm in the Android API — not a phone limit, an API ceiling costing ~7 dB versus what the chipset can physically do. The Phase 0 probe cycles 1→4→7→10→13 dBm and reports what is actually granted. **Untested. Takes two minutes and needs no distance.**
4. **Campus path-loss exponent** — unmeasured.

---

## 5. Not done yet

- **v2 fountain code.** Round-robin currently needs `k·H(k)` receptions; an XOR/LT code needs ~`1.1k`. Measured baseline: 167 packets to deliver a 70-character message through 95% loss. Theory predicts ~110 with the upgrade — roughly 35% faster. The v1-vs-v2 comparison is itself a result worth writing up.
- **Retry policy** for messages that never get ACKed.
- **Field trial:** PDR and time-to-delivery vs distance vs height, run at two times of day to capture 2.4 GHz interference.

---

## 6. How to use it

1. Install the APK on **both** phones (Android only — iOS has no Coded PHY API at all).
2. Turn **Bluetooth ON** on both. **Do not pair them** — there is no connection to make.
3. Open the app, allow **Nearby devices**.
4. Tap **⚙ Setup** and set the **same passphrase** on both (default `anits300`). Different passphrases = messages arrive and are silently discarded.
5. Type, press **Send**.

Reading the screen:
- `> text` outgoing · `< text` incoming · `✓ delivered` the ACK came back
- `phy=CODED` is the one to watch — if it reads `1M`, the radio silently downgraded
- `pkt/s` above 0 means the two phones can hear each other, even with no messages sent

**Note for OnePlus/Oppo/Xiaomi:** ColorOS and MIUI throttle background Bluetooth aggressively. Keep the app in the foreground and disable battery optimisation for it, or the packet rate silently collapses and looks like a radio problem.

---

## 7. Status summary

| | |
|---|---|
| **Works today** | Encrypted, bit-exact text between two Android phones over Bluetooth, with live link telemetry and CSV logging |
| **Proven range** | ~5 m (desk) |
| **Predicted range** | 300 m at 2.5 m height with line of sight |
| **Biggest risk** | RX sensitivity unverified; line-of-sight requirement |
| **Cheapest next win** | The TX power probe — 2 minutes, no distance needed, potentially +9 dB |
