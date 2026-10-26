# Sources for the Nissan/Infiniti, Mazda and Ford trouble code tables

Research date: 2026-10-26. Tables: `obd-core/src/main/resources/dtc/make-nissan.tsv`, `make-mazda.tsv` and `make-ford.tsv`. Rules from `car-makes.md` section 2.3, same as for the VW Group and Honda tables (`dtc-sources-vag-honda.md`): own wording, at least two separate publications that agree and none that disagrees, no bulk import, no VCDS/ODIS-style tool data. All text in the tables is our own wording; the sources were read for the meaning only. No car was available, so nothing here was tested on a vehicle.

## Method

1. Candidates: every P1xxx code that a make-labelled list carries. Lists were read as text, parsed into code to wording, and compared across sources by component, bank/cylinder/sensor number and fault type (open, short, high, low, implausible, performance), not by wording.
2. Kept: at least two separate publications agree and no source gives a different component, bank, sensor or fault type. Left out: any code where sources disagree (including a single source that is plainly mis-transcribed, such as a swapped high/low), where only one source exists, or where the meaning is known to change with the engine generation.
3. Selection, not import: families that are US-specific or not useful on road cars (Ford fuel pump driver module, transmission solenoids, Nissan A/F sensor family P1271-P1289, hybrid-only codes) were not taken even where sources agree.
4. The Wal33D aggregator (`dtc-database`) was not used at all (counts as zero, provenance unknown). Mirrors count once; this is noted per make below.
5. Honest limit: for all three makes the public lists ultimately reproduce the manufacturer's own diagnostic code chart (Nissan EC section, Ford OBD-II chart, Mazda service data). "Independent" therefore means separate publications, not separate origins, exactly as in the VW/Honda document. Codes where publications disagree were dropped for that reason.

Blocked sites (Cloudflare or login walls: ecutesting.com, go-parts.com, the autocodes.com list pages, nissanhelp.com, manualslib.com, Scribd) were not circumvented. A few single code pages of tat.net.au, engine-codes.com and autocodes.com, and the obd-codes.com list pages, were read through a fetch service.

## Nissan / Infiniti (`make-nissan.tsv`)

| Key | Source | Notes |
|---|---|---|
| NM | Nissan Engine Control (EC) section service manuals: 2003 Murano (servicemanuals-backend.fly.storage.tigris.dev/manuals/Nissan/murano/2003/ec.pdf), 2005 Titan (.../titan/2005/ec.pdf), 2008 Sentra (.../Sentra/2008_Sentra/ec.pdf) | OEM manual text, read for the meaning only; the three manuals count as one source (one origin). `car-makes.md` 2.3 item 4 says not to ship anything derived from OEM manuals, so NM is never the only source of a code: every code in the table has at least one non-NM source. |
| ET | EcuTek Nissan GT-R ECM DTC list (ecutek.atlassian.net, PDF) and Nissan 370Z DTC list | one tuning vendor, counted once; covers the 2008+ cars, where many P1xxx codes were replaced by generic ones |
| TC | https://www.troublecodes.net/p1codes/ , entries labelled Nissan/Infiniti (174 codes) | multi-make list; noisy for some codes (P1195-P1197 are hybrid codes mislabelled, P1550-P1552 are shared with Toyota entries); used only where it agrees |
| OC | https://www.obd-codes.com/trouble_codes/nissan/ (33 codes) | the Carvitas Nissan list is a word-for-word copy, so OC counts once |
| TA | tat.net.au fault code database, Infiniti entries (ids 5410-5450, P1111 to P1401) | Australian technician site; titles contain typos and some loose wording |
| AC | https://aboutthecars.com/infiniti/infiniti-obd2-codes-full-list-with-causes-and-fixes/ | Infiniti code list with Nissan service bulletin references |
| AU | single code page on autocodes.com (P1805 Nissan); engine-codes.com P1146 Nissan counted as a confirmation of NM/TA | same operator |

Read and discarded:

- upfix.com / myairbags.com "Nissan/Infinity Factory DTC Code Definitions": OCR-damaged ("[CM", "H025"), the same wording for different codes (P1166/P1167, P1169/P1170) and several contradictions (P1126 thermostat vs throttle relay, P1446 purge valve vs vent valve). Not used.
- obdadvisor.com / OBDCodex Nissan PDF: the code column and the description column are offset in the extracted text, so codes and meanings cannot be paired reliably. Not used.
- Wal33D Nissan file: not used.

