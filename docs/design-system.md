# Design system: motorsport display

K-Line Dash looks and behaves like a motorsport data display (AIM, MoTeC, Cosworth): black glass,
white numerals, a segmented RPM bar, hard edges, and colour only when something needs attention.
It is read by a driver at arm's length in glances of under a second, day and night.

Phase 5 adds more themes as JSON; they swap the colour tokens only. The grid, type scale, shapes
and the meaning of colour stay fixed.

## Principles

1. **An instrument, not an app.** No cards, no shadows, no rounded corners, no gradients, no
   icons. Structure comes from a strict grid and 1 dp rules, like the fields on a race display.
2. **One glance, one answer.** The RPM bar and the RPM number dominate. Everything else is quieter
   and smaller, in a fixed position so the eye learns where to look.
3. **Colour means state.** White and greys carry all normal content. Amber = caution (the last 1 000 rpm before the
   redline, reconnecting), red = danger (redline, failed). Green only for "connected". A red pixel
   always means something.
4. **Numbers never move.** Tabular figures, right-aligned in fixed-width slots, units outside the
   slot. A stale value is dimmed, a missing one shows "--" in the same slot. No thousands
   separator: "8175", not "8,175" or "8 175" (a gap reads as two numbers at a glance). Decimal
   point always, whatever the system locale. Flags (the MIL) read "On"/"Off", not 1/0.
5. **Big, forgiving targets.** Every control is at least 72 dp high on a head unit (never below
   48 dp on a phone, see Screen sizes); no precision needed while the car moves.

## Colour tokens (default theme "Race")

