# Using the ESP32 so the head unit gets OBD data fast: bridge, UART tap, direct K-line, or buy an STN adapter

Research date: 2026-10-26. Car: Toyota Corolla E12 T-Sport (2ZZ-GE), EU ~2002, ISO 9141-2 (confirmed by `ATSP0`/`ATDP` on the real car). Adapter: Konnwei KW905 (ELM327 "v1.5" clone, BLE only in practice). Head unit: Chinese Android unit that only sees Bluetooth Classic. Boards: `esp32dev` and M5StickC Plus2 (both original ESP32, so Classic SPP works; S2/S3/C3 do not have it).

Legend: **[V]** verified in a primary source I read (standard text, datasheet, source code) or on our own hardware. **[R]** reported: README, vendor page, forum, benchmark by a third party, or my own arithmetic from [V] numbers. **[U]** uncertain / not found / sources disagree. Where sources disagree I say so.

Builds on `esp32-obd2-lessons.md`, `2zz-ge-obd.md`, `elm327-clones.md`, `popular-obd-adapters.md` (not repeated here).

---

## 0. Short answer

- The ELM327 clone is slow mainly because of **its own waiting**, not because the K-line is slow. ISO 9141-2 as written allows roughly **7-8 requests/s** on one ECU; the clone does 2-3.5/s. [R, derived from [V] numbers in section 2]
- **Option A (BLE bridge) does not make anything faster.** It adds roughly **60-120 ms per request** (typical) on top of the clone's 280-500 ms. It is the quick way to get any data onto the head unit; it is not a speed fix.
- **Option B (solder to the KW905 UART) saves only ~35-70 ms versus A**, keeps the clone firmware (AT commands only), and needs a teardown nobody has published. Not worth it.
- **Option C (ESP32 talks K-line itself, ELM-text over Classic SPP)** is the only option that removes the 200 ms clone wait. Expect **4-6.5 requests/s through the app unchanged** (RPM about 2-3 Hz), and more with a read-ahead cache in the dongle. Cost ~EUR 10-20, effort 1-3 weeks of evenings, all of it reversible. Two independent open-source ESP32/Arduino K-line libraries report 8-9 and up to 20 requests/s. [R]
- **Option D (OBDLink LX / STN)** is the no-build route: published ISO 9141-2 rate of an OBDLink MX is **4.2-4.9 PIDs/s** [R, vendor-app benchmark, on a simulator], about 2x the clone, for EUR 60-90.
- Before buying or building anything there are **free software experiments** on the KW905 (count digit check, lower `ATST`, BLE connection priority). They are untested on this car and could already give ~1.3-1.8x. See section 5.0.
- **Recommendation for this owner: Option C in two stages** (stage 1: the same transceiver board used as a passive K-line logger next to the KW905, which also answers the open timing questions; stage 2: ESP32 takes over). Keep A (about a day of work, reuses `esp32-obd2/src/ble_link.cpp`) only as a stopgap if he wants data on the head unit this week.

---

## 1. Where the ~0.3-0.5 s per request goes

### 1.1 What was measured on this car (own hardware)

| Source | Path | Per request | Notes |
|---|---|---|---|
| `esp32-obd2-lessons.md` §1 (session, 2026-10-21) | ESP32 -> BLE -> KW905 | 0.45-0.5 s | 5 PIDs = 2.2-2.5 s; 3 PIDs = 1.5-1.75 s. [V] |
| `esp32-obd2/hwtest-report-m5.md` (same day, 15:49-15:53, `Live:` lines) | ESP32 -> BLE -> KW905, AT2/AT1, count digit **off** | **0.28-0.30 s** | Round = RPM + speed + 1 rotating PID = 3 bus requests in "last 825-905 ms" (my reading of `lib/obd/src/live_poller.cpp:66-83`; `ATRV` and the 0101/0103 status request are extra and rare). AT2 1.10 vs AT1 1.00 rounds/s, i.e. only ~10%. [V] |
| K-Line Dash today (owner) | Android -> BLE -> KW905 | 0.45-0.5 s | Owner's figure. Whether the response-count digit was actually active is not known (see 1.3). [V by owner, U on config] |

The two ESP32 numbers were taken on the same day with the same adapter and disagree by 1.6x. I do not know why (different round composition in the earlier build, ECU state, or adapter state). Treat 0.28-0.5 s as the clone's real range.

### 1.2 Budget model for ONE request on ISO 9141-2 with an ELM327-class chip

| Phase | Time | Source |
|---|---|---|
| Tester sends 6 bytes (`68 6A F1 01 0C cs`), P4 >= 5 ms between bytes, 0.96 ms per byte at 10.4 kbaud | ~31 ms | P4 [V ISO 9141-2 Table A.2], arithmetic [R] |
| ECU answers: P2 = 25-50 ms (key word 08H) | 25-50 ms | [V ISO 9141-2 §12 + Table A.2] |
| Response 8 bytes (`48 6B xx 41 0C hh ll cs`) incl. P1 gaps (0-20 ms allowed) | ~8-20 ms | P1 [V], arithmetic [R] |
| **ELM waits the ST timer for "more responses"**: default `PP 03` = 0x32 x 4.096 ms = **~205 ms** | ~205 ms | [V ELM327 datasheet, ST command + PP 03 table] |
| Sum | **~270-310 ms** | [R] |

This model predicts exactly the 0.28-0.30 s seen in the ESP32 hwtest. So the dominant cost is the ~200 ms "final wait", not the bus (~65-100 ms of real traffic). The datasheet says three ways to shrink it:

1. **Response-count digit** (`010C1`): ELM returns right after n responses, skipping the final timeout (v1.3+). [V datasheet p.33/p.53 as cited in the esp32-obd2 README]
2. **Adaptive timing** (`ATAT1` default, `ATAT2`): learns a shorter timeout, never longer than `ATST`. [V datasheet] On this clone AT2 gave only ~10%, so either it is weak or not implemented [U].
3. **`ATST hh`** (hh x 4 ms): lowering the cap directly. [V datasheet] Sensible floor for ISO 9141 is P2 max 50 ms => `ATST 0D` (52 ms) or safer `ATST 14` (80 ms); OBD Now uses 100 ms (`ATST 19`) as its default. [R, OBD Now guide]

Caveat: the model is a fit, not a proof. The OBD Now benchmark (2.2) shows a BLE Kiwi 3 at the app's 100 ms timeout reaching only 2.8-3.5 requests/s on ISO 9141-2 (about 300-350 ms each), where the model would predict ~180 ms plus link time. So lowering `ATST` will help, but perhaps 1.3-1.8x rather than 2-3x. [U]

