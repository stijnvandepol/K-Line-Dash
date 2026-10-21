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
- **Values outside their normal range** switch the value colour, not the field background:
  coolant ≥ 100 °C `caution`, ≥ 105 °C `danger`; battery < 12.0 V or > 15.0 V `caution` with
  the engine running. (Configurable thresholds and alarms come in phase 4.)
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

## Writing

English, plain verbs. Buttons say what happens ("Connect", "Export log"). Errors say what went
wrong and what to do next ("No adapter selected. Choose one under Bluetooth LE."). Empty states
invite the next step. Dashboard labels use the short race-display vocabulary (WATER, INTAKE,
THROTTLE, LOAD, IGN, BATT, MAF).
