# Sources for the VW Group and Honda/Acura trouble code tables

Research date: 2026-10-23. Tables: `obd-core/src/main/resources/dtc/make-vag.tsv` and `make-honda.tsv`. Rules come from `car-makes.md` section 2.3: own wording, at least two independent publications that agree, no bulk import, no OEM-tool data. All text in the tables is our own wording; the sources were read for the meaning only. No car was available, so nothing here was tested on a vehicle.

## Method

1. Candidates: for VAG every P1xxx page of the Ross-Tech wiki fault-code category (214 pages, fetched 2026-10-23); for Honda every P1xxx code that at least two of the Honda lists below carry.
2. Each candidate was compared across all sources. Meaning was compared by component, bank/cylinder number and fault type (open circuit, short to ground, short to positive, too lean/rich, implausible, limit reached), not by wording.
3. Kept: at least two independent sources agree and no source gives a different component, bank, cylinder or fault type. Left out: any code where a source disagreed, or where only one source gave the detail.
4. The Wal33D aggregator (`dtc-database`) and mirrors of the same list count as zero: it was only used to see where it diverged. Wording was written by hand.

## Sources used

### VW Group

| Key | Source | Notes |
|---|---|---|
| RT | Ross-Tech wiki, https://wiki.ross-tech.com/wiki/index.php/Category:Fault_Codes (one page per `VAG number/P-code/hex`) | Content copyright Ross-Tech; read for meaning only. Three pages (P1337, P1338, P1538) have no fault text in their title, so RT is not counted for them. |
| VH | https://www.vag-hub.com/vw-audi-seat-skoda-dtc/ (list of VAG number, hex and P-code) | machine-translated; the pages https://www.vag-coding.net/tutorials-information/fault-codes/ and https://errors-codes.jimdofree.com/vag/ carry the same list and count once |
| TC | https://www.troublecodes.net/p1codes/ (entries labelled Audi/Volkswagen) | multi-make list; only the VW/Audi-labelled lines were read |
| CW | https://carwiki.de/obd-code-pNNNN-vw-audi | German, per-code pages; some pages have visible translation artefacts, so it is used to confirm, not as the only source |

Read and not used as a source:

- Bentley Publishers VW DTC table (https://www.vaglinks.com/Docs/VW/BentleyPublishers.com_VW_DTC_Table.pdf and its mirror passat.pavsweb.com): its rows are shifted against every other list for most P1xxx codes (for example P1213 is cylinder 2 there, cylinder 1 everywhere else), so it cannot confirm anything. It is also the origin of the "Acura" lists mentioned below.
- Wal33D `volkswagen_codes.txt`: counted zero (unknown provenance).

### Honda / Acura

| Key | Source | Notes |
|---|---|---|
| FF | https://www.ffchips.com/obd2-p-codes/ and https://icarsoft.com/knowledge/article/specific-trouble-codes-for-honda | share their wording (including the same typos), so they count as one source |
| UP | https://blog.upfix.com/honda-acura-dtc-code-definitions | |
| EL | https://www.engine-light-help.com/acura-check-engine-light.html | Acura/Honda list |
| TC | https://www.troublecodes.net/p1codes/ (entries labelled Honda/Acura) | |

Read and discarded: the "Acura" lists of https://carvitas.com/frequently-asked-questions/fault-code-lists/acura-obd2-fault-code-list-dtc and https://icarsoft.com/knowledge/article/specific-trouble-codes-for-acura. They contradict every Honda list (for example P1128 as a bank 1 fuel trim fault), but their text is the Audi/VW table of the Bentley manual ("Long Term Fuel Trim mult.", "Fuel Inj.Air Contr.Valve") under an Acura title. Honda and Acura are one manufacturer for CARB, and the four Honda/Acura-labelled lists agree with each other.

## Counts

- VAG: 214 candidates (Ross-Tech P1xxx pages). 150 kept. 13 dropped because sources disagreed or named a different bank/component (P1018, P1031, P1093, P1131, P1177, P1248, P1613, P1666, P1667, P1668, P1669, P1699, P1999). 5 dropped for being too thin or part of a dropped family (P1670, P1671, P1740, P1753, P1923). 46 agreed but were left out on purpose to keep the table a selection (chosen by judgement of what is common on road cars).
- Honda: 188 P1xxx candidates in at least two lists (most of them automatic-transmission codes, which were not taken). 52 kept. 29 dropped because the lists named a different component or fault (P1109, P1133, P1134, P1149, P1153, P1154, P1157, P1158, P1164, P1165, P1168, P1169, P1192, P1201, P1202, P1203, P1204, P1205, P1206, P1220, P1336, P1337, P1387, P1415, P1416, P1509, P1523, P1635, P1656).

## Per-code sources

Keys as above. A code is in the table only with at least two keys.

### VW Group

| Code | Description (ours) | Sources |
|---|---|---|
| P1020 | Fuel pressure regulation: control range exceeded | RT, VH, TC, CW |
| P1025 | Fuel pressure regulating valve (N276): mechanical fault | RT, VH, TC, CW |
| P1035 | NOx sensor (G295): implausible signal | RT, VH, TC, CW |
| P1103 | O2 sensor heater, bank 1 sensor 1: power too low | RT, VH, TC, CW |
| P1111 | Lambda control, bank 1: mixture too lean | RT, VH, TC, CW |
| P1116 | O2 sensor heater, bank 1 sensor 1: open circuit | RT, VH, TC, CW |
| P1117 | O2 sensor heater, bank 1 sensor 2: short to ground | RT, VH, TC, CW |
| P1118 | O2 sensor heater, bank 1 sensor 2: open circuit | RT, VH, TC, CW |
| P1128 | Long-term fuel trim (under load), bank 1: too lean | RT, VH, TC, CW |
| P1130 | Long-term fuel trim (under load), bank 2: too lean | RT, VH, CW |
| P1136 | Long-term fuel trim (idle), bank 1: too lean | RT, VH, TC, CW |
| P1137 | Long-term fuel trim (idle), bank 1: too rich | RT, VH, TC, CW |
| P1139 | Long-term fuel trim (idle), bank 2: too rich | RT, VH, TC, CW |
| P1141 | Load calculation cross-check: implausible value | RT, VH, TC, CW |
| P1142 | Load calculation cross-check: below the lower limit | RT, VH, TC, CW |
| P1143 | Load calculation cross-check: above the upper limit | RT, VH, TC, CW |
| P1144 | Mass air flow sensor (G70): open circuit or short to ground | RT, VH, TC, CW |
| P1145 | Mass air flow sensor (G70): short to positive | RT, VH, TC, CW |
| P1151 | Long-term fuel trim (air), bank 1, range 1: too lean | RT, VH, TC, CW |
| P1152 | Long-term fuel trim (air), bank 1, range 2: too lean | RT, VH, TC, CW |
| P1155 | Intake manifold pressure sensor (G71): short to positive | RT, VH, TC, CW |
| P1158 | Intake manifold pressure sensor (G71): implausible signal | RT, VH, TC, CW |
| P1159 | Air mass sensors, bank 1 and 2: signals do not match | RT, VH, CW |
| P1161 | Intake manifold temperature sensor (G72): open circuit or short to positive | RT, VH, TC, CW |
| P1163 | Fuel temperature sensor (G81): open circuit or short to positive | RT, VH, TC, CW |
| P1171 | Throttle actuator angle sensor 2 (G188): implausible signal | RT, VH, TC, CW |
| P1176 | Lambda correction after catalyst, bank 1: control limit reached | RT, VH, TC, CW |
| P1200 | Turbo bypass valve (N249): mechanical fault | RT, VH, TC, CW |
| P1213 | Cylinder 1 injector (N30): short to positive | RT, VH, TC, CW |
| P1214 | Cylinder 2 injector (N31): short to positive | RT, VH, TC, CW |
| P1215 | Cylinder 3 injector (N32): short to positive | RT, VH, TC, CW |
| P1216 | Cylinder 4 injector (N33): short to positive | RT, VH, TC, CW |
| P1217 | Cylinder 5 injector (N83): short to positive | RT, VH, TC, CW |
| P1218 | Cylinder 6 injector (N84): short to positive | RT, VH, TC, CW |
| P1219 | Cylinder 7 injector (N85): short to positive | RT, VH, TC, CW |
| P1220 | Cylinder 8 injector (N86): short to positive | RT, VH, TC, CW |
| P1237 | Cylinder 1 injector (N30): open circuit | RT, VH, TC, CW |
| P1238 | Cylinder 2 injector (N31): open circuit | RT, VH, TC, CW |
| P1239 | Cylinder 3 injector (N32): open circuit | RT, VH, TC, CW |
| P1240 | Cylinder 4 injector (N33): open circuit | RT, VH, TC, CW |
| P1241 | Cylinder 5 injector (N83): open circuit | RT, VH, TC, CW |
| P1242 | Cylinder 6 injector (N84): open circuit | RT, VH, TC, CW |
| P1243 | Cylinder 7 injector (N85): open circuit | RT, VH, TC, CW |
| P1244 | Cylinder 8 injector (N86): open circuit | RT, VH, TC, CW |
| P1250 | Fuel level too low | RT, VH, TC, CW |
| P1255 | Coolant temperature sensor (G62): short to ground | RT, VH, TC, CW |
| P1256 | Coolant temperature sensor (G62): open circuit or short to positive | RT, VH, TC, CW |
| P1260 | Pump injector valve, cylinder 1 (N240): implausible signal | RT, VH, TC, CW |
| P1261 | Pump injector valve, cylinder 1 (N240): control range exceeded | RT, VH, TC, CW |
| P1263 | Pump injector valve, cylinder 2 (N241): implausible signal | RT, VH, TC, CW |
| P1264 | Pump injector valve, cylinder 2 (N241): control range exceeded | RT, VH, TC, CW |
| P1266 | Pump injector valve, cylinder 3 (N242): implausible signal | RT, VH, TC, CW |
| P1267 | Pump injector valve, cylinder 3 (N242): control range exceeded | RT, VH, TC, CW |
| P1269 | Pump injector valve, cylinder 4 (N243): implausible signal | RT, VH, TC, CW |
| P1270 | Pump injector valve, cylinder 4 (N243): control range exceeded | RT, VH, TC, CW |
| P1297 | Pressure drop between turbocharger and throttle valve | RT, VH, TC, CW |
| P1337 | Camshaft position sensor (G40), bank 1: short to ground | VH, TC, CW |
| P1338 | Camshaft position sensor (G40), bank 1: open circuit or short to positive | VH, TC, CW |
| P1340 | Camshaft sensor (G40) and crankshaft sensor (G28): signals do not correlate | RT, VH, TC, CW |
| P1347 | Camshaft sensor 2 (G163) and crankshaft sensor (G28): signals do not correlate | RT, VH, TC, CW |
| P1348 | Ignition coil output stage 1: open circuit | RT, VH, TC, CW |
| P1349 | Ignition coil output stage 2: open circuit | RT, VH, TC, CW |
| P1355 | Cylinder 1 ignition circuit: open circuit | RT, VH, TC, CW |
| P1358 | Cylinder 2 ignition circuit: open circuit | RT, VH, TC, CW |
| P1361 | Cylinder 3 ignition circuit: open circuit | RT, VH, TC, CW |
| P1364 | Cylinder 4 ignition circuit: open circuit | RT, VH, TC, CW |
| P1370 | Cylinder 6 ignition circuit: open circuit | RT, VH, TC, CW |
| P1388 | Throttle control monitoring: control unit fault | RT, VH, TC, CW |
| P1391 | Camshaft position sensor (G163), bank 2: short to ground | RT, VH, TC, CW |
| P1392 | Camshaft position sensor (G163), bank 2: open circuit or short to positive | RT, VH, TC, CW |
| P1403 | EGR system: control deviation | RT, VH, TC, CW |
| P1411 | Secondary air system, bank 2: insufficient flow | RT, VH, TC, CW |
| P1420 | Secondary air valve (N112): electrical fault | RT, VH, TC, CW |
| P1421 | Secondary air valve (N112): short to ground | RT, VH, TC, CW |
| P1422 | Secondary air valve (N112): short to positive | RT, VH, TC, CW |
| P1423 | Secondary air system, bank 1: insufficient flow | RT, VH, TC, CW |
| P1424 | Secondary air system, bank 1: leak detected | RT, VH, TC, CW |
| P1426 | Charcoal canister purge valve (N80): open circuit | RT, VH, TC, CW |
| P1432 | Secondary air valve (N112): open circuit | RT, VH, TC, CW |
| P1433 | Secondary air pump relay (J299): open circuit | RT, VH, TC, CW |
| P1434 | Secondary air pump relay (J299): short to positive | RT, VH, TC, CW |
| P1435 | Secondary air pump relay (J299): short to ground | RT, VH, TC, CW |
| P1436 | Secondary air pump relay (J299): electrical fault | RT, VH, TC, CW |
| P1443 | EGR potentiometer (G212): signal too low | RT, VH, TC, CW |
| P1455 | Exhaust gas temperature sensor 1 (G235): implausible signal | RT, VH, TC, CW |
| P1458 | Exhaust gas temperature sensor 2 (G236): short to ground | RT, VH, TC, CW |
| P1473 | EVAP leak detection pump: open circuit | RT, VH, TC, CW |
| P1475 | EVAP leak detection pump: malfunction or no signal | RT, VH, TC, CW |
| P1476 | EVAP leak detection pump: insufficient vacuum | RT, VH, TC, CW |
| P1503 | Alternator load signal (terminal DF): implausible | RT, VH, TC, CW |
| P1507 | Idle control: lower regulation limit reached | RT, VH, TC, CW |
| P1508 | Idle control: upper regulation limit reached | RT, VH, TC, CW |
| P1517 | ECU power supply relay (J271): electrical fault | RT, VH, TC, CW |
| P1519 | Camshaft adjustment, bank 1: malfunction | RT, VH, TC, CW |
| P1522 | Camshaft adjustment, bank 2: malfunction | RT, VH, TC, CW |
| P1523 | Crash signal from airbag control unit: implausible | RT, VH, TC, CW |
| P1524 | Fuel pump relay (J17): open circuit or short to ground | RT, VH, TC, CW |
| P1526 | Camshaft timing valve (N205), bank 1: short to positive | RT, VH, TC, CW |
| P1527 | Camshaft timing valve (N205), bank 1: short to ground | RT, VH, TC, CW |
| P1528 | Camshaft timing valve (N205), bank 1: open circuit | RT, VH, TC, CW |
| P1534 | Camshaft timing valve (N208), bank 2: short to positive | RT, VH, TC, CW |
| P1538 | Fuel shut-off valve (N109): open circuit or short to ground | VH, TC, CW |
| P1542 | Throttle actuator angle sensor 1 (G187): implausible signal | RT, VH, TC, CW |
| P1543 | Throttle actuator angle sensor 1 (G187): signal too low | RT, VH, TC, CW |
| P1545 | Throttle control: malfunction | RT, VH, TC, CW |
| P1546 | Boost pressure control valve (N75): short to positive | RT, VH, TC, CW |
| P1547 | Boost pressure control valve (N75): short to ground | RT, VH, TC, CW |
| P1548 | Boost pressure control valve (N75): open circuit | RT, VH, TC, CW |
| P1549 | Boost pressure control valve (N75): open circuit or short to ground | RT, VH, TC |
| P1550 | Boost pressure control: deviation from target | RT, VH, TC, CW |
| P1555 | Boost pressure: maximum exceeded | RT, VH, TC, CW |
| P1556 | Boost pressure control: below target (negative deviation) | RT, VH, TC, CW |
| P1557 | Boost pressure control: above target (positive deviation) | RT, VH, TC, CW |
| P1559 | Throttle valve unit (J338): basic setting fault | RT, VH, TC, CW |
| P1562 | Quantity adjuster (N146): upper limit reached | RT, VH, TC, CW |
| P1563 | Quantity adjuster (N146): lower limit reached | RT, VH, TC, CW |
| P1569 | Cruise control switch (E45): implausible signal | RT, VH, TC, CW |
| P1570 | Engine start blocked by the immobilizer | RT, VH, TC, CW |
| P1579 | Throttle valve unit (J338): adaptation not started | RT, VH, TC, CW |
| P1580 | Throttle actuator, bank 1: malfunction | RT, VH, TC, CW |
| P1581 | Throttle valve unit (J338): basic setting not performed | RT, VH, TC, CW |
| P1592 | Barometric and boost pressure sensors: signals do not match | RT, VH, TC, CW |
| P1601 | Terminal 30 supply relay (J317): implausible signal | RT, VH, TC, CW |
| P1602 | Terminal 30 supply voltage too low | RT, VH, TC, CW |
| P1612 | Engine control unit incorrectly coded | RT, VH, TC, CW |
| P1619 | Glow plug relay (J52): open circuit or short to ground | RT, VH, TC, CW |
| P1624 | Malfunction lamp (MIL) request signal active | RT, VH, TC, CW |
| P1625 | Powertrain bus: implausible message from transmission control unit | RT, VH, TC, CW |
| P1631 | Accelerator pedal sensor 1 (G79): signal too high | RT, VH, TC, CW |
| P1633 | Accelerator pedal sensor 2 (G185): signal too low | RT, VH, TC, CW |
| P1634 | Accelerator pedal sensor 2 (G185): signal too high | RT, VH, TC, CW |
| P1636 | Powertrain bus: no message from airbag control unit | RT, VH, TC, CW |
| P1637 | Powertrain bus: no message from central electrics control unit | RT, VH, TC, CW |
| P1639 | Accelerator pedal sensors 1 and 2 (G79/G185): implausible signal | RT, VH, TC, CW |
| P1640 | Engine control unit: internal fault (EEPROM) | RT, VH, TC, CW |
| P1649 | Powertrain bus: no message from ABS control unit | RT, VH, TC, CW |
| P1650 | Powertrain bus: no message from instrument cluster | RT, VH, TC, CW |
| P1660 | Radiator fan control 1: short to ground | RT, VH, TC, CW |
| P1676 | Throttle (EPC) warning lamp (K132): circuit fault | RT, VH, TC, CW |
| P1680 | Reset-resistant limp-home mode active | RT, VH, TC, CW |
| P1681 | Control unit programming not completed | RT, VH, TC, CW |
| P1696 | Powertrain bus: implausible message from steering column control unit | RT, VH, TC, CW |
| P1746 | Transmission solenoid power relay: electrical fault | RT, VH, CW |
| P1841 | Engine and transmission control units are incompatible | RT, VH |
| P1850 | Powertrain bus: no message from engine control unit | RT, VH |
| P1853 | Powertrain bus: implausible message from ABS control unit | RT, VH, TC, CW |
| P1857 | Load signal: error message from engine control unit | RT, VH, TC, CW |
| P1914 | Brake booster pressure sensor (G294): implausible signal | RT, VH, TC, CW |
| P1950 | Radiator fan (V7): stiff or blocked | RT, VH |
| P1951 | Radiator fan 2 (V177): stiff or blocked | RT, VH, TC, CW |

### Honda / Acura

| Code | Description (ours) | Sources |
|---|---|---|
| P1106 | Barometric pressure (BARO) sensor: range/performance problem | FF, UP, EL, TC |
| P1107 | Barometric pressure (BARO) sensor circuit: input low | FF, UP, EL, TC |
| P1108 | Barometric pressure (BARO) sensor circuit: input high | FF, UP, EL, TC |
| P1121 | Throttle position lower than expected | FF, UP, EL |
| P1122 | Throttle position higher than expected | FF, UP, EL, TC |
| P1128 | MAP sensor: pressure lower than expected | FF, UP, EL, TC |
| P1129 | MAP sensor: pressure higher than expected | FF, UP, EL, TC |
| P1162 | Primary O2 / A/F sensor: circuit malfunction | FF, UP, EL, TC |
| P1163 | Primary O2 / A/F sensor: slow response | FF, UP, EL, TC |
| P1166 | Primary O2 / A/F sensor heater: electrical fault | FF, UP, EL, TC |
| P1167 | Primary O2 / A/F sensor heater: system fault | FF, UP, EL, TC |
| P1241 | Throttle control motor circuit 1: malfunction | UP, EL, TC |
| P1242 | Throttle control motor circuit 2: malfunction | UP, EL, TC |
| P1246 | Accelerator pedal sensor 1: circuit malfunction | UP, EL, TC |
| P1247 | Accelerator pedal sensor 2: circuit malfunction | UP, EL, TC |
| P1248 | Accelerator pedal sensors 1 and 2: signals do not correlate | UP, EL, TC |
| P1253 | VTEC system malfunction | FF, UP, EL, TC |
| P1257 | VTEC system malfunction | FF, UP, TC |
| P1258 | VTEC system malfunction | FF, UP, TC |
| P1259 | VTEC system malfunction | FF, UP, EL, TC |
| P1279 | VTEC system malfunction, front bank | UP, EL, TC |
| P1297 | Electrical load detector (ELD) circuit: input low | FF, UP, EL, TC |
| P1298 | Electrical load detector (ELD) circuit: input high | FF, UP, EL, TC |
| P1300 | Random/multiple cylinder misfire | FF, UP, EL, TC |
| P1301 | Cylinder 1 misfire | UP, EL, TC |
| P1302 | Cylinder 2 misfire | UP, EL, TC |
| P1303 | Cylinder 3 misfire | UP, EL, TC |
| P1304 | Cylinder 4 misfire | UP, EL, TC |
| P1305 | Cylinder 5 misfire | UP, EL |
| P1306 | Cylinder 6 misfire | UP, EL, TC |
| P1316 | Spark plug voltage detection circuit fault, front bank | UP, EL, TC |
| P1317 | Spark plug voltage detection circuit fault, rear bank | UP, EL, TC |
| P1318 | Spark plug voltage detection module reset circuit fault, front bank | UP, EL, TC |
| P1319 | Spark plug voltage detection module reset circuit fault, rear bank | UP, EL, TC |
| P1324 | Knock sensor circuit: voltage low | UP, EL, TC |
| P1359 | CKP/TDC sensor circuit: connector disconnected | FF, UP, EL, TC |
| P1361 | TDC sensor 1: intermittent signal | FF, UP, EL, TC |
| P1362 | TDC sensor 1: no signal | FF, UP, EL, TC |
| P1366 | TDC sensor 2: intermittent signal | FF, UP, EL, TC |
| P1367 | TDC sensor 2: no signal | FF, UP, EL, TC |
| P1381 | Cylinder position (CYP) sensor: intermittent signal | FF, UP, EL, TC |
| P1382 | Cylinder position (CYP) sensor: no signal | FF, UP, EL, TC |
| P1456 | EVAP system leak detected (fuel tank side) | FF, UP, EL, TC |
| P1457 | EVAP system leak detected (canister side) | FF, UP, EL, TC |
| P1459 | EVAP purge flow switch malfunction | FF, UP, EL, TC |
| P1486 | Thermostat: range/performance problem | FF, UP, TC |
| P1491 | EGR valve lift insufficient | FF, UP, EL, TC |
| P1498 | EGR valve lift sensor: voltage high | FF, UP, EL, TC |
| P1508 | Idle air control (IAC) valve circuit failure | FF, UP, EL, TC |
| P1519 | Idle air control (IAC) valve circuit failure | FF, UP, EL, TC |
| P1607 | ECM/PCM internal circuit failure A | FF, UP, EL, TC |
| P1608 | ECM/PCM internal circuit failure B | UP, EL, TC |

## Caveats

- The VAG fault-text catalogue is group-wide, but the P-code a VW Group ECU reports can still depend on the engine control unit generation. Treat the descriptions as the usual meaning.
- Ross-Tech, the VAG list and carwiki.de all derive from the same VW Group catalogue, so "independent" means separate publications, not separate origins. That is why codes with any dissent were dropped.
- Honda A/F sensor codes (P1162-P1167) name the primary O2 sensor on some models and an A/F sensor on others; the wording covers both.
- The fuel-trim note ("check for intake or vacuum leaks, the MAF sensor and fuel pressure first") is the usual first check named by repair sources for lean fuel trim faults; it is not a diagnosis.
