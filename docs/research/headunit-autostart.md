# Chinese Android head units: ACC/sleep signals, autostart, Bluetooth, lights

Research date: 2026-10-21. Target unit: Android 12/13 head unit, most likely FYT-based UIS7862 / UIS7870 ("TS10"/"TS18" class), alternatively MTCD/MTCE (Microntek), Reglink/MediaTek or Allwinner units.

Legend: **[V]** verified in a cited source, **[U]** uncertain/anecdotal/from general knowledge, **[R]** reasoning/derived.

Bottom line: no vendor documents any of this. Everything comes from reverse-engineering by hobbyists (XDA, GitHub). **Do not depend on a vendor broadcast alone.** The reliable pattern is: get whitelisted by the unit's own "do not kill" list, run a foreground service while the car is on, register receivers *dynamically*, and treat ACC broadcasts as hints on top of Android's own BOOT_COMPLETED / SCREEN_ON / lifecycle signals.

---

## 1. What happens when the key is turned (platform behaviour)

### 1.1 Generic sequence (observed on a Reglink/MediaTek unit, same pattern reported for FYT/MTCD) [V]
Source: https://gist.github.com/andrecuellar/3c39b4f1bdc03a5c4e97336217f13c38 (HeadUnit Revived autostart guide, includes on-device diagnostics)
1. ACC goes low; unit starts a 20-30 s countdown.
2. Firmware **force-stops all non-whitelisted third-party apps** (`stopped=true`) ~22 s after ACC off. Foreground services, alarms and wake locks are cancelled; the app then receives **no broadcasts** (including BOOT_COMPLETED/ACC broadcasts) until the user opens it manually once.
3. Unit sleeps (suspend/"deep sleep") or does a "Quick Boot" (a real reboot with screen off).
4. On ACC on: either a real boot (BOOT_COMPLETED is sent but force-stopped apps do not get it) or a resume from suspend (**no** BOOT_COMPLETED at all).
- Being a system/priv-app is not enough; the vendor's own whitelist decides. [V, Reglink case]
- Allwinner units use suspend-to-RAM; "no documented whitelist mechanism for stock firmware"; workarounds: "start on screen on", Tasker/MacroDroid "Display On" trigger, custom launcher autostart, battery-optimisation exemption. [V (same gist)]

### 1.2 FYT (UIS7862 / UIS8581 / UIS7870, Joying/Dasaita/etc.)
- Sleep behaviour is configurable: switch off vs. "deep sleep" when ACC is off; deep sleep keeps the car battery alive "~30 days" but after ~3 days the unit performs a total shutdown. [V] https://xdaforums.com/t/general-fyt-based-spreadtrum-uis7862-s-unisoc-ums512-uis8581a-sc9863-q-a-mods-tips-firmware.4396339
- What the unit shows after ACC on is set by a property: `ro.build.remember_type` (0 = home, 1 = last app, 2 = app of last channel, 3 = navigation); editable via `config.txt` per XDA. [V] same thread (page 118)
- **Whitelisting for autostart/keep-alive:**
  - `/oem/app/pwctl_config.xml` listing `<packageName>` entries (root needed). **Removed in newer firmware** (UIS7862 kernel date 2023-12-26 or later, UIS8581 2023-12-08 or later). [V] gist above
  - "FYT Factory" tool by Mario Dantas (fytfactory.mariodantas.com) has an autostart/whitelist section, no root. [V per gist; not independently tested]
  - Non-FYT UIS7862 units (ROCO/QF001 family) have a "sleep whitelist" in the factory settings menu that stops apps being killed in standby; factory codes reported there: `8888` advanced settings (other codes exist; may differ per firmware). [V] https://xdaforums.com/t/general-discussion-qf001-roco-k706-head-units-with-uis-7862s-not-fyt-based-read-first-post-first.4525675/page-406
  - Repo with mods for FYT UIS7862 (no root, flashable USB zips; `com.syu.ms` modded versions): https://github.com/hvdwolf/FYTuis7862BinRepo
