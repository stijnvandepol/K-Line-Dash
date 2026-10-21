# Toyota Corolla E12 T-Sport (2ZZ-GE): OBD facts, Toyota DTCs, warning thresholds

Research date: 2026-10-21. Car: Toyota Corolla E12 (E120/E130 generation) T-Sport, 1.8 VVTL-i, engine 2ZZ-GE, European market 2002-2006.

Legend: **[V]** verified in a cited source, **[U]** uncertain/anecdotal/inferred, **[R]** reasoning by me.
Important: I found **no public Torque/forum log of a European Corolla T-Sport's supported-PID bitmap** and no raw K-line capture from this car. Everything about "which PIDs exist" is therefore expectation, to be confirmed with the first `0100/0120/0140` scan on the real car (the app should show/save that bitmap).

---

## 1. OBD protocol for the European Corolla E12 / 2ZZ-GE

### 1.1 Evidence
- Owner-tested compatibility database (klavkarr): **Toyota Corolla E120/E130 (2000-2006), 1.8 VVTi 192 hp, model years 2002 and 2003: "Protocol: ISO 9141"**. Same site lists Celica VII (T23) 1.8 192 hp 2003 as ISO 9141. [V, vendor database, no raw logs]
  - https://www.klavkarr.com/compatible/toyota-corolla-e120/e130-59000131
  - https://www.klavkarr.com/compatible/toyota-celicavii-59000130
- pinouts.ru Toyota table (explicitly "not 100% complete"): Corolla 1.8 VVTi 192 HP 2002 = ISO 9141 / "Gasoline (190 HP) 2002 ISO 9141-2"; "Toyota Corolla (European) 2002: ISO 14230-4, ISO 9141-2"; summary line "1999-2003: ISO 9141; 2004-2006: ISO 9141 or CAN". E12 petrol Combi 1.4/1.8 2006-2007 also ISO 9141-2. Diesels 2004+ differ (CAN/KWP). [V, secondary]
  - https://pinouts.ru/CarElectronics/Toyota_lexus_obd2_diagnostic.shtml
- European Toyotas of the same era with the same ECU family (Avensis T22/T25 petrol 2000-2005, Yaris, RAV4, MR2) are listed ISO 9141-2 in the same table. [V secondary]
- EOBD became mandatory for new European petrol cars from 2001 (hence "OBD2-compliant" and K-line at pin 7). [V] https://allpinouts.org/pinouts/connectors/car/obd-2-iso-14230-4-iso-9141-2-kwp2000-rs-232/
- Counter-evidence for *Australian/Japanese-market* ZZE Corollas / Celica ZZT230: generic auto-detect fails; Torque needs `ATIB 96`, `ATIIA 13`, `ATSH8113F1`, `ATSP A4`, `ATSW00` (KWP2000 5-baud init at 9600 baud, address 0x13). That is a Toyota/JDM-style variant and not what the EU spec sheets say. Sources: https://wiki.torque-bhp.com/view/Custom_Init (also lists `Toyota Vitz 01.2002: ATSH8213F1 ATIB96 ATIIA13`) and the Toyota Owners Club AU thread https://au.toyotaownersclub.com/topic/29475-obdii-reader-for-zze-corollas-now-working/ (page 3; fetch blocked, text seen via search tool; one ZZE121L owner with an ELM v1.5 still failed with everything he found). [V]
- Pin check: Toyota DLC pin 7 = K-line, pin 15 = L-line (older models); pins 6/14 CAN only on later cars. [V pinouts.ru]

### 1.2 Conclusion
- **Expected: ISO 9141-2 (ELM protocol 3), 10400 baud, 5-baud init to address 0x33 (ELM default), request header `68 6A F1`, response header `48 6B xx`.** [R/V]
- 2004-2006 E12 petrol cars: mostly still K-line; CAN is possible on some 2005+ trims ([U]; T-Sport/Compressor 2005-2006 unknown). Include CAN (`ATSP6`) as the last rung of the fallback ladder.
- ECU address in the response (`xx`): standard engine ECU is $10 in J1979 examples (datasheet shows $10 = engine, $18 = transmission). [V for the general convention] **Not confirmed for this Toyota.** The parser must accept any `xx`.

