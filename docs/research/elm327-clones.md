# ELM327 clones, K-line behaviour and raw-response fixtures

Research date: 2026-10-21. Target: cheap "v1.5" ELM327 clone (Bluetooth Classic SPP, possibly BLE or Wi-Fi) on an ISO 9141-2 / KWP car (Toyota Corolla E12 2ZZ-GE).

Legend: **[V]** = verified in a cited source, **[R]** = reconstructed/derived (logic or arithmetic, not copied from a real log), **[U]** = uncertain / single weak source / from general knowledge, not verified in this research.

Source quality note: the official ELM327 datasheet is authoritative for the *genuine* chip only. Almost all clone behaviour below comes from forum/GitHub anecdotes and varies per board. Design for "any command may answer `?`".

---

## 1. Clone landscape

- ELM Electronics never released a "v1.5" (nor a "v2.1" as sold on the cheap market). Clones print whatever version they like. The Linux can327 docs say it explicitly: the version string says nothing about the real feature set. [V]
  - https://docs.kernel.org/7.0/_sources/networking/device_drivers/can/can327.rst.txt
  - https://en.wikipedia.org/wiki/ELM327
  - https://github.com/PowerBroker2/ELMduino/issues/69 (user: "my device responds ELM327 v1.5 ... this never existed")
- Two common hardware families (from a hobbyist teardown page and a GitHub reference; treat as [U]):
  - "v1.5" boards with a real/relabelled PIC18F25K80 + 4 MHz crystal: broadly follow the ELM327 v1.x command set (ATPPS answers, CAN filters work).
  - Newer "v2.1" boards on ARM/STM32-class chips with a partial re-implementation: reported to miss ATPPS, ATAT1, ATS0, ATR1, ATCF/ATCM and to silently truncate commands. Chip names seen: STM32F042, YM1130, BK3231Q, relabelled QBD327.
  - http://cvtz50.info/en/elm327/
  - https://github.com/TheTom/elm327_obd_for_mac/blob/main/ELM327_TECHNICAL_REFERENCE.md (section 7; lower-quality doc, likely partly machine-written. Use only as hints.)
  - https://github.com/uholeschak/ediabaslib/blob/master/docs/Replace_Elm327_BT_Mini_Firmware.md (a "v1.5" BT board = PIC18F25K80 + YC1021 BT chip + 24C32 EEPROM; BT PIN 1234 only.) [V for that specific board]
- Consequence for us: the version string is useless for feature detection. Probe by behaviour (send command, accept `OK`, accept `?`).

## 2. Commands that typically fail on clones (answer `?`) or misbehave

There is **no authoritative per-command list**. Evidence found:

| Item | Evidence | Confidence |
|---|---|---|
| `ATPPS` answers `?` or nothing on weak clones | FORScan community, cvtz50, TheTom doc | [U] widely repeated |
| `ATCRA xxx`, `ATCF`, `ATCM` answer `?` | same (CAN only; irrelevant for K-line) | [U] |
| `ATS0` / `ATR1` / `ATAT1` answer `?` on "v2.1 ARM" clones | TheTom doc (weak) | [U] |
| `ATMA` (monitor all) not implemented on some fakes | https://blog.jklr.org/2018/05/26/fake-elm327-monitor.html (could not fetch body; summary via search) | [U] |
| `ATSP0`/`ATTP A0` can hang the board until power-cycle | ELMduino issue #69, one clone | [V] for that board |
| Clone answers `NOT CONNECTED` where real chip says `NO DATA` when no vehicle attached | ELMduino issue #69 | [V] for that board |
| Clone does not remember protocol across power cycles (always back to AUTO) | ELMduino issue #69 | [V] for that board |
| `ATRV` voltage inaccurate / sometimes garbage (e.g. 28.3V first value) | jonesrh.info Volvo page note; Torque forum | [U] |
| Real chip: `ATRV` is only ~2% accurate and rounds to 0.1 V | datasheet | [V] |

Not found anywhere: reliable evidence on how clones treat `ATLP`, `AT@1`, `ATIB`, `ATKW`, `ATWM`, `ATSW`, `ATST`, `ATSH`. Positive evidence that `ATIB 96`, `ATIIA 13`, `ATSH8113F1`, `ATSP A4`, `ATSW00` and `ATSTxx` are accepted by cheap clones comes from owners using them in Torque custom init on Toyota/Nissan cars (Torque wiki, section 5), i.e. those K-line commands are at least *implemented* on common clones. [V that it is used, U on every board]