- Android 13 UIS7870 FYT units exist with the same `com.syu.*` stack (a developer runs a foreground service with sticky restart on one). https://github.com/Vasilich/FYT_CustomService [V exists; behaviour U]

### 1.3 MTCD/MTCE (Microntek, MediaTek/Rockchip PX3/PX5/PX6, Android 6-10)
- Whitelist lives in `MTCManager.apk` (Android 6-9) or `HCTManagerService.apk` (Android 10+), or the "BootControl" app (`com.microntek.bootcontrol`) on genuine firmware; Malaysk ROM on QCM6125 has a UI "Whitelist of programs to run after sleep". [V per gist, platform detection commands included in gist]
- Detect: `/system/priv-app/MTCManager` or `HCTManagerService`.
- Microntek exposes a hidden system service `carservice` (`android.microntek.CarManager`, AIDL `ICarService`) with `getParameters/setParameters/getXxxState` and a callback registry; third-party apps (MtcdTools) reach it by reflection `ServiceManager.getService("carservice")`. [V] https://github.com/f1xpl/MtcdTools/blob/master/app/src/main/java/android/microntek/CarManager.java
- Vehicle data (speed, revs, voltage, temp, mileage) is broadcast as `com.microntek.sync` with a `syncdata` byte[20] extra, only while the stock "Vehicle" (MTCControlInfo) app is running. [V (2017 XDA, old firmware)] https://xdaforums.com/t/reading-vechicle-sensor-data-in-android-app-canbus-input.3670024

## 2. Broadcast actions seen in the wild

| Platform | Action string | Meaning | Source / confidence |
|---|---|---|---|
| FYT | `com.fyt.boot.ACCON` | ignition ON / wake | FytFunctionalityExtender manifest [V]; HeadUnit Revived gist [V] |
| FYT | `com.fyt.boot.ACCOFF` | ignition OFF | FytFunctionalityExtender manifest [V] |
| FYT (older GLSX OEM variant) | `com.glsx.boot.ACCON` / `com.glsx.boot.ACCOFF` | same | same manifest [V] |
| FYT / MTC-derived | `android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT` | wake from sleep | same manifest (used as AccOn) [V] |
| FYT / MTC-derived | `android.intent.action.ACTION_MT_COMMAND_SLEEP_IN` | going to sleep | same manifest (used as AccOff) [V] |
| MTCD | `com.microntek.startApp` | head unit woke from sleep | gist [V per gist; U on current firmware] |
| MTCD (PX3) | `com.cayboy.action.ACC_ON` (also `com.carboy.action.ACC_ON` variant in diagnostics) | ACC on | gist [V per gist] |
| MediaTek generic | `QUICKBOOT_POWERON`, `android.intent.action.QUICKBOOT_POWERON`, `BOOT_IPO` | quick-boot signals | gist (listed as tried; not seen on Reglink) [U] |
| Generic Android | `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `SCREEN_ON`, `SCREEN_OFF`, `USER_PRESENT`, `ACTION_SHUTDOWN`, `ACTION_POWER_CONNECTED/DISCONNECTED` | standard | Android docs [V] |
| Reglink | `com.reglink.action.systemui.SEND` (flood, not useful) | vendor internal | gist [V] |

Not found/verified: `com.syu.ms.acc_on`, `com.microntek.acc_on`, `com.microntek.bootcheck` (the strings suggested in the task) - **no source found; do not ship as defaults without a device test**. Teyes, Atoto, Topway, Dasaita-specific ACC actions: nothing found.
(FYT's own app-side signal is a vendor Binder IPC rather than a public broadcast, see 5.)

Observed practical limits of these broadcasts [V]:
- HeadUnit Revived author: `com.fyt.boot.ACCON` "only works on Android 7 and below due to Android's implicit broadcast restrictions. On Android 8+, you need the whitelist approach."
- FytFunctionalityExtender README: works on Android 6, not on Android 8 "due to the background limitations".
- FYT sends ACCOFF early; the unit keeps playing media ~4 min before Android really switches off (FytFunctionalityExtender README) while the force-stop at ~20-30 s (Reglink) is separate; timing differs per firmware. [V/U]

## 3. Android 12+ constraints for our app

### 3.1 Starting a foreground service from the background (API 31+)
Source: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (page dated 2026-10-16) [V]
Exempt triggers (selection): app transitions from a visible activity; app can start an activity from background; high-priority FCM; **receiving `ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED` or `ACTION_MY_PACKAGE_REPLACED` in a broadcast receiver**; `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, `ACTION_LOCALE_CHANGED`; NFC tag events; device/profile owner; Companion Device Manager with the right permissions. Targeting Android 14+ adds restrictions on some FGS *types* from BOOT_COMPLETED (see Android 15 behaviour-change page; types dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone are the restricted ones to my knowledge [U]; `connectedDevice` should be allowed - verify).
- **Custom vendor broadcasts (ACCON, SLEEP_OUT, ...) are NOT on the exemption list.** A manifest receiver for them cannot start a foreground service when the app is in the background on API 31+, and the call throws `ForegroundServiceStartNotAllowedException`. [V + R]
- FGS that needs a *while-in-use* permission (location, camera, mic, body sensors) cannot be created from background even in exempt cases; Bluetooth connect is not in that group, but a `connectedDevice` FGS needs `BLUETOOTH_CONNECT`-type permission declared (Android 14+: `FOREGROUND_SERVICE_CONNECTED_DEVICE`). [U: from general knowledge]
- Practical workaround if a vendor broadcast does arrive: start an **Activity** (allowed if the app can start activities from background, e.g. the unit grants "display over other apps"/system alert window), or use `AlarmManager.setExactAndAllowWhileIdle` with exact-alarm exemptions, or rely on the user's own launcher autostart. [U]