### 1.3 Suggested connection ladder (selectable profiles in the app)
1. `ATZ`, `ATE0`, `ATL0`, `ATS1`, `ATH1`, `ATAT1`, `ATSP3`, then `0100` (expect `BUS INIT: ...OK` and `41 00 ..`).
2. If `BUS INIT: ...ERROR` / `UNABLE TO CONNECT`: `ATSP5` (KWP fast init), then `ATSP4` (KWP 5-baud).
3. `ATKW0` + `ATSP3` retry (ignore key-word values).
4. Toyota/AU/JDM profile: `ATIB 96`, `ATIIA 13`, `ATSH 81 13 F1` (3-byte header form `ATSH8113F1`), `ATSP A4`, `ATSW 00`.
5. `ATSP6` (CAN 11/500) as a final probe.
Always require ignition ON (engine not necessarily running) before init. [V datasheet: most common error is key not ON]
Slow init takes 2-3 s per the datasheet; expect ~3 s from the first request to the first reply, and again after any >5 s idle gap. [V/R]

## 2. Expected Mode 01 PIDs and engine behaviour

No MAP sensor: the 2ZZ-GE uses an air flow meter (MAF) with the intake-air temperature sensor built into it. Sources: GenVibe summary of Car & Driver/Sport Compact Car 2002 article ("multi-point EFI; AFM") and Haltech's 2ZZ-GE page ("inlet air temperature sensor incorporated in the MAF sensor", narrow-band O2). [V, secondary] https://forums.genvibe.com/phpBB3/viewtopic.php?t=315 ; https://support.haltech.com/portal/en/kb/articles/2zz-ge-engine

Expected supported (to be confirmed, [U/R] standard J1979 behaviour for a 2002 EOBD petrol engine):
`01 03 fuel system status`, `04 calc load`, `05 ECT`, `06 STFT B1`, `07 LTFT B1`, `0C RPM`, `0D speed`, `0E timing advance`, `0F IAT`, `10 MAF g/s`, `11 throttle`, `13 O2 sensors present`, `14 O2 B1S1 voltage + trim`, `15 O2 B1S2`, `1C OBD standard`, plus `20`-bitmap items (`21 distance with MIL`, `2x/3x` unlikely on K-line). `0B` (MAP) **not expected**. `0A/23` fuel pressure, `42` control module voltage, `5C` oil temp typically **not** supported on this generation. Use `ATRV` for battery voltage. [U]
- O2 sensors: Haltech says the 2ZZ-GE comes with a **4-wire narrow-band** O2 sensor. [V, secondary] The Toyota 1ZZ/2ZZ DTC flash-code table lists P0135 (O2 heater) and P0171 rather than Toyota A/F-sensor codes. [V] https://procarmanuals.com/toyota-engine-1zz-fe-2zz-fe-error-codes-list . So: Bank1-Sensor1 = narrow-band (0..1.0 V swinging), Bank1-Sensor2 = narrow-band downstream. A/F ("wideband type") sensors, which report as ~3.3 V centre in generic PID `0x14`-family on other Toyotas, are **not expected** here. [U: later/US/Compressor variants may differ]
  - Source for A/F-sensor Toyotas (for contrast): https://www.motor.com/magazine-summary/dealing-with-sensitive-types-toyota-af-sensors (A/F sensor needs ~1200 F; combined fuel trims normally 0 +/-10%).
- Fuel trims: use generic ranges (STFT swings +/-5-10 % around 0 in closed loop; LTFT normally within +/-10 %; >15 % = suspicious, >25 % = fault). The MOTOR article gives "combined trims normally 0 +/-10 %". [V for that range, general Toyota]
- Mode 02 (freeze frame): normally supported by OBD-II ECUs; format in `elm327-clones.md` section 7.6. [U]
- Mode 06: on non-CAN protocols the generic Mode 06 test is not guaranteed; probe `0600`. [U]
- Mode 09 (VIN, CAL ID): frequently **not** supported on pre-CAN Toyotas; expect `NO DATA`. [U]
- Mode 03/07 (stored/pending DTCs), Mode 04 (clear): expected to work; frames contain 3 DTCs each. [U]
- Torque/Car Scanner "Toyota extended PIDs" (Mode 21/22 via KWP) exist as separate PID files but use proprietary services; not researched here. https://wiki.torque-bhp.com/view/PIDs

