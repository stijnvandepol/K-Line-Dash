# Car makes: manufacturer DTC tables, VIN to make, per-make connection quirks

Research date: 2026-10-23. Question: can K-Line Dash work well for many makes (German and Japanese first, plus the common European/Korean/US ones) by adding a "make" setting that selects a per-make table of manufacturer-specific trouble codes?

Legend (same as the other research docs): **[V]** verified in a cited source, **[R]** my reasoning or arithmetic from verified facts, **[U]** uncertain / anecdotal / single weak source.

Method and limits, read this first:
- Web research only. No car other than the 2ZZ-GE Corolla is available, so nothing here was tested on a vehicle.
- The authoritative documents (SAE J2012, ISO 15031-6, OEM service manuals) are paywalled or proprietary. For SAE/ISO I relied on the primary regulations that quote them (CARB 13 CCR 1968.2, EU 2017/1151) and on secondary descriptions.
- There is **no authoritative, public, per-make DTC list** for any make in the groups asked. Everything below about meanings comes from aggregator sites, forums and one OEM-tool vendor wiki. Where that is the only source it is marked as such.
- Not legal advice. The licensing section is a risk assessment.

---

## 1. Which DTC ranges are manufacturer-controlled

### 1.1 The ranges

The second character of a DTC (0-3) says who controls the code. Wikipedia, FenderBender and a scan-tool vendor KB agree on this table; the SAE text itself is paywalled. [V secondary]

| Range | Controlled by | Source notes |
|---|---|---|
| P0xxx | SAE/ISO (generic) | all sources agree |
| **P1xxx** | **manufacturer** | all sources agree |
| P2xxx | SAE/ISO (generic) | all sources agree |
| **P30xx-P33xx** | **manufacturer** | vendor KB (fcarusa) and ricksfreeautorepairadvice; Wikipedia only says P3 is "jointly defined" |
| P34xx-P39xx | SAE/ISO (generic/reserved) | vendor KB; consistent with the "jointly defined" wording |
| B0xxx, C0xxx, U0xxx | SAE/ISO (generic) | all sources agree |
| **B1xxx, B2xxx, C1xxx, C2xxx, U1xxx, U2xxx** | **manufacturer** | Wikipedia, FenderBender, vendor KB agree |
| B3xxx, C3xxx, U3xxx | **reserved for future use** | Wikipedia and FenderBender say "reserved"; the fcarusa KB calls them "generic". Sources disagree, neither is the standard. Treat as "unassigned, meaning unknown". |

Sources: https://en.wikipedia.org/wiki/On-board_diagnostics ; https://www.fenderbender.com/running-a-shop/operations/article/33029725/the-evolution-of-onboard-diagnostics ; https://www.fcarusa.com/TechSupport/KB/explanation-obd2-dtc ; SAE J2012 (current revision J2012_202509) https://saemobilus.sae.org/standards/j2012_202509-diagnostic-trouble-code-definitions ; EU text that names ISO 15031-6 / SAE J2012 as the DTC standard: https://www.legislation.gov.uk/eur/2017/1151/annex/XI/appendix/1/division/1/data.xht

### 1.2 Regulatory facts that matter for us
- CARB 13 CCR 1968.2 (Attachment A of the 2002 rulemaking, vehicles MY2004+): manufacturers shall use SAE-defined codes (P0xxx, P2xxx) whenever possible; with approval they may use manufacturer-defined codes "(e.g., P1xxx)". **"Manufacturer-defined fault codes shall be used consistently (i.e., the same fault code may not be used to represent two different failure modes) across a manufacturer's entire product line."** [V] https://ww2.arb.ca.gov/sites/default/files/barcu/regact/obd02/attacha.pdf (section (f)(4.4.3))
  - Consequence: for **US/California-certified** vehicles a P1xxx means one thing per *manufacturer* (VW Group, Toyota Motor Corp incl. Lexus, Honda incl. Acura, Hyundai and Kia are separate manufacturers, BMW incl. Mini, Daimler, Ford...). That is the legal basis for a "make-wide" table. [R]
  - I found **no equivalent clause** in the EU OBD text (2017/1151 Annex XI Appendix 1, the successor of 692/2008; it only references ISO 15031-6 / SAE J2012). So for EU-market cars the consistency is a convention, not a rule. [V that the clause is absent in that appendix; U for older EU rules]
- The same regulation shows when protocols were allowed (US): ISO 9141-2 not on MY2007+, ISO 14230-4 not on MY2008+, ISO 15765-4 (CAN) allowed from MY2003 and required from MY2008. VIN via J1979 required on all MY2005+ vehicles. [V] same file, sections (f)(3.2)-(3.4), (f)(4.8)
- Mode 03 is the emissions-related DTC service, so B/C codes essentially never arrive through it; U codes (U0xxx for lost communication) do. [R] general J1979 knowledge, not verified in a source here.

### 1.3 The current implementation
`Dtc.isManufacturerSpecific` (obd-core/.../diag/DiagnosticReport.kt): P with digit `1`, or digit `3` followed by `0..3`; B/C/U with digit `1` or `2`. **That matches the table above exactly. It is right.** [V]
Two small observations, not bugs:
- P34xx-P39xx and B3/C3/U3 return `false` and therefore have no fallback text when `generic.tsv` lacks them. Fine. If wanted, B3/C3/U3 could say "reserved by the standard".
- P2xxx codes that SAE has not yet assigned still fall in the generic range and are shown as unknown, not as manufacturer-specific. Correct (a manufacturer should not use them).

---

## 2. Manufacturer-specific code meanings per make

