# Lessons from esp32-obd2 (real KW905 + Corolla E12 T-Sport) for K-Line Dash

Research date: 2026-10-21. Sources: `C:\Users\Stijn\Documents\GitHub\esp32-obd2` (git history, README, `lib/obd/src/*`, `src/*`, `scripts/*`) and the Claude session "ESP32-OBD2 flashen en testen met hardware" (exported transcript, treated as data). Citations: `repo file:line` or commit hash. "(session)" = only visible in that session's log, not in the repo.

Line numbers for our files are from the working tree on 2026-10-21 (Elm327Session.kt and ElmConnectionManager.kt had uncommitted edits); re-check before editing.

Legend: **[CAR]** observed on the real car/adapter. **[CODE]** only in code or docs, never confirmed on hardware. **[UNTESTED]** planned, not run.

---

## 1. Verified on real hardware / car

One test day (2026-10-21), ESP32 + Konnwei KW905 in the OBD port, ignition on, engine off then idling and warming up, car stationary. Commits `72e3f23`, `19fc3bc`, `699c506` document it.

**Adapter and Bluetooth**
- **[CAR]** Adapter is a **Konnwei KW905** with a physical button. Bluetooth is off until the button is pressed (lamp on). For the first ~20 minutes nothing connected: the button had not been pressed, and afterwards the configured name was `OBDII` while the adapter advertises `KONNWEI` (session).
- **[CAR]** It shows up in a Bluetooth **Classic** inquiry but **refuses every Classic connect**: PIN 1234 ("koppelen (PIN) mislukt"), no security, and RFCOMM channel 1 (`72e3f23` message, `src/obd_worker.cpp:402-411`).
- **[CAR]** It works over **BLE**, no pairing or PIN needed. Public address type. Advertisement carries service `FFF0` (seen by ESP32 and by a Windows bleak scan). GATT: service `FFF0` (handles 26-65535), `FFF1` handle 28 props `0x10` (notify only), `FFF2` handle 31 props `0x04` (write-without-response only) (`19fc3bc`, session).
- **[CAR]** Full service discovery stalls: only `1800`, `1801`, `180A` (handles 1-25) come back, then the search ends with an error or never ends. arduino-esp32 `BLEClient::getServices()` blocked forever; Windows bleak also failed at `get_characteristics` (WinError -2147024874). A **filtered** search for `FFF0` works (`src/ble_link.cpp:312-349`, `19fc3bc`).
- **[CAR]** Default MTU 23 (20-byte writes) was enough; no MTU exchange was ever done (`src/ble_link.cpp:15`, `457-465`).
- **[CAR]** One connection at a time. After an ESP32 crash/restart the first BLE connect was refused ("BLE-verbinding geweigerd"), the retry 5 s later worked (session). While another client holds it, it is not found at all ("niet gevonden via BLE"). Classic attempts right before a BLE attempt disturbed BLE on this dual-mode device, so BLE goes first (`src/obd_worker.cpp:355-358`).
- **[CODE, from manual research, not hardware-verified]** The adapter auto-sleeps after ~30 min without traffic (`README.md:27`).

**ELM and car**
- **[CAR]** Banner `ELM327 v1.5` (a clone; Elm Electronics never made v1.5, `README.md:143`).
- **[CAR]** `ATSP0` auto-detection found **ISO 9141-2** (`ATDP` text). The Toyota custom init (ATIB96/ATIIA13/ATSH8113F1/ATSPA4) was **not needed** with this adapter and car (`lib/obd/src/elm_session.cpp:98,110-120`; log line `Auto: ISO 9141-2, 19 PID's`).
- **[CAR]** Supported Mode 01 PIDs reported (merged over 0100 then 0120; the 0120 mask did not advertise 0x40):
  `01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 14 15 1C 20 21`.
  Not supported: 0B (MAP), 2F (fuel level), 42 (control module voltage), 46, 5C, 1F. This matches the expectation in `docs/research/2zz-ge-obd.md` section 2, plus 12 and 21.
