# GPS, trips, laps and export for PC analysis

Research date: 2026-10-22. Context: K-Line Dash is an offline Android app (minSdk 26, targetSdk 34, no Google Play Services) for head units (Android 12/13) and phones. `ObdService` is a `connectedDevice` foreground service started by `BootReceiver`; `TripRecorder` writes a CSV per trip (1 Hz tick, `time_iso,elapsed_s,<id (unit)>...`, readings older than 30 s become empty cells) and a Room row (`Trip`: start/end, file name, distance, max rpm/speed/coolant). An HTTP+PIN export server already serves every file in the trips folder (single files or ZIP), so any new export file dropped in that folder is downloadable from a PC without extra work. Car: 2ZZ-GE over K-Line; PIDs 0C rpm, 0D speed, 05 ECT, 0F IAT, 10 MAF, 11 throttle, 04 load are expected (see `2zz-ge-obd.md`).

Legend: **[V]** verified in a cited source, **[U]** uncertain / anecdotal / general knowledge, **[R]** my reasoning.

## 0. Decisions in short
1. Location = plain `LocationManager.GPS_PROVIDER` + `GnssStatus`; no Play Services needed.
2. Do **not** request a `location` FGS type at boot. Start the existing `connectedDevice` FGS at boot as today and add the `location` type only when the app has a visible activity (or the user granted "Allow all the time").
3. Laps are derived data: store raw fixes in the trip CSV, detect gate crossings with segment intersection + linear interpolation of the crossing time, recompute after the fact from the CSV.
4. User-defined start/finish gates ("set here" while driving, or pick on a recorded trace). No usable open track database exists.
5. Export first: enriched CSV (already the base), VBO (Racelogic format; opens in free Circuit Tools, RaceChrono, RaceRender), GPX 1.1 (+ Garmin TPX v2) or KML `gx:Track` for maps.

---

## 1. Location without Google Play Services

### 1.1 API and rates
- `LocationManager.requestLocationUpdates(GPS_PROVIDER, ...)` is the AOSP path; `GPS_PROVIDER` needs `ACCESS_FINE_LOCATION`; `registerGnssStatusCallback` gives satellite counts / C/N0 (needs fine location). [U: general API knowledge; the Android reference pages fetched here did not return the method text]
- API 31+ prefers the `LocationRequest.Builder(intervalMs).setMinUpdateIntervalMillis(...)` overload; older `requestLocationUpdates(provider, minTimeMs, minDistM, listener)` is deprecated but works on API 26-33. `minTime` is a hint: the provider may deliver slower, and the chip decides the real rate. [U] Forum quotes of the Android docs say the interval is never less than minTime but can be more. [U, secondary only; not re-checked against the reference page]
- Fields per `Location`: lat/lon, `getTime()` (GPS UTC ms), `getElapsedRealtimeNanos()` (monotonic, use for ordering), `getSpeed()` m/s (+`getSpeedAccuracyMetersPerSecond()`), `getBearing()` (+`getBearingAccuracyDegrees()`), `getAccuracy()` (68 % horizontal radius, m), `getAltitude()`, vertical accuracy (API 26+). [U: from memory; the reference page text could not be read]
- Rate: nothing documents a hard 1 Hz cap in the OS. In practice phone and head-unit internal GNSS delivers 1 Hz by default; RaceChrono's own advice is that 1 Hz internal GPS is fine to start but 5-10 Hz external receivers are recommended for lap timing (RaceBox 25 Hz, Qstarz BL-1000GT 10 Hz, Dual XGPS160 10 Hz, Garmin GLO 7-8 Hz). [V] https://racechrono.com/?p=1492 and https://racechrono.com/forum/discussion/comment/8416/
- A forum user states "internal GPS ... 1 Hz, obviously" [V anecdotal] same RaceChrono forum. Whether a given head-unit chip can be driven to 5-10 Hz through the Android API is **unknown** [U]; you can only find out by logging the fix interval on the real unit (cheap test: request `minTime=100 ms`, print the histogram of `elapsedRealtimeNanos` deltas).