### 3.2 Implicit broadcasts and manifest receivers (API 26+)
Source: https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions [V]
- Apps targeting API 26+ cannot declare manifest receivers for implicit broadcasts except an exemption list (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, locale/others, etc.). `ACTION_PACKAGE_REPLACED` is *not* exempt (but `MY_PACKAGE_REPLACED` is explicitly sent to the package and works).
- Broadcasts sent to the app explicitly (component or package specified) are delivered to manifest receivers. Vendor system services sometimes set `FLAG_RECEIVER_INCLUDE_BACKGROUND`/explicit packages for whitelisted apps; no source found describing this for FYT/MTC. [U]
- **Dynamic registration** (`registerReceiver` in a running FGS or app process, with `RECEIVER_EXPORTED` flag on API 33+ because the sender is another app/system) works for vendor and system actions as long as the process is alive. Android 14+: dynamically registered context receivers require an export flag; use `RECEIVER_EXPORTED` for vendor broadcasts. [U general knowledge, consistent with docs]
- If the process was force-stopped by the unit: no receiver of any kind fires until the app is launched again (stopped state). [V gist]

### 3.3 Other Android 12/13 items
- Android 13 runtime notification permission for FGS visibility; Car Scanner docs also mention it for background running. [V] (Veepeak VP11 guide: "Since Android 13, you will need to grant 'send notifications'.")
- Android 12: `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`; ensure `neverForLocation` flag if BLE scanning without location. [V partly: Car Scanner]
- Battery optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) helps on stock Android; vendor whitelists matter more. [V gist]
- Autostart behaviour in other apps (Torque, Car Scanner): not found documented; HeadUnit Revived (Android Auto emulator) implements the same mix of OEM broadcast listeners + BOOT_COMPLETED + screen-on and still needs the whitelist. [V gist]

## 4. Day/night and headlight (illumination) signal