### 2.1 Engine reference figures (2ZZ-GE)
| Item | Value | Source |
|---|---|---|
| Displacement / power (EU Corolla T-Sport, Celica 190) | 1796 cc, 192 PS peak at ~7800 rpm, 180 Nm at 6800 | https://www.autodata1.com/en/car/toyota/celica/celica-t23-18-vvtl-i-t-sport-192-hp [V]; Wikipedia 2ZZ-GE shows 7600-7800 depending on market |
| VVTL lift (cam change-over) | ~6000-6200 rpm (set-points 6000-6700 depending on vehicle); high-lift cam not engaged until coolant >= ~60 C (Toyota ECU gate) | https://en.wikipedia.org/wiki/Toyota_ZZ_engine ; Haltech [V] |
| Rev limiter | PCM cuts fuel/spark at ~8200 rpm (Wikipedia); a 2002 magazine summary states redline 7800 / fuel cut 8400; NA Celicas 7900-8200; Lotus tune 8500; early JDM 8600 | Wikipedia; GenVibe thread [V, conflicting] |
| Idle (warm) | 750-800 rpm | https://www.engine-specs.net/toyota/2zz-ge.html [U] |
| Idle (cold start) | typically elevated (~1000-1500 rpm, drops as it warms) | [U] common knowledge, no source found |
| Thermostat opening temp | 82 C (aftermarket spec for 2ZZ-GE Corolla); Toyota ZZ family cooling spec 80-84 C | https://www.123spareparts.co.uk/car-parts/toyota/corolla-zze12-nde12-zde12/1-8-vvtl-i-ts-zze123-165-kw/thermostat/19252 ; https://www.toyota-club.net/files/faq/04-01-10_rem_1zzfe_eng.htm [V] |
| Normal operating coolant | ~85-95 C (warm cruise ~90) | https://mywikimotors.com/toyota-2zz ("~95 C") [U] |
| Cooling fan | electric fan, switch-on region ~93-100 C in a Toyota cooling manual (engine not identified) | toyotaownersclub attachment 31129 [U] |
| Oil system weak points | oil burn >150k km, VVTL lift bolt, timing chain | mymotorlist / mywikimotors [U] |

## 3. Toyota-specific P1xxx codes relevant to the 2ZZ-GE

Primary source for what the ZZ ECU actually sets (flash-code table with DTC numbers): https://procarmanuals.com/toyota-engine-1zz-fe-2zz-fe-error-codes-list (covers 1ZZ-FE and 2ZZ-GE together; some entries belong to the 1ZZ ETCS/turbo/D-4 variants). Generic Toyota code catalogues: https://toyotaspace.com/toyota-obd2-codes-list ; https://www.autotalk.com/toyota-obd-ii-codes-2104 ; https://github.com/Wal33D/dtc-database/blob/main/data/source-data/toyota_codes.txt . Code text varies slightly between sources.