- **[CAR]** Sample values (sane): engine off, ignition on: RPM 0, speed 0, coolant 46-48 C, load 0, throttle 9.4 %, MAF 0.5 g/s, IAT 28 C. Idle warm-up: RPM 1400 -> ~830 (cold start ~1090-1400, warm ~830-860), coolant 46 -> 76 C, load 15-17 %, throttle 9.4-9.8 %, MAF 2.3-3.3 g/s. Throttle (PID 11) reads **~9.4 % with the pedal released**, not 0. The user was asked to compare RPM/temperature with the dash; no answer is recorded (session).
- **[CAR]** **K-line latency: ~0.45-0.5 s per request** (5 PIDs = 2.2-2.5 s round; 3 PIDs = 1.5-1.75 s) over BLE with ELM "v1.5" (`699c506`, session `Live:` lines). That is **~2 requests/s in total**, not the 5/s our docs plan with. Once a single round took 6.4 s with one PID missing (unexplained; maybe ECU session re-init or a timeout; session 13:00:23).
- **[CAR]** Init time (BLE connect + ATZ + ATSP0 + 0100 + bitmaps) was not timed; the log has no timestamps between the connect line and the `Auto:` line.
- **[CAR]** From ~13:18 (local) the scanner was no longer found over BLE (cause not recorded: car off, adapter asleep or held by another client), so no further car data exists.

**Not verified in the car (do not assume)**
- VIN (09 02), DTC read (03/07), DTC clear (04), pre-CAN Mode 09 support (`docs/research/2zz-ge-obd.md` guesses NO DATA; nobody probed `0900`).
- Ignition-off behaviour, re-init after ignition on (`T8`/`T9` in `docs/autotest.md:28-30`), 30-minute stability, speed > 0, battery voltage via `ATRV`.
- AT1 vs AT2 adaptive timing (firmware sets AT2 on non-CAN, `src/obd_worker.cpp:492`, "nog te meten" in session; the measuring script `scripts/hwtest.py:155-162` was never run on the car).
- Any other clone. The only Classic clone logic is code (`src/obd_worker.cpp:393-427`).

---

## 2. ELM init / parse / decode: what esp32-obd2 does that we do not (or do differently)