- Most aftermarket head units have a wire "ILL/illumination" (headlight detect) and a "backlight/dimming control" setting that follows it. On FYT: set "backlight control" to *headlights*; users report the unit then switches Android Auto/maps to night mode when headlights are on. [V anecdotal] https://xdaforums.com/t/general-fyt-based-spreadtrum-uis7862-s-unisoc-ums512-uis8581a-sc9863-q-a-mods-tips-firmware.4396339/page-44 and /page-110
- Some cars (CAN-bus decoder boxes) pass illumination via CAN decoder instead of the wire; the "control by lights" setting must match. [V anecdotal] (QF001 thread)
- Whether the unit also flips Android's `UiModeManager` night mode is **not documented**; one FYT user reports Google Maps only dimmed rather than going dark with headlights, while Android Auto switched. [V anecdotal] So: **do not rely on `uiMode` night.**
- No public broadcast for lights found on FYT or MTC. `com.microntek.light` (from the task) not verified. FYT exposes car state through the `com.syu.ms` toolkit (Binder IPC, modules MAIN=0, RADIO=1, BT=2, DVD=3, SOUND=4) which a hobbyist decoded for audio-source id; light/ACC fields are presumably in the MAIN module but **no field list was found**. [V for IPC recipe: https://github.com/PimpinPumpkin/FytBt/blob/main/FINDINGS.md ; U for light field]
- Pragmatic day/night options for us: (1) follow system `Configuration.uiMode` if it changes; (2) ambient light sensor if the unit has one (rare); (3) time-of-day/sunrise-sunset from GPS fallback; (4) manual toggle; (5) screen brightness observed via `Settings.System.SCREEN_BRIGHTNESS` changes (dimmed = night) - a cheap heuristic that works when "backlight control" follows the headlights [U]. Make the mode a user setting: Auto / Day / Night.

## 5. Bluetooth on these units

### 5.1 General
- On FYT, the Android Bluetooth stack (AOSP) works and exposes a normal `BluetoothAdapter` to third-party apps; the *vendor UI* (`com.syu.bt`) is often the broken part ("can't set name, can't pair, shows nothing connected"). A developer drove pairing, A2DP-sink and PBAP directly through standard Android APIs on an FYT Android 13 unit. [V] https://github.com/PimpinPumpkin/FytBt/blob/main/FINDINGS.md  (UNISOC UMS9620/UIS7870SC; the UIS7862 may differ.)
- The unit is the A2DP *sink* and HFP client; audio routing is controlled by an MCU with its own "APP_ID" model, independent of Android audio focus. Not relevant to SPP, but means do not expect Android audio APIs to control radio/BT audio. [V] same doc
- Hidden risk: on the Android 13 FYT unit, a Bluetooth HFP call wedged the microphone until reboot; relevant only to calls. [V same doc]

### 5.2 OBD dongles on head units
- The dongle must still be paired. The DuDuAuto (FYT-based) forum shows a user who could **not** pair a VGATE iCar Pro (BT 3.0/4.0) through Android's Bluetooth settings (PIN box accepted nothing) but it worked after pairing with a "Bluetooth-pair" APK, and the vendor's own menu path is `Setup > Device > OBD > OBD Settings > OBD Category > ELM_327`; BT4 adapters do not need pre-pairing in the vendor's own OBD page; owners complain MTC units connected to everything while the FYT-class unit needed "verified" OBD adapters. [V anecdotal] https://forum.dudu-auto.com/d/98-vgate-icar-pro-bluetooth-3040-couldnt-be-paired
- Veepeak: "may have compatibility issues with some Android head units due to lack of support for some Bluetooth profiles or limitations from the manufacturer ... check whether the pairing PIN is disabled or incorrect (should be 1234)". [V] https://m.media-amazon.com/images/I/915287zOFAL.pdf
- Joying's own ELM327 v2.1 listing has reviews: "Not compatible with their own Joying head unit". [V anecdotal] https://www.amazon.com/Bluetooth-Diagnostic-OBD2-ELM327-Interface/dp/B076CD78HT
- Claim from the task ("FYT units with separate GOC/IVT BT chips, 'OBD' pairing menu"): FYT does have an OBD settings page (above); the *separate BT chip* claim for UIS7862 was **not verified** - the AOSP-level evidence above suggests the host stack is Android's. [U]
- Another launcher developer documents plain usage: pair the module in the unit's Bluetooth, then enable ELM327 in the launcher; ECU must be on first; it does not change the ELM configuration. https://xdaforums.com/t/elm327-bluetooth-diagnostic-for-android-head-units.4713728 [V]
- Implication: implement (a) bonded-device list + our own "pair new device" flow using `createBond()`/`BluetoothDevice` APIs (PIN 1234 via `setPin` needs privileged permission, so rely on system dialog), (b) BLE fallback (GATT does not need bonding), (c) Wi-Fi TCP, (d) clear on-screen instructions when `connect()` fails: "pair the adapter in the unit's own Bluetooth/OBD menu first".
- BLE support on these units: depends on the BT chip; the BT4 dongle worked on a DuDu unit without pre-pairing. [V anecdotal]

### 5.3 Wi-Fi OBD vs hotspot
- Head unit Wi-Fi client can join one network. If the unit's internet comes from the phone hotspot, a Wi-Fi OBD dongle (AP at 192.168.0.10:35000) is unusable at the same time; Bluetooth is the better choice for always-on-hotspot users. [R] (not documented; general Wi-Fi limitation). Many head units also have a built-in "Wi-Fi hotspot" feature (the unit itself as AP); some can run STA+AP, many cannot. [U]
- Wi-Fi after wake: Joying PX5 bug where Wi-Fi rarely comes back after sleep (FytFunctionalityExtender turns it on at ACCON). [V] (old firmware)

## 6. Recommendations

### 6.1 Default configurable action list
Make the list user-editable (comma-separated in settings, plus "learn ACC action" diagnostics screen that logs every broadcast received while the app is in the foreground, to help discover real strings on the user's unit).

Default **ON actions** (start/connect): 
1. `android.intent.action.BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` (manifest, exempt) 
2. `android.intent.action.MY_PACKAGE_REPLACED` (manifest, exempt) 
3. `com.fyt.boot.ACCON`, `com.glsx.boot.ACCON`, `android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT`
4. `com.microntek.startApp`, `com.cayboy.action.ACC_ON`, `com.carboy.action.ACC_ON`
5. `android.intent.action.QUICKBOOT_POWERON`, `QUICKBOOT_POWERON` (no namespace), `com.htc.intent.action.QUICKBOOT_POWERON` [U], `BOOT_IPO`
6. `android.intent.action.SCREEN_ON`, `android.intent.action.USER_PRESENT` (dynamic only)
7. `android.intent.action.ACTION_POWER_CONNECTED` (dynamic; some units emulate it) [U]

Default **OFF actions** (stop/disconnect, close sockets, optional `ATLP`): 
`com.fyt.boot.ACCOFF`, `com.glsx.boot.ACCOFF`, `android.intent.action.ACTION_MT_COMMAND_SLEEP_IN`, `android.intent.action.ACTION_SHUTDOWN`, `android.intent.action.SCREEN_OFF`, `ACTION_POWER_DISCONNECTED` (dynamic).
Also stop when K-line says "ignition off" (see below).

Register manifest receivers only for the exempt actions; register everything else dynamically from the foreground service.

### 6.2 Fallback strategy
1. **User setup step**: guide the user to whitelist the app in the unit's own autostart/sleep whitelist (FYT Factory, factory "sleep whitelist", Malaysk/BootControl for MTC), disable battery optimisation, and grant notification + Nearby devices permissions. Detect the platform with `Build`/`getprop` hints (`ro.board.platform`, presence of `com.syu.ms`, `com.fyt.*`, `MTCManager`) and show the right instructions.
2. **Foreground service** (`connectedDevice`) started from the Activity or BOOT_COMPLETED; hold it while the car is "on". Dynamically registered receivers handle vendor ACC/SLEEP broadcasts and screen events.
3. **Self-detection of ignition without vendor help**: if Bluetooth/TCP link to the adapter drops or `ATRV` falls or K-line re-init fails repeatedly for >N s, go to "idle/waiting" (slow reconnect backoff 10 s -> 60 s) instead of crashing; resume when the ECU answers. Because the ECU is silent with ignition off, "adapter reachable but no ECU" == car off (or sleeping).
4. **Resume without a reboot**: after suspend/resume the process may be frozen or killed; use `onStartCommand(START_STICKY)`, a lightweight `JobScheduler`/`AlarmManager` re-arm (exempt triggers on boot), and a launcher-level autostart fallback (e.g. user sets our app as "start on boot" in the unit's setting, if offered).
5. **Quick sanity diagnostics screen** showing: last broadcasts received (action + timestamp), whether process is in stopped state (`ApplicationInfo.FLAG_STOPPED` is hidden/`isPackageStopped` unavailable; infer from "never received anything since boot"), BT adapter state, battery-optimisation status.
6. Do **not** rely on `BOOT_COMPLETED` after wake: it fires only for a real boot.

## 7. Open items needing an on-device test (cannot be settled from the web)
- Actual ACC broadcast strings on the user's exact unit (use the learn/log screen; also `adb shell dumpsys activity broadcasts | grep -iE "acc|sleep|boot"` after an off/on cycle, as in the HeadUnit Revived diagnostics).
- Whether `FLAG_STOPPED` kill happens, and the whitelist file/tool available on the firmware date.
- Bluetooth pairing from third-party app: try `createBond()` + system PIN dialog vs. the unit's OBD pairing page.
- Whether `uiMode` night changes with the headlight wire.

## 8. Sources
- Autostart/whitelist deep dive (Reglink, FYT, MTCD, Allwinner, DuraSpeed): https://gist.github.com/andrecuellar/3c39b4f1bdc03a5c4e97336217f13c38
- FYT ACC/sleep actions in a real manifest: https://raw.githubusercontent.com/hvdwolf/FytFunctionalityExtender/master/app/src/main/AndroidManifest.xml ; https://github.com/hvdwolf/FytFunctionalityExtender ; FYT Extra Tool https://github.com/hvdwolf/FET
- FYT general thread (sleep modes, remember_type, backlight control): https://xdaforums.com/t/general-fyt-based-spreadtrum-uis7862-s-unisoc-ums512-uis8581a-sc9863-q-a-mods-tips-firmware.4396339
- FYT binder IPC and Bluetooth findings: https://github.com/PimpinPumpkin/FytBt/blob/main/FINDINGS.md ; https://github.com/hvdwolf/FYTuis7862BinRepo ; https://github.com/Vasilich/FYT_CustomService
- QF001/ROCO UIS7862 (non-FYT) thread: https://xdaforums.com/t/general-discussion-qf001-roco-k706-head-units-with-uis-7862s-not-fyt-based-read-first-post-first.4525675/page-406
- Microntek CarManager and broadcasts: https://github.com/f1xpl/MtcdTools ; https://xdaforums.com/t/reading-vechicle-sensor-data-in-android-app-canbus-input.3670024
- Android docs: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ; https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
- OBD on head units: https://forum.dudu-auto.com/d/98-vgate-icar-pro-bluetooth-3040-couldnt-be-paired ; https://m.media-amazon.com/images/I/915287zOFAL.pdf ; https://xdaforums.com/t/elm327-bluetooth-diagnostic-for-android-head-units.4713728 ; https://www.carscanner.info/en/bluetooth-troubleshooting