Counts: about 183 distinct P1xxx codes seen across TC, OC and ET (plus the manual indexes). 66 kept. Dropped for disagreement or era dependence: P1126 (thermostat on older cars, throttle motor relay open on newer ones), P1550 (TCC solenoid on older cars, battery current sensor on newer ones), P1553/P1554 (open/closed circuit vs performance), P1705 (A/T throttle sensor vs CAN signal compatibility), P1720, P1235, P1234, P1140, P1145, P1110. Dropped as single-source or too thin: the A/F sensor family P1271-P1289, P1400, P1402, P1441-P1443, P1492/P1493 (same title, differing detail), P1610/P1612/P1613/P1614/P1615 (official titles are "chain of ..." and "difference of key", too terse to put in plain words), P1800/P1801, the A/T solenoid codes.

Uncertain:

- Nissan P1xxx meanings drift with the engine generation (some numbers were reassigned, and 2008+ cars replaced many of them with generic codes). The table keeps only codes whose meaning is the same in the 2003 to 2008 manuals and in the independent lists; cars from before about 2000 and very new cars may differ.
- P1143/P1144/P1163/P1164 are kept as "lean/rich shift monitoring" (NM and TC). TA words the same sensors as "voltage low/high", which is the same condition seen from the signal side.
- P1225 and P1226 have the same title in every source (they differ in the detecting condition: value out of range vs learning repeatedly not completed), so both rows carry the same wording.
- P1126 and P1550 above show why this table must not be extended without checking the engine generation.

## Mazda (`make-mazda.tsv`)

| Key | Source | Notes |
|---|---|---|
| MA | https://www.obd-codes.com/trouble_codes/mazda/ ; same text on https://www.mellens.net/mazda/mazda_miata_extra_manual/mazda_trouble_codes.pdf , icarsoft.com and engine-light-help.com | one list, counted once; its P1000-P1500 range is Ford text ("SCP error", "DPFE"), which is why Ford-shared codes were not taken from it |
| MB | https://blog.myairbags.com/mazda-factory-dtc-code-definitions | rewritten, SAE-style wording of the Mazda list (252 codes) |
| TC | https://www.troublecodes.net/p1codes/ , entries labelled Mazda (264 codes) | many Mazda entries are the Ford text; its wording may share an origin with MB |
| KI | Kia lists of obd-codes.com and https://www.engine-light-help.com/kia-check-engine-light.html | the 1.6/1.8 Kia engines of the Sephia/Spectra era used Mazda ECU codes, so they confirm Mazda-only codes (EGR stepper coils, alternator, ignition switch) |

Counts: about 320 distinct P1xxx codes seen. 26 kept. Rule: only codes that Mazda-specific publications confirm and that are not merely a Ford text. Dropped: P1195 (EGR boost sensor in MA and KI, BARO sensor in TC and engine-codes.com), P1449 (MB: tank pressure problem, others: canister drain cut valve), P1485/P1486 (the two Kia lists disagree on which one is vacuum and which vent), P1500-P1508 and P1512 (Kia lists give other meanings, MA/MB differ), P1621-P1624 immobilizer codes (MB and TC share wording, MA says only "immobilizer system fault"), P1627/P1628, P1529, P1540, P1601-P1609, and every Ford-shared code whose only Mazda evidence is a Ford list. Mazda CX-7 and Mazda3 workshop manuals (seen only through search result summaries; manualslib is blocked) describe P1260 as an immobilizer fault, which agrees.

Uncertain: the table fits the older Mazda 323/Protege/MX-5/626 era. Newer Mazda engines (MZR, Skyactiv) use few P1xxx codes and no public per-code list was found; P1260 is the only one known to exist on a 2016+ MX-5 (mx5manual.com).

## Hyundai / Kia (skipped)

No table. Reasons:

- Hyundai and Kia are separate manufacturers for CARB, and the public lists contradict each other for the same number: P1195/P1196 (Hyundai: electronic throttle limp-home valve, Kia: EGR boost sensor and ignition switch start), P1401/P1402 (Hyundai: fuel tank leak diagnostic module, Kia: EGR) and the idle air control codes P1505-P1508 (open/short circuit vs coil voltage low/high).
- Only one Hyundai list exists (obd-codes.com, myairbags.com and upfix.com are the same text); troublecodes.net words it differently and disagrees with it for several codes (for example P1523). There is no second independent Hyundai source.
- The Kia list is the Mazda lineage (see above), so it is not evidence for Hyundai.
- Where the Hyundai and Kia lists agree (P1307-P1309 rough road/G sensor, P1586 AT coding, P1611/P1614 MIL request circuit, P1665 ignition power stage A) the set is about eight codes, each from one list per make. Not worth a table; the make-aware fallback text stays.