| # | esp32-obd2 | Ours | Recommendation |
|---|---|---|---|
| 1 | **Never sends a bare CR.** A lone CR makes the ELM repeat the last command (datasheet p.9), dangerous after `04`. `command()` rejects empty, space-only and control-char commands (`elm_session.cpp:38-48`). | `Elm327Session.execute` (`Elm327Session.kt:30-43`) has no guard. | **Adopt.** `require(cmd.isNotBlank() && cmd.none { it < ' ' })`. |
| 2 | **Resync after a timeout with a single space, not CR**, then wait up to 1.5 s for `>` and drain (`elm_session.cpp:68-81`, called from `query`, `129-138`). Datasheet: any character aborts a running command. | After `Timeout` we just return; the next command is written into a possibly still-busy ELM (`Elm327Session.kt:35-40`). | **Adopt** a `resync()` after every Timeout (inside `execute`), before the next command. Never auto-retry mode 04. |
| 3 | Keeps **every response line/ECU message** and merges them: PID bitmaps (`obd_decode.cpp:94-105`, caller `obd_worker.cpp:481`), MIL/count (`166-180`), DTC lists (`213-225`). | `ElmResult.Data.lines` has all lines, but nothing decodes yet. | **Adopt**: OR the bitmaps of all `4100`/`4120`... lines, never use `lines.first()`. Cheap insurance against an extra ECU (ABS, automatic). |
| 4 | Supported-PID discovery **chains**: ask 0100, then 0120/0140 only while the previous mask has the "next block" bit (PID 20/40) set; stop on any error (`obd_worker.cpp:471-488`). | Plan says 0100/0120/0140/0160 unconditionally. | **Adopt the chain**. On this car 0140 and 0160 would be wasted ~0.5 s requests that answer NO DATA. |
| 5 | **Transient vs. real errors.** `Empty`, bus error, `LV RESET`, `STOPPED`, `BUFFER FULL`, `UNABLE TO CONNECT` never count as "this PID/feature is unsupported" (`live_poller.cpp:19-22`); a single `DATA ERROR` is noise, two in a row disable an optimisation (`24`, `146-149`). | `ElmParser` classifies well (`ElmParser.kt:25-29,145-155`), but `LV RESET`/`DATA ERROR` are merged in `BusError`. | **Adopt the policy** in the scheduler: mark a signal unsupported only if absent from the bitmap or after N consecutive NO DATA (not on the first). Keep ours more detailed otherwise. |
| 6 | **"No vehicle" detection**: 5 completed rounds without any valid value -> state NoVehicle, cancel queued jobs, full re-init (ATZ... 0100) every 10 s (`obd_worker.cpp:89,545-570,310-318`). | `ElmConnectionManager` only reconnects after 3 transport timeouts (`ElmConnectionManager.kt:115-120`). Ignition off gives `NO DATA`/`UNABLE TO CONNECT`, not a timeout, so the link would stay "Connected". | **Adopt**, but unverified on this car. Needs an `ElmLink` entry point to re-run `initialize()` without dropping the transport. Distinguish *engine off, ignition on* (valid RPM 0, **[CAR]**) from *ECU not answering*. |
| 7 | `NO DATA` for Mode 03/07 means **"no stored codes"**, not an error (`obd_worker.cpp:639-649`). Legacy DTC replies have **no count byte**; `00 00` pairs are padding (`obd_decode.cpp:194-211`). | Not built yet (phase 3). | **Adopt for phase 3.** |
| 8 | Legacy (non-CAN) VIN: five lines `49 02 nn d d d d`, sorted by `nn`, leading `00` dropped, 17 chars required (`obd_decode.cpp:241-263`). **[CODE only]** | Simulator has VIN support (`SimulatorTransport.kt:376-390`). | **Adopt for phase 3**, but probe `0900` first; pre-CAN Toyotas may answer NO DATA. |
| 9 | Detection: `ATSP0`, then `0100` with a **20 s** timeout, then `ATDP`/`ATDPN` (`elm_session.cpp:110-125`). | `DETECT_PROBE_TIMEOUT_MS = 15 s` (`Elm327Session.kt:300`). | **Adopt 20-25 s** for the ATSP0 path (auto search walks several protocols, then the 5-baud init). Keep our saved-protocol shortcut and fallback ladder; it is an improvement. Keep the Toyota profile as last resort only. |
| 10 | Adaptive timing `ATAT2` on non-CAN (datasheet p.13-14) (`obd_worker.cpp:489-492`). | `ATAT1` (`Elm327Session.kt:294`). | **Experiment, do not adopt blindly**: effect never measured on the car. Log per-request latency and compare AT1/AT2 (and `ATST`) on the first car test. |
| 11 | Multi-frame CAN reassembly (`0:`/`1:` segments, length line), multi-PID requests, response-count digit (`elm_response.cpp:159-225`, `live_poller.cpp:112-201`). | none | **Ignore.** CAN only (datasheet p.46); the car is ISO 9141-2. The count digit was never tried on K-line; it is the only untested lever for the 0.45 s/request. Optional later experiment, clones may answer `?`. |
| 12 | `ATS1`, spaces on. | `ATS0` (`Elm327Session.kt:294`). | **Ignore.** Our parser normalises hex either way. |
| 13 | Banner-in-reply (clone reset) detection: no. | Yes (`ElmParser.kt:44-45,108`, `Elm327Session.kt:246-257`). | Keep ours. |
| 14 | `ERRxx`, `ACT ALERT`, `OUT OF MEMORY`, `BUS INIT ... ERROR` recognised (`elm_response.cpp:76-84`). | Same set minus `OUT OF MEMORY`. | Add `OUT OF MEMORY` to `BufferFull` (trivial). |

---

## 3. Polling strategy

