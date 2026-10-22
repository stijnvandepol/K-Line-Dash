# Changelog

All notable changes to K-Line Dash are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/stijnvandepol/CarPlayinfo/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/stijnvandepol/CarPlayinfo/releases/tag/v1.0.0