## Ford (`make-ford.tsv`)

| Key | Source | Notes |
|---|---|---|
| EL | https://www.engine-light-help.com/ford-check-engine-light.html (413 P1xxx codes) | |
| OC | https://www.obd-codes.com/trouble_codes/ford/ | https://www.carcarehacks.com/ford-fault-codes-list-meaning-identify-and-fix-the-issues-easily/ is the same list (154 codes), counted once |
| TC | https://www.troublecodes.net/p1codes/ , entries labelled Ford/Lincoln/Mercury (496 codes) | has visible errors (A/C pressure sensor high/low swapped, P1158 lean instead of rich) |

All three reproduce Ford's OBD-II diagnostic code chart, so they share one origin; codes where they differ were dropped. Ford's own manuals are copyrighted and were not used.

Counts: 505 distinct P1xxx codes seen, 410 in at least two lists. 76 kept. Selection: engine management sensors and actuators, oxygen sensor switching, ignition coils, variable cam timing, EGR (DPFE) and secondary air monitors, EVAP, cooling fan control, idle air control, A/C clutch, power steering switch. Dropped because the three lists differ: P1127-P1129, P1135, P1158, P1230, P1383, P1405, P1407, P1411, P1442, P1451, P1455, P1461, P1462, P1464, P1484, P1500-P1502, P1512, P1513, P1519. Not taken on purpose: the fuel pump driver module family P1231-P1238 (US trucks and SUVs), intake manifold runner control variants with bank splits, all transmission codes (P17xx, P18xx), diesel and glow plug codes.

Uncertain: Ford of Europe petrol PCMs (Zetec, Duratec) share the EEC-V lineage of this list, but no EU-specific list was found to confirm each code; several rows (DPFE sensor, cylinder head temperature, vehicle load control module) mostly exist on US Ford models and will rarely appear on a European car. TDCi diesel ECUs (Delphi, Siemens, Bosch) use other meanings and are not covered.

## Subaru and Mitsubishi (not researched)

Not started: the only material seen is the aggregator file (93 and 33 lines) and the troublecodes.net entries, which gives no second independent source per code. Left for a later round.

## Per-code sources

Keys as defined in each make's section. A code is in a table only with at least two keys.

### Nissan / Infiniti