- **Why multi-PID was "dropped":** it never applied. Multi-PID is CAN-only (`README.md:120`, `live_poller.cpp:50-51`: `multi_ = info.is_can`). On this K-line car the log always says "multi-PID uit, cijfer uit". `8719fe9` built it for CAN speculatively; nothing about it was validated.
- **First K-line scheme:** fast group (RPM, speed, throttle, load, MAF, MAP) every round, rest every 5 s. Result: RPM/speed only every ~2.3 s (`699c506`, 0.5 rounds/s).
- **Final K-line scheme (`699c506`, refined in `41dbbfd`; `live_poller.cpp:68-83`):** every round = RPM + speed + **one** other PID, alternating between "rest of the fast group" and "the slow group", each rotating. Measured 0.5 -> ~0.85 rounds/s. Side effect: with 19 PIDs the 8 slow PIDs refresh only every ~25 s and the 3 other fast ones every ~9 s. Acceptable for coolant, too slow for throttle/load.
- **Extra traffic outside the rotation** (each costs ~0.5 s): `0101` + `ATRV` every 5 s (`obd_worker.cpp:327-330,600-612`), `0103` every ~15 s (`617-633`), `011C` once at connect (`493-500`).
- **Timeouts used:** AT commands 2000 ms; `ATZ` 4000 ms (retry once after a resync); `query()` default 3000 ms; `0100` detect 20000 ms; DTC read 5000 ms each; VIN 5000 ms; DTC clear 10000 ms; `ATRV` 2000 ms (`elm_session.cpp:38-153`, `obd_worker.cpp:640,646,676,692`). Poll loop sleeps 10 ms between rounds.
- **Interrupting:** a `stop()` callback is checked before every request so user jobs (DTC/VIN/reconnect) never wait for a full round (`live_poller.cpp:95`, `obd_worker.cpp:534-537`).
- **Failure handling:** see table row 5 and 6. Link lost -> 5 s fixed reconnect backoff (`obd_worker.cpp:85,294`). ECU silent -> NoVehicle + retry setup every 10 s so the ELM does not keep a sleeping bus awake (`87`). Wait times are intervals, not absolute timestamps (millis overflow) (`8719fe9`).
- **Reconnect memory:** only the link type ("ble"/"classic") is stored (NVS), and the scanner address/name; the **protocol is not remembered** (every connect runs `ATSP0`). BLE-only restart and heap tricks are ESP-specific. Ours (saved protocol per adapter) is better.
- **Shape of the measured budget:** ~2 requests/s. A realistic target for a K-line dashboard is RPM+speed ~1 Hz each, one more signal per round. A gauge needs interpolation/smoothing; the ESP32 dashboard animates the needle and treats data as "old" after 5 s (`41dbbfd`).

---

## 4. PID decoding table used (`lib/obd/src/obd_decode.cpp:11-31,44-73`)

| PID | Name | Bytes | Formula in esp32-obd2 | Supported on this car? | Notes |
|---|---|---|---|---|---|
| 04 | Calculated load | 1 | 100/255 * A | yes | idle 15-17 % **[CAR]** |
| 05 | Coolant | 1 | A - 40 | yes | 46 -> 76 C **[CAR]** |
| 06 / 07 | STFT / LTFT bank 1 | 1 | 100/128 * A - 100 | yes | not logged |
| 0B | Intake MAP | 1 | A kPa | **no** | |
| 0C | RPM | 2 | (256A + B) / 4 | yes | **[CAR]** |
| 0D | Speed | 1 | A km/h | yes | still 0 in all logs |
| 0E | Timing advance | 1 | A/2 - 64 | yes | not logged |
| 0F | Intake air temp | 1 | A - 40 | yes | 25-31 C **[CAR]** |
| 10 | MAF | 2 | (256A + B) / 100 g/s | yes | 2.3-3.3 g/s idle **[CAR]** |
| 11 | Throttle | 1 | 100/255 * A | yes | 9.4 % closed **[CAR]** |
| 14 / 15 | O2 B1S1 / B1S2 voltage | 2 | A / 200 V (B = trim, ignored) | yes | narrow-band expected |
| 1F | Run time | 2 | 256A + B s | **no** | |
| 21 | Distance with MIL | 2 | 256A + B km | yes | |
| 2F | Fuel level | 1 | 100/255 * A | **no** | |
| 42 | Control module voltage | 2 | (256A + B) / 1000 V | **no** | use `ATRV` |
| 46 / 5C | Ambient / oil temp | 1 | A - 40 | **no** | |
| 03 | Fuel system status | 2 | bit-coded: 1,2,4,8,16 (`obd_decode.cpp:117-141`) | yes | text, not numeric |
| 1C | OBD standard | 1 | enum 1-35 (`143-164`) | yes | expect 6 = EOBD |
| 01 | Monitor status | 4 | only A decoded: bit7 = MIL, bits0-6 = DTC count (`107-113`) | yes | readiness bits (B-D) not decoded |
| 12 / 13 | Secondary air status / O2 sensors present | 1 | **not decoded** | yes | bit-coded, nothing to chart |