**Design rules that follow**
1. Treat every non-essential AT command as optional: send, read until `>`, log `?`, continue.
2. Essential for K-line: `ATZ` (or `ATWS`), `ATE0`, `ATL0`, `ATSP3` (or `ATSP A3`/`ATTP`), `ATH1` (for the fixture-compatible parser), `ATS1` or `ATS0` (parser must accept both), `ATRV`.
3. Optional tuning: `ATAT1`/`ATAT2`, `ATST xx`, `ATSW xx`, `ATKW0`, `ATIIA`, `ATIB`, `ATSH`.
4. Avoid `ATSP0`/`ATTP A0` on first connect if a hang is seen; use an explicit protocol ladder instead (see 2zz-ge-obd.md).
5. After `ATZ` wait >= 1 s (datasheet: full reset incl. LED test; other sources agree on ~1 s). Prefer `ATWS` for quick restarts (no LED test). [V datasheet]
6. Pace commands: a few sources recommend 50-100 ms between rapid commands on clones. [U]
7. Never assume the adapter replies to a command while a long OBD request is pending: sending any character during an OBD operation aborts it and the chip prints `STOPPED`. [V datasheet]

## 3. Serial/format conventions (genuine chip, datasheet)

Source: https://www.scantool.net/scantool/downloads/103/elm327dsh.pdf (ELM327DSH, firmware v1.4b), plus https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DSH.pdf

- Commands end with CR (0x0D). Spaces inside commands are ignored; case-insensitive. [V]
- Replies end with CR; LF is added only with `ATL1`. Default echo is ON (`ATE1`) on a fresh chip. Prompt is `>`. [V]
- The prompt `>` is the only reliable "ready" marker. Do not rely on a trailing CR after it. [V]
- Unrecognised command: `?`. [V]
- Data lines are uppercase hex bytes separated by single spaces when `ATS1` (default); no spaces with `ATS0`. [V]
- Real chips often print a trailing space after the last data byte when spaces are on (e.g. `41 0C 1A F8 \r`). [U] common observation; parser must `trim()`.
- Typical blank line before the prompt: reply is `<text>\r\r>`. [V as pattern in datasheet examples]
- `ATE0` itself is echoed: the reply to `ATE0` when echo was on is `ATE0\rOK\r\r>`. [R]
- Command input timeout ~20 s without CR -> adapter answers `?`. [U] (TheTom doc)

### Errors / status strings (datasheet, "Error Messages and Alerts") [V]
`?`, `NO DATA`, `UNABLE TO CONNECT`, `BUS INIT: ...ERROR`, `BUS BUSY`, `BUS ERROR`, `CAN ERROR`, `DATA ERROR`, `<DATA ERROR` (line before it has bad checksum), `BUFFER FULL`, `FB ERROR`, `STOPPED`, `LV RESET`, `ACT ALERT`, `LP ALERT`, `ERRxx`, `<RX ERROR`.
- `STOPPED`: any RS232 character received while the chip is executing an OBD operation interrupts it. Cause on our side: sending the next command before the previous reply ended, or a stray LF. (jonesrh.info reports a stray linefeed from a terminal causing STOPPED.) [V]
- `BUFFER FULL`: 256-byte UART buffer overflow (mostly CAN). Not expected on K-line.
- `LV RESET`: brown-out reset; adapter lost its settings (back to defaults, echo ON) -> our app must detect and re-init. A sagging cigarette/OBD supply on cranking causes this. [V cause; U on frequency]
- `ACT ALERT` / `LP ALERT` only appear if the low-power auto timers are enabled (PP 0E). [V]

## 4. Bluetooth / BLE / Wi-Fi connectivity

