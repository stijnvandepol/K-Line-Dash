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
3. **Colour means state.** White and greys carry all normal content. Amber = caution (VVTL-i cam
   zone, reconnecting), red = danger (redline, failed). Green only for "connected". A red pixel
   always means something.
4. **Numbers never move.** Tabular figures, right-aligned in fixed-width slots, units outside the
   slot. A stale value is dimmed, a missing one shows "--" in the same slot. No thousands
   separator: "8175", not "8,175" or "8 175" (a gap reads as two numbers at a glance). Decimal
   point always, whatever the system locale. Flags (the MIL) read "On"/"Off", not 1/0.
5. **Big, forgiving targets.** Every control is at least 72 dp high; no precision needed while
   the car moves.

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
| `caution` | `#FFB000` | VVTL-i zone segments (from 6 200 rpm), reconnecting, values nearing a limit |
| `danger` | `#FF2D2D` | Redline segments (from 7 800 rpm), failed, over-limit values |
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
- **RPM bar:** 45 segments of 200 rpm (0 to 9 000), 2 dp gaps, full width, 96 dp high. Lit
  segments up to the current RPM: white below 6 200, `caution` 6 200 to 7 800, `danger` from
  7 800. Unlit segments are `surface` (#1C1C1C, clearly visible on black but quiet), with the
  zone colour at 25 % opacity on top for the amber and red zones, so the whole bar and its zones
  keep the screen's structure at idle and with the engine off. A stale reading draws the lit
  segments in `inkFaint`. Scale numbers every 1 000 rpm below the bar in `fieldLabel`/`inkMuted`,
  left-aligned to their segment (the 9 right-aligned to the end of the bar).
- **Bar motion:** the lit length is interpolated between readings with a linear tween as long
  as the measured RPM update interval (clamped 100 to 1 000 ms), so the bar sweeps instead of
  jumping. No other animation on the dashboard.
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
list): **Connection**, **Warnings**, **Debug** (and in phase 5 Display and Startup). The right
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

Switch rows are a `body` label and the square toggle, 72 dp, no second line: the toggle shows
the state. Buttons are rectangular, at least 72 dp high, text in `fieldLabel` style.

### Debug

Two columns, grouped by what they act on:

- **Left two thirds, the log:** monospace on `background`, TX in `ink`, RX in `inkMuted`, errors
  in `danger`. It starts 8 dp below the top and is a whole number of lines high, so following
  new messages never shows half a line at the top edge. Along its bottom a toolbar of 72 dp cells
  separated by rules: Pause/Resume log, Clear log, Export log, and the Auto-scroll switch (two
  cells wide). An export result shows for a few seconds just above the toolbar.
- **Right third, the adapter:** "Adapter" with ELM version, protocol, whether the saved protocol
  was used, and unsupported commands (label `inkMuted`, value `ink`, "--" while not connected so
  nothing below moves). The battery voltage is not repeated here; it is live on the dashboard.
  Then "Command": the input field with Send, and the quick commands as a grid of 72 dp cells.
  A reply appears under the grid. Everything fits in 720 dp without scrolling.

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

File format and validation rules: `docs/themes.md`. The token values of the built-in themes are the
files in `app/src/main/assets/themes/`.

### Dashboard pages (SETUP > Dashboard)

The dashboard becomes a set of pages, swiped horizontally. There are no page dots; after a swipe
the page name shows for 1 s at the top in `fieldLabel`. Page templates:

- **Main:** the layout above; the user picks the three primary signals and the six field-row
  signals.
- **Grid:** 2 x 2, 3 x 2 or 3 x 3 cells; each cell is a **Number** (label, unit, big value) or a
  **Graph** (last 60 s as a 2 dp line in `ink`, min/max labels, current value top-right).
- **Track:** see below.

The editor shows the page as a scaled preview; tapping a cell opens a list of supported signals
(72 dp rows) and the widget type. Add page, delete page (confirm), move page left/right. Changes
apply immediately; pages are stored as JSON in settings.

As built: the editor has the page list on the left (320 dp, 72 dp rows, move left/right and
add/delete cells under it) and the sketch on the right, sized to the room that is left. A page has
no name of its own: it is called after its template ("Main", "Grid", "Track"), numbered when the
template occurs more than once ("Grid 2"). Up to 12 pages; the last page cannot be deleted. A grid
page has a size choice above the sketch, the track page a shift point stepper (100 rpm steps,
4 000 to 9 000). The signal list shows what the car supports, or everything with a note while not
connected. A stored signal this version does not know shows "--" until the user picks another. All
numbers of one grid page share one size, so "0.0" and "13.9" read at the same height. Peak hold is
kept per session only.

### Track page

For track days. RPM as the full-height numeral on the left two thirds, the RPM bar across the top,
coolant (`primaryValue`) and speed on the right, and **peak hold** below them: max RPM and max
speed since the last reset, with a 72 dp "Reset peaks" cell. **Shift light:** from the shift
point (default 7 800 rpm, set in SETUP > Dashboard) the whole RPM bar and a 12 dp frame around
the screen flash `danger` at 4 Hz. This is the one place the app flashes: on a track it is the
convention and the point, and it only happens above the user's own shift point.

### Startup (SETUP > Startup)

Rows: **Start with the head unit** (boot), **Reconnect when the unit wakes up** (ACC/screen-on
broadcasts while the service runs), **Open the dashboard on start** (needs "display over other
apps"; the row explains and links to the permission screen when it is missing). Below that the
ACC broadcast lists, one for **Wake (ignition on)** and one for **Sleep (ignition off)** (action
strings in `mono`, one per 72 dp row, remove cell) with the defaults from
docs/research/headunit-autostart.md, and per action the last time it was received ("never" until it
fires) so the user can see which one their unit sends. Under the lists one text field with **Add as
wake** and **Add as sleep** (the direction is not in the action's name); a problem with the text
(empty, too long, characters other than letters, digits, dots and underscores, duplicate) shows as
a `danger` line above the field, because below it is off screen while the keyboard is open. Then
**Reset to defaults**. Then **Keep the app alive**: the detected platform and its instructions
(FYT/UIS: factory settings > sleep whitelist; MTCD/MTCE; other) and two buttons, the battery
optimization exemption and "display over other apps".

## Writing

English, plain verbs. Buttons say what happens ("Connect", "Export log"). Errors say what went
wrong and what to do next ("No adapter selected. Choose one under Bluetooth LE."). Empty states
invite the next step. Dashboard labels use the short race-display vocabulary (WATER, INTAKE,
THROTTLE, LOAD, IGN, BATT, MAF).
