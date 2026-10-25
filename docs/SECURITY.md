# Security

K-Line Dash is an offline app: no account, no server, no analytics, no network traffic except to a
Wi-Fi OBD adapter and the optional Wi-Fi trip export. This page says what it protects, how, and
where the limits are. It was last reviewed for release 1.0.0.

## Threat model

- **Assets:** trips (CSV files with GPS positions, so where and when the owner drove), saved tracks,
  the adapter's Bluetooth address, the VIN last read from the car (kept in the settings to pick the
  car's make), settings, and the car itself (the app can clear trouble codes).
- **Who we defend against:** other apps on the same head unit or phone, and other devices on the
  same Wi-Fi or hotspot while the Wi-Fi export runs.
- **Out of scope:** someone with physical, unlocked access to the device, a rooted device, and a
  malicious OBD adapter (the adapter is trusted by design: it is plugged into the car).

## What is protected, and how

| Area | Protection |
|---|---|
| Components | Only the launcher activity is exported; it reads no extras. `BootReceiver` and `ObdService` are not exported. Notification `PendingIntent`s are immutable and explicit. |
| Broadcast receivers | Screen-on and user-present use a not-exported runtime receiver. The vendor ignition broadcasts (ACC on/off) must be exported, because the head unit's own app sends them, and Android cannot tell who sent one. The user chooses the actions (letters, digits, dots and underscores only). See the limitations below. |
| Storage | Settings and tracks live in the app's private database. Trips are in the app-specific external folder (`Android/data/nl.stijnvdpol.klinedash/files/trips`). Backup and device transfer are off (`allowBackup="false"` plus exclude-all rules). |
| Files from outside | User theme files: at most 50 files of 64 KB, nesting depth checked before parsing, every value validated, invalid files listed and ignored. Trip file names from the database are resolved inside the trips folder only (`resolveTripFile`). The USB/SD copy writes only through the folder the user picked in the system picker. |
| Wi-Fi export | Off by default and started by the user. Read-only: GET of files from the current list, by index, never by a path from the request. Answers only IPv4 peers in private ranges (10/8, 172.16/12, 192.168/16), so a public IPv6 address on mobile data does not expose it. A new random 4-digit PIN (`SecureRandom`) per start; 5 wrong PINs lock the export until it is restarted on the device (1 in 2 000 chance for a guesser per run). 128-bit random session cookie, `HttpOnly`, `SameSite=Strict`. Strict request parser (size limits, no chunked bodies, 15 s read deadline), a stalled download is dropped after 30 s, the server stops after 10 minutes without requests. Headers: `Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy`, `Cache-Control: no-store`. File names are sanitised for downloads and HTML-encoded on the page. |
| Logs and crash reports | No log line contains a position, the adapter address, the VIN, the PIN or a session token. Crash reports stay on the device and hold the exception and app version; they leave only when the user exports them. |
| Permissions | Each one is used and explained in SETUP > About. Location is only requested for GPS (and for Bluetooth LE scans on Android 11 and older); there is no background location permission. `BLUETOOTH_SCAN` is declared `neverForLocation`. |
| Release builds | Signed with one release key (see `docs/RELEASING.md`), minified with R8 and no extra keep rules. Every release lists SHA-256 checksums. |

## Known limitations (accepted)

- **The Wi-Fi export is plain HTTP.** An offline head unit cannot present a certificate a browser
  trusts. Anyone on the same network can read the traffic of a running export, including the PIN
  and the downloaded trips. Use it on your own hotspot or home network, not on public Wi-Fi.
  This is an approved exception to the TLS rule in `CODE GUIDELINES.md`.
- **The lockout is per export run, not per client.** Someone on the same network can lock the
  export with five wrong PINs; restarting the export on the device gives a new PIN and clears it.
- **Ignition broadcasts can be faked.** Any app on the device can send the configured ACC actions.
  The worst it can do is end the running trip or make the app reconnect or pause the adapter
  link; it cannot read data or send commands to the car.
- **Trips on Android 8 and 9.** On these versions, apps with the storage permission can read the
  app-specific external folder, and with it the trips. From Android 10 that folder is private.
- **Position checks.** The app trusts the GPS of the device and the answers of the adapter.

## Reporting a vulnerability

Open an issue on GitHub: <https://github.com/stijnvandepol/K-Line-Dash/issues>. Describe the problem
and how to reproduce it, but leave out working exploit details and any personal data (trip files,
positions, VINs). If the issue needs a private channel, say so in the issue and the maintainer will
contact you. Fixes ship as a new release; see `CHANGELOG.md`.