| Code | Description (ours) | Sources |
|---|---|---|
| P1031 | Air-fuel ratio sensor 1 heater, bank 1: control circuit low | NM, TC |
| P1032 | Air-fuel ratio sensor 1 heater, bank 1: control circuit high | NM, TC |
| P1051 | Air-fuel ratio sensor 1 heater, bank 2: control circuit low | NM, TC |
| P1052 | Air-fuel ratio sensor 1 heater, bank 2: control circuit high | NM, TC |
| P1065 | ECM power supply (back-up memory) circuit fault | NM, TC |
| P1105 | MAP/barometric pressure switching solenoid valve fault | OC, TC |
| P1111 | Intake valve timing control solenoid valve, bank 1: circuit fault | NM, TC, TA |
| P1120 | Throttle position sensor 2 (secondary) fault | TC, TA |
| P1121 | Electronic throttle control actuator fault | NM, TC, TA |
| P1122 | Electronic throttle control: performance problem | NM, TC |
| P1124 | Throttle control motor relay circuit: short | NM, TC |
| P1128 | Throttle control motor circuit: short | NM, TC, TA |
| P1130 | Swirl control valve solenoid fault | OC, TC, TA |
| P1136 | Intake valve timing control solenoid valve, bank 2: circuit fault | NM, TC, TA |
| P1143 | Front O2 sensor, bank 1: lean shift monitoring | NM, TC |
| P1144 | Front O2 sensor, bank 1: rich shift monitoring | NM, TC |
| P1146 | Rear O2 sensor (sensor 2), bank 1: minimum voltage monitoring | NM, TC, TA |
| P1147 | Rear O2 sensor (sensor 2), bank 1: maximum voltage monitoring | NM, TC, TA |
| P1148 | Closed-loop fuel control, bank 1: function fault | NM, ET, OC, TC, TA, AC |
| P1163 | Front O2 sensor, bank 2: lean shift monitoring | NM, TC |
| P1164 | Front O2 sensor, bank 2: rich shift monitoring | NM, TC |
| P1165 | Swirl control valve vacuum switch fault | OC, TC, TA |
| P1166 | Rear O2 sensor (sensor 2), bank 2: minimum voltage monitoring | NM, TC, TA |
| P1167 | Rear O2 sensor (sensor 2), bank 2: maximum voltage monitoring | NM, TC, TA |
| P1168 | Closed-loop fuel control, bank 2: function fault | NM, ET, OC, TC, TA, AC |
| P1211 | ABS/traction control unit: fault reported to the ECM | NM, ET, OC, TC |
| P1212 | ABS/traction control unit: communication line fault | NM, ET, OC, TC, TA |
| P1217 | Engine overheating (over temperature) (note: Check the coolant level and that the cooling fans run.) | NM, ET, OC, TC, AC |
| P1220 | Fuel pump control module (FPCM) fault | ET, TC, TA |
| P1223 | Throttle position sensor 2: circuit low input | TC, TA |
| P1224 | Throttle position sensor 2: circuit high input | TC, TA |
| P1225 | Closed throttle position learning: performance problem | NM, ET, TC, TA |
| P1226 | Closed throttle position learning: performance problem | NM, ET, TC, TA |
| P1227 | Accelerator pedal position sensor 2: circuit low input | TC, TA |
| P1228 | Accelerator pedal position sensor 2: circuit high input | TC, TA |
| P1229 | Sensor power supply circuit fault | NM, TC, TA |
| P1233 | Electronic throttle control, bank 2: performance problem | ET, TC |
| P1236 | Throttle control motor circuit, bank 2: short | ET, TC |
| P1238 | Electronic throttle control actuator, bank 2 fault | ET, TC |
| P1239 | Throttle position sensor, bank 2: range/performance problem | ET, TC |
| P1290 | Throttle control motor relay circuit, bank 2: open | ET, TC |
| P1320 | Ignition signal fault | OC, TC, TA |
| P1335 | Crankshaft position sensor (REF) fault | OC, TC, TA |
| P1336 | Crankshaft position sensor (CKPS) fault | OC, TC, TA |
| P1401 | EGR temperature sensor fault | OC, TC, TA |
| P1421 | Cold start emission reduction strategy: monitoring fault | NM, ET, TC, AC |
| P1440 | EVAP control system: small leak | OC, TC |
| P1444 | EVAP canister purge volume control solenoid valve fault | NM, OC, TC |
| P1445 | EVAP canister purge volume control valve fault | OC, TC |
| P1446 | EVAP canister vent control valve: stuck closed | NM, OC, TC |
| P1447 | EVAP control system: purge flow monitoring fault | OC, TC |
| P1448 | EVAP canister vent control valve: stuck open | OC, TC |
| P1464 | Fuel level sensor circuit fault | OC, TC |
| P1490 | Vacuum cut valve bypass valve: circuit fault | OC, TC |
| P1491 | Vacuum cut valve bypass valve fault | OC, TC |
| P1551 | Battery current sensor: circuit low | ET, TC |
| P1552 | Battery current sensor: circuit high | ET, TC |
| P1564 | Cruise control (ASCD) steering switch fault | NM, ET, TC, AC |
| P1572 | Cruise control (ASCD) brake switch fault | NM, ET, TC |
| P1574 | Cruise control (ASCD) vehicle speed sensor fault | NM, ET, TC, AC |
| P1611 | Immobilizer (NATS): ID mismatch between immobilizer unit and ECM | NM, ET, TC |
| P1706 | Park/neutral position switch fault | NM, OC, TC |
| P1715 | Transmission input (primary) speed sensor fault | NM, TC |
| P1777 | CVT step motor: circuit fault | NM, TC |
| P1778 | CVT step motor: function fault | NM, TC |
| P1805 | Brake (stop lamp) switch fault (note: Check the brake light switch and that the brake lights work.) | NM, ET, TC, AU |

### Mazda