| Token | Hex | Use |
|---|---|---|
| `background` | `#000000` | Screen. Pure black blends into the head unit's bezel. |
| `surface` | `#1C1C1C` | Unlit RPM segments, input fields, disabled filled buttons. Light enough that the unlit bar stays visible at idle |
| `rule` | `#2A2A2A` | 1 dp grid lines between fields, table dividers, outlines |
| `ink` | `#FFFFFF` | Values, lit RPM segments, primary text |
| `inkMuted` | `#8C8C8C` | Field labels, units, secondary text |
| `inkFaint` | `#4D4D4D` | Stale values, disabled controls, minor scale marks |
| `ok` | `#2ED47A` | Connected dot only |
| `caution` | `#FFB000` | Caution zone segments (from 1 000 rpm below the car's redline), reconnecting, values nearing a limit |
| `danger` | `#FF2D2D` | Redline segments (from the redline set for the car), failed, over-limit values |
| `accent` | `#FFFFFF` | Lit RPM segments below the caution zone, the active-page marker (phase 5; follows `ink` in themes that do not set it) |

## Typography

System fonts only. Numerals and field labels use **Roboto Condensed** (`sans-serif-condensed`),
running text uses **Roboto**. Every numeric style sets `fontFeatureSettings = "tnum"`.

| Style | Size / weight | Use |
|---|---|---|
| `heroValue` | 168 sp / Bold, condensed, letter spacing -2 % | RPM number (grows to fill its field, see Dashboard) |
| `primaryValue` | 112 sp / Bold, condensed | Speed, coolant on the dashboard (grows likewise) |
| `fieldValue` | 56 sp / Bold, condensed | Bottom-row fields, track values |
| `rowValue` | 28 sp / Medium, condensed | Sensors table values |
| `fieldLabel` | 16 sp / Bold, condensed, all caps, letter spacing 8 % | Every caps label: dashboard field labels and units, RPM scale, table headers, buttons, nav |
| `title` | 24 sp / Medium | Section titles on Connection/Debug |
| `body` | 20 sp / Regular | All running text: rows, hints, switch labels, table names, status detail |
| `caption` | 16 sp / Regular | Secondary text: units and rates in tables, device addresses, bottom-bar status |
| `mono` | 15 sp / Regular, monospace | Debug log only |

All caps is only used for `fieldLabel`: short labels on a race display are a convention of the
genre and stay readable at 16 sp. Everything else is sentence case.

## Layout

8 dp grid. No outer card padding: fields run edge to edge, separated by 1 dp `rule` lines.
Inner padding of a field: 16 dp horizontal, 12 dp vertical. Corner radius 0 everywhere. Every
text, value and control starts 16 dp from its column edge, on every screen, so left edges line up
down the whole column. Rows of buttons that belong to a grid (Connection actions, Debug quick
commands and log toolbar) are 72 dp cells separated by rules, not floating boxes.

Navigation is a **bottom bar**, 72 dp high, text-only buttons in `fieldLabel` style separated by
rules. The active page has a 4 dp white bar along its top edge and `ink` text; inactive pages use
`inkMuted`. The right end of the bar shows the link status: coloured dot, protocol, and the
measured request rate ("ISO 9141-2  4.4/s").

### Dashboard (1280 x 720)

```
+--------------------------------------------------------------------------+
| ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▯▯▯▯▯▯▯▯▯▯▯▯▯ [amber▯▯▯▯] [red▯▯▯]   |  RPM bar
| ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮▯▯▯▯▯▯▯▯▯▯▯▯▯ [amber▯▯▯▯] [red▯▯▯]   |  (96 dp)
| 0     1     2     3     4     5     6     7     8     9                    |  scale
+-----------------------------------+--------------------+-----------------+
| RPM                               | KM/H               | WATER       °C  |
|                                   |                    |                 |
|                                   |                    |                 |
|  3450 (fills the field)           |              87    |            88   |
+-----------+-----------+-----------+----------+---------+-----------------+
| INTAKE °C | THROTTLE %| LOAD %    | IGN °    | BATT V  | MAF g/s         |
|        24 |        12 |        31 |     14.0 |    13.9 |            4.0  |
+-----------+-----------+-----------+----------+---------+-----------------+
| DASH | SENSORS | CONNECTION | DEBUG |            ● ISO 9141-2   4.4/s    |
+--------------------------------------------------------------------------+
```

- **Proportions (720 dp tall):** RPM bar block 140 dp (12 dp inset, 96 dp bar, 4 dp gap, 20 dp
  scale, 8 dp), primary row takes the rest (about 400 dp), field row 104 dp, bottom bar 72 dp.
  The bar is the first thing the eye finds, so it gets real height; the primary row is only as
  tall as its numerals need plus room for the label.
- **RPM bar:** segments of 200 rpm over the car's rpm scale, 2 dp gaps, full width, 96 dp high.
  The scale follows the redline set for the car: it ends at the next whole 1 000 rpm at least
  1 000 above it, so the red zone is always on the bar (redline 4 500: 0 to 6 000, 30 segments;
  6 500: 0 to 8 000, 40; 8 000: 0 to 9 000, 45; 10 000: 0 to 11 000, 55). Lit
  segments up to the current RPM: white below the caution rpm (1 000 below the redline), `caution`
  from there to the redline, `danger` from the redline. Unlit segments are `surface` (#1C1C1C, clearly visible on black but quiet), with the
  zone colour at 25 % opacity on top for the amber and red zones, so the whole bar and its zones
  keep the screen's structure at idle and with the engine off. A stale reading draws the lit
  segments in `inkFaint`. Scale numbers every 1 000 rpm below the bar in `fieldLabel`/`inkMuted`,
  left-aligned to their segment (the last one right-aligned to the end of the bar). The
  tachometers of the cluster styles use the same scale, zones and numerals.
- **Bar motion:** the lit length moves to each reading with an ease-out tween as long as the
  measured RPM update interval, clamped 100 to 350 ms: on a fast link the bar sweeps without
  stopping, on a slow K-line link it reaches the new value within a third of a second instead of
  trailing a whole interval. No other animation on the dashboard.
- **Primary row:** three fields with widths 2 : 1.4 : 1. Label top-left, unit top-right in the
  same `fieldLabel` style, value right-aligned at the bottom of the field, so all three share
  one baseline. The numerals grow past their design size to fill the field: RPM up to 1.75x
  (`heroValue` 168 to about 290 sp, as wide as its "8888" slot allows), speed and water up to
  1.6x (`primaryValue` 112 to 179 sp, the same size in both fields). On a smaller head unit
  they shrink to fit instead; they never wrap.
- **Field row:** six equal fields (the ECU-supported ones from the default list; unsupported
  ones are replaced by the next supported signal). Same label/unit/value arrangement;
  `fieldValue` stays at its design size (it only shrinks), so all six read as one row.
- **Values outside their normal range** switch the value colour, not the field background. The
  limits are the warning thresholds from SETUP > Warnings (one source of truth, `AlertSettings`):
  coolant from the caution limit `caution`, from the danger limit `danger`; battery outside the
  running/engine-off limits `caution`.
- **Engine off:** the RPM field shows "0" and the bottom bar status reads "Engine off, saving
  power".

### Sensors

One table split in two halves side by side (from 1 000 dp wide; a single column below that), so
the 18 sensors of the target car fit without scrolling: the first half down the left, the rest
down the right, a vertical rule between them. Per cell: name (`body`, left-aligned), value
(`rowValue`, right-aligned in a fixed five-digit slot shared by every row), unit (`caption`,
`inkMuted`, left-aligned next to the slot; flags have none), update rate (`caption`, `inkMuted`,
right-aligned). All four share one baseline. Header row in `fieldLabel`. Rows 56 dp with 1 dp
rules. No cards.

### Navigation (from phase 4)

Five pages: **DASH · SENSORS · DIAG · TRIPS · SETUP**. Driving pages first, setup last. SETUP has
its own left menu (240 dp, 72 dp rows, selected row with the 4 dp white left bar like the adapter
list): **Connection**, **Car**, **Warnings**, **Debug** (and in phase 5 Display and Startup; in phase 7 Help and About, placed before Debug, and the menu scrolls). The right
part shows the chosen section; Connection and Debug keep their layouts below, narrowed to the
remaining width (Connection's 400 dp adapter column becomes the section's left column).

### Warnings overlay (all pages)

A warning must be seen without blocking the screen:

- A 6 dp frame around the whole screen in the warning's colour (`caution` or `danger`) while any
  warning is active. It does not cover content (content insets by 6 dp only while it shows).
- A 56 dp banner directly above the bottom bar: severity colour fill, black `fieldLabel` text for
  `caution` and white for `danger`, saying what and how much ("WATER 106 °C", "BATTERY 11.4 V",
  "NEW FAULT CODE: check DIAG", "CHECK ENGINE LIGHT ON", "ADAPTER CONNECTION LOST"). With several
  warnings the most severe shows, then the oldest, with "+2" at the right end.
- Tapping the banner hides it for that warning until it clears and comes back (the frame stays).
  Nothing else is interactive; no dialogs, no sound from the UI (the service beeps).
- `danger` banners blink never; colour and position are enough and blinking distracts a driver.

### Trips

Two columns separated by a rule:

- **Left (480 dp), recording:** the state in `title` ("Recording" with a `danger` dot, or
  "Not recording"), elapsed time and sample count as `fieldValue` numerals, the file name in
  `caption`; one 72 dp action cell: **Record** (white fill) or **Stop** (outline). Below it the
  "Record automatically when the engine runs" switch row. Below that **Export**: "Copy to USB or
  SD card" (outline cell, opens the folder picker), and the Wi-Fi export block: **Start Wi-Fi
  export** / **Stop**; while it runs, the address in `title` ("http://192.168.43.50:8080"), the
  PIN as a `fieldValue` numeral labelled PIN, and "Stops by itself after 10 minutes without use."
  in `caption`.
- **Right, the trip list:** newest first, 72 dp rows: date and start time (`body`), duration,
  distance, max RPM as tabular values in fixed slots (`rowValue`, units `caption`), a delete cell
  at the right end (text "Delete", `danger` text). Delete asks once in a dialog ("Delete trip of
  21 Oct 14:05? The CSV file is removed."). The trip being recorded shows "Recording" instead of
  a delete cell. Empty list: "No trips yet. Recording starts when the engine runs."

### Warnings (SETUP section)

One row per warning, 72 dp: name (`body`), on/off toggle, sound toggle (labelled "Sound"), and for
thresholds a stepper: **−** and **+** cells (72 x 72 dp) around the value in `rowValue` with its
unit. No keyboard input while driving. Steps: coolant 1 °C, voltage 0.1 V. The two coolant limits
cannot cross (caution stays below danger). "Reset to defaults" as a text cell at the bottom.

### Car (SETUP section)

What the app cannot read from the car, and the make that decides whose trouble code meanings DIAG
shows. Two columns separated by a rule (the side by side rule in "What changes with the width" below):

- **Left (440 dp):** the **Red line (rpm)** stepper (250 rpm steps from 3 000 to 10 000, the same
  stepper as Warnings) with a one-line hint in `caption`: it sets the scale of every tachometer.
  Under it, only while the effective make has researched engines, one switch row per engine with a
  `caption` hint: **2ZZ-GE engine notes** for Toyota / Lexus ("Corolla T-Sport, Celica 1.8 VVTL-i").
  Switching it on also sets the red line to 8 000, but only while it is still the default 6 500.
- **Right, MAKE:** a header, then the list, which scrolls by itself: **Automatic** (its `caption`
  says what the VIN gave: "VW Group, from the VIN WVW…", "Other make, from the VIN WP0…", or "No
  VIN reported by this car yet. Standard meanings only."; only the first three VIN characters are
  shown, the maker identifier), **Other make** (standard SAE meanings; a manufacturer-specific code
  reads "Manufacturer-specific code. Its meaning depends on the make."), then the makes: VW Group
  (brands in the `caption`), BMW / Mini, Mercedes-Benz / smart, Opel / Vauxhall, Ford, Renault /
  Dacia, Peugeot / Citroën / DS, Fiat / Alfa Romeo / Lancia, Volvo, Toyota / Lexus, Honda / Acura,
  Nissan / Infiniti, Mazda, Mitsubishi, Subaru, Suzuki, Hyundai / Kia. Selectable rows with the 4 dp
  bar, like the adapter list.

A make chosen by hand always wins; Automatic uses the make of the last VIN read from a car and stays
generic without one. The DIAG page follows every change at once; while a manufacturer-specific code is
listed and the make is not known it shows one 72 dp row above the code lists ("Set the make under Setup,
Car to see what manufacturer codes mean." and a **Set make** text cell that opens this section). Where the section is narrower than
900 dp (a 640 x 360 dp phone) the make list follows the red line in one scrolling column.

### Connection

Two columns separated by a rule:

- **Left (400 dp):** an 88 dp header ("Adapter type" in `title`), then the adapter types as
  full-width 72 dp rows. The selected row has a 4 dp white bar on its left edge and `ink` text;
  others `inkMuted`. No radio buttons. Below them the "Connect at start" switch row.
- **Right:** the status block on top, 88 dp like the left header and on the same first
  baseline (lamp, state in `title`, detail in `body`/`inkMuted`). Directly under it the actions
  as one row of three 72 dp cells separated by rules, level with the first adapter row:
  **Connect** (white fill, black text), **Disconnect** and **Forget protocol** (text). Status
  and actions read as one block and never move. Below them the controls for the chosen type:
  section title, hint, then the switch, the device list (72 dp rows), scan/settings buttons
  (1 dp white outline) or the host/port fields.
  A BLE scan that does not start or finds nothing says why under the Scan button (`caution` for
  a block, with a "Location settings" button for Location; a short checklist for an empty result).

Switch rows are a `body` label and the square toggle, 72 dp, no second line: the toggle shows
the state. Buttons are rectangular, at least 72 dp high, text in `fieldLabel` style.

### Debug

Two columns, grouped by what they act on:

- **Left two thirds, the log:** monospace on `background`, TX in `ink`, RX in `inkMuted`, errors
  in `danger`. It starts 8 dp below the top and is a whole number of lines high, so following
  new messages never shows half a line at the top edge. Along its bottom a toolbar of 72 dp cells
  separated by rules: Pause/Resume log, Clear log, Export log, and the Auto-scroll switch (two
  cells wide). An export result shows for a few seconds just above the toolbar.
- **Right third, the adapter:** "Adapter" with chip (ELM327, STN or clone), firmware (the ATI
  answer), protocol, CAN, adaptive timing (AT1/AT2), whether the saved protocol was used, and
  unsupported commands (label `inkMuted`, value `ink`, "--" while not connected so nothing below
  moves). The battery voltage is not repeated here; it is live on the dashboard.
  Then "Command": the input field with Send, and the quick commands as a grid of 72 dp cells.
  A reply appears under the grid. Below that, "Bluetooth": Android version and API level, whether
  Bluetooth LE is declared, Bluetooth on/off, the Location switch and permission, the Nearby
  devices permission (Android 12+) and what the last scan found (LE scan, classic discovery,
  error code, throttled), so one screenshot says why a scan finds nothing. The right column
  scrolls to reach it.

## Phase 5

### Themes (SETUP > Display)

A theme is a JSON file that sets the colour tokens above plus one more, `accent`: the colour of
lit RPM segments below the caution zone and of the active-page marker (Race: `#FFFFFF`, so the
accent is invisible as a "colour" there). Status colours (`ok`, `caution`, `danger`) keep their
meaning in every theme; a theme may only adjust their shade for contrast. Built-in themes, each
checked for 4.5:1 label contrast on its background:

| Theme | Character | background / ink / accent |
|---|---|---|
| Race | the default described above | `#000000` / `#FFFFFF` / `#FFFFFF` |
| Minimal | quieter night display, grey ink, less glare | `#000000` / `#C8C8C8` / `#7FA7C9` |
| Retro | amber vacuum-fluorescent look of an 80s cluster | `#0A0603` / `#FFB54A` / `#FF8A1F` |
| Day | light theme for bright sun | `#F2F2F2` / `#0A0A0A` / `#0A0A0A` |

The user can override `accent` with a picker of 8 swatches (no free colour wheel while driving).
User themes are extra `*.json` files in the app's `themes` folder; invalid files are listed with
the reason and ignored. Day/night switching: **Manual**, **Schedule** (day theme from / to a set
time) or **Follow head unit** (Android night mode, which many head units drive from the headlight
input). One day theme and one night theme are chosen for the automatic modes.

As built: the left column is the theme list (each row with a strip of five swatches: background,
ink, accent, caution, danger) followed by the user folder and any ignored files; it scrolls on its
own, and **Reload themes** stays as a 72 dp cell at the bottom of the column, so nothing is out of
reach on a short screen or under a warning banner. In the automatic modes a Day theme / Night theme
choice sits above the list. The right column holds the mode rows, the schedule steppers and the
accent swatches.

File format and validation rules: `docs/themes.md`. The token values of the built-in themes are the
files in `app/src/main/assets/themes/`.

### Dashboard pages (SETUP > Dashboard)

The dashboard becomes a set of pages, swiped horizontally. There are no page dots; after a swipe
the page name shows for 1 s at the top in `fieldLabel`. Page templates:

- **Main:** the layout above; the user picks the three primary signals and the six field-row
  signals.
- **Grid:** 2 x 2, 3 x 2 or 3 x 3 cells; each cell is a **Number** (label, unit, big value) or a
  **Graph** (last 60 s as a 2 dp line in `ink`, current value top-right, the bottom and top of the
  axis as labels at the left). The axis spans at least 15 % of the signal's display range and ends
  on whole 1-2-5 steps ("78" and "94", "0" and "9000"), so a coolant temperature that moves 2 °C
  reads as the small change it is; it stays inside the display range while the data does.
- **Track:** see below.

The editor shows the page as a scaled preview; tapping a cell opens a list of supported signals
(72 dp rows) and the widget type. Add page, delete page (confirm), move page left/right. Changes
apply immediately; pages are stored as JSON in settings.

As built: the editor has the page list on the left (320 dp, 72 dp rows, move left/right and
add/delete cells under it) and the sketch on the right, sized to the room that is left. A page has
no name of its own: it is called after its template ("Main", "Grid", "Track"), numbered when the
template occurs more than once ("Grid 2"). Up to 12 pages; the last page cannot be deleted. A grid
page has a size choice above the sketch (72 dp cells separated by rules, the chosen one filled),
the track page a shift point stepper (100 rpm steps, 3 000 to 11 000) in the 72 dp −/value/+ cells
of the Warnings and Display steppers. The signal list shows what the car supports, or everything with a note while not
connected. A stored signal this version does not know shows "--" until the user picks another. All
numbers of one grid page share one size, so "0.0" and "13.9" read at the same height. Peak hold is
kept per session only.

### Track page

For track days. RPM as the full-height numeral on the left two thirds, the RPM bar across the top,
coolant (`primaryValue`) and speed on the right, and **peak hold** below them: max RPM and max
speed since the last reset, with a 72 dp "Reset peaks" cell. **Shift light:** from the shift
point (default 6 200 rpm, set in SETUP > Dashboard; never used past the end of the car's rpm scale) the whole RPM bar and a 12 dp frame around
the screen flash `danger` at 4 Hz. This is the one place the app flashes: on a track it is the
convention and the point, and it only happens above the user's own shift point. The page keeps a
12 dp margin for the frame also while it is dark, so the flash never covers a value and nothing
moves when it starts.

### Startup (SETUP > Startup)

Rows: **Start with the head unit** (boot), **Reconnect when the unit wakes up** (ACC/screen-on
broadcasts while the service runs), **Open the dashboard on start** (needs "display over other
apps"; the row explains and links to the permission screen when it is missing). Below that the
ACC broadcast lists, one for **Wake (ignition on)** and one for **Sleep (ignition off)** (action
strings in `mono`, one per 72 dp row, remove cell) with the defaults from
docs/research/headunit-autostart.md, and per action the last time it was received ("never" until it
fires) so the user can see which one their unit sends. A sleep signal also finishes the running
trip at once (the unit may stop the app seconds later), whether or not reconnecting is on. Under the lists one text field with **Add as
wake** and **Add as sleep** (the direction is not in the action's name); a problem with the text
(empty, too long, characters other than letters, digits, dots and underscores, duplicate) shows as
a `danger` line above the field, because below it is off screen while the keyboard is open. Then
**Reset to defaults**. Then **Keep the app alive**: the detected platform and its instructions
(FYT/UIS: factory settings > sleep whitelist; MTCD/MTCE; other) and two buttons, the battery
optimization exemption and "display over other apps".

## Phase 6

### GPS status (bottom bar)

The status block on the right of the bottom bar gains a GPS mark before the link status: "GPS"
in `fieldLabel`, `ok` when there is a fix (with the satellite count in `caption`), `inkMuted`
while searching, `inkFaint` and struck through when location is off or not permitted. Tapping it
opens SETUP > GPS & Tracks. It is information first, so it stays small.

### Trips page (replaces the phase 4 layout)

Two columns, like before, but the left column is now the **session**:

- **Not recording:** "Start trip" (white fill, 72 dp) and "Start lap session" (outline) with the
  track to use (last used track, or "Choose a track"); the auto-record switch rows ("When the
  engine runs", "When the car moves (no OBD needed)"). Under them **Keep trips**: a `title`, a
  `caption` hint ("How many of the newest trips to keep. When a trip ends, older ones are deleted with
  their PC files. Lap sessions are always kept and not counted.") and a 72 dp stepper (the Warnings
  stepper) through 50, 100, 250, 500 (default 500). Stepping deletes nothing by itself; the limit is
  applied when a trip ends and once at app start, oldest first, never the trip being recorded.
- **Recording a trip:** live statistics as a 2 x 3 grid of fields like the dashboard field row:
  TIME, DISTANCE, AVG KM/H, MAX KM/H, MAX RPM, FUEL (estimate, `caption` "est."). Below, side by
  side as 72 dp outline cells: "Stop" and "Start lap timing". The second turns the trip that is
  already running (typically one the engine started) into a lap session, without a new file: the
  panel switches to the lap session below, timed on the active track if one is set, otherwise
  starting with "Set start/finish here".
- **Lap session:** CURRENT LAP as the hero numeral (`primaryValue`, `m:ss.s`), under it LAST and
  BEST (`fieldValue`) and DELTA to best (`fieldValue`, `ok` when faster, `danger` when slower,
  with a sign), then the lap count and the sector splits of the current lap ("S1  32.1" per sector
  gate). Up to three splits sit on one line in `body`; with more they go compact: `caption`, four to
  a line in equal columns, wrapping onto further lines, so a split is never cut off or overlapped
  (20 sectors make five lines). While no
  start/finish line exists: "Set start/finish here" (white fill) with the hint "Drive across the
  line at a steady speed and tap when you pass it." It needs a GPS fix with accuracy ≤ 15 m;
  otherwise the button explains why it is disabled. Once the line exists, "Add sector here" and
  "Stop" sit side by side (72 dp outline cells), with the reason under them while the lines cannot
  be set. A new sector line keeps the lap clock running; only a new start/finish line restarts lap
  timing. Sector lines are kept in driving order, not in the order they were added: a line set
  earlier in the lap than an existing one goes before it. Its place is measured on the latest
  complete lap of the running trip, or on the lap so far (the new line is the head of it, lines not
  reached yet lie after it); with no lap to measure on it is appended, and the lines are put in
  order as soon as a lap is complete, and when a stored track becomes active (against its newest
  recorded trip with a complete lap). The order is stored with the track.
- **Active track:** the track lap timing runs for. A lap session makes its track the active one
  only while it runs: when its trip ends the track is released, so the next trip (also an automatic
  one) is a plain trip, and the idle panel offers the track again as the last used one. The release
  belongs to the recorder in the service, not to this screen: it happens however the trip ends (Stop,
  ignition off, the engine or link going away, the service stopping, or recovery of a trip cut off by
  a power loss) whether or not the TRIPS page exists. USE under
  GPS & Tracks makes a track active outside a session; ACTIVE there switches it off again.
- The right column keeps the trip list (newest first): date, duration, distance, and for lap
  sessions a second line in `caption` with the track name, lap count and best lap. The phase 4
  max RPM column is gone: the list is narrower next to the session panel, and max rpm is in the
  trip detail. Tapping a row opens the trip detail.

### Trip detail

A full page (back arrow cell top-left, 72 dp) with three parts:

- **Top strip:** date, track (if any), and the actions "Export for PC" (opens a choice of VBO,
  GPX, CSV, all as 72 dp cells; shows the file names when done, which the Wi-Fi export and USB
  copy then offer) and "Delete".
- **Left:** the route as a line (2 dp, `ink`) on `surface`, scaled to fit, north up, no map tiles
  (offline). Fixes the lap rules reject as jumps are left out of the route, and the scale uses the
  central 99 % of the positions (per axis), so one stray GPS fix cannot shrink the route to a dot;
  the field clips what lies outside it. Start/finish and sector gates as short `accent` bars; the selected lap's part in
  `accent`, the rest in `inkFaint`.
- **Right** (scrolls on its own): a statistics table (TripStats) and, for lap sessions, the lap
  table: lap number, lap time, delta to best, max speed, max RPM, full-throttle %, in tabular
  slots; the best lap marked with `ok` text "BEST", a lap that cannot be trusted (GPS dropout or a
  jump in the positions during the lap) with `inkMuted` "NO GPS"; such a lap is never the best.
  The best lap is selected when the page opens (the first lap when none is valid). Selecting a lap
  highlights it on the route and shows its sector times ("S1  0:31.234", four to a line, wrapping
  onto further lines when there are more). Under the tables the graph of the selected
  lap, in the style of the dashboard Graph cell: two plots stacked, KM/H above RPM, each with its
  own axis labels and "MAX" value top-right, sharing one time axis from the start crossing to the
  lap time, whose two ends are captioned under the plots. Two plots instead of one with two
  scales: a single plot would need a second axis, which reads badly at this size.

### GPS & Tracks (SETUP > GPS & Tracks)

On top the location rows: GPS status (fix, satellites, measured rate), "Allow location" when the
permission is missing, "Use the simulated circuit" (only with the simulator connection). Then a
list of saved tracks (name, length if known, number of sessions) with rename (text field, only
when parked: it is a keyboard) and delete (confirm). Sector gates are added from a lap session
("Add sector here") the same way as the start/finish line.

### Unsupported adapters (SETUP > Connection)

When the link ends in "unsupported adapter", the status block shows the reason in `body` and,
below it, "Adapters that work" as a short list (from the README's supported list) instead of the
reconnect countdown. In the device lists, devices whose name matches a known app-locked dongle get
a `caution` "May not work" caption; they stay selectable.

## Phase 7

### First-run setup (wizard)

Shown once on first launch, again from SETUP > Help > Run setup again. One screen per step; a 72 dp
header row ("Step 3 of 6" in `fieldLabel`, as text, and a "Skip setup" cell) and a 72 dp footer (Back
and one filled Next cell) frame it, the content between scrolls. From 840 dp wide the content is two
halves side by side (instructions left, list or choices right), narrower it stacks. Steps: Welcome,
Adapter (Bluetooth, Bluetooth LE, Wi-Fi, simulator), Connect (not for the simulator, so the count
becomes "of 5"), Test, Preferences (units, red line, theme, record trips), Done. Runtime permissions are asked
only where needed, with a one-line reason above the button that opens the system dialog. The Test
step shows the connection states as they happen, then protocol, adapter, battery and the number of
sensors, or one specific fix per failure kind with Retry and Choose another adapter. The wizard
reuses ConnectionViewModel and DisplayViewModel; it has no connection code of its own.

### Dashboard without an adapter

While no adapter is chosen (the simulator counts as one) DASH shows a centred invitation: "Connect
your adapter to see live data", a filled "Set up adapter" and an outlined "Try the simulator".

### Help and About (SETUP)

Help: a topic list (280 dp) and the chosen topic. Troubleshooting is a list of questions that open on
a tap; the others read top to bottom, headings in `fieldLabel`. All text is string resources, offline.
About: version, privacy and what each permission is for, open-source licenses with the Apache License
2.0 text on a tap, and the crash reports (latest, Export crash log, Clear).

### Units

SETUP > Display > Units: Metric (default) or Imperial, applied at once everywhere a value is shown or
edited: km/h and mph, km and mi, °C and °F, kPa and psi, L and US gal, L/h and gal/h, and fuel
consumption as L/100 km or mpg. RPM, %, V, g/s and degrees never change. Imperial pressure and fuel
get one more decimal. The speedometers run 0 to 260 km/h or 0 to 160 mph (ticks every 10, numerals
every 40); the coolant dial 40 to 130 °C or 100 to 270 °F. Warning limits are stored in °C and
stepped in 1 °F or 1 °C. Stored values and every export (VBO, GPX, CSV) stay metric.

## Screen sizes

The app runs on head units from 1024 x 600 dp up to 1920 x 720 dp, on tablets, and on phones held
in landscape (640 x 360 dp and up) in a car mount. Everything above is the design for the reference
unit, 1280 x 720 dp. The other screens are the same design, scaled, with a few layouts that change.

### Tiers

There are three **size tiers**, chosen once from the real height of the window (the system bars are
hidden, so it is the whole display; the keyboard cannot change it). The selection is
`sizeTierOf` in `ui/theme/ScreenSize.kt`; the one place that applies it is `ScreenSizeHost`.

The whole UI is laid out on a canvas as tall as the reference unit, and a smaller screen draws that
canvas at the tier's **scale**: touch targets, bars, padding, column widths and type shrink by the
same factor, so the proportions, the grid and the look stay exactly as designed. The type is then
enlarged by the tier's **text boost**, because text cannot shrink as far as the controls and stay
readable at arm's length. The user's own font scale is kept on top. A layout that needs to know the
width asks for the canvas width (real width divided by the scale), so a 640 dp phone is an 853 dp
canvas and a 914 dp phone a 1 219 dp one.

| Tier | Real height | Scale | Text boost | Typical screens |
|---|---|---|---|---|
| COMPACT | under 480 dp | 0.75 | 1.12 | phones in landscape: 640 x 360, 914 x 411 |
| MEDIUM | 480 to 659 dp | 0.85 | 1.0 | small head unit 1024 x 600 |
| EXPANDED | 660 dp and up | 1.0 | 1.0 | 1280 x 720, 1920 x 720, tablets 1333 x 800 |

The edge between MEDIUM and EXPANDED sits below 720 so a 1280 x 720 unit with a thin permanent
system bar is still the reference design.

What the tokens come to on the screen (token value times scale; text times scale and boost):

| Token | COMPACT | MEDIUM | EXPANDED |
|---|---|---|---|
| Touch target, bottom bar (`touchMin`) | 54 dp | 61 dp | 72 dp |
| List row, table header (`listRowMin`, `tableHeaderHeight`) | 42 dp | 48 dp | 56 dp |
| Section header (`sectionHeaderHeight`) | 66 dp | 75 dp | 88 dp |
| Field padding, horizontal / vertical | 12 / 9 dp | 13.6 / 10.2 dp | 16 / 12 dp |
| `body` / `caption` / `fieldLabel` | 16.8 / 13.4 / 13.4 sp | 17 / 13.6 / 13.6 sp | 20 / 16 / 16 sp |
| `title` / `rowValue` | 20.2 / 23.5 sp | 20.4 / 23.8 sp | 24 / 28 sp |
| Dashboard RPM bar, block with its scale | 48 dp, 78 dp | 82 dp, 119 dp | 96 dp, 140 dp |
| Dashboard row of small fields | 66 dp | 88 dp | 104 dp |

A touch target never drops below 48 dp, and body text never below 16 sp (both are tested). The
big numerals (`heroValue`, `primaryValue`, `fieldValue`) are not in the table: they fit their
field, as before, and on a short screen they simply have the room the other tokens leave them.
The RPM bar and the field row are the two sizes chosen per tier on top of the scale (`dashMetricsOf`
in `ui/ScreenLayout.kt`): on a phone the bar is 64 canvas dp instead of 96 and the field row 88
instead of 104, which gives the three big numerals about a third more height. Speed and coolant
share one text size on every screen.

### What changes with the width

The rules are pure functions in `ui/ScreenLayout.kt` (tested), in canvas dp:

| Rule | Below | From |
|---|---|---|
| SETUP: menu beside the section | the menu and one section take turns (below 1 000) | 240 dp menu beside the section |
| Sensors table | one column | two halves (1 000) |
| Link status in the bottom bar | lamp, GPS and request rate only | protocol name too (1 000) |
| Dashboard small fields | two rows of three if a field would be under 130 dp wide | one row of six |
| Two-page text (wizard, About) | stacked | two halves (840) |
| Warnings row | the steppers get their own line (section under 960) | one line |
| Car section | red line and make list in one scrolling column (section under 900) | two columns, red line and engine notes 440 dp (45 % of the section, never under 400 dp) beside the make list |
| Lap table in trip detail | five columns, no max rpm or full-throttle share (table under 680) | seven |
| Diagnostics readiness and freeze frame | one cell per row (panel under 520) | two |
| Side columns (adapter list, themes, pages, help topics, session, route) | take 32 to 50 % of the width, never under a minimum | the design width (400, 440, 320, 280, 480, 500 dp) |
| Running text | | never wider than 800 dp (one column wizard steps 960 dp, centred) |
| Trips list | the date column takes what the numbers leave | the date column stops at 480 dp, so duration and distance follow the date; the rest of the row lies before the delete cell |
| Trip detail | route column as above | the statistics and lap tables stop at 880 dp, the route takes the rest |

On a phone (a canvas lower than 600 dp) the buttons of a running trip or lap session (Stop, Start lap
timing, Add sector here) sit at the bottom of the TRIPS session column and do not scroll, and the lap
timer rows are lower (hero 112 dp, fields 80 dp), so the driver never scrolls for Stop
(`areSessionActionsPinned`). On the smallest phone SETUP is a menu of two columns of rows; a section opens with a back strip
(a full-height 72 dp cell with an arrow) along its left edge, and the back key or tapping SETUP in
the bottom bar returns to the menu. The wizard has no header on a phone: "Step n of m" and "Skip
setup" join the footer row. The Trips list writes the distance unit once in the header ("DIST KM")
instead of in every row. On the Track page coolant and speed sit side by side, not stacked.
Dialogs never exceed the screen: title and buttons stay, the text between them scrolls.

### Safe areas and the keyboard

The app hides the system bars. The root keeps clear of a display cutout (`WindowInsets.displayCutout`),
so nothing is drawn under a notch in landscape. On a phone the keyboard covers most of a 360 dp
screen; while it is open the bottom bar, the warning banner, the wizard footer and the status above
the Connection fields are hidden (`LocalIsKeyboardCrowding`), so the field being edited stays in view.

### Checked sizes

Every screen was checked on the emulator at 640 x 360 (1920 x 1080 at 480 dpi), 914 x 411
(2400 x 1080 at 420), 1024 x 600, 1280 x 720, 1333 x 800 (2000 x 1200 at 240) and 1920 x 720.
Screenshots: `docs/screenshots/sizes/<screen>-<size>.png`.

## Writing

English, plain verbs. Buttons say what happens ("Connect", "Export log"). Errors say what went
wrong and what to do next ("No adapter selected. Choose one under Bluetooth LE."). Empty states
invite the next step. Dashboard labels use the short race-display vocabulary (WATER, INTAKE,
THROTTLE, LOAD, IGN, BATT, MAF).