| Code | English description | Nederlands (1 regel) | Applies to 2ZZ-GE? |
|---|---|---|---|
| P1349 | VVT system malfunction (bank 1) - variable valve timing / VVTL-i system | Fout in het variabele kleptiming-systeem (VVT-i/VVTL-i). | **Yes** (flash code 59). Typical suspects: oil level/oil control valve/lift solenoid. [V code/[U] cause] |
| P1346 | VVT sensor / camshaft position sensor circuit range/performance (bank 1) | Nokkenaspositiesensor/VVT-signaal buiten bereik of onjuist. | **Yes** (flash code 18) |
| P1656 | OCV (oil control valve) circuit malfunction (bank 1) | Stroomkring van het oliecontroleventiel (VVT) defect. | **Yes** (flash code 39) |
| P1335 | No crankshaft position sensor signal while engine running | Geen krukaspositiesignaal terwijl de motor draait. | **Yes** (flash code 13) |
| P1300 | Igniter circuit malfunction No. 1 | Ontstekingsmodule/bobine-aansturing cilinder 1 defect. | **Yes** (flash code 14) |
| P1305 | Igniter circuit malfunction No. 2 | ...cilinder 2 defect (nummering Toyota-afhankelijk). | **Yes** (flash code 15) |
| P1310 | Igniter circuit malfunction No. 3 (No. 2 on some models) | ...cilinder 3 defect. | **Yes** (flash code 15) |
| P1315 | Igniter circuit malfunction No. 4 | ...cilinder 4 defect. | **Yes** (flash code 14) |
| P1600 | ECM BATT malfunction (backup/keep-alive power) | ECU-geheugenvoeding (accu-backup) onderbroken of defect. | Likely (general Toyota; seen after battery disconnect) [U] |
| P1605 | Knock control CPU malfunction | Klopregeling-processor in ECU defect. | Possible (general Toyota) [U] |
| P1200 | Fuel pump relay circuit | Stroomkring brandstofpomprelais defect. | Generic Toyota, may not apply (not in the ZZ flash table) |
| P1130 / P1133 / P1135 | A/F sensor circuit range/performance / response / heater circuit (bank 1 sensor 1) | Lucht/brandstofsensor (breedband) bereik / respons / verwarming defect. | **Probably not** (2ZZ-GE has a narrow-band O2; check P0135/P0141 instead). Only if a later variant has an A/F sensor. |
| P1150 / P1153 / P1155 | Same for bank 1 sensor 2 | Idem, tweede sensor. | Probably not (as above) |
| P1120 / P1121 | Accelerator pedal position sensor circuit / range | Gaspedaalpositiesensor defect/buiten bereik. | Probably not (ETCS-i is on other ZZ variants; the EU T-Sport is believed cable-throttle [U]) |
| P1125-P1129, P1633 | ETCS (electronic throttle control) motor/power/lock faults | Elektronische gasklepbesturing (ETCS) defect. | Probably not |
| P1010 / P1011 / P1012 | OCV for VVTL circuit / open / closed malfunction (bank 1) | Lift-oliecontroleventiel (VVTL) elektrisch defect/open/klemt. | **Uncertain**: listed in a Toyota catalogue as "VVTL" but not in the ZZ flash-code table [U] |
| P1780 | PNP (park/neutral) switch malfunction | Park/neutraal-schakelaar defect. | No (automatic gearbox only; T-Sport is manual) |
| P1405 / P1406 / P1400 / P1401 | Turbo pressure / sub-throttle sensor | Turbodruk-/hulpgasklepsensor. | No (other engines) |

Notes
- All 2ZZ-GE ECU faults can also appear as generic P0xxx codes (e.g. P0335 crank, P0340 cam, P0171/P0174 lean, P0420 catalyst); the app should show the standard SAE description for P0/P2/P3 and use this table for P1xxx.
- Manufacturer-specific codes' meaning differs between brands (e.g. P1135 means something else on Nissan) -> only decode P1xxx with the Toyota table when the vehicle profile is Toyota.
- Mode 03 gives the code, not the flash-code number; the flash code column is for the shop manual only.

## 4. Suggested default warning thresholds (all user-adjustable)