### 1.2 What Chinese head units have
- Found: Foryou-class units used u-blox 6 modules [V] (https://www.u-blox.com/zh/press-release/foryou-chooses-u-blox-gps-car-navigation-and-infotainment). u-blox 6 is a 1 Hz default receiver that can run up to 5 Hz [U, datasheet knowledge]. UIS7862/FYT internals: **not found** (no GPS part number, rate or cold-start figures in any source) [U].
- Typical complaints: slow first fix, antenna placed inside the dashboard, satellites vanishing while driving; advice is an outdoor test with a satellite app and a relocated antenna (antenna on dash / against A-pillar). [V anecdotal] https://android-headunits.com/intermittent-poor-gps-on-android-head-unit/ and the Team-BHP style guide surfaced by search.
- Some units route GPS to the vendor navigation app only or expose it through the vendor MCU; the standard `LocationManager` provider is usually still present [U]. Check with a "GPS Test" app on the target unit before building anything.
- Planning values [R]: cold start 30 s to several minutes; assume `hdop`/`accuracy` 3-10 m; reject fixes with `accuracy > 20 m` or no `hasSpeed()` for lap timing; show a GPS status chip (sats used, accuracy).
- Head-unit wall clocks are often wrong when offline (no NTP) [U], so use `Location.getTime()` as the trip time base once a fix exists.

### 1.3 Permissions (Android 12-14)
- Manifest today has `ACCESS_FINE_LOCATION` only with `maxSdkVersion=30` (BLE scan on old APIs). Needed now: `ACCESS_FINE_LOCATION` (unlimited maxSdk) **and** `ACCESS_COARSE_LOCATION` (Android 12 rule: fine must be requested together with coarse; user may grant "approximate" only, in which case the app only receives approximate location, which is useless for laps - detect and tell the user). [V] https://developer.android.com/develop/sensors-and-location/location/permissions
- `ACCESS_BACKGROUND_LOCATION` is **not** needed while a foreground service is running and the app could access location when it was started in the foreground; the docs define foreground location as "activity visible or running a foreground service". [V] same page. The background permission is only needed to *start* a `location` FGS from the background (1.4). On Android 11+ it cannot be granted from the runtime dialog; the user goes to the system settings page ("Allow all the time"). [V] https://developer.android.com/develop/sensors-and-location/location/permissions/background
- Head units may ship a stripped Settings app without that page [U]; do not make the design depend on it.

### 1.4 Foreground service type `location`: boot and background
- Types are declared in the manifest with `|` (e.g. `connectedDevice|location`) and passed as OR-ed constants in `ServiceCompat.startForeground(..., type)`; each type needs its own `FOREGROUND_SERVICE_*` permission (`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`). [V] https://developer.android.com/develop/background-work/services/fgs/service-types (the multi-type XML shown there is the page-author's "standard approach", not an official example)
- Prerequisites for `location`: location services on + fine/coarse permission. "You can't create a `location` foreground service while the app is in the background unless it has been granted `ACCESS_BACKGROUND_LOCATION`." [V] same page.
- For targetSdk 34+ the system checks while-in-use permissions at service creation; a background app throws `SecurityException`; `checkSelfPermission` still returns GRANTED so it does not protect you. Exemptions that count as "foreground" for while-in-use: system component, notification action, widget, visible app's PendingIntent, device owner, etc. **Boot broadcasts are not in that list.** [V] https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Boot itself: starting an FGS from `BOOT_COMPLETED` is generally exempt. Android 15 blocks `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` from BOOT_COMPLETED; `location` and `connectedDevice` are not on that list (but `location` hits the while-in-use rule above). [V] https://developer.android.com/about/versions/15/behavior-changes-15
- Not found documented: whether a `connectedDevice` FGS started at boot silently gets no location callbacks (Android 11+ background restriction on non-location FGS) [U]; and whether a running FGS can add the `location` type later by calling `startForeground()` again with the combined type (common practice, but only allowed while the app is in a state with while-in-use access) [U]. Both need an on-device test (adb: `am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>`, then reboot of the real unit).
- App already holds `SYSTEM_ALERT_WINDOW`; the FGS-restriction page lists it as an exemption for starting FGS from the background (Android 15+: only with a visible overlay window), but it is not in the while-in-use exemption list. [V for first, R for second]

**Recommended approach [R]:**
1. Keep `BootReceiver` -> `ObdService` as `connectedDevice` only (works today, no new boot risk). Declare in the manifest `foregroundServiceType="connectedDevice|location"` plus the `FOREGROUND_SERVICE_LOCATION` permission.
2. Create a `GpsSource` inside the service. It is *armed* when permission is granted; it starts collecting when (a) the activity becomes visible (call `startForeground(id, n, CONNECTED_DEVICE or LOCATION)` from the activity-triggered `startForegroundService`), or (b) the user granted background location, in which case it can be requested right at boot.
3. If GPS is not yet available, the trip still records (OBD-only rows, empty lat/lon cells), exactly as the CSV already tolerates missing values. When GPS comes up mid-trip the later rows have fixes; laps are only computed over the rows that have fixes.
4. On a head unit the dashboard activity is normally what the user sees after ignition (launcher autostart / "remember last app", see `headunit-autostart.md`), so (a) happens within seconds of boot in practice [R].
5. Test matrix to run once: boot with/without activity, wake from sleep, "Allow only while using the app" vs "all the time", and whether location callbacks arrive in the no-activity case.

Sources: Android docs links above; `headunit-autostart.md` section 3.

---

## 2. Lap timing from GPS

### 2.1 How the apps do it (what is documented)
- RaceChrono: tracks are start/finish ("trap") lines plus optional split lines; library of user-submitted tracks; default trap width 50 m ("usually good for a normal full-size circuit"), FAQ says 50-75 m; laps are missed if the driven line leaves the trap; editing a trap recalculates old sessions. Interpolation and minimum lap time are **not documented**. [V] https://racechrono.com/article/faq/can-i-make-the-start-finish-line-wider-so-all-of-my-laps-will-register ; https://www.oneshift.com/articles/news/racechrono-measures-your-lap-times
- RaceChrono lap-time error "typically 0.15-0.25 s" depending on GPS quality (old article). [V, dated] oneshift link above.
- RaceCapture (open firmware): start/finish and sectors are "geo-circles" (point + radius, default 11 m); a "geo-trigger" at 2x radius must be left before the point can fire again, which prevents double triggers; sectors are sequential counters; overlapping circles cause early finishes. [V] https://wiki.autosportlabs.com/RaceCapture_CustomTrackBestPractices
- Racelogic VBO files store lap gates as two coordinates + name in `[laptiming]`, i.e. line gates. [V] https://developers.racelogic.co.uk/Portal/Tutorials/tutorialVBOfilestructure.html
- Forum consensus: 1 Hz data can be interpolated for sub-second resolution; precision is not accuracy; 10 Hz is a good balance (vendor CEO). [V anecdotal] https://forum.autosportlabs.com/viewtopic.php?p=29006 and the "Lap Time Accuracy" threads.
- Harry's LapTimer, TrackAddict: no algorithm documentation found [U].

### 2.2 Algorithm to implement (line gate, segment intersection) [R, standard geometry]
Gate = two points A, B (or point + heading + width; derive A/B). Work in a local flat frame around the gate midpoint: `x = (lon-lon0)*cos(lat0)*111320`, `y = (lat-lat0)*110574` (error < 0.1 % over a few km).
For consecutive accepted fixes P1 (t1) and P2 (t2): with `r = P2-P1`, `s = B-A`, `den = cross(r,s)`:
- `t = cross(A-P1, s)/den` (position along the fix segment), `u = cross(A-P1, r)/den` (position along the gate).
- Crossing if `den != 0`, `0 <= t < 1`, `0 <= u <= 1`.
- Direction check: `dot(r, n) > 0` where `n` is the gate normal (driving direction at set-time); rejects opposite-direction crossings.
- Crossing time `tc = t1 + t*(t2-t1)` using GPS time (`Location.getTime()`; ms is enough). Lap time = `tc_n - tc_(n-1)`. Optional improvement at 1 Hz: Hermite interpolation using the Doppler speed and bearing of both fixes instead of straight-line (helps in corners).
- Gate half-width default 20-25 m on a car (RaceChrono uses 50 m total); allow editing.
- Debounce / hysteresis [R, with RaceCapture precedent]: ignore new crossings until (a) at least `max(10 s, 0.5*bestLap)` since the last one, and (b) the car has been > 2x half-width away from the gate (re-arm). Skip fixes with accuracy worse than ~15-20 m or gaps > 3 s (do not interpolate across a GPS dropout; mark the lap invalid).
- Sector splits: additional gates in order; a split counts only after the previous gate fired in the same lap (sequential counter, RaceCapture-style).
- Reference laps and delta: store the best lap as (distance-from-start, elapsed-time) pairs; for the live delta, integrate distance along the current lap, look up the reference elapsed time at the same distance (linear interpolation), `delta = tNow - tRef(d)`. Predictive lap = `bestLap + delta`. [R; concept widely used, e.g. discussed in https://forum.autosportlabs.com/viewtopic.php?p=29006]

### 2.3 Accuracy expectations [R, derived]
- 1 Hz at 30 m/s: fixes are 30 m apart; linear interpolation along a straight is good to roughly the position error / speed = 3-5 m / 30 m/s = 0.1-0.17 s per crossing; errors are partly systematic (same line each lap), so lap-to-lap *differences* are better than single times. Slow, tight tracks (karting, autocross) at 1 Hz are poor. Matches RaceChrono's 0.15-0.25 s claim. [V for the claim, R for the arithmetic]
- 10 Hz: crossing error dominated by position noise (~0.03-0.1 s). 5 Hz is a decent compromise if the chip supports it.

### 2.4 Track data
- RaceChrono's library: user-submitted, copy-before-edit, **licence not found** [V that none is stated in what I could read]. Do not scrape or bundle it.
- OpenStreetMap: `highway=raceway` ways exist, and `raceway=start|finish|start-finish` nodes mark the line position. [V] https://wiki.openstreetmap.org/Tag:highway=raceway . Data is ODbL (attribution + share-alike on derived databases) [U, general knowledge]; coverage of the start/finish node and the exact line position/direction is patchy, and a node is a point not a line [R]. Not suitable as the primary source; could be an optional "suggest nearby circuit" hint.
- TUMFTM `racetrack-database` (centerlines + widths for ~20 mostly F1/DTM tracks, listed as LGPL-3.0, last push 2021): no start/finish lines. [U, secondhand licence listing] https://github.com/TUMFTM/racetrack-database (not fetched)
- Recommendation: user-defined gates, stored per named "track" in Room. Create by (a) a "Set start/finish here" button while driving (gate through the current position, perpendicular to the current bearing, default width 40 m), (b) after the fact: show the recorded trace, tap a point, auto-orient the gate perpendicular to the local direction, recompute laps. Auto-recognise a saved track when the car passes within ~50 m of its gate with a matching heading (±45°).

---

## 3. Export formats for PC analysis

### 3.1 Enriched CSV (base; zero new tooling) 
Keep the current format (`time_iso,elapsed_s,<signals>`; decimals per signal, `.` separator, UTF-8, `\n`) and append columns: `lat (deg)`, `lon (deg)`, `gps_speed (km/h)`, `heading (deg)`, `gps_acc (m)`, `sats`, `lap`, `sector`. Rationale: Excel/pandas/Python/LibreOffice all open it; RaceRender's generic CSV needs a `Time` column in seconds, decimal-degree lat/lon, optional speed, RPM, throttle 0-100, heading, accuracy in m and a `Lap` column; it lists RaceChrono/MoTeC/Race Capture/Torque as CSV sources. [V] https://racerender.com/RR3/docs/HowTo-DataInput.html . Mapping to those names can be a second "RaceRender CSV" variant later (unverified exact header names beyond those listed [U]).

### 3.2 VBO (Racelogic) - best value
Spec facts [V] https://developers.racelogic.co.uk/Portal/Tutorials/tutorialVBOfilestructure.html and https://github.com/quentinsf/vboxutils :
- Text file (UTF-8 or ANSI), CR+LF line ends, sections separated by a blank line.
- Sections: first line = creation date/time; `[header]` (channel names, one per line); `[channel units]` (units for *non-standard* channels only); optional `[comments]`, `[laptiming]` (gates, one per line), `[avi]`, `[circuit details]`, `[session data]`; then `[column names]` (single line, space separated, no spaces inside names) and `[data]` (one line per sample, space separated, fixed width).
- `time` = `hhmmss.sss` (2-3 decimals, UTC time of day). `latitude` = **minutes** (degrees*60), positive north; `longitude` = **minutes, positive WEST** (the reverse of normal). So divide by 60 and flip the longitude sign. [V] vboxutils README and Racelogic KB https://support-centre.racelogic.co.uk/automotive/kb/vbox-latitude-and-longitude-calculations/
- Real sample row (sats time lat long velocity heading height vertvel + a CAN channel): `137 162235.40 +03119.09973 +00058.49277 000.140 321.85 +00152.58 +000.000 00000`. [V] vbo-reader README https://unpkg.com/vbo-reader@1.4.0/README.md . That sample implies 51.985 N, 0.975 W (so printed longitude is positive).
- Standard column names seen: `sats time lat long velocity heading height vertvel`; velocity in km/h, heading degrees, height m. [V partly; confirm exact `[header]` long names against a real file from Circuit Tools' sample data before shipping [U]]
- Custom channels (rpm, throttle, coolant, maf): list in `[header]`, give units in `[channel units]`, and the same names in `[column names]`. [V structure]
- Constructed minimal file [R, from the spec above, verify with Circuit Tools]:
```
File created on 22/10/2026 at 14:03:07

[header]
satellites
time
latitude
longitude
velocity kmh
heading
height
rpm
throttle

[channel units]
rpm
%

[comments]
K-Line Dash trip, 1 Hz

[laptiming]
Start +3119.10000 -0058.49000 +3119.12000 -0058.49500 ¬ Start/Finish

[column names]
sats time lat long velocity heading height rpm throttle

[data]
009 140307.250 +03119.09973 +00058.49277 054.321 321.85 +00152.58 03120 018
```
  (`[laptiming]` line syntax is only partly documented: signed coordinates then `¬` then the name; check against a Racelogic-written file [U]. Skip it in the first version; laps can be recomputed on the PC.)
- Who reads it: RaceChrono imports VBO but needs heading, speed, lat and long, and ignores g-force fields (it recomputes acceleration from GPS) [V] https://racechrono.com/forum/discussion/comment/13471/ ; RaceRender lists VBO [V] https://racerender.com/RR3/docs/Dataloggers.html ; G-Box Lap Timer imports VBO [V]. Circuit Tools (Racelogic) is described as free to download/use on Windows; the current download link and whether v3 reads arbitrary third-party VBO were **not** confirmed [V free, U for the rest] https://en.racelogic.support/VBOX_Motorsport/Software_Info/Circuit_Tools/Circuit_Tools_3/Quick_Start_Guide

### 3.3 GPX 1.1
- Root `gpx` version="1.1", `trk > trkseg > trkpt lat lon` (WGS84 decimal degrees). Child order in `trkpt` is fixed: `ele, time, magvar, geoidheight, name, cmt, desc, src, link, sym, type, fix, sat, hdop, vdop, pdop, ageofdgpsdata, dgpsid, extensions`. `time` is xsd:dateTime (use `2026-10-22T14:03:07.250Z`). `extensions` is last and accepts elements from other namespaces. [V] https://www.topografix.com/GPX/1/1/
- Garmin TrackPointExtension v2, namespace `http://www.garmin.com/xmlschemas/TrackPointExtension/v2`: `atemp`, `wtemp` (C), `depth` (m), `hr` (bpm), `cad` (rpm, 0-254), `speed` (m/s), `course` and `bearing` (degrees true). Nothing for engine rpm; `cad` is capped at 254 so do not abuse it for engine rpm. [V] https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd
- Put `speed` and `course` in the TPX extension (read by Garmin tools, GPS Visualizer, RaceRender lists GPX), plus `sat`, `hdop`, `fix` as native elements. Engine channels (rpm, throttle, coolant, maf) in an own namespace (e.g. `kld:`); generic tools ignore them [U], so the CSV stays the lossless companion.
- Laps: one `trk` per trip, one `trkseg` per lap; start/finish gate as `wpt` [R].

### 3.4 KML (Google Earth)
- `gx:Track` (namespace `http://www.google.com/kml/ext/2.2`): equal numbers of `<when>` and `<gx:coord>` (lon lat alt, space separated); optional `<gx:angles>` (heading tilt roll); `ExtendedData/SchemaData/gx:SimpleArrayData` carries per-point arrays (speed, rpm...) and Google Earth shows them in the elevation profile. [V] https://developers.google.com/kml/documentation/kmlreference
- Good for visualising on a map with a time slider; Harry's LapTimer also exports KML for Google Earth [V anecdotal, Italian review via search]. Low effort given the same sample loop.

### 3.5 Other formats
- RaceChrono: has CSV v3 export (v7.3) with elaborate headers/units; imports Tesla Track Mode CSV, AiM CSV, GoPro, NMEA, VBO etc. No CSV v3 spec text found; do not target it. [V existence, U spec] https://racechrono.com/forum/discussion/comment/11815/ (changelog; the page itself returned 404 when fetched directly, so the facts come from the search excerpt) 
- NMEA 0183 (RMC/GGA): read by RaceRender and RaceChrono [V]; trivial to write but loses OBD data.
- MoTeC i2: `.ld` is binary, no official spec; reverse-engineered readers/writers exist (Python `ldparser`; Rust crate `motec-i2`, MIT, parse/write .ld/.ldx, "unstable") [V] https://github.com/gotzl/ldparser , https://lib.rs/crates/motec-i2 . CSV import in i2 Pro is a licensed feature (30-day licences or dealers convert) and channel units are not mapped from third-party CSV. [V, forum] https://forum.motec.com.au/viewtopic.php?p=20617 . Verdict: out of scope.
- AiM Race Studio 3: exports XRK/CSV; no documented import of arbitrary GPS/CSV logs found. [U] Out of scope.
- Harry's LapTimer exports CSV/TRK/KML [V anecdotal]; TrackAddict is "designed for maximum compatibility with RaceRender" [V]; neither CSV spec found [U]. Not worth targeting.

### 3.6 Which 2-3 first (hobby driver on Windows)
1. **Enriched CSV** - Excel/LibreOffice/Python; the lossless source of truth (every channel plus GPS plus lap number).
2. **VBO** - the one lap-timing-ecosystem file format with a public spec: opens in Racelogic Circuit Tools (free), RaceChrono (phone), RaceRender (video overlay, paid [U]), G-Box. Channels beyond GPS fit through custom channels.
3. **GPX 1.1 + TPX v2** (works in GPS Visualizer, Google Earth, any map tool, Strava-like sites) - or KML `gx:Track` if the goal is Google Earth playback. If only one of the two, pick GPX (wider support).
Free viewers: Circuit Tools [V free], Google Earth for KML/GPX [U], GPS Visualizer (web, maps/profiles from GPX/CSV) [U], Excel / LibreOffice / Python pandas for CSV. RaceChrono desktop: not verified to exist; RaceChrono is a phone app [U].

---

## 4. Statistics

### 4.1 Per lap (what lap timers show; general knowledge [U])
Lap time, best lap, last lap, delta to best (live and per lap), predicted lap, sector times, theoretical best (sum of best sectors), average and max speed, min speed (apex), max rpm, time at full throttle (needs PID 11 poll rate high enough; K-Line total poll rate is a few PIDs/s so quantisation is ~0.2-0.5 s [R]), max coolant, max/min IAT, distance (GPS), lap validity flag (GPS gap, accuracy), max lateral/longitudinal g derived from GPS speed and heading change (noisy at 1 Hz) [R].

### 4.2 Per trip
Duration (wall and moving), distance, average speed (total and moving), max speed, idle time (speed < 2 km/h and rpm > 300; engine on), max rpm, rpm histogram, coolant max and time-to-warm (ECT 70 C), fuel estimate (below), start/end location (optional), number of laps/best lap if gates were defined.

### 4.3 Fuel estimate from MAF (petrol)
- Fuel mass flow = `MAF[g/s] / AFR`; stoich petrol AFR 14.7. Volume = mass / density (density ~720-775 g/L; use ~745). Litres per hour: `L/h = MAF * 3600 / (14.7 * 745) ~= 0.329 * MAF[g/s]`. L/100 km = `L/h * 100 / speed[km/h]` (undefined below ~5 km/h; show L/h at idle). Trip fuel = integral of `L/h` over time (trapezoid), with the same gap rule as `TripSummarizer`. [R, formula standard] A paper uses the same method with a different density assumption (820 g/dm3), showing the density choice moves the result by several percent. [V] https://arxiv.org/pdf/1701.02160
- Accuracy: "within a few percent" at closed-loop cruise; wrong during open loop (WOT enrichment, cold start), and during decel fuel cut (MAF high, injectors off) the estimate overcounts. [V anecdotal] https://ecomodder.com/forum/ (threads surfaced by search); flag as estimate. Improve: if PID 03 says open loop or throttle > ~85 % use AFR ~12.5 [U, rough]; zero the flow when rpm > 1500 and throttle ~0 and fuel system status = "open loop due to deceleration" [U]. 2ZZ-GE is narrow-band closed loop [V secondary, `2zz-ge-obd.md`].
- STFT/LTFT could correct slightly, but the MAF reading already includes it; ignore.

---

## 5. Recommendations for K-Line Dash (prioritized)

1. **Keep the CSV as the lossless source of truth; derive everything else.** Extend `CsvTripWriter` with GPS columns (`lat`, `lon`, `gps_speed`, `heading`, `gps_acc`, `sats`) and compute laps/stats from it, so changing a gate recomputes old trips. Missing GPS = empty cells (already the convention).
2. **`GpsSource` in `ObdService`, plain `LocationManager`.** Request `GPS_PROVIDER` at 1000 ms (try 200 ms and log the real interval histogram on the head unit before promising 5-10 Hz). Expose `StateFlow<GpsFix?>` with `elapsedRealtimeNanos`, `time`, lat/lon, speed, bearing, accuracy, sats (from `GnssStatus`). Drop fixes older than 3 s.
3. **FGS/permission:** manifest `connectedDevice|location` + `FOREGROUND_SERVICE_LOCATION` + fine/coarse permissions; keep boot start as `connectedDevice` only, add `location` when the activity is visible or background location is granted; degrade to OBD-only recording otherwise; show a clear "GPS: waiting for app/permission/fix" chip. Test on the real unit (1.4).
4. **Sampling:** one row per GPS fix when the rate is > 1 Hz, else the existing 1 s tick; OBD values carried forward with per-signal freshness (rpm/throttle ~1 s, coolant ~30 s) rather than the single 30 s limit. Use GPS UTC time for `time_iso` when a fix exists.
5. **Data model:** `Trip` unchanged plus nullable GPS summary (start lat/lon, has_gps). New Room tables `tracks` (name, gates as JSON: id, type start/finish/sector, A, B, direction) and `laps` (tripId, trackId, index, startMs, endMs, timeMs, valid, maxSpeed, maxRpm, ...) — laps are a cache, rebuildable from the CSV.
6. **Lap detector as pure Kotlin in `obd-core`** (JVM-testable like `TripSummarizer`): local flat projection, segment intersection with direction check, linear (optionally Hermite) interpolation of the crossing time, debounce `max(10 s, 0.5*best)` + re-arm distance, invalid-lap rules (gap > 3 s, accuracy > 20 m). Same code runs live (per fix) and after the fact (over the CSV).
7. **Gate creation:** "Set start/finish here" (gate perpendicular to current bearing, width 40 m, editable), plus a trace screen to place/adjust a gate and recompute; optional sector gates; auto-start of lap mode when a saved track's gate is passed. No bundled track database.
8. **Auto/manual trips and lap sessions:** manual start/stop already exist; add "track session" mode (lap timer on, auto-stops after N minutes standing still). Auto-start on engine running stays; recording a lap session requires a gate but not a fix at start.
9. **Statistics:** extend `TripSummarizer` with moving time, idle time, avg speed, fuel from MAF, per-lap aggregates, best/theoretical-best, delta vs best-lap-by-distance; one set of accumulators used both live (per sample) and from a CSV replay (after the fact). Prefer GPS speed (Doppler) for distance when a fix exists, OBD speed otherwise.
10. **Export, in this order:** (a) enriched CSV (also add `lap` column), (b) VBO writer (spec 3.2; validate by opening a produced file in Circuit Tools and RaceChrono), (c) GPX 1.1 + TPX v2 with per-lap `trkseg`. KML `gx:Track` only if requested. All files go into the existing trips folder so the HTTP+PIN export server and ZIP download serve them with no changes; generate on demand and cache.
11. **Privacy/safety:** GPS traces reveal home and routes; keep them local, and add an option to trim the first/last 200 m or strip lat/lon when sharing; the export server is plain HTTP + PIN on LAN (existing approved exception), so mention it in the export screen.
12. **Validate on the real unit before UI work:** a throwaway "GPS test" screen (fix rate histogram, accuracy, time to first fix, behaviour after sleep/resume and after boot without activity). Decide on a 5-10 Hz target (or an external Bluetooth GNSS later) based on those numbers.

## 6. Open items needing an on-device test
- Real GNSS fix rate and accuracy of the target head unit via `LocationManager`; cold/hot start time; behaviour after sleep.
- Does a `connectedDevice`-only FGS started at boot receive location? Can `startForeground` with an added `location` type be called later when the activity is visible (targetSdk 34)?
- Exact VBO flavour that Circuit Tools and RaceChrono accept (column names, `[laptiming]` syntax, longitude sign).
- Whether GPX with a custom namespace survives in the viewers the owner uses.

## 7. Sources
- Android: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ; https://developer.android.com/develop/background-work/services/fgs/service-types ; https://developer.android.com/about/versions/15/behavior-changes-15 ; https://developer.android.com/develop/sensors-and-location/location/permissions ; https://developer.android.com/develop/sensors-and-location/location/permissions/background
- VBO: https://developers.racelogic.co.uk/Portal/Tutorials/tutorialVBOfilestructure.html ; https://github.com/quentinsf/vboxutils ; https://support-centre.racelogic.co.uk/automotive/kb/vbox-latitude-and-longitude-calculations/ ; https://unpkg.com/vbo-reader@1.4.0/README.md
- GPX/KML: https://www.topografix.com/GPX/1/1/ ; https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd ; https://developers.google.com/kml/documentation/kmlreference
- RaceChrono: https://racechrono.com/article/faq/can-i-make-the-start-finish-line-wider-so-all-of-my-laps-will-register ; https://racechrono.com/?p=1492 ; https://racechrono.com/forum/discussion/comment/13471/ ; https://racechrono.com/forum/discussion/comment/11815/ (via search excerpt) ; https://www.oneshift.com/articles/news/racechrono-measures-your-lap-times
- RaceCapture: https://wiki.autosportlabs.com/RaceCapture_CustomTrackBestPractices ; https://forum.autosportlabs.com/viewtopic.php?p=29006
- RaceRender: https://racerender.com/RR3/docs/HowTo-DataInput.html ; https://racerender.com/RR3/docs/Dataloggers.html
- MoTeC: https://github.com/gotzl/ldparser ; https://lib.rs/crates/motec-i2 ; https://forum.motec.com.au/viewtopic.php?p=20617
- Circuit Tools: https://en.racelogic.support/VBOX_Motorsport/Software_Info/Circuit_Tools/Circuit_Tools_3/Quick_Start_Guide
- OSM raceway: https://wiki.openstreetmap.org/Tag:highway=raceway ; TUMFTM racetrack-database (aggregator listing) https://awesome.ecosyste.ms/projects/63690
- Fuel/MAF: https://arxiv.org/pdf/1701.02160 ; https://ecomodder.com/forum/
- Head unit GPS: https://www.u-blox.com/zh/press-release/foryou-chooses-u-blox-gps-car-navigation-and-infotainment ; https://android-headunits.com/intermittent-poor-gps-on-android-head-unit/
- Internal: `docs/research/headunit-autostart.md`, `docs/research/2zz-ge-obd.md`, `app/.../service/TripRecorder.kt`, `obd-core/.../trip/{CsvTripWriter,TripSummarizer,TripLog}.kt`, `obd-core/.../export/TripDownloads.kt`