Formula check: every formula equals the common SAE J1979 / Wikipedia form; I found **no deviation**. Points to double-check on our side:
- All formulas come from Wikipedia's table, not from J1979 itself, and none was checked against the dash except plausibility of RPM/coolant/MAF. Speed was never non-zero.
- PID 14/15 `B` byte is a short-term fuel trim `100/128*B - 100`; `B == 0xFF` means "sensor not used in trim" (shown as +99.2 %). Ignore B for this narrow-band car.
- Throttle 0x11 is "absolute throttle position"; closed reads ~9.4 % here. Do not assume 0 % closed (offer a calibration offset or display as-is).
- Bit-coded PIDs (03, 12, 13, 01) and enum 1C need their own decoders, not a numeric `Signal`.
- A PID response must be validated as `41 <pid>` plus the exact byte count (`obd_decode.cpp:47-48`). Keep this; K-line and ATH0 can deliver stale or foreign lines.

---

## 5. BLE / Classic learnings for the Android transports

1. **Do not rely on a full service discovery succeeding.** KW905's GATT DB makes the standard "find all services" fail after handle 25 for two independent stacks (ESP-IDF, Windows). Android's `discoverServices()` has no filter variant, so on `status != GATT_SUCCESS` check `g.services` for `FFF0` before giving up, retry once (disconnect, short pause, reconnect), and log the full service list. `BleTransport.discoverServices` (`BleTransport.kt:230-244`) currently throws on any non-success status. iOS/Android apps use the adapter fine, so Android will likely work, but this is the one known weak spot.
2. **Profile selection already fits:** `FFF0/FFF1/FFF2` is in `KNOWN_PROFILES` (`BleTransport.kt:506-516`). Notify-only RX and write-without-response-only TX are handled (`WRITE_TYPE_NO_RESPONSE` path with retry, `writeChunk`).
3. **CCCD write must complete before the first command** (esp32-obd2 waits up to 2 s, `ble_link.cpp:416-440`, so the `ATZ` reply is not lost). Ours awaits `onDescriptorWrite`; keep it.
4. **MTU is optional.** ESP32 ran everything at MTU 23 (20-byte chunks) without trouble. Our `requestMtu(185)` is non-fatal and last; keep that, never make the MTU a precondition. Replies still arrive in <=20-byte notifications, so `PromptBuffer` reassembly matters.
5. **Dual-mode device (`BtDeviceType.DUAL`) named KONNWEI: try BLE first, and do not run a failing Classic attempt directly before BLE** (it disturbed BLE, `obd_worker.cpp:355-358`). Classic was refused for every security variant (PIN pairing reported as failed). Our manual `TransportKind` choice in `TransportFactory` is fine, but the UI should suggest BLE when the scan shows DUAL/LE + `KONNWEI` or an `FFF0` advertisement (`ble_link.cpp:64-73`: detect adapters with no name by `FFF0` in the advert).
6. **Only one BLE central at a time, and a held connection hides the adapter.** If our service dies without `disconnect()`+`close()`, the next connect fails or "device not found" until the old link times out. Keep the clean teardown (`BleTransport.kt:358`), keep exponential backoff, and keep the hint text "niet al verbonden met een telefoon?" (`BleTransport.kt:427`). Warn users not to leave Car Scanner (or similar) connected.
7. **The KW905 is only reachable after the user presses its button** (and may sleep after ~30 min idle, unverified). An unattended head-unit autostart will not connect to a sleeping KW905; the UI needs a "press the button on the adapter" message, not just retries. This affects `docs/research/headunit-autostart.md` expectations.
8. **Timeouts that worked on ESP32:** advert scan 5 s, GATT open 10 s, service search 8 s, CCCD 2 s (`ble_link.cpp:12-14`, `436`). Ours (15 s / 10 s / 5 s) are at least as generous. `connectGatt(address, autoConnect=false)` needs the adapter to be advertising; ESP32 scanned first and used the address type from the advert (public here). A random-address adapter would need a scan first.
9. **Simulator realism:** `scripts/elm_sim.py` emulates what mattered: echo until `ATE0`, `\r\r>` terminator, 20-byte notification chunks 5 ms apart, 20-50 ms reply delay, lone-CR-repeats-last-command (and reports it), `NO DATA` for multi-PID on non-CAN, `UNABLE TO CONNECT` for `0100` with ignition off and `NO DATA` otherwise, 5-line legacy VIN, zero-padded legacy `43` replies (`elm_sim.py:113-173`). Our `SimulatorTransport` should add the lone-CR behaviour and slow K-line timing (below).

