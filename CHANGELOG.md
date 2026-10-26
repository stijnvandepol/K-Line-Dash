# Changelog

All notable changes to K-Line Dash are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.3.2] - 2026-10-26

More robust Bluetooth Classic pairing on head units with their own Bluetooth app.

### Fixed

- **Pairing a Bluetooth Classic adapter in the app is more reliable on head units** (tested
  against an ESP32 bridge with a PIN on Android 8.1):
  - The PIN request is answered ahead of a vendor app and then no longer passed on, so no second
    answer or dialog follows.
  - A retry only tries the next PIN when the adapter turned the last one down; when the adapter was
    out of range or not discoverable it says so and keeps the same PIN.
  - A pairing that takes too long is checked once more and counted as paired if it made it, otherwise
    it is cancelled. A device that is already paired is selected at once.
  - Search also lists adapters that are already paired ("Already paired"), so an old pairing no
    longer hides them. When connecting to a paired adapter fails like a stale pairing (for example
    after the ESP32 was re-flashed), the app says so and offers Pair again.
  - Pairing waits until the search has really stopped, and for Android's own service lookup before
    the first connect.
  - Pairing no longer needs Location (only searching does, on Android 11 and older) and says why it
    could not start: Bluetooth off, no Bluetooth, or a missing permission.
  - A late "search finished" from an earlier search no longer ends a new search immediately.

## [1.3.1] - 2026-10-26

Pair a Bluetooth Classic adapter from inside the app, for head units whose own Bluetooth screen
only lists phones.

### Added

- **Search and pair Bluetooth Classic adapters in the app** (SETUP > Connection and the setup
  wizard): Search finds unpaired Classic and dual-mode devices (all device classes, OBD-looking names
  first), tapping one pairs it, answering a PIN request with 1234 by itself (a retry tries 0000, then
  6789). When it is paired it becomes the selected adapter. Needed on head units whose own Bluetooth
  screen only lists phones. Says why when Bluetooth is off, a permission is missing, Location is off
  (Android 11 and older) or nothing was found.

## [1.3.0] - 2026-10-26

More makes with trouble code meanings, old trips cleaned up, and a Bluetooth LE scan that says
why it finds nothing.

### Added

- **Keep trips** (TRIPS, under the automatic recording switches): how many of the newest trips to
  keep, 50, 100, 250 or 500 (default 500, what the list showed until now). When a trip ends, and
  once when the app starts, older trips beyond the limit are deleted with their CSV file and the
  generated PC files. Lap sessions are always kept and do not count towards the limit; a trip being
  recorded is never touched. Changing the limit deletes nothing until the next trip ends.
- **Manufacturer code meanings** for Nissan / Infiniti (66 codes), Mazda (26) and Ford petrol (76),
  own wording, each code confirmed by at least two sources (`docs/research/dtc-sources-more-makes.md`).
  Hyundai / Kia, Subaru and Mitsubishi stay on the make-aware hint: their public lists contradict
  each other or have no second source.
- **Help:** two new troubleshooting answers, about trouble codes marked "manufacturer-specific" and
  about a rev counter scale that does not match the car, both with a button to SETUP > Car. The lap
  timing steps say that the pass at which the line is set starts lap 1, and "Values update slowly"
  explains the RPM priority and the link speed on SENSORS.

### Fixed

