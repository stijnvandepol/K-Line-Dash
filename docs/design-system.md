# Design system

The UI is read by a driver at arm's length, in glances of under a second, by day and by night.
Every rule below serves that. Phase 5 adds more themes as JSON; they only swap the colour tokens,
never the grid, type scale or the meaning of colour.

## Principles

1. **One glance, one answer.** The dashboard answers "how fast is the engine turning, and is
   anything wrong" without reading. RPM is the hero; everything else is quiet.
2. **Colour means state, nothing else.** Neutral greys and white carry all normal content.
   Green = ok/connected, amber = caution, red = danger/redline. No decorative accent colour, so a
   red element always means something.
3. **Numbers never jitter.** Tabular figures everywhere, fixed-width value slots, needles
   interpolated between readings. A value that is stale (older than a few seconds) is dimmed, not
   hidden, so the layout never jumps.
4. **Big, forgiving touch targets.** At least 72 dp, 16 dp apart. No interaction needs precision
   while the car moves.
5. **Quiet structure.** No card-per-value shadows or gradients. Panels are flat tonal steps of the
   background; spacing does the grouping.

## Colour tokens (default theme "Dark")

| Token | Hex | Use |
|---|---|---|
| `background` | `#101418` | Screen background (graphite, less halation than pure black on IPS panels) |
| `panel` | `#181E24` | Grouped areas, nav rail, tiles |
| `panelRaised` | `#222A32` | Selected nav item, pressed state, input fields |
| `ink` | `#E9EDF0` | Primary text, values, gauge needle |
| `inkMuted` | `#8E9AA6` | Labels, units, secondary text (contrast ≥ 4.5:1 on `panel`) |
| `line` | `#2C353E` | Gauge track, dividers, outlines |
| `ok` | `#45C486` | Connected, values in normal range |
| `caution` | `#F2B33D` | Reconnecting, values approaching a limit |
| `danger` | `#F0524F` | Failed, redline, over-limit values |

## Typography

System fonts only (no font files to ship): **Roboto Condensed** (`sans-serif-condensed`) for
numbers, **Roboto** for text. All numeric styles set `fontFeatureSettings = "tnum"`.

| Style | Size / weight | Use |
|---|---|---|
| `heroValue` | 104 sp / Medium, condensed | RPM inside the gauge |
| `largeValue` | 56 sp / Medium, condensed | Speed, track-mode values |
| `tileValue` | 40 sp / Medium, condensed | Tile values |
| `title` | 26 sp / Medium | Screen and section titles |
| `body` | 20 sp / Regular | Settings text, list rows |
| `rowValue` | 26 sp / Medium, condensed | Values in the sensor table (a 56 dp row cannot hold `tileValue`) |
| `label` | 16 sp / Medium | Tile labels, units, nav labels (smallest size used while driving) |
| `mono` | 15 sp / Regular, monospace | Debug log only |

Labels are sentence case. Units sit next to the value in `label` size, `inkMuted`, on the value's
baseline.

## Layout

8 dp grid. Screen margin 24 dp, gutter 16 dp, panel padding 16 dp (20 dp for the hero panel).
Nav rail is on the left (driver side on a left-hand-drive car), 104 dp wide.

```
+------+--------------------------------------------------------------+
| nav  |  +---------------------------+  +------------+------------+  |
| rail |  |                           |  | Coolant    | Intake air |  |
|      |  |        RPM gauge          |  |  88 °C     |  24 °C     |  |
| Dash |  |          3,450            |  +------------+------------+  |
| Sens |  |           rpm             |  | Throttle   | Load       |  |
| Conn |  |                           |  |  12 %      |  31 %      |  |
| Debug|  |     87 km/h               |  +------------+------------+  |
|      |  +---------------------------+  | Timing     | Battery    |  |
|      |                                 |  14 °      |  13.9 V    |  |
|      |  ● Connected     5.2 per sec.   +------------+------------+  |
+------+--------------------------------------------------------------+
```

- The gauge panel and the tile grid share top and bottom edges; the status line sits under both.
- Tile labels are left-aligned at the top, values left-aligned and baseline-aligned across a row.
- Status line (connection state + measured update rate) sits bottom-left under the gauge, in
  `body` style (20 sp: it is the only place that says whether the link is healthy); it is
  information, not a control.
- Settings and lists: left-aligned text, values right-aligned, one column of controls with a fixed
  label width so switches and fields line up.

## Gauge

- 240° sweep, start bottom-left. Track in `line`, filled arc in `ink`, redline zone in `danger`
  from 7,800 rpm (2ZZ-GE limiter ~8 200).
- Major ticks every 1,000 rpm with numbers (`label` style, muted), minor ticks every 500.
- Needle: `ink`, 4 dp, rounded cap, interpolated with a linear tween as long as the expected
  update interval so it moves continuously instead of stepping.
- No glow, no gradients, no animation other than the needle.

## Values

English, one formatter for everything (`formatNumber`, fixed `Locale.US`, whatever the head unit's
locale is): decimal point, comma as thousands separator ("13.9 V", "3,450"). A value slot has a fixed
width, so the unit beside it never moves. A value is dimmed (`inkMuted`) when it is stale and shows
"–" in the same slot when there is none. Stale means older than 5 s for FAST signals, 15 s for MEDIUM
and 45 s for SLOW ones: the poller refreshes those every 3 s and 15 s and shares one request slot per
round, so a healthy value is regularly older than 5 s.

## Writing

English, sentence case, plain verbs. Buttons say what happens ("Connect", "Export log").
Errors say what went wrong and what to do next ("No adapter chosen. Pick one under Connection.").
Empty states invite the next step ("Not connected yet. Connect under Connection.").
No all-caps labels and no "·"-joined strings.