### 4.1 Bluetooth Classic (SPP) on Android
- Pairing PIN is almost always `1234` (sometimes `0000`, `6789`, `7890`, `1111`). Car Scanner doc lists "1234, 0000, 7890 or 1111". [V]  https://www.carscanner.info/en/android-bluetooth
- Android 12+ needs the runtime permission `BLUETOOTH_CONNECT` ("Nearby devices"); <=11 needs location for discovery. [V]  https://www.carscanner.info/en/bluetooth-troubleshooting
- SPP UUID `00001101-0000-1000-8000-00805F9B34FB`. Standard call `createRfcommSocketToServiceRecord(uuid)`.
- Known fallback chain used by apps when "read failed, socket might closed": 1) secure socket to SPP UUID, 2) `createInsecureRfcommSocketToServiceRecord(uuid)`, 3) hidden `createRfcommSocket(int)` via reflection with channel 1 (then 2, 3), 4) always `cancelDiscovery()` before `connect()`, use a fresh socket per attempt. Evidence: B4X forum (some ELMs only connect with explicit port 1), CANblue thread (insecure variant with port 2 on another device). [U: anecdotal, device-specific, but common practice]
  - https://b4x.com/android/forum/threads/bluetooth-serial-connect.60095
  - https://forum.hms-networks.com/t/canblue-ii-with-android/37042
- Some BT clones drop the link after ~20 s of silence or when the ignition is cycled; send a keep-alive (`ATI`/`AT` is harmless but see section 6 about wakeups) or reconnect. [U]
- Car Scanner: "pairing in Android settings is required for Classic adapters; Android apps can leave a background service holding the RFCOMM channel -> only one app can connect." [V] (Issue #4 in the troubleshooting page.)
- Head-unit specific problems: see `headunit-autostart.md` (FYT units often can't pair OBD dongles from Android's BT settings).

### 4.2 BLE UART services used by OBD dongles
| Service | Notify (RX) | Write (TX) | Seen on | Confidence |
|---|---|---|---|---|
| `0000FFF0-0000-1000-8000-00805F9B34FB` | `FFF1` | `FFF2` (write / write-no-response) | OBDLink CX; Veepeak OBDCheck BLE/BLE+ (Particle example) | [V] |
| `000018F0-...` | `2AF0` | `2AF1` | OBDLink MX+ style layout (mentioned in a bug report which claimed the CX was wrongly mapped to it) | [U] secondary source |
| `0000FFE0-...` | `FFE1` (notify + write on same char) | same | generic HM-10/CC254x BLE-UART modules | [U] general knowledge, not verified here |
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART) | `6E400003-...` | `6E400002-...` | nRF-based adapters | [U] general knowledge |

Sources: https://support.obdlink.com/support/solutions/articles/43000746707-obdlink-cx-adapter-notes ; https://github.com/particle-iot/ble-gateway-library/blob/main/examples/veepeak/README.md ; https://github.com/fdittgen-png/tankstellen/issues/3180 ; https://pypi.org/project/elm327-obdii/ (discovers UUIDs dynamically).
**Recommendation:** discover services at runtime; pick the first service containing (a) a characteristic with NOTIFY and (b) one with WRITE or WRITE_NO_RESPONSE (possibly the same). Keep a known-UUID preference list as a fast path. Request a large MTU (e.g. 185-247) and reassemble notifications until `>`. Car Scanner author notes BLE is not faster than Classic and recommends Classic on Android when both exist. [V]

### 4.3 Wi-Fi ELM327
- Defaults: SSID like `WiFi_OBDII`, `OBDII`, `OBD2`, `WIFI_OBDII`, `V-LINK`; IP `192.168.0.10`; TCP port `35000`; usually open network. [V] https://www.carscanner.info/wifi/ ; teardown of a VGATE: transparent TCP<->serial at 38400, SSID `VLINK`. [V] (https://www.elektroda.com/news/news3886733.html via search summary)
- The head unit can usually join only one Wi-Fi network at a time, so a Wi-Fi OBD dongle blocks the phone hotspot/internet. Android 10+ also may route traffic away from a no-internet Wi-Fi unless the app binds to that network (`ConnectivityManager.bindProcessToNetwork`). [U: general Android knowledge, not verified for these units]

## 5. K-line specifics (ISO 9141-2 / ISO 14230-4)

### 5.1 ELM protocol numbers [V datasheet]
`3` ISO 9141-2 (5-baud init, 10.4 kbaud) | `4` ISO 14230-4 KWP (5-baud init) | `5` ISO 14230-4 KWP (fast init). `ATSP A3` = try 3 first, then auto-search. `ATSP 0` = auto.
- Auto search order on genuine chips is *not* 3,4,5 sequence; it is optimised. (TheTom doc claims `6,8,1,7,9,2,3,4,5,A` [U].) The datasheet notes ISO 9141 and 14230 look identical when only monitoring, so the chip may stop at 9141 even if the car is 14230, and corrects itself on the first real request. [V]
- Setting a fixed protocol and failing gives `BUS INIT: ...ERROR` (no fallback); `UNABLE TO CONNECT` after a full auto search failed. [V]