- **Bluetooth LE scan finding nothing on a head unit** (SETUP > Connection > Bluetooth LE, and the
  setup wizard), where a phone finds the adapter. The scan no longer fails silently:
  - On Android 11 and older it checks the Location switch first and, when it is off, says so with
    a button to Android's location settings (Android drops every scan result while it is off).
  - It keeps inside Android's limit of 5 scans per 30 seconds (beyond it Android silently returns
    nothing) and tells you how many seconds to wait; tapping Scan or opening the wizard step while
    a scan runs no longer restarts it.
  - It also runs classic Bluetooth discovery next to the LE scan, which on some head-unit stacks
    finds LE adapters the LE scan misses, and scans 12 seconds instead of 10. Discovery is stopped
    when the scan ends.
  - Bluetooth off, no Bluetooth adapter, and a unit whose Bluetooth cannot scan for LE adapters
    each get their own message instead of an empty list.
  - A scan that finds nothing shows a checklist (Location, adapter power, no other connection,
    wait 30 seconds, check Android's Bluetooth settings), and the stack's error code if it gave one.
  - A device first seen without a name is listed again once its name arrives.
- **SETUP > Debug > Bluetooth:** Android version, LE support, Bluetooth, Location switch and
  permission, Nearby devices permission, and the result of the last scan, for one-screenshot reports.
- Old trips no longer pile up for ever: the list showed at most 500 trips, but older ones stayed on
  the head unit with their files. The list now shows every kept trip, and a trip's half-written PC
  files left by an interrupted export are removed with it.
- Stopping the Wi-Fi export now waits until its port is released, so starting it again right away
  no longer fails with the port still in use; the stop no longer blocks the screen.

## [1.2.1] - 2026-10-25

An optimisation round after a full audit: faster live data on K-line cars, a lighter dashboard, a
faster start, and fixes for head units whose clock jumps at boot.

### Changed

- **Live data on K-line and J1850 cars** (no multi-PID requests): RPM is polled twice per round,
  so at 0.5 s per request it updates every second instead of every two. Speed stays at every two
  seconds; throttle comes every third rotating slot (about every 6 s) and coolant, intake air, load
  and timing at least every 18 s (was 11 s). CAN cars are unchanged: those values go out in one
  request already.
- **The RPM bar and needles** reach a new reading within 350 ms (ease-out) instead of gliding over
  the whole interval, so on a slow link they no longer trail a full reading behind.
- **Dashboard drawing:** moving bars, needles and arcs are drawn in layers of their own, so a frame
  of movement no longer redraws the whole page. The RPM bar and the digital arc only redraw when a
  segment turns on or off: about 70 % less CPU for those dashboards. The status chip no longer
  recomposes on every request.
- **Start-up:** the settings are read once, in one database query shared by all settings, and the
  settings and theme files are loaded in parallel while the app starts, so the first screen no longer
  waits for a series of reads one after another.
- The first 0100 of a connection is no longer asked twice (about 0.5 s faster to live data on K-line).
- SENSORS shows the link speed: requests per second and the mean time per request.

### Fixed

- **Head units without a battery-backed clock:** polling, trip recording, automatic stop, warnings
  and the Wi-Fi export now measure time on a monotonic clock. A clock that steps when GPS or network
  time arrives no longer stops slow values from being polled for hours, leaves a trip file without
  its header (so a power cut lost the trip), or ends an automatic trip early.
- With the ignition off (or no ECU behind the adapter) the app no longer retries every second; after
  five failed requests it checks every 5 to 10 s and resumes by itself when the car answers.
- One bad reply right after connecting no longer switches off the faster response-count request
  for the whole connection; only two in a row do.
- An adapter that resets itself while driving is reconnected at once instead of after three
  timeouts, and the first request after a pause on a K-line bus gets time for the bus to wake up.
- An unexpected error in the poller, the trip recorder or location handling is logged and costs
  one sample, no longer the live data, the running trip or the whole app.
- Stopping the connection no longer hangs when the adapter's Bluetooth or Wi-Fi peer has died.
- A misbehaving adapter that streams data without ever finishing a reply is disconnected and
  reconnected instead of slowly filling the memory.
- Wi-Fi adapters on a phone with mobile data: the connection now goes over the adapter's Wi-Fi
  network instead of mobile data.
- Recovering an unfinished trip after a power cut reads the file row by row instead of three
  copies in memory; exporting the same trip twice at once no longer fails; the boot receiver can no
  longer hang; raw commands from the Debug screen no longer interleave with live polling.
- **Wi-Fi export:** an internal error no longer stops the app; other devices that only connect can
  no longer keep it running or tie up its connections; requests must name the export by IP address
  and come from its own page (no DNS rebinding or cross-site logins); a download link can no longer
  hand out another trip when a trip was added in the meantime.

### Security

- CI checks that the Gradle wrapper is an official release, the Gradle download is checked against
  its SHA-256, and CI also builds the minified release build.

## [1.2.0] - 2026-10-25

K-Line Dash learns the make of the car: trouble code meanings and the connection now fit German,
Japanese and other cars, not just Toyota.

### Added

- **SETUP > Car:** the make of the car replaces the trouble code profile. "Automatic" reads the VIN
  from the car after connecting and picks the make family by itself (VW Group, BMW / Mini,
  Mercedes-Benz / smart, Opel / Vauxhall, Ford, Renault / Dacia, Peugeot / Citroën / DS, Fiat / Alfa
  Romeo / Lancia, Volvo, Toyota / Lexus, Honda / Acura, Nissan / Infiniti, Mazda, Mitsubishi,
  Subaru, Suzuki, Hyundai / Kia); you can also choose the make yourself, or "Other make". A car
  that reports no VIN stays on the standard meanings. DIAG says so when a manufacturer-specific
  code is listed and the make is not known, and opens SETUP > Car.
- **Manufacturer code meanings** for VW Group (150 codes) and Honda / Acura (52), each confirmed by
  two independent sources and worded for the app; Toyota / Lexus keeps its table. For the other
  makes a manufacturer-specific code names the workshop tool that reads it (ISTA, Xentry, Diagbox
  and so on) instead of guessing a meaning.
- **Connecting:** when the adapter's automatic search finds nothing, the app now also tries CAN
  (11 and 29-bit, 500 and 250 kbaud) and J1850 (PWM, VPW) explicitly, in an order that suits the
  make, and says which protocol it is trying.

### Changed

- The trouble code profile of 1.1.0 is carried over: "Toyota 2ZZ-GE" becomes the make
  Toyota / Lexus with the new "2ZZ-GE engine notes" switch, "Any car" becomes Automatic.

## [1.1.0] - 2026-10-23

K-Line Dash no longer assumes a Toyota Corolla T-Sport: the tachometers and trouble codes now fit
any car. Fixes from testing 1.0.0 with the simulator and lap timing.

### Added

- **SETUP > Car:** the red line of the car's rev counter (sets the scale of every tachometer) and the
  trouble code profile, "Any car" or "Toyota 2ZZ-GE". The setup also asks for the red line, and
  choosing the simulator there switches on the simulated GPS circuit.

### Fixed

- Toyota-specific trouble code meanings and notes were shown for every car. They now apply only to
  the Toyota 2ZZ-GE profile; any other car gets the standard meanings, and manufacturer-specific
  codes say that their meaning depends on the make.
- Every tachometer was scaled for a 9 000 rpm engine with fixed caution and red zones. The scale and
  the zones now follow the red line set for the car.
- Lap timing: the lap screen, the trip list, the trip detail and the exports now show the same laps.
  Setting the start/finish line while driving starts lap 1 at that spot, and passes of that spot
  before the lap session no longer count as laps.
- Trips now always record air flow and throttle, so the fuel estimate and the full-throttle share no
  longer disappear from the trip detail and the exports. Trips recorded earlier still show "--" and
  0 %.
- The simulator button on an empty dashboard now also switches on the simulated GPS circuit.
- The release page now shows what changed and how to install, and the README's checksum command no
  longer fails on the crash-report mapping it does not need.

## [1.0.0] - 2026-10-22

The first public release.

### Added

- **Adapters:** ELM327-compatible OBD2 adapters over Bluetooth Classic, Bluetooth LE and Wi-Fi.
  Automatic protocol detection (CAN, ISO 9141-2, KWP2000, J1850) with the last working protocol
  remembered, a Toyota K-line profile, tolerance for clone adapters that refuse optional commands,
  detection of app-locked dongles with a list of adapters that work, and reconnection with backoff.
- **Simulator:** a virtual car with fault injection and a simulated GPS circuit, to try every
  feature without a car.
- **Dashboard:** swipeable pages: a main page (RPM bar or one of five cluster styles: race bar,
  twin dials, center tach, digital arc, virtual cockpit), grid pages of numbers and 60-second
  graphs, and a track page with peak hold and a shift light. A page editor under SETUP > Dashboard.
  Only the signals on screen are polled.
- **Sensors:** every supported signal with its update rate.
- **Diagnostics:** stored, pending and permanent trouble codes with offline descriptions, the
  check-engine light, readiness monitors, freeze frame, VIN and ECU name; clearing codes with two
  confirmations and only with the engine off.
- **Warnings:** coolant temperature, battery voltage (running and engine off), a new trouble code,
  the check-engine light and a lost adapter connection, as a frame and banner on every page with an
  optional tone; limits under SETUP > Warnings.
- **Trips:** automatic recording when the engine runs or the car moves, manual start and stop,
  recovery of trips cut off by a power loss, live and recorded statistics (distance, speeds, max
  RPM, max coolant, full-throttle share, estimated fuel use) and a trip detail page with the route.
- **Lap timing:** lap sessions with a start/finish line and sector lines set while driving, current
  and best lap, delta to best, sector splits, saved tracks, and a lap table with speed and RPM
  graphs per lap.
- **Export:** VBO, GPX and CSV files for PC analysis; copy to a USB stick or SD card through the
  system folder picker; a PIN-protected, read-only Wi-Fi export page that stops itself when idle.
- **Themes:** nine built-in themes, user themes as JSON files, an accent colour choice and automatic
  day/night switching by schedule or by the head unit's night mode.
- **Head units:** start on boot, reconnect on the vendor ignition broadcasts, finish the trip on
  ignition off, and instructions to keep FYT/UIS and Microntek units from closing the app.
- **First-run setup**, in-app Help and About (privacy, permissions, licenses, local crash reports).
- **Units:** metric or imperial everywhere in the app; exports stay metric.
- **Screen sizes:** every screen works from a 640 x 360 phone in landscape up to a 1920 x 720 head
  unit.

### Security

- Wi-Fi export: answers only devices on a private IPv4 network, never a public or IPv6 address.
- Position values are kept out of error messages and crash reports.
- See `docs/SECURITY.md` for the threat model and known limitations.

[Unreleased]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.3.2...HEAD
[1.3.2]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.3.1...v1.3.2
[1.3.1]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/stijnvandepol/K-Line-Dash/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/stijnvandepol/K-Line-Dash/releases/tag/v1.0.0
