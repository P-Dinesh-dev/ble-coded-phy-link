# BLE Coded PHY Text Link

A two-phone, **Bluetooth-only, text-only** messaging link built on BLE 5 **Coded PHY (S=8)** and **connectionless extended advertising**. No pairing, no GATT connection, no internet, no extra hardware — two Android phones and nothing else.

Target range: **300 m** line-of-sight.

---

## Status

| Phase | What it is | State |
|---|---|---|
| **0** | Handset viability probe | ✅ **Passed on hardware** — `primary=CODED` confirmed |
| **1** | Link calibration logging | ✅ Built — CSV logger, awaiting field data |
| **2** | Messaging protocol | ✅ Built + 10 passing JVM tests |
| **3** | Field trial logging | ✅ Built (same logger as Phase 1) |

**Not yet measured:** RX sensitivity, and anything at distance — the link is verified at ~5 m only.
See [CONDITION.md](CONDITION.md) for a dated status report, and [Open questions](#open-questions).

---

## Why connectionless

A GATT connection needs the link to close **bidirectionally, continuously**, with a supervision timeout that tears everything down on a fade. At the margins we're working with, that's hopeless.

A **broadcast** needs one packet to survive the trip, once.

So this is built entirely on non-connectable, non-scannable extended advertisements. A fade costs you a packet, not the session. There is nothing to reconnect, nothing to time out, and no pairing step.

---

## Measured link budget

These are numbers read off real hardware, not datasheet figures.

```
TX power                 −1..+1 dBm     <- see note
TX antenna                  −1 dBi
RX antenna                  −1 dBi
RX sensitivity (Coded S=8) −102 dBm     <- ASSUMED, not yet measured
                          ─────────
Gross                       101 dB
Fade margin                  −3 dB
                          ─────────
Usable                       98 dB
```

> **The +1 dBm is not a phone limitation.** `AdvertisingSetParameters.TX_POWER_HIGH` is *defined* as +1 dBm in the Android API. Extended advertising — the only path that can carry Coded PHY — cannot request more via the named constants. This costs ~7 dB versus what the chipset can physically do, and it is the single biggest practical constraint on the whole system.
>
> Observed on hardware: the probe reported **+1 dBm** and the messenger reported **−1 dBm** for the same request. Either the controller reports achieved rather than requested power, or it varies with radio state. Budget accordingly — 2 dB is real at these margins.

### Range, two-ray ground-reflection model

`PL = 40·log₁₀(d) − 20·log₁₀(h₁) − 20·log₁₀(h₂)`

Margin against the 98 dB budget, with both phones at height *h*:

| Distance | h = 1.5 m (standing) | h = 2.5 m (compound wall) | h = 3 m |
|---|---|---|---|
| **300 m** | +6.0 dB | **+14.8 dB** | +18.0 dB |
| **400 m** | +1.0 dB | +9.8 dB | +13.0 dB |
| **500 m** | −2.9 dB ✗ | +6.0 dB | +9.1 dB |

**Height dominates.** Going from 1.5 m to 2.5 m is worth **+8.9 dB** — more than Coded PHY's own +12 dB coding gain buys you in cluttered propagation. A compound wall is not optional.

Model validation so far: at 5 m with phones lying flat, predicted −72 dBm, **measured −70 dBm**.

---

## Protocol

### Packet — 24 bytes of manufacturer data (company ID `0xFFFF`)

```
[0]     sender    random per install
[1]     msgId     rolling 0–255
[2]     totalLen  ciphertext length; 0 marks a control packet
[3]     esi       encoding symbol index; on control packets 0=beacon, 1=ACK
[4..23] symbol    20 bytes
```

Beacon and ACK must be distinguishable, or an idle beacon would false-ACK `msgId 0` once the
counter wraps 255→0.

Payloads are kept small deliberately. At S=8 you're at 8 µs/bit, so packet error probability scales with airtime — a long PDU at −102 dBm is a PDU that dies.

### Pipeline

```
text → compress (only if smaller) → AES-256-GCM (once) → fragment → advertise
```

**Encrypt-then-fragment**, never the reverse: one 16-byte tag per *message* instead of per packet. At 20-byte symbols, a per-packet tag would eat 60% of throughput.

- **Compression** is raw DEFLATE, applied only when it actually shrinks the input. A 1-byte flag *inside the ciphertext* records which was used.
- **Nonce** is 12 random bytes prepended to the message — no counter to persist, no rollover.
- **Key** is PBKDF2-HMAC-SHA256 over a shared passphrase. Fixed salt, because both phones must derive the same key with no exchange.
- **Fountain:** round-robin symbol rotation (v1). See [Roadmap](#roadmap).
- **Idle beacon:** when there is nothing to send the radio beacons rather than going silent, so
  `pkt/s` and RSSI stay live at distance without anyone having to send a message. That is what
  a range walk needs — you can watch RSSI decay as you walk, hands free.

### The exactness guarantee

Three independent layers, none of which can produce garbled output:

1. **BLE PHY CRC** — corrupt PDUs never reach the app.
2. **Reassembly** — emits only when every symbol slot is filled; partial data produces *nothing*.
3. **AES-GCM tag** — proves the plaintext is bit-identical. Forgery probability ≈ 2⁻¹⁰⁰.

**Either the exact message arrives, or nothing does.** This is verified, not asserted — see below.

---

## Build and run

Requires Android Studio, JDK, and **two Android phones that support Coded PHY** (Android-to-Android only — iOS `CoreBluetooth` exposes no Coded PHY API).

```bash
git clone <this repo>
cd codedphy-probe
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on **both** phones, or press Run ▶ in Android Studio with each connected over USB debugging.

> **Windows build note:** if Gradle fails with `Unable to establish loopback connection`, your user path contains a space, which breaks JDK 25's AF_UNIX NIO pipes. Build with:
> ```bash
> TMP=/c/gtmp TEMP=/c/gtmp TMPDIR=/c/gtmp ./gradlew assembleDebug
> ```
> Android Studio's Run button is unaffected.

### Using it

1. Turn **Bluetooth on** on both phones. **Do not pair them** — there is no connection to make.
2. Launch the app, grant the **Nearby devices** permission.
3. Set the **same passphrase** on both (defaults to `anits300`). Different passphrases = messages arrive and are silently discarded.
4. Type, press **Send**.

Tap **⚙ Setup** for the passphrase, distance label and Phase 0 probe; it stays collapsed so the
composer remains reachable with the keyboard up.

`> text` is outgoing, `< text` is incoming, `✓ delivered` means an ACK came back.

The status line reads:
```
id=  pkt/s=  rssi=  phy=  tx=  sent=  ack=
```
`phy=CODED` is the one to watch — if it says `1M`, the radio silently downgraded.

**Open Phase 0 probe** opens the handset diagnostic (capability flags, granted TX power, per-packet PHY).

---

## Tests

```bash
./gradlew testDebugUnitTest
```

`Message.kt` is pure JVM, so the exactness guarantee is tested rather than assumed. **10 tests, 0 failures:**

- Round trips at 0 / 50 / 60 / **95%** packet loss
- **Every message length from 1 to 200 characters**
- Unicode, including Telugu
- Wrong passphrase → `null`
- **Every single-bit corruption across 200 byte positions → `null`, never text**
- Reused `msgId` with differing lengths does not mix messages
- ACK packets never parse as data

Measured: **167 transmitted packets** to deliver a 70-character message through 95% loss.

---

## Data logging

Every second, plus one row per event, to
`Android/data/com.dinesh.codedphyprobe/files/link_log.csv`:

```
time,label,event,pps,rssi,phy,granted_tx,sent,delivered
```

Events: `TX`, `RX`, `ACK`, `BADTAG`, `MARK`. The distance field stamps a label into the log so a range walk can be analysed afterwards.

---

## Open questions

- **RX sensitivity is assumed at −102 dBm and unverified.** With only 98 dB of budget this is the largest unknown — a 5 dB error either way flips the 400 m result. Resolved by walking out in 25 m steps and finding the RSSI at 50% packet delivery.
- **Does requesting >1 dBm TX get granted?** The probe cycles 1→4→7→10→13 dBm and reports what the controller actually returns. If anything above 1 is granted, every range figure improves.
- **Campus path-loss exponent.** Fit `PL(d) = A + 10n·log₁₀(d)` from the walk data to replace the two-ray prediction with a measured model.

---

## Roadmap

- **v2 fountain code.** Replace round-robin with XOR/LT combinations. Coupon-collector says round-robin needs `k·H(k)` receptions vs ~`1.1k` for a fountain — theory predicts ~110 packets against the measured 167, a ~35% gain. The v1-vs-v2 comparison is itself a result worth publishing.
- **Retry policy** for messages that never get ACKed.
- **Field trial:** PDR and time-to-delivery vs distance vs antenna height, run at two times of day to capture 2.4 GHz interference.

---

## Limitations

- **Android only.** iOS exposes no Coded PHY API and has no published roadmap for one.
- **Line of sight required.** One exterior concrete wall costs 10–20 dB, which is the entire margin.
- `isLeCodedPhySupported()` **is unreliable** — some phones return `true` and receive nothing. Run the Phase 0 probe before trusting any handset.
- OEM battery management (MIUI, ColorOS, FuntouchOS) throttles background BLE aggressively. Keep the app in the foreground and disable battery optimisation.
- Max message ≈ 226 bytes after compression.