The remaining gap between 0.3 s (ESP32) and 0.45-0.5 s (Android app) is not explained by the clone. Candidates, all [U]: Android BLE connection interval (AOSP BALANCED is **30-50 ms**, HIGH is up to 15 ms [V AOSP config commit], versus Bluedroid's compile-time 12.5-15 ms on the ESP32 [V `btm_ble_api.h`]), `010C1` rejected by the clone and re-sent (`ResponseCountSuffix` in `PollConnection.kt` falls back for the rest of the connection), app-side scheduling delay. K-Line Dash already has `requestConnectionPriority(HIGH)` on its to-do list (`popular-obd-adapters.md` recommendation 7).

### 1.3 What K-Line Dash sends (so an emulator knows what to implement)

From `Elm327Session.kt`, `PollConnection.kt`, `DebugScreen.kt`, `diag/`:

- Setup: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`; identity: `ATI`, `AT@1`, `STI` (a `?` is a valid answer); protocol: `ATSP0` then `ATDPN` / `ATDP`; fallback ladder `ATSP<n>` incl. Toyota profile `ATIB96 ATIIA13 ATSH8113F1 ATSW00 ATSPA4`; `ATRV`.
- Discovery: `0100`, `0120`, ... (chained). Polling: Mode 01 PIDs, **with a trailing `1` (response-count digit)** appended to every OBD request unless the clone rejects it (`ResponseCountSuffix`). Also `0101`, `0103`.
- Diagnostics: `03`, `07`, `04`, Mode 02 freeze frame, `0902`, `090A`.
- Init timeouts the app allows: 25 s for the first `0100` after `ATSP0` (5-baud init), 10 s for a saved protocol. A direct-K-line emulator fits easily.
- The app never sends a bare CR and resyncs with a single space after a timeout (`RESYNC_CHAR`), as the ELM datasheet requires.

---

## 2. Reference numbers

### 2.1 ISO 9141-2 timing (primary source, read in full)

ISO 9141-2:1994(E) is publicly hosted (US CFR incorporation) at https://law.resource.org/pub/us/cfr/ibr/inc/iso/iso.9142-2.1994.pdf (the URL says 9142 but the document is ISO 9141-2). **[V]**, Tables A.1 and A.2 (times from end of stop bit to falling edge of next start bit):

| Symbol | Min | Max | Meaning |
|---|---|---|---|
| P1 | 0 | 20 ms | inter-byte, ECU -> tester |
| P2 (key word 94H) | 0 | 50 ms | between request and first response, and between multiple responses |
| P2 (key word 08H) | **25 ms** | **50 ms** | same, when synchronisation is by timing only (key words 08 08) |
| P3 | **55 ms** | 5 s | end of last response -> next tester request. Longer than 5 s: ECU assumes the session is over and needs re-init |
| P4 | 5 ms | 20 ms | inter-byte, tester -> ECU |
| W0 | 2 ms | - | bus high before the address byte |
| W1 | 60 ms | 300 ms | end of address byte -> sync byte `55` |
| W2 | 5 | 20 ms | sync -> key word 1 |
| W3 | 0 | 20 ms | key word 1 -> key word 2 |
| W4 | 25 | 50 ms | key word 2 -> tester's inversion; and tester's inversion -> ECU's inverted address (`CC`) |
| W5 | 300 ms | - | bus idle before the tester repeats a failed init |

Other facts from the same text [V]: the tester sends the address `33H` at **5 bit/s +-0.5%** (200 ms per bit, so +-1 ms per bit), the data rate is 10.4 kbit/s +-1%, the tester K-line has a **510 ohm +-5% pull-up to Vs** and must work from 8 to 16 V, a tester may not drive K above Vs or below -1 V, and the ECU side must survive 20 V DC permanently and 24 V for 30 min. Worst-case init + header time is 2.44 s.

**Throughput ceiling, one ECU, strictly by the standard** [R, my arithmetic from the table]:

| Case | Request (6 B + 5 gaps of P4) | P2 | Response (8 B + P1) | P3 | Cycle | Requests/s |
|---|---|---|---|---|---|---|
| Best compliant | 31 ms (P4 = 5) | 25 | 8 | 55 | **~119 ms** | **~8.4** |
| Typical ECU | 31 | ~40 | ~10-15 | 55 | **~135-140 ms** | **~7** |
| Worst compliant | 31 | 50 | 8 + 7 x 20 = 148 | 55 | ~285 ms | ~3.5 |

If the ECU spends longer than P2 max, it is non-compliant and nothing here applies. Whether the 2ZZ-GE ECU is in the best or the typical row is **unknown [U]**; nobody has published a capture of a Toyota ISO 9141 ECU (confirmed again in this research). Key words of this car are unknown too (`ATKW` prints them on an ELM).

### 2.2 Real measurements of direct K-line polling (open source)

| Source | Result | Notes |
|---|---|---|
| [iwanders/OBD9141](https://github.com/iwanders/OBD9141) README (MIT) | "performance of up to **20 requests per second** has been achieved" by tuning the header timings; "6 readings per second was the maximum with the Bluetooth dongle" on the same car | One 2004 Kia, Teensy 3; ESP32 example `reader_esp32` contributed in issue #38. Defaults: `INTERSYMBOL_WAIT` 5 ms, answer timeout 30+20 ms. 20/s = 50 ms per request, i.e. well below the 119 ms the standard allows (P3 55 ms + P4 5 ms are violated). [R] |
| [muki01/OBD2_KLine_Library](https://github.com/muki01/OBD2_KLine_Library) README (GPL-3.0) | "Measured read speed (average responses per second): **ISO 9141-2 about 8-9**, ISO 14230-4 about 9-10" | ESP32/Arduino, dedicated transceivers or discrete circuits. Matches the standard's ~7-8/s ceiling. [R] |
| OBD Now guide (GLM Software) ([PDF](https://glmsoftware.com/Documentation/OBDNowUserGuide.pdf), p.44-45, 47-48) | OBDLink MX Bluetooth Classic on an OBD simulator, ELM timeout 100 ms, 5 Android devices: **ISO 9141-2 4.21-4.91 PIDs/s**; ISO 14230-4 5-baud 5.6-6.5; fast init 6.0-6.5; CAN 11/500 27-58. Kiwi 3 (BLE, a different, cheaper adapter), same ELM timeout: ISO 9141-2 **2.84-3.5**, CAN 11/500 10-26 | Simulator, not a car. Vendor's own app, vendor's own claim "OBDLink MX is in a class by itself". Note the CAN column: BLE adapters were slower than Classic here, in line with the Car Scanner author's advice. [R] |
| esp32-obd2 hwtest (ours) | 3.3-3.6 requests/s (0.28-0.30 s each), AT2/AT1 | clone via BLE [V] |

These disagree by 2-4x on the same protocol because they differ in what they wait for: OBDLink/STN honours the P3 gap (56 ms default, see 2.3) and adaptive timeouts; the libraries wait for exactly the expected number of bytes; the clone waits a fixed ~200 ms.

### 2.3 STN chips (OBDLink) on ISO 9141-2

- STN FRPM (OBDLink Family Reference & Programming Manual, [PDF](https://www.scantool.net/scantool/downloads/678/obdlink_frpm_e.pdf)) [V]: `STPTRQ` "minimum time between the last response and the next request ... for ISO 9141-2 and ISO 14230-4 this is the P3 timing. **The default is 56**" ms (`PP 01` default `0F`, ~59 ms); `STPTO` (OBD request timeout) "**Default is 102 ms**" (PP 03), i.e. half of the ELM327's ~205 ms; `STIAT` adaptive P1 max timing for ECUs that break the P2-min rule; ISO keep-alives handled automatically. (The "300 ms" default in the same manual belongs to `STPTOT`, the bus-access timeout before `BUS BUSY`, not to the response wait.)
- Vendor claims "up to 4 times as many samples per second as the closest competitor" and "~100 PIDs/s" for the LX ([OBDLink LX page](https://www.scantool.net/obdlink-lx/)). Those are CAN numbers. For ISO 9141-2 the only published figure I found is the OBD Now benchmark above (4.2-4.9/s). I found no K-line benchmark of OBDLink LX specifically, and no K-line figure for vLinker/Vgate. [U]

### 2.4 Link latencies (ESP32/Android)

| Link | Latency | Source |
|---|---|---|
| Bluetooth Classic SPP, ESP32 <-> ESP32 (Bluedroid) | 12.9 ms (12 B), 12.6 ms (128 B) | [R] [Electric UI benchmark](https://electricui.com/blog/latency-comparison) (one-way "TX start to RX", boxplot medians) |
| BLE, ESP32 Bluedroid notify | 14.6 ms median; write-without-response **20 ms median, some > 40 ms**; bands ~7 ms apart (connection interval) | same [R] |
| ESP32 Bluedroid default LE connection interval | 12.5-15 ms (`BTM_BLE_CONN_INT_MIN_DEF` 10, `_MAX_DEF` 12 units of 1.25 ms), slave latency 0 | [V] [btm_ble_api.h](https://raw.githubusercontent.com/espressif/esp-idf/v5.1.4/components/bt/host/bluedroid/stack/include/stack/btm_ble_api.h). Actual interval is whatever is negotiated; the KW905 may request another one [U]. |
| Android default (`CONNECTION_PRIORITY_BALANCED`) | 30-50 ms interval; HIGH <= 15 ms; LOW_POWER 100-125 ms | [V] [AOSP commit](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/0ec1e3f%5E%21/) (11.25 ms lower bound for HIGH is from a Flutter plugin, [U]) |
| Android SPP/RFCOMM | no published figure found; expect ~10-30 ms one way | [U] |

---

## 3. The options

### Option A: BLE bridge (ESP32 = BLE central to the KW905 + Classic SPP server for the head unit)

**What it is.** Transparent byte pipe. Reuses the working `src/ble_link.cpp` of `esp32-obd2` (ESP-IDF GATT client with the filtered `FFF0` search that the KW905 needs, 20-byte chunks, CCCD wait) and adds a `BluetoothSerial` (or `esp_spp`) server. The KW905 stays a black box.

**Added latency per request** (request out + response back), [R] from the section 2.4 numbers:

| Hop | Typical | Bad case |
|---|---|---|
| head unit -> ESP32 SPP write (6-byte command) | 10-20 ms | 30 ms |
| ESP32 -> KW905 BLE write-without-response (waits for next connection event, avg half an interval) | 10-20 ms | 40+ ms (50-100 ms if the KW905 forces a slow interval) |
| KW905 -> ESP32 BLE notification (response <= 20 B fits one notification; sent at next event) | 10-15 ms | 40 ms (up to 100 ms with a 100 ms interval) |
| ESP32 -> head unit SPP | 10-20 ms | 30 ms |
| ESP32 processing | 1-3 ms | 5 ms |
| **Total added** | **~60-90 ms** | **~150-250 ms** |

Is it noticeable? Against the clone's 280-500 ms per request it is **+15-30%** (A's request rate ~1.7-2.9/s vs 2.0-3.5/s). It will not feel different from today's ~1 Hz RPM; it will not feel better either. Note the K-Line Dash app currently pays an Android BLE interval (30-50 ms, twice) on its direct BLE path; with A it pays SPP + a 12.5-15 ms ESP32 BLE hop instead, so A may be about **neutral versus the app-direct-BLE 0.45-0.5 s**. [U, inference; measure]

The KW905's negotiated connection interval is **unknown [U]**. `esp32-obd2` never logged it. One line in `ble_link.cpp` (log `ESP_GAP_BLE_UPDATE_CONN_PARAMS_EVT` / the connect event's `conn_params`) answers it.

**Other facts.** Requires: KW905 button pressed, one BLE central only (the app must not hold it), ~30 min auto-sleep (`esp32-obd2-lessons.md`). Memory: the esp32-obd2 README measured only ~13 KB free heap with BLE + Classic + WiFi + web server; a bridge without WiFi/web server needs a rebuild but both radios in dual mode are a supported ESP32 configuration [R, not tested by us]. Effort ~1-2 days. Hardware cost EUR 0. Risk low (nothing touches the car). Enables later: nothing except "head unit can see the KW905".

### Option B: solder the ESP32 onto the KW905's internal UART

**What is known about the KW905 internals: almost nothing public.** No teardown, FCC-internal-photo set or schematic found (searches for KW905/KW902/KW903/KW906 teardowns returned only product pages and a Leaf-forum thread about a different bad KW902 board revision).

- Chip claims conflict: one manual's spec table (not retrievable by me, Cloudflare) is reported to list "PIC25K80", probably a truncated PIC18F25K80; the KW902 manual says the adapter uses "a fast and reliable ARM chip"; retailers say BT 5.0 / 5.2. **[U]**
- Generic "v1.5" Classic boards are PIC18F25K80 + a cheap BT chip (YC1021/BK3231/CSR) + 24Cxx EEPROM; their MCU<->BT UART is 38400 baud by default or 115200 in alternative firmware, per [ediabaslib: Replace Elm327 BT Mini firmware](https://github.com/uholeschak/ediabaslib/blob/master/docs/Replace_Elm327_BT_Mini_Firmware.md) [R, for those boards, not the KW905]. That page does not state UART logic level or pinout.
- The KW905 shows up in a Classic inquiry but refuses every Classic connect and exposes a BLE UART (`FFF0/FFF1/FFF2`), so its radio is probably a dual-mode module of unknown make with a quirky firmware [U].

**Latency saved vs A:** removes the BLE hop pair, so the added delay becomes SPP both ways (~25-50 ms) plus UART time (a 14-byte reply at 38400 baud is 3.6 ms): **~35-70 ms better than A**, still ~25-50 ms worse than the bare clone.

**More possibilities?** No. The clone firmware is unchanged: AT commands only (`ATST`, `ATAT`, `ATSW`, `ATPP` if implemented [U], `ATKW`). Those are all reachable over BLE already, with no soldering. Re-flashing the MCU (if it is a PIC with ICSP pads and no code protection) is a different project and proven only for generic PIC18F25K80 boards [R].

**Risks:** opening a glued case, unknown 3.3 V vs 5 V logic (ESP32 pins are not 5 V tolerant), cutting or isolating the BT module's UART, powering the ESP32 separately, losing the button/sleep behaviour, irreversible. **Verdict: skip B.** If he wants a bridge without BLE, build C instead.

### Option C: ESP32 talks K-line directly, presents an ELM327 text interface over Classic SPP

**Why it is faster.** Per request: 31 ms send + P2 (25-50) + 8-20 ms response + wait for further ECUs. The extra-ECU wait can be bounded by P2 = 50 ms, and **it overlaps with the mandatory P3 = 55 ms gap**, so the bus cycle is ~120-150 ms instead of ~300 ms (section 2.1). The 200 ms ST wait of the clone simply disappears.

**Rates through the unchanged app** [R]:

| Mode | Per request at the app | Requests/s | RPM if RPM is every 2nd request |
|---|---|---|---|
| Pass-through, compliant timings (P4 5 ms, P3 55 ms) | bus 120-150 + SPP RTT 25-50 + app 5-20 = ~150-220 ms | **~4.5-6.5** | **2.3-3.3 Hz** |
| Pass-through, tightened (P4 2 ms, P3 20-30 ms; non-compliant, ECU-dependent, see OBD9141's 20/s) | ~90-130 ms | 7-11 | 3.5-5.5 Hz |
| Read-ahead cache in the dongle (C2, see 6.4) | app sees ~30-50 ms; bus runs flat out on the PIDs the app asked for recently | bus 6-8/s shared; RPM-priority gives RPM **4-5 Hz** | data up to one bus cycle old |

**Does the Toyota ECU answer within P2, so that 5-10 req/s is realistic?** The standard requires <= 50 ms, so a compliant ECU supports ~7/s. Nothing published confirms it for the 2ZZ-GE ECU **[U]**. The only indirect evidence is that the cheap clone's 0.28 s per request is consistent with ECU latency anywhere between 25 and ~100 ms, so it does not discriminate. Measure it (section 7).

**ECU/vehicle quirks.**
- This car initialises with plain `ATSP0`/ISO 9141-2 address `0x33`, 10400 baud [V on car]. The Toyota 0x13 / 9600 / `81 13 F1` KWP variant from the AU/JDM Torque init is **not needed** on this car (`esp32-obd2-lessons.md` §1), but belongs in a later "other Toyotas" profile (`ATIB96 ATIIA13 ATSH8113F1`, needs KWP framing with a format byte).
- Session keep-alive: the ECU drops the session if P3 exceeds 5 s [V]. The ELM sends its own wake-up (`68 6A F1 01 00`-style) after 3 s idle [V datasheet]; the dongle must do the same and must re-init (2-3 s) after a lost session.
- Mode 03/07 answers can be several frames, possibly from several ECUs; wait P2 (50 ms) after each frame.
- Mode 04 must only ever be sent when the app sends it; never repeat a command on a bare CR.
- Pre-CAN Toyotas likely answer `NO DATA` to Mode 09 (VIN) [U]; the dongle just passes the silence through as `NO DATA`.

**Electrical risks and mitigations.** Details in section 6. In short: use a real K-line/LIN transceiver (TI SN65HVDA195: 7-27 V supply, bus pin +-40 V, ESD protection quoted as up to +-12 kV HBM in the feature list, 3.3 V logic, [V datasheet](https://www.ti.com/lit/ds/symlink/sn65hvda195-q1.pdf)), 510 ohm pull-up per ISO 9141-2, a fuse and a reverse diode on the 12 V feed. The ESP32 never sees 12 V. Stage 1 below is receive-only.

**Effort.** Hardware: one evening. Firmware ~800-1200 lines: K-line layer 300-400 (adapt `OBD9141`), ELM text layer 300-400 (adapt `ELMulator`, test against the ELM behaviour already encoded in `esp32-obd2/scripts/elm_sim.py`), glue/robustness. 1-3 weeks of evenings.

**Reusable open source (licence matters):**

| Project | Use | Licence |
|---|---|---|
| [iwanders/OBD9141](https://github.com/iwanders/OBD9141) | ISO 9141 5-baud init and request/response with echo removal; has `reader_esp32`, a Mode 03/04/07 reader and an ECU **simulator** class (handy for a bench test with the second ESP32) | MIT |
| [jimwhitelaw/ELMulator](https://github.com/jimwhitelaw/ELMulator) | ESP32 Bluetooth Classic **and** WiFi (port 30000) ELM327 text front end; handles all AT commands, you plug in `handlePIDRequest()`; unregistered PIDs give `NO DATA` | MIT |
| [muki01/OBD2_KLine_Library](https://github.com/muki01/OBD2_KLine_Library) | ISO 9141/14230/KW1281 on ESP32, six transceiver schematics, reports 8-9 resp./s | **GPL-3.0** (viral; read-only reference unless the whole firmware is GPL) |
| [muki01/OBD2_K-line_Reader](https://github.com/muki01/OBD2_K-line_Reader) | WiFi web dashboard, **no ELM emulation** (README says so) | GPL-3.0 |
| [maximus64/opendiag-firmware](https://github.com/maximus64/opendiag-firmware) | ESP32-S3 (no Classic BT) ELM327-style AT interface over USB/BLE with ISO 9141-2 (protocol 3), CAN 6-9; "much of the ELM327 command set remains unimplemented" | GPL-3.0 |
| [Obeisance/Arduino_OBD_interface_for_Torque](https://github.com/Obeisance/Arduino_OBD_interface_for_Torque) | Closest prior art: Arduino + HC-05 + LM339 as ELM stand-in for ISO 9141; README says "so far the code is untested", no licence | none stated |
| [Ircama/ELM327-emulator](https://github.com/Ircama/ELM327-emulator) | PC-side ELM327 emulator to test K-Line Dash without a car | Apache-2.0 [U] |
| `esp32-obd2/scripts/elm_sim.py` (own) | Executable spec of ELM replies the app sees: echo until `ATE0`, `\r\r>`, lone CR repeats, legacy K-line VIN, zero-padded `43` | own |

I did not find an existing ESP32 project that combines a real ISO 9141-2 bus layer with a Torque/Classic-SPP ELM327 front end; the pieces exist (OBD9141 + ELMulator) and have to be joined. [R]

**Extension to CAN.** The ESP32 has one TWAI controller; it needs an external transceiver ([ESP-IDF TWAI docs](https://docs.espressif.com/projects/esp-idf/en/v5.1.4/esp32/api-reference/peripherals/twai.html): "does not contain an integrated transceiver", ready-made `TWAI_TIMING_CONFIG_500KBITS`). TI SN65HVD230 is a 3.3 V part, 1 Mbit/s, active ([TI](https://www.ti.com/product/SN65HVD230)). Adding `ATSP6` (ISO 15765-4 11/500) needs ISO-TP multi-frame handling (VIN, DTC lists) but then gives the same 20-100 PID/s a real ELM327 gives on CAN. Not needed for this car; makes the dongle useful for other cars.

### Option D: buy a faster Classic adapter (STN chip)

- **OBDLink LX**: Bluetooth 3.0 SPP, ISO 9141-2 listed, BatterySaver < 2 mA, "up to 100 PIDs/s" (CAN), list price **$89.95** on the vendor page (may be stale; street prices lower) ([page](https://www.scantool.net/obdlink-lx/)). The `FRPM` says STN honours P3 = 56 ms and adaptive timeouts [V].
- Expected on this car: **~4-5 requests/s** (OBD Now measurements for the MX, 2.1) => RPM about 2-2.5 Hz with RPM every 2nd request. That is a ~2x gain, roughly the pass-through number for C, but below C's tightened/read-ahead modes.
- Head-unit compatibility: OBDLink LX/MX+ use secure pairing without a PIN after pressing Connect (`popular-obd-adapters.md`); some Chinese head units cannot pair dongles from their own BT settings (`headunit-autostart.md` §5). K-Line Dash already detects `STI` and skips unsupported `ATAT2` on K-line.
- vLinker BM/MC+/FS (EUR 40-70): "ELM327 v2.2 with ST commands" [R], but **no K-line benchmark exists** [U]; Konnwei KW902/Veepeak mini are the same class as the current clone: no gain expected [R].

---

## 4. Comparison

Request and RPM figures are for this car, one ECU, RPM requested every 2nd request. "Now" = KW905 via BLE from the app.

| | Added latency / request | Requests/s | RPM update | Hardware | Effort | Risk | Enables later |
|---|---|---|---|---|---|---|---|
| Now (KW905, BLE, app) | - | 2.0-3.5 | 1.0-1.8 Hz (owner sees ~1 Hz) | - | - | - | - |
| 5.0 Software tweaks on the KW905 (digit, `ATST 19/14`, BLE priority) | 0 | 3.5-5 [U] | 1.7-2.5 Hz [U] | EUR 0 | hours | none (AT settings, reversible by `ATZ`) | knowledge about the clone |
| **A** BLE bridge | +60-90 ms typ., +150-250 bad | 1.7-2.9 | 0.9-1.5 Hz | EUR 0 | 1-2 days | low | head unit gets data; nothing else |
| **B** UART tap | +25-50 ms vs bare clone | 2.2-3.2 | 1.1-1.6 Hz | EUR 0-2 | 2-4 days + unknown teardown | medium (irreversible, unknown board) | nothing new (still clone AT set) |
| **C** direct K-line, pass-through | ELM wait gone: 150-220 ms total | **4.5-6.5** | **2.3-3.3 Hz** | EUR 10-20 | 1-3 weeks | medium, reversible | CAN via TWAI, WiFi TCP, logging, scope-like sniffer, Toyota KWP services, no sleep/button, own scheduler |
| **C2** + read-ahead cache | app sees 30-50 ms | bus 6-8/s | RPM 4-5 Hz | same | +1 week | same | per-signal rates tuned in the dongle |
| **D** OBDLink LX | -50% vs clone | 4-5 [R] | 2-2.5 Hz | EUR 60-90 | none | low (head-unit pairing) | nothing; closed |

Sources of the numbers: section 1 (clone), 2.4 (links), 2.1/2.2 (K-line, STN). All derived rates are [R], not measured on this car.

---

## 5. Recommendation for this owner

He likes building, has two original ESP32 boards, and wants the data to be fast. **Go for C, in stages**, with a free tuning round first. Reasons: it is the only option that attacks the real cause (the clone's 200 ms wait); the hardware is a ~EUR 10-20 daughterboard; every step is reversible; the board is also the best measuring instrument for the open questions; and it later extends to CAN and WiFi.

### 5.0 Stage 0 (hours, free): squeeze the KW905

On the existing KW905 and the existing app/firmware:
1. In K-Line Dash's comm log, check whether `010C1` is **accepted** (data/NO DATA) or got `?` and the digit was dropped (`ResponseCountSuffix` DISABLED). If dropped, the clone ignores the digit and that explains part of the 0.45-0.5 s.
2. Via the debug terminal (or `esp32-obd2` serial), try `ATST 19` (100 ms) then `ATST 14` (80 ms), then `ATST 0D` (52 ms); watch for `NO DATA`/garbled lines on `010C`. Also `ATAT2`. Record per-request time (`PollStats`).
3. Set BLE connection priority HIGH in the app (planned already).
4. `ATKW` (key words), `ATDP`, `AT@1` to log into this document.
If `ATST` works, the clone may reach ~4-5 req/s for free (see the caveat in 1.2). Then A (needed anyway for the head unit) is no longer a speed trap.

### 5.1 Stage 1 (one evening): the K-line board as a passive logger

Build the section 6 board **without the 510 ohm pull-up and with TXD held high**, put it on a Y-lead on OBD pin 7 next to the KW905, and let the ESP32 log every byte with microsecond timestamps from `UART2` at 10400 baud. The KW905 keeps polling, the sniffer only listens. You get, for the first time on this car: key words, ECU address byte (`xx` in `48 6B xx`), the true **P2 of the Toyota ECU**, the **clone's dead time** between response end and next request (the answer to "why 0.45 s"), and whether the clone adds its own wake-up frames. Zero risk to the car as long as the pull-up is not duplicated and TX stays recessive. A USB logic analyzer (8-channel clone, ~EUR 10, sigrok/PulseView) on K behind a 10 k/3.3 k divider does the same.

### 5.2 Stage 2 (1-3 weeks): the ESP32 becomes the interface

Add the 510 ohm pull-up, remove the KW905, implement init + request + ELM text layer (section 6.3), start with compliant timings, then shrink P3/P4 while counting errors. Test first on the bench with the second ESP32 running the OBD9141 ECU simulator, then in the car at idle against the KW905's numbers.

### 5.3 What not to do
- Do not build B. Do not build A as the end goal. A is justified only as a one-day stopgap (data on the head unit this week) or if Stage 0 shows the clone can reach ~5 req/s.
- Buy D (OBDLink LX) only if he stops liking this project: it is the guaranteed ~2x for money, but it ends the learning and the extension path.

---

## 6. Option C build details

### 6.1 Parts (indicative prices, check at purchase)

| Part | Why | Price |
|---|---|---|
| ESP32 DevKit (owned) or M5StickC Plus2 (owned) | `esp32dev`: UART2 on GPIO16/17, ADC on GPIO34. Plus2: HY2.0 port has G32/G33 + 5 V + GND ([M5 docs](https://docs.m5stack.com/en/core/M5StickC%20PLUS2)), has screen + 200 mAh battery | owned |
| TI **SN65HVDA195QDRQ1** (SOIC-8) | K-line capable LIN transceiver; "Supports ISO9141 (k-line)", 3.3 V I/O, bus +-40 V, ESD quoted up to 12 kV HBM | ~EUR 1-2 (LCSC about $1.09 each in cached listing; Arrow about $1.30) |
| SOIC-8 breakout / small perfboard | | EUR 1-2 |
| Alternative **L9637D** (ST, SO8) | The classic ISO 9141 chip; ~$1-2 | EUR 1-2. **Warning:** its logic side is supplied from VCC; hobby sources disagree whether 3.3 V works (a Land Rover ESP32 project uses 3.3 V, an Arduino-forum answer and another example say 5 V + level shifting). ST datasheet was not retrievable from here. [U] Prefer the HVDA195. |
| MC33290 | used by OBD9141; NXP "no longer manufactured", replaced by 33660 | avoid |
| Resistors: 510 ohm 1/2 W, 4 x 4.7 k, 1 k, 100 k, 22 k | 510 ohm = ISO 9141-2 tester pull-up (23.5 mA at 12 V) | EUR 1 |
| Caps: 100 nF x3, 10 uF x1 | decoupling, ADC filter | EUR 1 |
| Reverse diode (1N4007 or Schottky) + 250 mA fuse/PTC | feed protection | EUR 1 |
| Optional TVS (e.g. 24 V) at VSUP | load-dump margin | EUR 1 |
| OBD-II male plug with pigtail or J1962 breakout | pins 4, 5, 7, 16 | EUR 5-10 |
| Enclosure + heat shrink | | EUR 3 |
| Power: existing switched USB supply (as now) **or** 12 V -> 5 V buck | Pin 16 is permanently live: a 100 mA ESP32 would drain ~2.4 Ah/day. Use switched/USB power or implement a low-battery/ignition cut-off from the ADC reading | EUR 0-3 |
| **Total** | | **~EUR 10-20 beyond the ESP32** |

Alternative discrete circuits (transistors or an LM393/LM339 comparator) are documented in the muki01 README and the Obeisance repo; chips are easier and safer.

### 6.2 Wiring (text sketch, SN65HVDA195, pinout verified from the TI datasheet: 1 RXD, 2 EN, 3 NWake, 4 TXD, 5 GND, 6 LIN, 7 VSUP, 8 INH)

```
OBD-II (J1962)                         SN65HVDA195 (U1)                ESP32 DevKit
pin 16 +12 V (permanent) --[fuse 250 mA]--+--[1N4007/Schottky]--+-- pin 7 VSUP   (100 nF + 10 uF to GND)
                                           |                    |
                                           +--[510 ohm]---------+-- K node (not in stage 1)
                                           +--[100 k]--+--[22 k]-- GND ; node --> GPIO34 (ADC1) + 100 nF   (VBAT sense, ~2.7 V at 14.8 V)
pin 7  K-line  ---------------------------------------- pin 6 LIN  (and the 510 ohm K node above)
pin 4 + pin 5  GND ------------------------------------- pin 5 GND ------------------ GND
                                                          pin 1 RXD (open drain) --- GPIO16 (UART2 RX) + 4.7 k to 3V3
                                                          pin 4 TXD <--[1 k]-------- GPIO17 (UART2 TX)   (idle high = recessive)
                                                          pin 2 EN  ---[4.7 k]------ 3V3
                                                          pin 3 NWake --[4.7 k]----- VSUP
                                                          pin 8 INH  not connected
ESP32 powered from USB (switched), common chassis ground via OBD pins 4/5.
```
Notes: RXD is open drain and **needs** the external pull-up to 3.3 V; EN pulled high via 1-10 k and NWake tied to VSUP via 1-10 k when unused (TI layout guidelines); TXD is 3.3 V compatible. Because K is half-duplex, every transmitted byte appears again on RXD (echo): read and discard it. Do not put the 510 ohm in parallel with the KW905's own pull-up (stage 1); do not drive TXD low while the KW905 is connected. OBD9141's reference schematic uses 1 k plus a diode as the pull-up (LIN style); the standard says 510 ohm +-5% for a tester ([OBD9141 `.sch`](https://raw.githubusercontent.com/iwanders/OBD9141/master/extras/OBD9141_reader/OBD9141_reader.sch), ISO 9141-2 §8.3.1).

M5StickC Plus2 variant: RX = G32, TX = G33 (HY2.0 port), 3.3 V from the 3V3 header pin; power by USB-C. Note the repo's M5 build disables PSRAM because of IRAM pressure with BT + WiFi; a Classic-only build is lighter.

### 6.3 Firmware outline

Tasks (ESP32 Arduino or ESP-IDF; Classic BT SPP needs the original ESP32; `BluetoothSerial` is deprecated in arduino-esp32 core 4, so pin the core as `esp32-obd2` does or use `esp_spp_*` directly):

1. **SPP server** named `OBDII` (so K-Line Dash's name hints match), legacy PIN `1234` like the clones, or SSP "just works" for head units that cannot type a PIN (`headunit-autostart.md` §5). Line assembler: CR-terminated, ignore spaces/LF, never treat a lone CR as "repeat". Outputs `\r\r>` terminated replies; echo flag.
2. **ELM text layer** (state: echo, linefeeds, spaces, headers, adaptive timing, `ST` value, protocol): `ATZ`/`ATWS` (banner `ELM327 v1.5` for parity with today's profile; the app's `AdapterProfile` only enables `ATAT2` on CAN anyway), `ATI`, `AT@1`, `ATE/L/S/H n`, `ATAT n`, `ATST hh` (store), `ATSP n`/`ATSP0`/`ATSPA3`, `ATDP` (`ISO 9141-2` / `AUTO, ISO 9141-2`), `ATDPN` (`3` / `A3`), `ATRV` (ADC), `ATKW`, `ATKW0/1`, `ATSW`, `ATIB`, `ATIIA`, `ATSH`, `ATWM` accepted as `OK`; `STI` and everything else `?`. OBD hex commands: validate even length, strip an optional trailing count digit, pass to the bus layer; unknown service `?`. `SEARCHING...`, `BUS INIT: OK`, `UNABLE TO CONNECT`, `NO DATA`, `STOPPED` (a character during a running request aborts it), `<DATA ERROR` per datasheet. Use `esp32-obd2/scripts/elm_sim.py` as the oracle; run `Ircama/ELM327-emulator` or the K-Line Dash simulator for regression.
3. **K-line layer** (own FreeRTOS task, high priority, pinned to the core away from the BT stack):
   - `init_5baud(addr=0x33)`: bus idle high >= W0/W5, drive TX pin as GPIO (UART detached), shift out start + 8 data bits LSB first + stop at 200 ms per bit. Schedule bit edges on absolute `esp_timer_get_time()` timestamps (a GPTimer/`esp_timer` ISR-dispatch callback is better than `delay(200)`: the standard allows +-0.5% = +-1 ms per bit, equal to one FreeRTOS tick). Re-attach UART2 at 10400 8N1, read `0x55` in W1 (60-300 ms), key word 1/2 (W2 5-20, W3 0-20), after 25-50 ms send `~KW2`, expect `~0x33 = 0xCC` within 50 ms, then wait >= P3 (55 ms) before the first request. Matches the sequence coded in `OBD9141::initImpl`.
   - `request(svc, pid...)`: frame `68 6A F1 svc pid cs` (checksum = sum mod 256, ISO 9141-2 §11.2), bytes spaced by P4 (start 5 ms), read back and discard the echo, then collect response frames: first within P2 max (use 100-150 ms timeout while learning), each next byte within P1 (20 ms + margin), end of frame when the expected length is known (J1979 table) or after a 25 ms idle gap; then wait up to 50 ms for a second ECU's frame **inside the P3 gap**. Return all frames, with or without `48 6B xx` header (`ATH`). Honour the count digit by returning after n frames.
   - Keep-alive: if no request for 2.5 s send `68 6A F1 01 00` and drop the reply; on two failed requests or `NO DATA` for every PID, re-init (and report `BUS INIT: ...OK` / `UNABLE TO CONNECT` to the app); never retry Mode 04.
   - Counters: per-request time, P2 seen (min/avg/max), re-inits, checksum errors, all exposed on the USB serial console.
4. **Optional later**: WiFi AP + TCP 35000 (reuse `esp32-obd2/src/main.cpp`), TWAI/CAN, read-ahead cache (6.4), voltage-based sleep.

### 6.4 C2: read-ahead cache (the "smart" part)

K-Line Dash polls one request at a time and waits for `>`. The dongle can decouple that: it keeps a table of PIDs the app requested in the last few seconds, polls those on the bus continuously (RPM and speed weighted), and answers a new request for a cached PID immediately with the freshest value (`NO DATA` only if never fetched). The app then sees ~30-50 ms per request (SPP only) and can run its own scheduler faster than the bus. Caveat: values are up to one bus cycle (~120-300 ms) old, and Mode 03/04/07/09 must always bypass the cache. This is a refinement after pass-through works.

### 6.5 Risks, honestly

| Risk | Likelihood | Mitigation |
|---|---|---|
| ECU does not like tightened P3/P4 | medium [U] | start compliant; tighten stepwise; fall back automatically on errors |
| 5-baud init jitter | low | absolute timestamps / GPTimer; OBD9141 already works on ESP32 per its issue #38 |
| Toyota-specific quirk (key words, response header, slower P2) | low-medium [U] | Stage 1 sniffing shows real values before any code is written |
| Head unit cannot pair the ESP32 | low-medium | SSP "just works" or fixed PIN 1234; pair with a "Bluetooth pair" app as other users do (`headunit-autostart.md`) |
| Electrical (12 V into ESP32, transients, short in harness) | low | HVDA195 isolates the logic side (bus +-40 V), fuse + diode on VSUP, optional TVS; never wire the ESP32 to K directly |
| Battery drain via pin 16 | high if powered from it | switched USB power or ADC-based sleep/cut-off |
| Maintenance burden | certain | keep KW905 as fallback; add a unit-test layer by running the K-Line Dash simulator against the ELM text layer |

---

## 7. Cheap measurements that settle the open questions

| Question | How | Cost |
|---|---|---|
| What is the KW905's BLE connection interval? | Log the connect/`UPDATE_CONN_PARAMS` event in `ble_link.cpp`; or nRF Connect on a phone | 5 lines |
| True ECU response time (P2) and the clone's dead time | Stage 1 passive logger (UART2 RX timestamps) or a logic analyzer on pin 7 behind a divider | EUR 0 / EUR 10 |
| Key words / ECU address of this car | `ATKW`, `ATH1` + `0100` (header `48 6B xx`), or the logger | free |
| Does the clone honour the count digit / `ATST` / `ATAT2`? | Stage 0, `PollStats` mean round-trip in K-Line Dash, `Live:` lines in `esp32-obd2` | free |
| Android SPP RTT on the head unit | echo test between head unit and ESP32 (an SPP echo sketch, timestamps in the app) | 1 hour |
| Is the KW905's MCU a PIC or ARM; UART level and baud | open the case, read chip markings, scope the UART while the app talks (only needed for B) | - |

---

## 8. Where sources disagree / what is still unknown

- **K-line rate**: 20 req/s (OBD9141, non-compliant timing, one Kia) vs 8-9 (muki01, ESP32) vs 4-5 (STN, simulator) vs 3.3-3.6 (our clone, ESP32) vs 2-2.2 (our clone, Android/early session). Different waits explain it (2.2), but no single source measured a Toyota 2ZZ-GE ECU. [U]
- **Our own two numbers** (0.28-0.30 s vs 0.45-0.5 s on the same day) are unexplained.
- **KW905 internals**: PIC25K80 (one manual snippet) vs ARM (KW902 manual) vs unknown; BT 5.0 vs 5.2 vs "BT 3.0 Classic" in older Konnwei units; Classic connections are refused. [U]
- **BLE vs Classic speed**: the Car Scanner author says BLE is not faster than Classic; the OBD Now benchmark shows the BLE Kiwi 3 slower than the Classic MX on every protocol; the Electric UI benchmark shows ESP32 SPP ~ BLE notify (13 vs 15 ms). Different adapters and stacks. [R]
- **L9637D at 3.3 V**: conflicting hobby sources. Use the HVDA195 and avoid the question.
- **P2 minimum**: 25 ms only when key words are 08 08; 0 ms for 94 94 (ISO 9141-2 §12). Which one this ECU sends is unknown.
- **ESP32 SPP / BLE latency numbers** come from ESP32-to-ESP32 tests, not from Android; Android-side numbers may be 1.5-2x worse. [U]
- **Prices** are cached aggregator listings or vendor pages and move.

---

## 9. Sources

Standards and datasheets
- ISO 9141-2:1994(E), full text: https://law.resource.org/pub/us/cfr/ibr/inc/iso/iso.9142-2.1994.pdf (Tables A.1, A.2, clauses 6-8, 12-13)
- ELM327 datasheet: https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DSH.pdf (ST command, adaptive timing, response count, wakeup messages, PP 03)
- STN / OBDLink Family Reference & Programming Manual: https://www.scantool.net/scantool/downloads/678/obdlink_frpm_e.pdf (STPTRQ default 56, STPTO, STIAT)
- TI SN65HVDA195-Q1: https://www.ti.com/lit/ds/symlink/sn65hvda195-q1.pdf ; TI SN65HVD230: https://www.ti.com/product/SN65HVD230
- ESP-IDF TWAI: https://docs.espressif.com/projects/esp-idf/en/v5.1.4/esp32/api-reference/peripherals/twai.html
- ESP-IDF Bluedroid defaults: https://raw.githubusercontent.com/espressif/esp-idf/v5.1.4/components/bt/host/bluedroid/stack/include/stack/btm_ble_api.h
- Android connection priority values (AOSP): https://android.googlesource.com/platform/packages/apps/Bluetooth/+/0ec1e3f%5E%21/
- M5StickC Plus2 pins: https://docs.m5stack.com/en/core/M5StickC%20PLUS2

Open-source projects
- https://github.com/iwanders/OBD9141 (README, `OBD9141.h/.cpp`, `examples/reader_esp32`, `reader_time`)
- https://github.com/jimwhitelaw/ELMulator ; https://github.com/muki01/OBD2_KLine_Library ; https://github.com/muki01/OBD2_K-line_Reader ; https://github.com/maximus64/opendiag-firmware ; https://www.tindie.com/products/opendiag/opendiag-open-source-obd2-interface/ ; https://github.com/Obeisance/Arduino_OBD_interface_for_Torque ; https://github.com/Ircama/ELM327-emulator ; https://github.com/SimonRafferty/Land-Rover-Td5-Arduino-Diagnostics (ESP32 + L9637D wiring example)
- ediabaslib PIC18F25K80 board notes: https://github.com/uholeschak/ediabaslib/blob/master/docs/Replace_Elm327_BT_Mini_Firmware.md

Benchmarks and adapters
- OBD Now user guide with PID-rate tables: https://glmsoftware.com/Documentation/OBDNowUserGuide.pdf
- Electric UI latency benchmark (SPP vs BLE on ESP32): https://electricui.com/blog/latency-comparison
- OBDLink LX: https://www.scantool.net/obdlink-lx/ ; STN2120: https://www.obdsol.com/solutions/chips/stn2120/
- L9637D pricing (aggregators): https://www.findchips.com/search/l9637 ; https://www.lcsc.com/product-image/C153038.html ; SN65HVDA195 pricing: https://www.lcsc.com/product-image/C140290.html?whichImg=0
- L9637D at 3.3 V vs 5 V discussion: https://forum.arduino.cc/t/problem-with-l9637d-and-esp32c3-supermini-or-arduino-nano/1402658
- MC33290 status: https://www.nxp.com/kr/products/no-longer-manufactured/iso9141-k-line-serial-link-interface:MC33290
- KW905 sources (product pages only, no teardown): https://www.amazon.com/KONNWEI-Bluetooth-Wireless-Diagnostic-Performance/dp/B0FL6B8CHV ; https://satkit.at/ratgeber/elm327/vergleiche/generischer-elm327-vs-konnwei-kw903 ; https://mynissanleaf.com/threads/warning-defective-konnwei-kw-902-on-the-market.23776/

Repo-internal
- `docs/research/esp32-obd2-lessons.md`, `2zz-ge-obd.md`, `elm327-clones.md`, `popular-obd-adapters.md`, `headunit-autostart.md`
- `C:\Users\Stijn\Documents\GitHub\esp32-obd2`: `README.md`, `hwtest-report-m5.md`, `lib/obd/src/live_poller.cpp`, `src/ble_link.cpp`, `scripts/elm_sim.py`
- K-Line Dash: `obd-core/.../elm/Elm327Session.kt`, `obd/PollConnection.kt` (`ResponseCountSuffix`), `elm/AdapterProfile.kt`, `app/.../ui/DebugScreen.kt`