### 5.2 Init, timing, idle
- ISO 9141 allows only the slow 5-baud init (2-3 s); ISO 14230 allows slow or fast. [V]
- During the slow init the console shows `BUS INIT: ...` (three dots appear only for slow init; fast init shows none) followed by `OK` or an error. In *automatic* search no status is reported per datasheet; in practice `SEARCHING...` then `BUS INIT: OK`/`...OK` may appear. [V datasheet / U practice]  Parser rule: ignore lines starting with `SEARCHING` and `BUS INIT`.
- Default init address is `0x33` (`ATIIA hh` changes it, reset to 33 on defaults). [V]
- Most common failure: ignition not ON ("Forgetting to turn the key to ON"). [V]
- `ATKW0` disables key-word validation (init continues even if key words are unexpected; helps some odd ECUs). `ATKW` prints the two key-word bytes. [V]
- `ATIB 10|48|96` set 10400 / 4800 / 9600 baud (v1.4+); newer datasheet also 12500/15625. [V]
- Timing parameters in PP registers (defaults): P1 max inter-byte / P2 min inter-message `PP15=0x0A` ~21-22 ms; wakeup interval `PP17=0x92` ~2.99 s; ISO baud `PP16`; inter-byte stop-bit time `PP14=0x50` ~5.2 ms. [V datasheet]  Clones with `ATPP`/`ATPPS` support may allow tuning; many don't. [U]
- Keep-alive: the bus must see traffic at least every ~5 s or the ECU closes the session (bus "sleeps"). The ELM sends its own wake-up messages every ~3 s by default: `68 6A F1 01 00` (ISO 9141) or `C1 33 F1 3E` (KWP). Interval via `ATSW hh` (x20 ms, `FF` = just over 5 s), content via `ATWM`, `ATSW 00` stops them. [V]  After the ECU closes the session the next request triggers a new init (2-3 s with 5-baud) -> a fresh `BUS INIT:` line, or `BUS INIT: ...ERROR` if it fails.
- `ATSW00` is used in the Toyota Torque init strings (probably to stop the periodic wakeup frame, which some Toyota ECUs dislike). [V that it is used; U why]
- Multi-PID requests (several PIDs in one `01 xx yy ..` message) exist only for CAN. On K-line you must send one PID per request. [V] (manual excerpt: "only if you connect to the vehicle with CAN")
- Trailing response-count digit (`010C1`): v1.3+ lets you tell the chip how many responses to expect so it can skip the final timeout. [V datasheet] Clone support: [U] works on many "v1.5" boards; if the clone answers `?`, fall back to the plain form.

### 5.3 Throughput (K-line, single ECU)
- Datasheet example (J1850 VPW, not K-line): with ~90 ms adaptive timeout about 6 readings/s; with the response-count digit about 10-12/s. [V]
- Arithmetic for K-line [R]: 10.4 kbaud ~1 ms/byte. A 6-byte request, a 7-byte response, ~5 ms inter-byte stop time on the request, P2 15-50 ms ECU delay: ~60-120 ms per PID in the best case, i.e. roughly 8-15 PIDs/s theoretical. With default `ATST 32` (~200 ms final wait, shortened by adaptive timing) and no count digit expect roughly 3-6 PIDs/s. **Treat 5 PIDs/s as the planning number; measure on the car.** No measured K-line figure found in public sources.
- Car Scanner author: poll fewer parameters = faster; lowering `ATST` speeds things up but cheap adapters become unstable even at `ATST 64`; old VW/Audi need high ATST (96). [V] https://www.carscanner.info/en/optimizing-connection-speed
- `ATAT1` (default) is fine; `ATAT2` is more aggressive; do not set `ATST` below ~16-32 on a clone without a retry/back-off. [V datasheet + Car Scanner]
- Strategy for the dashboard: poll RPM and speed every cycle, coolant/IAT/throttle/load/fuel trims in a slower rotation (e.g. every 3rd-10th cycle).

## 6. Low power (`ATLP`) and battery drain