---

## 6. Change in K-Line Dash (prioritised)

1. **Re-baseline the K-line budget to ~2 requests/s.** Edit the comment in `obd/Signal.kt:6` ("roughly 5 requests per second") and `docs/research/elm327-clones.md:123` ("planning number 5 PIDs/s"). Make `PollStats` (`obd/ObdPoller.kt`) report real per-request latency so the first car test measures it, including an `ATAT1` vs `ATAT2` (and optionally `ATST`) comparison.
2. **Scheduler design (`ObdPollScheduler`, new):** RPM + speed in every cycle plus **one** rotating signal; choose the rotating one by "oldest vs. its tier's max age", not by fixed blocks. Reserve a fixed slice for `0101`/`ATRV`/`0103` housekeeping. Copy the pattern from `live_poller.cpp:68-83` and `699c506`.
3. **`Elm327Session.execute` (`Elm327Session.kt:30-43`):** reject blank/control-char commands, and after any `ElmResult.Timeout` send a single space (never CR), wait up to 1.5 s for `>`, discard. Never retry mode 04.
4. **`Elm327Session.DETECT_PROBE_TIMEOUT_MS` (`:300`)** from 15 s to 20-25 s for the ATSP0 path. Keep the fallback ladder; demote `TOYOTA_PROFILE` to the very last rung (the car did not need it).
5. **Supported-PID discovery in `obd/`:** chain 0100 -> 0120 -> 0140 -> 0160 only while the "next block" bit is set; OR all response lines; store the set as `supportedPids`. Add a debug/export of the raw masks (for `docs/research/2zz-ge-obd.md` section 5, item 2).
6. **Error policy in the scheduler:** transient (`NoData` once, `BusError`, `Stopped`, `Garbage`) never marks a signal unsupported; N consecutive `NoData` does. Add "ECU not answering for ~5 cycles" -> `PollMode.WAITING` + re-run `initialize()` on the live transport every ~10 s (needs a new method on `ElmLink`/`ElmConnectionManager`, because today only transport timeouts trigger a reconnect, `ElmConnectionManager.kt:115-120`). Keep valid `RPM = 0` as `ENGINE_OFF`, not as lost link.
7. **`BleTransport.discoverServices` (`BleTransport.kt:230-244`):** tolerate non-success discovery status when `FFF0` is present, retry once, log the service list. Keep MTU optional (`requestMtu`, `:213`). Add a UI hint in `ConnectionScreen`/`ConnectionViewModel` that KW905-type adapters need their button pressed and only work over BLE (suggest `TransportKind.BLE` for DUAL/KONNWEI; `BtDeviceType` at `BluetoothDevices.kt:24`, name hints at `:40`).
8. **`SimulatorConfig` (`sim/SimulatorConfig.kt`):** `latencyMinMs/MaxMs` (lines 41-42) from 60-120 to ~400-500 ms; `DEFAULT_SUPPORTED_PIDS` (line 119) to the real list `01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 14 15 1C 20 21`; optionally add a "lone CR repeats last command" fault and make Mode 09 default to NO DATA.
9. **Decoders (`obd/`, phase 2):** implement the PIDs in section 4 with unit tests that use the real values above (idle RPM ~830, throttle 9.4 %, coolant 76 C, MAF ~2.4 g/s). Separate text/bit decoders for 01, 03, 12, 13, 1C. Do not scale throttle to 0 %.
10. **Phase 3 prep:** legacy DTC decode (no count byte, `0000` padding, NO DATA = no codes) and legacy VIN (5 lines by sequence number). Probe `0900` before showing a VIN button; update `docs/research/2zz-ge-obd.md` section 1 and 2 with the verified ISO 9141-2 auto-detect result and the real PID bitmap.