| Parameter | Default | Reasoning |
|---|---|---|
| Coolant "warm-up" indicator | < 60 C | VVTL high-lift cam is inhibited below ~60 C; also marks "avoid hard use" [V] |
| Coolant normal band | 82-100 C (green) | Thermostat opens at ~82 C; hot-running ~90-95 C normal [V/U] |
| Coolant warning (amber) | >= 103 C | Above normal incl. fan cycling; leaves margin from boil-over of a 1:1 coolant mix under 1.0-1.5 bar cap [R] |
| Coolant alarm (red) | >= 108 C | Cooling system at limit; stop/idle recommended [R] |
| Intake air temp warning | >= 60 C (info) | heat soak; not critical [R] |
| Battery, ignition ON, engine off (ATRV) | warn < 12.0 V, alarm < 11.5 V | 12.6 V rested full; <12.0 V = ~50 % charge [R] |
| Battery, engine running | normal 13.4-14.8 V; warn < 13.2 V or > 15.0 V; alarm < 12.5 V or > 15.5 V | Alternator regulation ~13.8-14.5 V; charging faults [R] (clone `ATRV` can be inaccurate -> allow calibration offset) |
| Shift light | 7500 rpm (flash at 7900) | Peak power 7600-7800 rpm and VVTL on above ~6200, limiter 8200-8400 depending on source [V]; default leaves headroom; user sets own |
| High-rpm warning when cold | rpm > 5500 while coolant < 60 C (amber) | engine not at temperature and VVTL gated [R] |
| Rev limiter marker on gauge | 8200 rpm (red zone from 8000) | Wikipedia; GenVibe says 8400 fuel cut [V conflicting]; allow override |
| Lift engaged indicator | rpm >= 6200 and coolant >= 60 C | model of VVTL behaviour [V] (indicator is inferred, not measured from ECU) |
| Idle rpm out-of-range | outside 600-1100 rpm at warm idle | warm idle 750-800 [U] |
| LTFT / STFT warning | abs(LTFT) > 15 % amber, > 25 % red; abs(STFT) > 20 % for > 10 s amber | generic Toyota guidance; combined +/-10 % normal [V for 10 %] |
| Stale data | no valid PID for 3 s on K-line -> mark as stale | K-line session closes after ~5 s idle [V datasheet] |

## 5. Things to confirm on the real car (so the app should log them)
1. Actual protocol/profile that connects (ISO 9141-2 `ATSP3` vs one of the Toyota variants) and the response header byte `xx`.
2. The `0100/0120/0140/0160` bitmaps (does `0B`, `42`, `5C` exist?), plus Mode 02/06/09 support.
3. Real RPM range reached and whether RPM drops to 0 at limiter (fuel cut) - helps shift-light default.
4. Raw frames for the fixtures in `elm327-clones.md` section 7 (all K-line header-on samples there are reconstructed).

## 6. Sources
- Protocol: https://www.klavkarr.com/compatible/toyota-corolla-e120/e130-59000131 ; https://www.klavkarr.com/compatible/toyota-celicavii-59000130 ; https://pinouts.ru/CarElectronics/Toyota_lexus_obd2_diagnostic.shtml ; https://allpinouts.org/pinouts/connectors/car/obd-2-iso-14230-4-iso-9141-2-kwp2000-rs-232/
- AU/JDM Toyota variant: https://wiki.torque-bhp.com/view/Custom_Init ; https://au.toyotaownersclub.com/topic/29475-obdii-reader-for-zze-corollas-now-working/
- ELM327 datasheet (K-line behaviour): https://www.scantool.net/scantool/downloads/103/elm327dsh.pdf
- Engine: https://en.wikipedia.org/wiki/Toyota_ZZ_engine ; https://support.haltech.com/portal/en/kb/articles/2zz-ge-engine ; https://forums.genvibe.com/phpBB3/viewtopic.php?t=315 ; https://www.engine-specs.net/toyota/2zz-ge.html ; https://mywikimotors.com/toyota-2zz ; https://www.autodata1.com/en/car/toyota/celica/celica-t23-18-vvtl-i-t-sport-192-hp ; https://www.toyota-club.net/files/faq/04-01-10_rem_1zzfe_eng.htm ; thermostat part spec https://www.123spareparts.co.uk/car-parts/toyota/corolla-zze12-nde12-zde12/1-8-vvtl-i-ts-zze123-165-kw/thermostat/19252
- DTCs: https://procarmanuals.com/toyota-engine-1zz-fe-2zz-fe-error-codes-list ; https://toyotaspace.com/toyota-obd2-codes-list ; https://www.autotalk.com/toyota-obd-ii-codes-2104 ; https://github.com/Wal33D/dtc-database/blob/main/data/source-data/toyota_codes.txt ; https://www.carparts.com/blog/p1135-code-nissan-toyota-lexus/ ; https://www.motor.com/magazine-summary/dealing-with-sensitive-types-toyota-af-sensors