| Code | Description (ours) | Sources |
|---|---|---|
| P1196 | Ignition switch start signal circuit fault | MA, MB, KI |
| P1250 | Fuel pressure regulator control solenoid valve: circuit fault | MA, MB, TC, KI |
| P1252 | Fuel pressure regulator control solenoid valve 2: circuit fault | MA, MB, TC, KI |
| P1260 | Theft detected: engine disabled by the immobilizer | MA, MB, TC |
| P1270 | Engine speed or vehicle speed limit reached | MA, MB, TC |
| P1345 | Camshaft position sensor (SGC): signal fault | MA, MB, KI |
| P1402 | EGR valve position sensor: circuit fault | MA, MB, TC, KI |
| P1455 | Fuel tank level sensor: circuit fault | MA, MB, TC, KI |
| P1487 | EGR boost sensor solenoid valve: circuit fault | MA, TC, KI |
| P1496 | EGR valve motor coil 1: open or short circuit | MA, MB, TC, KI |
| P1497 | EGR valve motor coil 2: open or short circuit | MA, MB, TC, KI |
| P1498 | EGR valve motor coil 3: open or short circuit | MA, MB, TC, KI |
| P1499 | EGR valve motor coil 4: open or short circuit | MA, MB, TC, KI |
| P1521 | Variable intake (VRIS) solenoid 1: circuit fault | MA, MB, TC |
| P1522 | Variable intake (VRIS) solenoid 2: circuit fault | MA, MB, TC |
| P1523 | Variable intake (VICS) solenoid: circuit fault | MA, MB, TC, KI |
| P1524 | Charge air cooler bypass solenoid valve: circuit fault | MA, MB, TC |
| P1562 | ECM back-up battery supply (+BB): voltage low | MA, MB, TC |
| P1569 | Variable intake (VTCS) control circuit: low input | MA, MB, TC |
| P1570 | Variable intake (VTCS) control circuit: high input | MA, MB, TC |
| P1631 | Generator: no output voltage signal | MA, MB, TC, KI |
| P1632 | Generator regulator: battery voltage monitor circuit fault | MA, MB, TC, KI |
| P1633 | Generator: battery overcharge | MA, MB, TC, KI |
| P1634 | Generator terminal B: open circuit | MA, MB, TC, KI |
| P1650 | Power steering pressure switch: out of range | MA, MB, TC |
| P1651 | Power steering pressure switch: circuit fault | MA, MB, TC |

### Ford

