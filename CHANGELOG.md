# Changelog

All notable changes to K-Line Dash are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/stijnvandepol/CarPlayinfo/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/stijnvandepol/CarPlayinfo/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/stijnvandepol/CarPlayinfo/releases/tag/v1.0.0