### 2.1 What the evidence says, in numbers
I downloaded the aggregator database that K-Line Dash already cites for Toyota (https://github.com/Wal33D/dtc-database, MIT-licensed code, per-make text files in `data/source-data/`, provenance of the *data* not stated) and compared the P1xxx lines of 12 make files (VW 528, Ford 413, Mazda 233, BMW 250, Honda 94, Subaru 93, Kia 76, Nissan 59, Toyota 45, Mitsubishi 33, Mercedes 32, Suzuki 17). [V, my own analysis of that repo, 2026-10-23]
- 758 distinct P1xxx numbers; **553 of them occur in two or more makes** with different texts. Excluding the Ford/Mazda pair, only about **6 %** of cross-make pairs of descriptions are even similar (fuzzy match >= 0.6). Example, **P1135**: VW "O2 heater circuit bank 1+2 sensor 2", BMW "O2 heater circuit low voltage bank 1 sensor 1", Ford "pedal position sensor A intermittent", Toyota "A/F sensor heater response", Nissan "intake valve timing control circuit bank 2", Mazda "HO2S heater low input". P1300: VW "misfire, fuel level too low", Ford "boost calibration fault", Toyota "igniter circuit No. 1", Honda "random misfire", Mitsubishi "ignition timing adjustment circuit".
  - Conclusion: a P1xxx table must be keyed by make. Showing any one table to a different make is actively wrong. [V]
- **Ford and Mazda share a large lineage**: 137 shared codes, about half with similar text, and the Mazda file contains Ford-only wording ("SCP error", "DPFE sensor"). Mazda models from the Ford-alliance era probably share Ford's ECU code lists; later Mazda ECUs may not [R]. [V for the overlap]
- **The aggregator has visible mistakes**: the Honda and Infiniti files are byte-identical (so one of them is mislabelled; the content is Honda: BARO, VTEC, ELD), Acura differs from Honda in a few lines, the Audi file contains no P1xxx at all but generic P0xxx cause lists, the Mercedes file is a Bosch diesel pump-ECU list with component IDs (Y231, N142, K40) that belongs to one ECU generation, not to Mercedes as a whole. [V]
- The Toyota file (45 lines) matches the independent flash-code table used for the 2ZZ-GE research (procarmanuals) for the overlapping codes. [V, see `2zz-ge-obd.md` section 3]

### 2.2 Per make group
"Consistency" = how well one P1xxx number keeps one meaning across that make's models/years/engines. Grades: **A** good, **B** partial (varies by ECU family/era), **C** poor or no public list. All grades are **[R]** unless a source is named; no make has a primary-source list I could read.

| Group | Native fault code scheme besides P-codes | P1xxx consistency | What a generic ELM327 sees / notes | Public lists (licence) | Recommendation |
|---|---|---|---|---|---|
| **VAG** (VW, Audi, Seat, Cupra, Skoda) | 5-digit VAG codes (e.g. 17544) and, with UDS-era cars, 6-char hex codes; read only with VCDS/ODIS. Mode 03 returns the **P-code** (17544 = P1136). | **A.** VAG uses one group-wide fault-text catalogue, so a code keeps one meaning across the group (Ross-Tech wiki lists one text per `VAG/P/hex` triple, e.g. "17544/P1136/004406 - Fuel Trim: Bank 1 (Add): System too Lean"). [V for that page, R for "group-wide"] | The VAG<->P mapping is **not arithmetic** (a forum pair gives P0300 = 16684 [U], but Ross-Tech's P1136 = 17544, and 17544 - 16384 = 1160, not 1136). Ross-Tech says the P-code is shown "only if it exists" and "there are thousands of VAG codes without generic OBD-II equivalents", so many VAG faults never show up in Mode 03. [V] | Ross-Tech wiki (content (c) Ross-Tech LLC, all rights reserved) https://wiki.ross-tech.com/wiki/index.php/17544/P1136/004406 ; Wal33D `volkswagen_codes.txt` (528 lines, e.g. P1128 "Long Term Fuel Trim Bank 1 System Too Lean", P1136 "...Add Fuel Bank 1 System Too Lean") | **Ship a curated table** (target 80-150 of the most common P1xxx), own wording, each code confirmed against Ross-Tech wiki plus one more source. Biggest value-for-effort of the German makes. |
| **BMW / Mini** | 4-hex-digit DME/DDE codes read by INPA/ISTA [U, low-quality sources] | **C/unknown.** Aggregator has 250 BMW lines, mostly 1990s-2000s Siemens/Bosch strings (P1083-P1094 fuel control, P1400 heated cat). No source says they hold for current N-/B-series. | Standard P0xxx/P2xxx work. A German workshop blog says P1xxx, adaptation values and sub-ECU data need ISTA/XENTRY/ODIS [U]. Sources: https://kfz-dietrich.com/blog/werkstattauswahl-obd2-vs-xentry-unterschied/ | None public. | **Ship nothing.** Show the make-aware fallback ("BMW-specific code; BMW does not publish these; read with ISTA/INPA or a BMW-aware tool"). Revisit if a real-car log appears. |
| **Mercedes-Benz / smart** | Xentry/DAS fault codes over UDS [U] | **C.** The aggregator file is a single diesel-pump ECU generation (see 2.1). | Generic data works (klavkarr's tests: ISO 9141 on A-Class W168 and many CDI, KWP fast on petrol, CAN from 2006). | None public. | **Ship nothing**, fallback text. |
| **Opel / Vauxhall** | GM-Europe/ Tech2 codes | **B/C.** Supplier-dependent (Bosch Motronic, Simtec/Siemens, Delphi); GM-US lists exist but GM-Europe differs [R]. | Often KWP fast on 2001-04 petrol (klavkarr). | Not found. | **Ship nothing** now. |
| **Ford (Europe)** | Ford IDS / FORScan module DTCs | **B.** Petrol PCM (EEC-V family) shares lineage with the large, stable Ford US list; EU TDCi/Duratorq ECUs (Delphi, Siemens, Bosch) differ [R]. | Focus I 1998-2004: J1850 PWM (klavkarr rows); Fiesta VI 2002+: CAN. | Public aggregator lists (Wal33D `ford_codes.txt` 413 lines, ricksfreeautorepairadvice); Ford workshop manuals are Ford copyright. | **Small set only** (codes both Ford sources agree on; skip diesel-specific). Phase 2. |
| **Renault / Dacia** | `DFxxx` codes in CLIP; P-codes only for emissions faults | **C.** I found no authoritative DF<->P mapping; forum text says many non-engine functions never go through OBD [U]. | KWP fast on almost all 2000-05 Clio II; KWP slow on a few (1.0, 1.9D, 2.0 RS). | None. | **Ship nothing.** |
| **PSA** (Peugeot, Citroen, DS) | PSA codes (Lexia/Diagbox) | **C.** Forum anecdote: nearly every PSA HDi shows P1351 [U], meaning not verified. | Peugeot 206 table mixes KWP slow and fast within the same model years (1999-2005). | None. | **Ship nothing.** |
| **Fiat / Alfa / Lancia** | Examiner / Multiecuscan style codes | **C.** Marelli, Bosch, Delphi ECUs. | Mix of KWP slow/fast, ISO 9141, and **29-bit CAN 500k from ~2006** (Grande Punto, Multijet). Multiecuscan notes the ELM K-line pin 7 only reaches engine/gearbox ECUs. | None. | **Ship nothing.** |
| **Volvo** | VIDA codes (ECM-xxx etc.) | **C.** | S40 I: ISO 9141; 2.0 D 2004-06: 29-bit CAN 500k (klavkarr). | None. | **Ship nothing.** |
| **Toyota / Lexus** | Toyota flash codes (shop manual only) | **A.** Toyota is the best-documented Japanese make: flash-code tables, multiple catalogues agree for the engine-related codes; caveats: A/F-sensor (P11xx) vs narrow-band cars, ETCS vs cable throttle, turbo (P14xx) are model dependent. | Already researched in `2zz-ge-obd.md`. | procarmanuals, toyotaspace, autotalk, Wal33D (45 lines) | **Ship make-wide table** (the existing file, re-labelled; the engine-specific "applies to 2ZZ-GE?" notes stay a separate layer). |
| **Honda / Acura** | Honda blink codes (MIL) | **A/B.** Single manufacturer for CARB purposes; the Honda lines in the aggregator are internally consistent (BARO P1106-1108, MAP P1128/1129, VTEC P1259, ELD P1298, EGR P1491, EVAP P1456/1457). One website flagged P1491 as "open circuit" while others say "EGR valve lift insufficient": sources disagree on some. | ISO 9141 on Civic VII (klavkarr). JDM cars: see 4.3. | Wal33D `honda_codes.txt` (94 lines; **file is a duplicate of the Infiniti file**), Rick's | **Ship a small set** (about 30-60 codes confirmed by two sources). |
| **Nissan / Infiniti** | Consult codes | **B.** P1148 "closed loop control (bank 1)" and P1217 "engine over temperature" confirmed by two independent lists (EcuTek GT-R/370Z lists, other sites). The aggregator Nissan file (59 lines) is small. | EU Micra K12: ISO 9141 petrol; Renault K9K ECU on 1.5 dCi (KWP fast): ECU maker beats badge. | EcuTek PDFs, Nissan EC manuals (user uploads on Scribd: licence unclear) | **Small set later**; do not ship the aggregator file as is. |
| **Mazda** | - | **B.** Ford-era vs own-ECU era differ [R]. | Mazda 6 GG 2003-2008 (EU): CAN 11/500 on **every** listed engine. | Wal33D (233 lines, partly Ford text) | **Ship nothing yet** (needs a separate source that is not Ford's). |
| **Mitsubishi** | MUT-III | **B/C.** Aggregator has 33 odd lines (traction control...). | ISO 9141 on Outlander I. | - | Ship nothing. |
| **Subaru** | SSM codes | **B.** Aggregator 93 lines, Subaru-specific (tumble generator valves, fuel tank pressure). | Impreza GD: ISO 9141 to ~2005, KWP fast 2005-07. | - | Ship nothing for now. |
| **Suzuki** | - | **C.** 17 lines. | Ignis I 1.5 VVT: KWP fast. | - | Ship nothing. |
| **Hyundai / Kia** | GDS codes | **B/C.** Separate manufacturers for CARB; Kia aggregator list is small (76 lines) and unsourced; EU CRDi models use Bosch-type ECUs [U]. | Sportage II: ISO 9141 2004-05, CAN 2007+. | - | Ship nothing for now. |

Sources for the table rows (beyond those named in the cells): klavkarr protocol tables below in section 4; https://www.motor.com/wp-content/uploads/2025/10/MOTOR_DiagnosticTroubleCodes.pdf (commercial MOTOR DTC data, licensed, not usable).

### 2.3 Licensing and copyright, short version
- **Code number -> short component/fault name is a fact**, and facts are not protected by US copyright (Feist). In the EU, a database can carry the *sui generis* right (Directive 96/9/EC art. 7) against extraction of a **substantial** part; investment in *creating* data does not qualify, only in obtaining/verifying/presenting it (CJEU Football Dataco, C-604/10 and C-173/11). Copyright in the *selection and arrangement* needs creativity. Sources: https://www.ippt.eu/sites/ippt/files/2012/IPPT20121018_ECJ_Football_Dataco_v_Sportradar.pdf ; https://fieldfisher.com/en/insights/court-of-justice-confirms-that-you-cant-avoid-sui-generis-database-rights-by-locating-a-server-outside-of-the-jurisdict [V for the legal doctrine, as summarised by law firms; not tested for DTCs]
- **SAE J2012 / ISO 15031-6 are copyrighted standards** ("reproduction not permitted" on resellers' pages). The *titles* in them are short descriptions, and the whole industry (every scan tool, python-OBD, the existing `generic.tsv`) reproduces them in some form. K-Line Dash already ships ~750 such titles "in the standard English wording style"; that is the larger legal risk in the repo and was not part of this task. Cheapest mitigation: where `generic.tsv` is a verbatim copy of SAE wording, keep rephrasing it over time. [R]
- **Manufacturer tools and wikis are copyrighted**: Ross-Tech wiki ("Copyright (c) 2000-2025 by Ross-Tech LLC. All rights reserved" [V]), OEM manuals, MOTOR data. Aggregator repositories like Wal33D have an MIT licence on the repo but say nothing about where the data came from; the licence does not clean the data. Obd-codes.com style sites are copyrighted compilations.
- **Practical rule for shipping tables in an MIT app** [R]:
  1. Each shipped code gets **our own short wording**, never a copy-paste of a site's sentence.
  2. Include a code only if **at least two independent sources** agree on the meaning (independent = not mirrors of each other; the aggregator counts as zero because its provenance is unknown). Record the sources in a `#` comment per file, like `toyota-2zz.tsv` does.
  3. Do not bulk-import any list; curate a *selection* (target 30-150 codes per make). A small hand-verified selection is the lowest-risk and also the highest-quality option.
  4. Do not ship anything derived from VCDS/ODIS/ISTA/Xentry data or manuals.

### 2.4 Recommendation per make (summary)
| Phase | Makes | Why |
|---|---|---|
| **Now** | Toyota/Lexus (exists), VAG, Honda/Acura | The three makes with the best "one P1xxx = one meaning" evidence and a verifiable second source. Covers the user's German and Japanese priority for the largest groups. |
| **Later, if verified** | Ford (EU petrol), Nissan/Infiniti, Mazda, Hyundai/Kia, Subaru, Mitsubishi, Suzuki | Plausible but meaning varies by ECU family or sources are thin. Ship only codes confirmed by two sources. |
| **Do not ship** | BMW/Mini, Mercedes, Opel, Renault/Dacia, PSA, Fiat/Alfa, Volvo | Native code systems outside Mode 03 and/or ECU-supplier-defined P1xxx with no make-wide list. Wrong meanings are worse than the fallback. |

For the "do not ship" makes the make setting still helps: replace the generic "Manufacturer-specific code. Its meaning depends on the make." with a make-aware sentence naming the right tool class (e.g. "BMW-specific code. Read the full fault memory with ISTA/INPA."). That needs no table. [R]

### 2.5 Suggested data shape (no code written)
`dtc/make-<family>.tsv` with the same `CODE<TAB>description<TAB>note` columns as `toyota-2zz.tsv`; `BundledDtcDescriptions` gets the make family as an input instead of the boolean `includeToyota2zz`. Engine-specific notes (2ZZ-GE) stay a separate optional layer. Merge rule unchanged (vehicle overrides generic; manufacturer-specific uncovered code -> make-aware fallback).

---

## 3. VIN to make

### 3.1 Sources
- **KBA (German Federal Motor Transport Authority) WMI list**: https://www.kba.de/SharedDocs/Downloads/EN/SV/sv32_pdf_en.pdf (SV 3.2, the primary source cited by Wikipedia for most European WMIs). [V that Wikipedia cites it]
- **Wikipedia WMI table** (compiled from the KBA list and others): https://en.wikipedia.org/wiki/Vehicle_identification_number#List_of_common_WMI . All codes in 3.2 below were taken from its raw wikitext and checked. [V]
- **ISO 3780** assigns WMIs; SAE is the registration authority outside Europe. https://www.iso.org/standard/45844.html (paywalled; Wikipedia summarises it).
- **NHTSA vPIC** API (public domain, free): `https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/<WMI>?format=json`. **Coverage gaps**: I queried about 70 WMIs on 2026-10-23. vPIC returned *nothing* for: VSS (Seat), TMB (Skoda), WDF, VXK, VS5, VS6, NM1, UU1, VF7, VR3, VR7, ZLA, XLB, NLA, SJN, VSK, JMZ, JMB, XMC, TSM, TMA, NLH. It also lumps brands (`JN1` -> "NISSAN, INFINITI, DATSUN"; `JF1` -> "TOYOTA, SUBARU"). Conclusion: **do not use vPIC as the table source**; use the KBA/Wikipedia list. vPIC is only useful as a cross-check for US-sold makes. [V]
- Rules from Wikipedia/ISO: VIN is 17 characters, **no I, O, Q**; builders under 1000 vehicles/year have `9` as third character and use positions 12-14 too; Opel/Vauxhall use German WMIs (W0L, W0V) whatever the plant; Stellantis French/Italian brands still carry French/Italian WMIs. [V] The existing regex `[A-HJ-NPR-Z0-9]{17}` in `VehicleInfoParser` matches this.
- European VINs do not have a mandatory check digit (only North America); do not reject on it. [V Wikipedia: check digit rules are North American]

### 3.2 WMI table (first 3 characters; verified against the Wikipedia/KBA list)
Match the 3-character WMI first, then fall back to the 2-character prefix where noted.

| Family | WMIs | Notes |
|---|---|---|
| VAG - VW | `WVW` car, `WVG` SUV, `WV1` `WV2` commercial, `AAV` (South Africa), `3VW` (Mexico), `1VW` (US), `9BW` (Brazil) | `WV` prefix works for all German VW |
| VAG - Audi | `WAU` car, `WUA` Audi Sport / quattro, `TRU` (Audi Hungary) | |
| VAG - Seat | `VSS` | not in vPIC |
| VAG - Skoda | `TMB`, `TMP` | not in vPIC; `TMA` is **Hyundai Czech**, not Skoda |
| Porsche, Bentley, Lamborghini | `WP0` `WP1`, `SCB`, `ZHW` | VAG group but own diag; map to "Other" |
| BMW | `WBA` car, `WBS` M, `WBX` SUV, `WBY` i, `WB5` i SUV, `WB6` M SUV | `WB1` etc. are BMW **Motorrad** (motorbikes) |
| Mini | `WMW` car, `WMZ` SUV | |
| Mercedes-Benz | `WDB`, `WDC` SUV, `WDD` car, `WDF` French/Spanish-built vans (Citan, Vito), `WMX` AMG, `VSC` (Vitoria Spain), `NMB` (Turkey) | `WD0`-`WD8` etc. are Sprinter/Freightliner vans; |
| smart | `WME` | |
| Opel / Vauxhall | `W0L` (Opel car), `W0V` (Opel since PSA era and Holden), `VXK` car/SUV, `VXE` van, `VSX` (Spain) | `W0S` special vehicles |
| Ford (Europe) | `WF0` (Ford of Europe), `VS6` (Spain), `NM0` (Ford Otosan, Turkey) | `WF1` = Merkur |
| Renault | `VF1`, `VS5` (Spain), `NM1` (Turkey), `KNM` (Renault Samsung) | `VF2`/`VF6` = Renault Trucks; Alpine `VFA` |
| Dacia | `UU1` | |
| Peugeot | `VF3`, `VR3` (newer), `VS8` (Spain) | |
| Citroen | `VF7`, `VR7`, `VS7` | |
| DS | `VR1` | |
| Fiat | `ZFA` car, `ZFB` MPV/SUV | |
| Alfa Romeo | `ZAR`, `ZAS` | |
| Lancia / Maserati / Jeep (EU) | `ZLA` / `ZAM` / `ZAC` | Jeep and Dodge Hornet |
| Volvo | `YV1` car, `YV4` SUV, `XLB` (NedCar) | `YV2`/`YV3` trucks and buses |
| Saab | `YS3` | |
| Toyota | `JT2` `JT3` `JT4` `JTD` `JTE` `JTF` `JTG` `JTK` `JTL` `JTM` `JTN`, `SB1` (UK), `VNK` (France), `NMT` (Turkey), `AHT` (S. Africa), `MR0` (Thailand), `TW1` (Portugal) | `JT` -> Toyota/Lexus family; Lexus: `JTH` `JTJ` `JT6` `JT8` |
| Honda | `JHM` car, `JHL` MPV/SUV, `SHH` `SHS` (UK), `NLA` (Turkey), `MRH` (Thailand) | Acura: `JH4` |
| Nissan | `JN1` car (also Infiniti), `JN6` truck, `JN8` SUV, `SJN` (UK), `VSK` (Spain), `VNV` | Infiniti: `JNK` `JNR` `JNX` `SJK` (UK) |
| Mazda | `JM1` `JM3` `JM6` `JM7`, `JMZ` (Europe export), `JM0` (Oceania) | |
| Mitsubishi | `JA3` `JA4` `JA7`, `JMA` `JMB` `JMF` `JMY`, `XMC` (NedCar) | `JAA`-`JAM` partly Isuzu |
| Subaru | `JF1` `JF2` `JF3` | `JF1` also on Toyota-built GT86/BRZ siblings (vPIC) |
| Suzuki | `JS2` `JS3`, `TSM` (Hungary), `MA3` (India) | `JS1` etc. are motorbikes |
| Hyundai | `KMH` car, `KM8` SUV, `TMA` `TMC` (Czech), `NLH` `NLJ` (Turkey), `MAL` (India) | Genesis `KMT` `KMU` |
| Kia | `KNA` `KNC` `KNE` car, `KND` SUV/MPV, `U5Y` `U6Y` (Slovakia) | |
| Chevrolet/Daewoo (Europe) | `KL1` `KL7` `KL8` | GM Korea |
| Jaguar / Land Rover | `SAJ` / `SAL` | |

Two-character fallback (only when the 3-character WMI is unknown): `JT` Toyota/Lexus, `JN` Nissan/Infiniti, `JH` Honda/Acura, `JF` Subaru, `JS` Suzuki, `JM` Mazda **except** `JMA` `JMB` `JMF` `JMY` Mitsubishi, `JA` Mitsubishi **except** `JAA` `JAB` `JAC` `JAE` `JAL` `JAM` Isuzu, `WV` VW, `WA` Audi, `WB` BMW (`WB1`/`WB3`/`WB4` are Motorrad motorcycles), `WD` Mercedes (but `WD0`-`WD8` etc. Sprinter/Freightliner vans); `WM` is mixed (`WME` smart, `WMX` AMG, `WMW`/`WMZ` Mini, `WMA` MAN), so no 2-char rule, `VF` Renault/Peugeot/Citroen mixed (no safe 2-char), `ZF` Fiat, `YV` Volvo, `KM` Hyundai (but `KM4`/`KM9` others), `KN` Kia (but `KNM` Renault Samsung, `KNJ` Ford Festiva). [R from the table above]

Caveat that matters for design: **the make follows the engine/ECU, not the badge.** Examples seen in the data: Nissan Micra 1.5 dCi runs a Renault K9K ECU, Mazda used Ford codes, Opel 1.3 CDTI = Fiat Multijet. A VIN-derived "Nissan" can therefore decode Renault codes. That is another reason to ship tables only where the meaning is well-defined and to let the user override. [R]

### 3.3 Do old / K-line cars answer Mode 09 PID 02?
- Regulation: **California requires VIN via J1979 for all MY2005+ vehicles** [V CARB 1968.2 (f)(4.8)]; the ISO 9141-2 / ISO 14230 limits listed in 1.2 mean a US-market K-line car of MY2005-2006/07 should report it, and CAN (MY2008+) always. For EU type approval I could not retrieve a VIN requirement from the EU regulation text (EUR-Lex blocked the fetch; the 2017/1151 appendix text I read does not mention it) [U].
- J1979 itself only says "Mode 09 is optional, check PID 0900 first"; Wikipedia's PID page names PID 02 but does not state a mandatory model year. [V]
- Reports: a single ISO 9141-2 motorcycle forum case returns NO DATA for 0902; our own 2ZZ-GE research already says "frequently not supported on pre-CAN Toyotas" and Mode 09 was **not** verified on the real car. I found **no measured statistics** for how often the VIN is missing. [U]
- Expectation by era (my synthesis, [R]):
  | Car | VIN via 0902 |
  |---|---|
  | CAN cars 2008+ (US-sold) and most EU cars 2008+ | very likely |
  | CAN cars 2003-2007 (Mazda 6, Ford Fiesta, Fiat 2006+ per klavkarr tables) | likely but not guaranteed |
  | K-line, MY2005-2007 | possible; ECU may answer NO DATA or a null/garbage VIN |
  | K-line before 2005 (incl. all EU 2001-2004 EOBD, like the Corolla E12) | unlikely |
  | JDM imports | VIN is a short chassis number, not a 17-character VIN (Wikipedia/NEVDIS: "Japanese VIN PV35-400637"), so never |
- Quality problems seen: clone ELM327s return **fragments of the VIN** (a Reddit report, multi-frame handling), ECUs can store a blank/all-zero VIN. The app's `VIN_FORMAT` check already rejects both. [V that the regex exists; U on the reports]

### 3.4 What auto-detection should do
1. Read the VIN once after connecting (already done). Validate with the existing regex.
2. 3-char WMI table -> 2-char fallback -> family. Store family **with the vehicle (adapter + VIN)**, not only the adapter.
3. VIN missing, short, or unknown WMI: **do not guess.** Keep the saved/user choice; if none, behave as "Generic": show the make-aware fallback only when a family is chosen, otherwise today's text. Offer a one-time prompt after the first DTC read ("Which make is this car?") rather than in the connection flow.
4. If a stored VIN differs from the new one, drop the auto-detected family (the user override survives only if the VIN is missing).
5. Other signals are too weak to infer a make (protocol J1850 PWM suggests Ford, VPW suggests GM, 29-bit CAN suggests Fiat/Volvo/some Asian cars; none is reliable). Do not use them for decoding; they are fine for ordering the connection fallback (section 4). [R]

---

## 4. Connection quirks per make

### 4.1 Protocols actually reported, per model (owner-tested, vendor database)
Source: klavkarr "compatible vehicles" pages, each row = engine / protocol label / model year (the label comes from the app that was run with an ELM-type interface; the vendor is a seller of OBD tools, so it is a vendor database [U] but it is the only per-engine public dataset I found, and it agrees with pinouts.ru and Ross-Tech where they overlap). The label "ISO 9141" vs "KWP SLOW" differs only by key bytes; both use 5-baud init. "KWP FAST" = ELM protocol 5.

| Model (years) | What is reported |
|---|---|
| VW Golf IV (1997-2002 page) | ISO 9141 on every engine 1999-2003; KWP slow from 2004-06 (2.0 FSI 2004, TDI 2005-06) https://www.klavkarr.com/compatible/volkswagen-golfiv-60000133 |
| Skoda Octavia I | diesel: ISO 9141 2000-03, KWP slow 2002-09, KWP fast 2006; petrol 1.8T ISO 9141 https://www.klavkarr.com/compatible/skoda-octaviai-1u-54000108 |
| BMW 3 E46 | ISO 9141 on 15 of 22 rows (320i, 323i, 325i, 330i, 320d/330d 2000-06), KWP fast on 316i/318i 2002-05 and 320d 2005-07 https://www.klavkarr.com/compatible/bmw-3-series-e46-4000109 |
| Mercedes A168 (2000-04) | ISO 9141, every row https://www.klavkarr.com/compatible/mercedes-aclass-168-37000100 |
| Mercedes C203 (2000-08) | mixed ISO 9141 (most diesels) and KWP fast (petrol and later diesels); **CAN 11/500 from 2006** https://www.klavkarr.com/compatible/mercedes-cclass-203-37000115 |
| Opel Astra G | petrol KWP fast 2001-04; diesel ISO 9141 (1999-2002) / KWP fast (2002-04); CAN only on 1.7 CDTI 2004 https://www.klavkarr.com/compatible/opel-astrag-43000108 |
| Ford Focus I | **J1850 PWM** on almost all 1998-2004 (a few 2002 1.6 and 2004 TDCi on CAN) https://www.klavkarr.com/compatible/ford-focusi-18000140 ; Fiesta VI 2002-08: **CAN 11/500 on all** https://www.klavkarr.com/compatible/ford-fiestavi-18000133 |
| Renault Clio II (2001-03) | KWP fast almost everywhere; KWP slow on 1.0, 1.9D, 2.0 RS; CAN 11/500 on a 2005 1.4 https://www.klavkarr.com/compatible/renault-clioii-49000107 |
| Peugeot 206 | **KWP slow and KWP fast mixed in the same years** (1.1/1.4/1.9D/2.0 16V slow, 1.4 HDi/1.6/2.0 HDi fast), one ISO 9141 petrol, CAN 2008 HDi https://www.klavkarr.com/compatible/peugeot-206-45000106 |
| Fiat Punto II | KWP slow, KWP fast, ISO 9141 (1.9 JTD), and **CAN 29-bit 500k** from 2006 on Multijet/1.4 https://www.klavkarr.com/compatible/fiat-puntoii-188-17000152 |
| Volvo S40 I | ISO 9141 (1.9 D 2001, 2.0T 1998-2001); **CAN 29-bit 500k** on 2.0 D 2004-06 https://www.klavkarr.com/compatible/volvo-s40i-61000106 |
| Toyota Avensis T25 | ISO 9141 on petrol and 2.0 D-4D 2003-05; KWP slow on D-4D 2006-09; KWP fast on 2.2 D-4D https://www.klavkarr.com/compatible/toyota-avensisii-59000112 |
| Honda Civic VII | ISO 9141 on every row https://www.klavkarr.com/compatible/honda-civicvii-21000116 |
| Nissan Micra K12 | ISO 9141 petrol; KWP fast on 1.5 dCi https://www.klavkarr.com/compatible/nissan-micraiii-k12-42000148 |
| Mazda 6 GG | **CAN 11/500 on every engine, 2003-08** https://www.klavkarr.com/compatible/mazda-mazda6i-gg/gy-36000154 |
| Mitsubishi Outlander I | ISO 9141 https://www.klavkarr.com/compatible/mitsubishi-outlanderi-39000149 |
| Subaru Impreza GD | ISO 9141 to ~2005, KWP fast 2005-07 https://www.klavkarr.com/compatible/subaru-imprezaii-gd/gg-57000113 |
| Suzuki Ignis I | KWP fast https://www.klavkarr.com/compatible/suzuki-ignisi-mh-58000125 |
| Kia Sportage II | ISO 9141 2004-05, CAN 11/500 2007+ https://www.klavkarr.com/compatible/kia-sportageii-28000189 |

Consistent with pinouts.ru (VW/Audi: ISO 9141 1996-2001, ISO 9141 or KWP 2002-04, any of the three 2005-07, CAN from 2008) and Ross-Tech's note that generic OBD-II on VAG cars is limited to ISO 9141-2, ISO 14230 and ISO 15765, **no J1850**. https://pinouts.ru/CarElectronics/audi_obd_ii_interface.shtml ; https://pinouts.ru/CarElectronics/volkswagen_obd2.shtml ; http://www.ross-tech.com/vag-com/tour/obd-2.html [V]

Takeaway: the *same model and year* can need a different protocol depending on engine (Peugeot 206, Octavia, Opel Astra, Fiat Punto). A make setting cannot select one protocol; it can at most **order** the fallback.

### 4.2 What the app does today (Elm327Session.kt)
- Setup `ATE0 ATL0 ATS0 ATH0 ATAT1`; then (optional saved protocol) -> `ATSP0` + `0100` (25 s) -> fixed K-line ladder `ATSP3`, `ATSP5`, `ATSP4`, then the Toyota profile (`ATIB96 ATIIA13 ATSH8113F1 ATSW00 ATSPA4`), 10 s per rung, 1.5 s pause between rungs.
- No explicit rung for **CAN (ATSP6-9) or J1850 (ATSP1/2)**. If `ATSP0` fails on a CAN or J1850 car (clone auto-search is flaky, see `elm327-clones.md`), the user gets "ECU not responding".
  **Update:** items 1 and 2 of 4.3 are implemented (`ProtocolLadder.kt`): the ladder now also has CAN (`ATSP6`-`9`) and J1850 (`ATSP1`/`2`) rungs, ordered per make by `fallbackOrder(make)`; K-line rungs keep 10 s + 1.5 s pause, CAN/J1850 rungs get 6 s + 0.3 s. Unknown make: 3, 5, 4, Toyota profile, 6, 7, 8, 9, 1, 2.
- No `ATST` tuning. `ATAT2` only on CAN and genuine/STN chips. `isCanProtocol` is `'6'..'9'`, correct for ELM numbering (6 = 11/500, 7 = 29/500, 8 = 11/250, 9 = 29/250; A is J1939). [V]

### 4.3 Concrete changes this research supports (only things that change behaviour)
Ordered by value for effort.

1. **Add CAN and J1850 rungs to the fallback ladder.** Evidence: Mazda 6 (all CAN since 2003), Ford Fiesta VI (CAN since 2002), Fiat/Volvo (29-bit 500k, ELM `ATSP7`), Ford Focus I (PWM `ATSP1`), Kia Sportage II CAN from 2007. Cost: each failed rung is up to 10 s + 1.5 s. Mitigation: skip rungs the make cannot be (see 2). `ATSP6`/`ATSP7` fail fast (no CAN activity: `UNABLE TO CONNECT` in well under a second), the slow rungs are the K-line ones. [R]
2. **Make-aware order of the ladder** (only matters when `ATSP0` and the saved protocol fail):
   | Make / era | Order |
   |---|---|
   | VAG, BMW, Mercedes, Volvo, Toyota, Honda, Nissan, Mazda, Mitsubishi, Subaru before ~2005 | 3, 4, 5, then 6/7 |
   | Renault, Peugeot, Citroen, Opel petrol, Suzuki, Fiat before ~2006 | 5, 4, 3, then 6/7 |
   | Ford Europe 1998-2004 | 1 (PWM), 3, 5, 6 |
   | Fiat/Alfa 2006+, Volvo 2004+ | 7 (29-bit/500k), 6, then K-line |
   | Anything 2008+ (any make) | 6, 7 first |
   Based on the table in 4.1. Era is unknown without a VIN, so default (make unknown) stays 3, 5, 4 plus the new CAN/J1850 rungs at the end. [R]
3. **Longer ATST on old VAG K-line.** Car Scanner's author: "Some cars like old Volkswagen, Audi, Skoda requires high ATST values like 96"; too low an ATST "will not allow the ECU to be connected at all". https://www.carscanner.info/en/optimizing-connection-speed [V that this is stated]. `ATST` is hex x 4 ms, so 96 = about 600 ms (default 32 = 200 ms) [R]; the page does not say hex. Proposal: when make = VAG and the protocol is K-line, send `ATST 96` after connecting, or escalate to it after the first `NO DATA` on `0100`. Needs a real-car test; the Corolla cannot validate it.
4. **Japanese K-line variants as extra Toyota-profile rungs.** Torque's Custom Init wiki lists four Japanese variants that differ from our single Toyota profile: https://wiki.torque-bhp.com/view/Custom_Init [V]
   | Car | Init |
   |---|---|
   | Celica ZZT230 (matches our profile) | `ATIB 96`, `ATIIA 13`, `ATSH8113F1`, `ATSP A4`, `ATSW00` |
   | Toyota Vitz 2002 (JDM) | `ATSH8213F1`, `ATIB96`, `ATIIA13` (header format byte **82**, no ATSP) |
   | JDM Nissan | `ATSP5`, `ATAL`, `ATIB10`, `ATSH8110FC`, `ATST32`, `ATSW00` (fast init, **10400 baud**, ECU address 0x10, tester FC) |
   | JDM Nadia / Harrier | `ATIB10`, `ATIIA13`, `ATSH8013F1`, `ATSPA4`, `ATSW00` (header **80**, **10400 baud**) |
   Our profile hard-codes `ATIB96` and `81`. Two cheap additions: a second Toyota rung with `ATIB10`, and header variants `80`/`82`. Only worth doing when the user marks the car as an import (JDM cars have no 17-character VIN, so detection cannot do it). [R]
5. **JDM / non-OBD2 cars.** J-OBD (Japan's own OBD from about 2002 for petrol, 2004 diesel) is described only by tool vendors, with conflicting claims about generic-scanner compatibility; Honda JDM and pre-2000 Japanese cars often have no J1962 K-line OBD2 at all. I found **no reliable source**. [U] Recommended behaviour change: none beyond item 4; instead make the failure message list "JDM / pre-2000 / non-OBD2 car" as a possible reason (text only).
6. **Pause before re-init.** Several K-line ECUs keep the previous session for the P3 timeout (about 5 s, "K-line session closes after ~5 s idle" in `2zz-ge-obd.md`). One forum case: `UNABLE TO CONNECT` on retry cleared after waiting about 5 s [U, anecdote: fiatforum ELM323 thread]. Today the pause between rungs is 1.5 s. Consider 5 s after a *failed bus init* only (not after a plain `NO DATA`). Low confidence; measure on the car first. [R]
7. **Fiat/Alfa K-line pin.** The common ELM327 board connects only pin 7 to its K-line transceiver; Fiat uses pin 7 for engine and automatic gearbox only (Multiecuscan/AlfaOBD docs). Not a software change; useful help text for Fiat. https://www.multiecuscan.net/MultiecuscanUserGuide.pdf [V, secondary quote]
8. **Not supported by evidence (no change proposed):** "gateway blocks Mode 01 with ignition only". I found no source for any make; the regulation requires emission-related Mode 01 data to be available, and VW Secure Gateway-style blocks affect OEM tools, not generic OBD [U, reasoning]. Header-based tweaks (`ATH1`) and `ATCRA` filters were also not backed by any make-specific report and stay out.

---

## 5. Recommendation summary

**Make list to offer** (setting values): Auto (default) | Generic / other | VW Group (VW, Audi, Seat, Cupra, Skoda) | BMW / Mini | Mercedes-Benz / smart | Opel / Vauxhall | Ford | Renault / Dacia | Peugeot / Citroen / DS | Fiat / Alfa Romeo / Lancia | Volvo | Toyota / Lexus | Honda / Acura | Nissan / Infiniti | Mazda | Mitsubishi | Subaru | Suzuki | Hyundai / Kia / Genesis. Porsche, Bentley, Lamborghini, Jaguar/Land Rover, Chevrolet, Chrysler/Jeep map to "Generic / other".

**Code tables**
- Now: Toyota/Lexus (existing), VAG (curated 80-150), Honda/Acura (curated 30-60). Own wording, two independent sources per code, sources listed in the file header.
- Later after verification: Ford, Nissan, Mazda, Hyundai/Kia, Subaru, Mitsubishi, Suzuki.
- Never from aggregators alone: BMW, Mercedes, Opel, Renault, PSA, Fiat/Alfa, Volvo; keep the fallback but make it make-aware.

**When the VIN is missing**: do not guess; use the stored or user-chosen make, otherwise "Generic" with today's fallback text; ask once, after the first DTC read.

**Risks**
- A wrong table is worse than none. Mitigation: only curated codes, wording "Usually ...", "may differ per engine" for tier B.
- Badge vs ECU (Micra dCi = Renault ECU, Mazda = Ford lineage): the make is a hint, the user can override.
- Copyright: no bulk imports, no OEM-tool data; keep per-code source notes. Existing `generic.tsv` is the larger open question. Not legal advice.
- Protocol ladder growth makes failed connections slower; bound it per make family and show progress ("Trying CAN").
- None of the proposed connection changes (ATST 96, extra Toyota variants, 5 s pause) could be tested here; they should be added behind the existing hardware test checklist (`2zz-ge-obd.md` section 5) and verified on a real car for each family, or labelled experimental.

---

## 6. Sources
- DTC ranges: https://en.wikipedia.org/wiki/On-board_diagnostics ; https://www.fenderbender.com/running-a-shop/operations/article/33029725/the-evolution-of-onboard-diagnostics ; https://www.fcarusa.com/TechSupport/KB/explanation-obd2-dtc ; https://saemobilus.sae.org/standards/j2012_202509-diagnostic-trouble-code-definitions ; https://ww2.arb.ca.gov/sites/default/files/barcu/regact/obd02/attacha.pdf ; https://www.legislation.gov.uk/eur/2017/1151/annex/XI/appendix/1/division/1/data.xht
- VAG codes: https://www.ross-tech.com/vag-com/tour-old/dtc_screen.html ; https://wiki.ross-tech.com/wiki/index.php/17544/P1136/004406 ; http://www.ross-tech.com/vag-com/tour/obd-2.html
- Aggregator data analysed: https://github.com/Wal33D/dtc-database (data/source-data/*.txt) ; https://github.com/mytrile/obd-trouble-codes
- BMW/Mercedes tooling: https://kfz-dietrich.com/blog/werkstattauswahl-obd2-vs-xentry-unterschied/ ; https://automotivetechinfo.com/wp-content/uploads/2019/01/XENTRY-and-OBD-II-Go-Deep.pdf
- Nissan checks: https://ecutek.atlassian.net/wiki/spaces/SUPPORT/pages/7078030/Nissan+370Z+DTC+List ; https://nextcarreview.com/dtc-p1148/
- Honda check: https://ricksfreeautorepairadvice.com/honda-obd2-codes/ ; https://www.go-parts.com/garage/obd-p1259
- Licensing: https://www.ippt.eu/sites/ippt/files/2012/IPPT20121018_ECJ_Football_Dataco_v_Sportradar.pdf ; https://fieldfisher.com/en/insights/court-of-justice-confirms-that-you-cant-avoid-sui-generis-database-rights-by-locating-a-server-outside-of-the-jurisdict
- VIN/WMI: https://en.wikipedia.org/wiki/Vehicle_identification_number ; https://www.kba.de/SharedDocs/Downloads/EN/SV/sv32_pdf_en.pdf ; https://www.iso.org/standard/45844.html ; https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/WVW?format=json ; https://en.wikipedia.org/wiki/OBD-II_PIDs ; Australian non-ISO VIN note via Wikipedia (infrastructure.gov.au/vehicles/imports/vins.aspx)
- Protocols per make: klavkarr pages in section 4.1 ; https://www.klavkarr.com/obd-presentation.php ; https://pinouts.ru/CarElectronics/audi_obd_ii_interface.shtml ; https://pinouts.ru/CarElectronics/volkswagen_obd2.shtml ; https://www.multiecuscan.net/MultiecuscanUserGuide.pdf ; https://www.fiatforum.com/threads/obd2-elm323-connection-problems.115050/page-2
- Connection tuning: https://wiki.torque-bhp.com/view/Custom_Init ; https://www.carscanner.info/en/optimizing-connection-speed ; project docs `2zz-ge-obd.md`, `elm327-clones.md`, `popular-obd-adapters.md`