| Code | Description (ours) | Sources |
|---|---|---|
| P1000 | OBD readiness (monitor) test not completed | EL, OC |
| P1001 | Key-on engine-running (KOER) self-test could not be completed | EL, OC, TC |
| P1039 | Vehicle speed signal missing or implausible | EL, OC, TC |
| P1051 | Brake switch signal missing or implausible | EL, OC, TC |
| P1100 | Mass air flow sensor: intermittent signal | EL, OC |
| P1101 | Mass air flow sensor: out of self-test range | EL, OC, TC |
| P1112 | Intake air temperature sensor: intermittent signal | EL, OC, TC |
| P1116 | Engine coolant temperature sensor: out of self-test range | EL, OC, TC |
| P1117 | Engine coolant temperature sensor: intermittent signal | EL, OC, TC |
| P1120 | Throttle position sensor: out of range | EL, OC, TC |
| P1121 | Throttle position sensor: signal does not match the mass air flow | EL, OC, TC |
| P1124 | Throttle position sensor: out of self-test range | EL, OC, TC |
| P1125 | Throttle position sensor: intermittent signal | EL, OC, TC |
| P1130 | Upstream O2 sensor, bank 1: not switching, fuel adaptation at its limit | EL, OC, TC |
| P1131 | Upstream O2 sensor, bank 1: not switching, mixture indicated lean | EL, OC, TC |
| P1132 | Upstream O2 sensor, bank 1: not switching, mixture indicated rich | EL, OC, TC |
| P1137 | Downstream O2 sensor, bank 1: not switching, mixture indicated lean | EL, OC, TC |
| P1138 | Downstream O2 sensor, bank 1: not switching, mixture indicated rich | EL, OC, TC |
| P1150 | Upstream O2 sensor, bank 2: not switching, fuel adaptation at its limit | EL, OC, TC |
| P1151 | Upstream O2 sensor, bank 2: not switching, mixture indicated lean | EL, OC, TC |
| P1152 | Upstream O2 sensor, bank 2: not switching, mixture indicated rich | EL, OC, TC |
| P1157 | Downstream O2 sensor, bank 2: not switching, mixture indicated lean | EL, OC, TC |
| P1220 | Series throttle control fault | EL, OC, TC |
| P1224 | Throttle position sensor B: out of self-test range | EL, OC, TC |
| P1250 | Fuel pressure regulator control solenoid: no power | EL, OC, TC |
| P1260 | Theft detected: engine disabled by the anti-theft system | EL, OC, TC |
| P1270 | Engine speed or vehicle speed limiter reached | EL, OC, TC |
| P1288 | Cylinder head temperature sensor: out of self-test range | EL, OC, TC |
| P1289 | Cylinder head temperature sensor: signal above the self-test range | EL, OC, TC |
| P1290 | Cylinder head temperature sensor: signal below the self-test range | EL, OC, TC |
| P1299 | Cylinder head temperature: engine overheating detected | EL, OC, TC |
| P1309 | Misfire detection monitor not enabled | EL, OC, TC |
| P1351 | Ignition diagnostic monitor: circuit input fault | EL, OC, TC |
| P1352 | Ignition coil A: primary circuit fault | EL, OC, TC |
| P1353 | Ignition coil B: primary circuit fault | EL, OC, TC |
| P1354 | Ignition coil C: primary circuit fault | EL, OC, TC |
| P1355 | Ignition coil D: primary circuit fault | EL, OC, TC |
| P1356 | Ignition diagnostic module input to the PCM lost | EL, OC, TC |
| P1358 | Ignition diagnostic monitor: signal out of self-test range | EL, OC, TC |
| P1359 | Spark output circuit fault | EL, OC, TC |
| P1364 | Ignition coil: primary circuit fault | EL, OC, TC |
| P1380 | Variable cam timing solenoid valve: short or open circuit | EL, OC, TC |
| P1381 | Variable cam timing: advance excessive | EL, OC, TC |
| P1390 | Octane adjust: out of self-test range | EL, OC, TC |
| P1401 | DPFE (EGR pressure) sensor: circuit high | EL, OC, TC |
| P1406 | DPFE (EGR pressure) sensor: downstream hose fault | EL, OC, TC |
| P1408 | EGR flow: out of self-test range | EL, OC, TC |
| P1409 | EGR vacuum regulator: circuit fault | EL, OC, TC |
| P1413 | Secondary air injection monitor: circuit low voltage | EL, OC, TC |
| P1414 | Secondary air injection monitor: circuit high voltage | EL, OC, TC |
| P1443 | EVAP purge control solenoid or valve fault | EL, OC, TC |
| P1444 | EVAP purge flow sensor: circuit input low | EL, OC, TC |
| P1445 | EVAP purge flow sensor: circuit input high | EL, OC, TC |
| P1450 | EVAP system cannot bleed the fuel tank vacuum | EL, OC, TC |
| P1460 | Wide-open-throttle A/C cutoff circuit fault | EL, OC, TC |
| P1463 | A/C pressure sensor: insufficient pressure change | EL, OC, TC |
| P1469 | A/C cycling period too low | EL, OC, TC |
| P1473 | Cooling fan secondary circuit: high with fans off | EL, OC, TC |
| P1474 | Cooling fan control primary circuit: low | EL, OC, TC |
| P1479 | Cooling fan control primary circuit: high | EL, OC, TC |
| P1480 | Cooling fan secondary circuit: low with low-speed fans on | EL, OC, TC |
| P1481 | Cooling fan secondary circuit: low with high-speed fans on | EL, OC, TC |
| P1483 | Cooling fan power draw exceeded normal | EL, OC, TC |
| P1504 | Intake air control circuit fault | EL, OC, TC |
| P1505 | Idle air control system at its adaptive limit | EL, OC, TC |
| P1506 | Idle air control: engine speed too high | EL, OC, TC |
| P1507 | Idle air control: engine speed too low | EL, OC, TC |
| P1518 | Intake manifold runner control: stuck open | EL, OC, TC |
| P1520 | Intake manifold runner control: circuit fault | EL, OC, TC |
| P1530 | A/C compressor clutch circuit: open or short | EL, OC, TC |
| P1539 | A/C compressor clutch circuit: current draw exceeded normal | EL, OC, TC |
| P1605 | PCM keep-alive memory test error | EL, OC, TC |
| P1625 | Vehicle load control module fan circuit: voltage not detected | EL, OC, TC |
| P1626 | Vehicle load control module circuit: voltage not detected | EL, OC, TC |
| P1650 | Power steering pressure switch: out of self-test range | EL, OC, TC |
| P1651 | Power steering pressure switch: input fault | EL, OC, TC |