- Genuine ELM327 (v1.4+): `ATLP` -> chip answers `OK` (no CR), ~1 s later enters standby; typical reference circuit draw falls from ~25.7 mA to ~3.6 mA. Wakes on any RS232 character (>= 128 us low pulse), rising edge on IgnMon (pin 15), or reset. Optional auto-sleep after 5 or 20 min UART inactivity (PP 0E bits) with `ACT ALERT` (1 min before) and `LP ALERT` (2 s before). [V] datasheet pages 7, 17, 62-63 ("ELM327DS" on SparkFun for the current/typical numbers: https://cdn.sparkfun.com/assets/learn_tutorials/8/3/ELM327DS.pdf)
- A Bluetooth/Wi-Fi *module* on a dongle is a separate chip that stays powered when only the ELM IC sleeps; the whole dongle commonly still draws ~15-45 mA. Veepeak quotes "operating/standby up to 35 mA / 15 mA" for a BT dongle. [V for that product]  https://m.media-amazon.com/images/I/B1T59dMpkyL.pdf (Veepeak guide)
- STN-based adapters (OBDLink) have their own PowerSave; `ATLP` works only in "ELM327 low power mode"; OBDLink says `ATLP` is deprecated in favour of `STSLEEP`. [V] https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
- Clone behaviour for `ATLP`: **unknown** (no source found). Expect: `?` or `OK` with no real current saving; a few may lock up. [U]
- Wake-from-low-power over BT: a clone that really sleeps is woken by sending any character; python-OBD sends a space and waits 1 s. [V] (python-OBD source)
- Recommended approach for us:
  1. On ACC off / app stop: stop polling, close the RFCOMM/GATT/TCP connection cleanly. With no host traffic the ELM stops sending wake-ups after the ECU closes the session; the dongle idles at its own standby current.
  2. Optionally try `ATLP` once at shutdown (best effort, ignore `?`). Do not rely on it. Never send it while we expect more traffic.
  3. Real battery protection is in the car/unit: the ECU's OBD port is typically permanently powered (pin 16), so a clone left plugged in drains the battery for weeks regardless. Advise users in-app (a one-time hint). Hard-wired alternatives: adapters with an on/off switch, or ones with voltage-based auto-sleep (OBDLink PowerSave, STN-based vLinker/OBDLink). [V for OBDLink]
  4. Use `ATRV` (when it is trustworthy) to detect "engine off / ignition off" (battery <~12.4 V and falling vs running ~13.5-14.5 V) as a secondary signal. Note: `ATRV` on clones can be off by several tenths. [U]

## 7. Raw response samples (parser fixtures)

Conventions
- `\r` = 0x0D. Every reply ends with `\r\r>` (data/status line, empty line, prompt) unless noted. Samples are shown *as the app should receive them after the `ATE0` echo has been turned off*.
- Hex bytes uppercase, single-space separated with `ATS1`; with `ATS0` remove all spaces.
- Header format ISO 9141-2 (`ATH1`): request header `68 6A F1` (priority 0x68, target 6A = functional, tester F1). Response header `48 6B xx` (0x48, 6B = "to tester", xx = ECU address, 0x10 in datasheet/most OBD docs; Toyota may differ -> parser must accept any xx). Last byte = checksum = sum of all preceding bytes mod 256. [V for header/checksum rule: datasheet "three header bytes, up to seven data bytes, one checksum"; KWP default wakeup `C1 33 F1 3E 23` and its checksum 0x23 verified by sum.]
- Samples marked **[V payload]** have payload bytes taken from the ELM327 datasheet (J1850/ISO examples); the K-line framing/checksum around them is **[R]** (checksums computed by me, sum mod 256). Note: the datasheet's own multi-ECU `48 6B 10 41 00 BE 3E B8 11 FA` line carries the *J1850* CRC (0xFA), not an ISO 9141 sum checksum (would be 0xC9) -> do **not** use that last byte as a K-line fixture.
- No raw log from a real Toyota ISO 9141 ECU was found in public sources. All K-line header-on lines below are **[R]**. Please replace with captures from the real car (see section 8, capture plan).

### 7.1 Adapter / session

```
ATZ  (echo off, banner)           ->  \r\rELM327 v1.5\r\r>               [R]  (datasheet shows 'ELM327 v1.4b' then prompt [V])
ATZ  (echo still on)              ->  ATZ\r\r\rELM327 v1.5\r\r>          [R]
ATI                               ->  ELM327 v1.5\r\r>                   [R]
ATE0                              ->  OK\r\r>   (first time: ATE0\rOK\r\r>) [R]
ATL0 / ATS0 / ATH1 / ATAT1        ->  OK\r\r>                            [R]
ATSP3                             ->  OK\r\r>                            [R]
ATRV                              ->  12.6V\r\r>                         [V datasheet shows "12.6V" + prompt]
ATRV (engine running)             ->  14.1V\r\r>                         [R]
ATDP (fixed protocol 3)           ->  ISO 9141-2\r\r>                    [R] (datasheet shows 'AUTO, SAE J1850 VPW' form [V])
ATDP (auto, found 3)              ->  AUTO, ISO 9141-2\r\r>              [R]
ATDP (KWP 5 baud) / (KWP fast)    ->  ISO 14230-4 (KWP 5BAUD)\r\r>  /  ISO 14230-4 (KWP FAST)\r\r>   [U] general knowledge; clones may format differently
                                      (one clone printed 'ISO 15765-4 (CAN11/500)' without spaces [V ELMduino #69])
ATDPN (fixed 3) / (auto, 3)       ->  3\r\r>  /  A3\r\r>                 [R]
ATIB 96 / ATIIA 13 / ATSW00 / ATKW0 -> OK\r\r>                          [R]  (any may be '?\r\r>' on a clone)
ATKW                              ->  KW1=08 KW2=08\r\r>                 [U] format unverified; ISO 9141 key words are typically 08 08 / 94 94
unknown command                   ->  ?\r\r>                             [V]
```

### 7.2 Init / search messages

```
(first request, ATSP0 auto)   ->  SEARCHING...\rBUS INIT: OK\r41 00 BE 3E B8 11 \r\r>    [R] ('BUS INIT: ' line in auto mode is [U])
(first request, ATSP3 fixed)  ->  BUS INIT: ...OK\r41 00 BE 3E B8 11 \r\r>                [R] (3 dots = slow init [V])
(init failed, fixed proto)    ->  BUS INIT: ...ERROR\r\r>                                 [V string; format R]
(auto search failed)          ->  SEARCHING...\rUNABLE TO CONNECT\r\r>                    [V strings; format R]
(ECU silent / no such PID)    ->  NO DATA\r\r>                                            [V]
(interrupted by next char)    ->  STOPPED\r\r>                                            [V]
(checksum/format error)       ->  48 6B 10 41 0C 1A F8 00 <DATA ERROR\r\r>               [R] marker '<DATA ERROR' follows the bad line [V datasheet]
(not connected, some clones)  ->  NOT CONNECTED\r\r>                                      [V ELMduino #69, one clone]
(brown-out)                   ->  LV RESET\r\r>   (settings back to defaults, echo ON)     [V]
```

### 7.3 Mode 01 without headers (ATH0, ATS1)

```
0100 ->  41 00 BE 3E B8 11 \r\r>          payload [V datasheet]
0120 ->  41 20 90 15 B0 15 \r\r>          [R] plausible bitmap (PIDs 21,24,... ) - replace with real capture
0140 ->  41 40 44 CC 00 21 \r\r>          [R]
010C ->  41 0C 1A F8 \r\r>                [V datasheet: 0x1AF8/4 = 1726 rpm]
0105 ->  41 05 7B \r\r>                   [V datasheet: 0x7B-40 = 83 C]
010D ->  41 0D 3C \r\r>                   [R]  60 km/h
```
With `ATS0`: `410C1AF8\r\r>`. If the clone does not strip the trailing space, expect `41 0C 1A F8 \r`.

### 7.4 Mode 01 with headers (ATH1) - ISO 9141-2, ECU address 0x10 [R]

```
0100 ->  48 6B 10 41 00 BE 3E B8 11 C9 \r\r>
0120 ->  48 6B 10 41 20 90 15 B0 15 8E \r\r>
0140 ->  48 6B 10 41 40 44 CC 00 21 75 \r\r>
010C ->  48 6B 10 41 0C 1A F8 22 \r\r>
0105 ->  48 6B 10 41 05 7B 84 \r\r>
010D ->  48 6B 10 41 0D 3C 4D \r\r>
```
Checksums verified by sum-mod-256. Two ECUs answering (engine + gearbox) would produce two lines with different address bytes (datasheet example `48 6B 10 ...` and `48 6B 18 ...` [V]); the E12 T-Sport is a manual car so expect one.
Requests, for the record: `68 6A F1 01 0C D0` (what the ELM puts on the wire for `010C`); KWP form `81 13 F1 01 0C 92` for the Toyota 0x13 variant (section 8). [R]

### 7.5 Mode 03 / 07 (stored / pending DTCs)

On ISO 9141/KWP each frame carries 3 DTCs (6 data bytes, padded with `00`). With 0 DTCs the ECU either answers one all-zero frame or, on some ECUs, nothing (`NO DATA`) - parser must treat both as "no codes". Multi-frame results are *separate lines*, each starting with `43` (not `0:`/`1:` CAN style). All **[R]**.

```
03, 0 DTC (ATH0)       ->  43 00 00 00 00 00 00 \r\r>
03, 0 DTC (ATH1)       ->  48 6B 10 43 00 00 00 00 00 00 06 \r\r>
03, 1 DTC P0171 (ATH0) ->  43 01 71 00 00 00 00 \r\r>
03, 1 DTC (ATH1)       ->  48 6B 10 43 01 71 00 00 00 00 78 \r\r>
03, 3 DTC (ATH0)       ->  43 01 71 13 49 16 56 \r\r>          P0171, P1349, P1656
03, 3 DTC (ATH1)       ->  48 6B 10 43 01 71 13 49 16 56 40 \r\r>
03, 4 DTC (ATH0)       ->  43 01 71 13 49 16 56 \r43 04 20 00 00 00 00 \r\r>    (two frames, one CR between lines)
03, 4 DTC (ATH1)       ->  48 6B 10 43 01 71 13 49 16 56 40 \r48 6B 10 43 04 20 00 00 00 00 2A \r\r>
07, 0 DTC (ATH1)       ->  48 6B 10 47 00 00 00 00 00 00 0A \r\r>
07, 1 DTC (ATH1)       ->  48 6B 10 47 01 71 00 00 00 00 7C \r\r>
04 (clear)             ->  44 \r\r>        (ATH1: 48 6B 10 44 xx)       [R]; some ECUs return NO DATA/7F
```
DTC decoding: bits 15-14 = P/C/B/U (00=P, 01=C, 10=B, 11=U), bits 13-12 = first digit, then three hex digits. `01 71` -> P0171; `13 49` -> P1349; `16 56` -> P1656; `04 20` -> P0420. [V standard J1979 decoding; example Python in evrenonur reference confirms byte1=0x01,byte2=0x33 -> P0133]
Parser note: count of DTCs is *not* given on K-line; iterate all lines, drop `00 00` pairs.

### 7.6 Mode 02 freeze frame

Request `02 02 00` (PID 02 = DTC that caused freeze frame, frame 0). Response format per J1979: `42 <PID> <frame> <data...>`.
```
02 02 00 (ATH0) ->  42 02 00 01 71 \r\r>              [R]  (matches the fixture in the task description; P0171)
02 02 00 (ATH1) ->  48 6B 10 42 02 00 01 71 79 \r\r>  [R]
02 0C 00 (ATH0) ->  42 0C 00 1A F8 \r\r>              [R]
no freeze frame ->  NO DATA \r\r>                     [R]
```

### 7.7 Mode 09 02 (VIN) on ISO 9141-2

J1979 for non-CAN: 5 lines `49 02 01 ... 49 02 05`, the first has 3 filler `00` + first VIN char. Toyota pre-CAN ECUs frequently do not support Mode 09 at all (`NO DATA`) [U].
```
09 02 (ATH0), VIN 1D4GP00R55B123456 ->
  49 02 01 00 00 00 31 \r
  49 02 02 44 34 47 50 \r
  49 02 03 30 30 52 35 \r
  49 02 04 35 42 31 32 \r
  49 02 05 33 34 35 36 \r\r>
```
payload [V datasheet, J1850 example]; K-line `ATH1` form adds `48 6B 10` + sum checksum, e.g. `48 6B 10 49 02 01 00 00 00 31 40`, `... 49 02 02 44 34 47 50 1F`, `... 03 30 30 52 35 F8`, `... 04 35 42 31 32 EC`, `... 05 33 34 35 36 E5` [R].
VIN assembly: drop the three leading `00`, concatenate the 4 data bytes of each line in line order, ASCII decode, trim NUL.
Also `09 00` (supported PIDs) -> `49 00 55 00 00 00` style [R].

### 7.8 Noise a robust reader must survive
- Leading/trailing `\r`, `\n`, spaces; NUL (0x00) bytes occasionally seen on cheap BT bridges [U general]; echoed command line when `ATE0` failed; `>` possibly arriving in the same chunk as data or alone; unsolicited `LV RESET`/`ELM327 v1.5` banner when the adapter resets mid-session; `SEARCHING...` lines; `BUS INIT:` lines; `STOPPED`.
- Read until `>`; split on `\r`/`\n`; strip; ignore empty lines.

## 8. Toyota-specific note (matters for the init sequence)

Owner reports for Australian Corolla Sportivo / Celica ZZT230 / JDM Toyotas (2ZZ-GE family) show that **generic auto-detect fails** and Torque needed a custom init: `ATIB 96`, `ATIIA 13`, `ATSH8113F1`, `ATSP A4`, `ATSW00` (9600 baud, slow init to address 0x13, KWP header `81 13 F1`). Sources: https://wiki.torque-bhp.com/view/Custom_Init and https://au.toyotaownersclub.com/topic/29475-obdii-reader-for-zze-corollas-now-working/ (page 3; forum returns 403 to fetchers, quoted via search tool). European EOBD cars are listed as plain **ISO 9141-2** (see `2zz-ge-obd.md`), which should work with the standard `ATSP3`. Another owner with a ZZE121L (2004) and an "ELM327 v1.5" reported failing with all generic commands (same thread). Treat the Toyota init as a *selectable fallback profile*, not the default.

Capture plan for real fixtures: add a debug "raw log" toggle that writes every TX/RX chunk with timestamps and escapes (`\r`, `\n`, `\0`) to a file; ask the user to log one session: `ATZ`, `ATE1`, `ATI`, `ATDP`, `ATDPN`, `ATSP3`, `0100`, `0120`, `0140`, `010C`, `0105`, `03`, `07`, `0902`, `ATH1`, repeat `0100`, `ATRV`, and one idle >5 s then `010C` (to see the re-init).

## 9. Sources (consolidated)
- ELM327 datasheet (v1.4b): https://www.scantool.net/scantool/downloads/103/elm327dsh.pdf ; https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DSH.pdf ; later revisions https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf
- OBDLink command manual (ATLP deprecated, STN PowerSave): https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
- Wikipedia ELM327 (version history, clones report fake versions): https://en.wikipedia.org/wiki/ELM327
- Linux can327 docs (clones, `?`, BUFFER FULL): https://docs.kernel.org/7.0/_sources/networking/device_drivers/can/can327.rst.txt
- ELMduino clone issue: https://github.com/PowerBroker2/ELMduino/issues/69
- Clone identification: http://cvtz50.info/en/elm327/ ; https://github.com/TheTom/elm327_obd_for_mac/blob/main/ELM327_TECHNICAL_REFERENCE.md ; https://blog.jklr.org/2018/05/26/fake-elm327-monitor.html
- BT board teardown/firmware: https://github.com/uholeschak/ediabaslib/blob/master/docs/Replace_Elm327_BT_Mini_Firmware.md
- BLE UUIDs: https://support.obdlink.com/support/solutions/articles/43000746707-obdlink-cx-adapter-notes ; https://github.com/particle-iot/ble-gateway-library/blob/main/examples/veepeak/README.md ; https://github.com/fdittgen-png/tankstellen/issues/3180
- Car Scanner docs: https://www.carscanner.info/en/android-bluetooth ; https://www.carscanner.info/en/bluetooth-troubleshooting ; https://www.carscanner.info/en/optimizing-connection-speed ; https://www.carscanner.info/wifi/
- Veepeak head-unit/PIN notes: https://m.media-amazon.com/images/I/915287zOFAL.pdf
- python-OBD (ATLP handling): https://github.com/brendan-w/python-OBD/blob/master/obd/elm327.py
- Torque custom init (Toyota): https://wiki.torque-bhp.com/view/Custom_Init
- Generic command log structure (no real K-line logs): https://jonesrh.info/volvo850/elm327_volvo_850_svc70_scan.html
