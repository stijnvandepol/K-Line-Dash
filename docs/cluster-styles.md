# Cluster styles and brand-inspired themes

A theme is no longer only colours: it also brings a **cluster style**, the way the main page draws
engine speed, road speed and coolant. The user can keep a theme's own cluster or pick another one
(SETUP > Display > Cluster). Pages other than Main, the field row, the bottom bar and the warning
overlay look the same in every style, so the app stays recognisable and the driver always finds
the same controls in the same place.

Themes are inspired by well-known cars but carry **neutral names, no brand names and no logos**
(user decision 2026-10-22). Descriptions in the app say what the theme looks like ("Yellow centre
tachometer, Italian red"), never which brand it imitates.

## Where the cluster lives

On the Main page the cluster replaces the RPM bar block and the primary row: the area between the
top edge and the field row (1280 x 544 dp on a 1280 x 720 screen). The field row (six values) and
the bottom bar stay. Each style must work from 1024 x 600 up to 2000 x 1200; dials scale with the
smaller of width and height, never stretch.

## Styles

| Id | Name in the app | Look |
|---|---|---|
| `bar` | Race bar | The existing segmented bar (200 rpm per segment, over the car's rpm scale) with RPM / KM/H / WATER numerals. |
| `twin` | Twin dials | Tachometer left, speedometer right, round, about 500 dp across, 250 degree sweep from bottom-left; between them a slim coolant dial (90 degree arc) with the value under it. Digital RPM small inside the tach, digital speed inside the speedo. |
| `center` | Center tach | One large tachometer in the middle (about 520 dp), digital speed large on its left, coolant and battery stacked on its right. |
| `arc` | Digital arc | A row of 10 shift lights across the top (green, then amber, then red, lighting up over the last 1 500 rpm before the shift point); below it a 200 degree segmented arc tachometer with the speed as the hero numeral in its centre and the RPM smaller under it. |
| `virtual` | Virtual cockpit | Two thin ring gauges cut by the screen's left and right edges (tach left, speed right) with large light-weight numerals in their centres; the middle shows four values as a calm list. |

Shared dial rules:

- Scale: the tach runs from 0 to the end of the car's rpm scale (the next whole 1 000 rpm at least
  1 000 above the redline set in the car settings: 6 000 for a 4 500 redline, 8 000 for 6 500, 9 000 for
  8 000, 11 000 for 10 000; major ticks every 1 000 with numerals, minor every 500), speedometer 0 to
  260 km/h (major every 20, numerals every 40). Redline zone from the redline, caution zone from
  1 000 rpm below it, drawn as an arc band inside the ticks in the theme's caution/danger colours.
  The numerals of those zones take the same colours.
- Needles move with the same interpolation as the RPM bar (tween as long as the measured update
  interval, linear) so they glide instead of jumping; the needle is drawn in the draw phase only.
- A stale value parks nothing: the needle stays where it was and the digital value dims.
- Engine off: needles at zero, digital RPM "0".
- No needle bounce, no start-up sweep animation (a driver glances, it must never look busy).

## Theme tokens added

Optional keys in the theme JSON (see docs/themes.md), with defaults derived from the base tokens:

| Key | Default | Use |
|---|---|---|
| `cluster` | `bar` | The theme's own cluster style id (a key of the file, next to `name`). |
| `face` | `background` | Dial face fill. |
| `faceInk` | `ink` | Numerals and ticks on the dial face (checked for 4.5:1 on `face`). |
| `ring` | `rule` | Dial rim and the arc track. |
| `needle` | `accent` | Needles and the lit arc of the `arc` style. |
| `numerals` | `condensed` | `condensed` (Roboto Condensed), `serif` (Noto Serif, system) or `light` (Roboto Light); a key of the file, next to `name`. Used for the numerals on the dials and for the digital values of the cluster. The side fields of the `center` style are ordinary dashboard fields in the same face. |

`face`, `faceInk`, `ring` and `needle` live in `colors`. The tokens are in `KLineColors`
(`ui/theme/Tokens.kt`), the cluster style and numerals in `ClusterLook`; `LocalClusterLook` carries
them to the main page. `docs/themes.md` has the file format and the validation.

## Built-in themes

| Theme | Inspired by (not shown in the app) | background / ink / accent | face / faceInk / needle | Cluster | Numerals |
|---|---|---|---|---|---|
| Race | motorsport data display | `#000000` / `#FFFFFF` / `#FFFFFF` | default | `bar` | condensed |
| Minimal | quiet night display | as defined | default | `bar` | condensed |
| Retro | 80s VFD cluster | as defined | default | `bar` | condensed |
| Day | bright sun | as defined | default | `bar` | condensed |
| **Rosso** | Italian GT: yellow centre tach | `#000000` / `#FFFFFF` / `#E3001B` | `#FFD200` / `#000000` / `#E3001B` | `center` | condensed |
| **R-Spec** | Japanese hot hatch, red on black, digital | `#000000` / `#FFFFFF` / `#E60012` | `#000000` / `#FFFFFF` / `#E60012` | `arc` | condensed |
| **Autobahn** | German sports saloon, black dials, orange-red needles | `#050608` / `#F2F2F2` / `#FF5A1F` | `#0A0B0D` / `#F2F2F2` / `#FF5A1F` | `twin` | condensed |
| **Virtual** | German digital cockpit, thin and grey | `#000000` / `#FFFFFF` / `#D4001A` | `#000000` / `#FFFFFF` / `#D4001A` | `virtual` | light |
| **Classic** | oldtimer: cream dials, chrome rims | `#1B1712` / `#EFE6D2` / `#CC1520` | `#EFE6D2` / `#1A1A1A` / `#CC1520` | `twin` | serif |

Classic is the one style that suggests materials: the chrome rim is three concentric 2 dp strokes
(light, mid, dark grey) rather than a gradient, and the cream face sits on the dark "dashboard"
background. Every theme must pass the existing contrast rule (`inkMuted` on `background` 4.5:1)
plus `faceInk` on `face` 4.5:1.

Two colours differ from the table above, for contrast, and the others are chosen to go with it:

- **Classic**: accent, needle and `danger` are `#CC1520`, not `#C1121F`. The specified red measures
  2.9:1 on the Classic background, below the 3:1 every built-in accent must reach.
- **Rosso**: `caution` is `#D45500` (burnt orange) and `danger` `#E3001B`. On the yellow face the
  usual amber (`#FFB000`) would vanish, and the burnt orange still reads 5:1 on the black screen.
  Classic's `caution` is `#C77700` for the same reason (cream face).
- Surfaces and greys of the new themes: Rosso and R-Spec keep Race's; Autobahn `#16181D` /
  `#23262B` / `#8E9299` / `#50545B` (surface, rule, inkMuted, inkFaint) with a `#3C4047` ring;
  Virtual `#151515` / `#262626` / `#9A9A9A` / `#555555` with a `#4A4A4A` ring; Classic `#2C261D` /
  `#3A3226` / `#A89F8A` / `#6B6354` with a `#B5B5B5` ring (which makes it chrome).
- Green for the shift lights of the `arc` style is the theme's `ok` colour.

## SETUP > Display additions

- The theme list shows each theme's swatches and a small cluster preview.
- A **Cluster** row with the five styles as 72 dp cells, "Theme default" first. The choice is one
  global override (simpler to understand than one per theme); "Theme default" follows the active
  theme, so day/night switching between, say, Autobahn and Day also switches the cluster.

## How the styles are drawn

Everything lives in `ui/cluster/`. Each style is one composable that fills the cluster area and
sizes everything from the area (twin dials about 495 dp across and the centre tach about 520 dp on
1280 x 544; the arc and the rings as big as fit), so they scale from 1024 x 600 to 2000 x 1200. The
numbers (angle for a value, tick positions, how many shift lights burn, the layout of each style)
are plain functions in `ClusterGeometry.kt` with unit tests.

- Every dial is laid out in "reference dp" of a 500 dp dial and scaled, so it looks the same at any
  size. The static parts (face, rim, bands, ticks, numerals, captions) are drawn once into their
  own layer with `drawWithCache`; the needle is another layer that reads the glided value in the
  draw phase, so a moving needle redraws one path and recomposes nothing. The digital values are
  the only text that recomposes, and only when their text changes.
- The glide is the one of the RPM bar (`rememberGlide`): a linear tween as long as the measured
  update interval. It starts at the current reading, so a page that scrolls back into view does not
  sweep up from 0. A stale reading changes nothing: the needle stays, the digital value dims.
  The coolant pointer reads the latest value directly (it changes slowly), and the engine off
  reading 0 puts every needle at 0.
- **Twin dials**: tachometer left (0 to the end of the rpm scale in thousands with "x1000", 250
  degrees from the bottom left, minor ticks every 500, caution and redline bands from the caution
  rpm and the redline), speedometer right (0 to 260,
  ticks every 10, major every 20, numerals every 40, no bands), the digital values under the hub.
  Between them the slim coolant dial: a 90 degree arc across the top, "C" left and "H" right, its
  bands at the caution and danger limits of SETUP > Warnings, the value and "WATER °C" under it. The
  three are centred as one group.
- **Center tach**: the dial with the rpm in it; speed, coolant and battery are the dashboard's own
  fields (label, unit, value at the bottom) left and right of it, separated by rules.
- **Digital arc**: ten shift lights across the top, burning one by one over the last 1 500 rpm
  before the shift point (4 green, 3 amber, 3 red; all ten at the shift point). The shift point is
  that of the first Track page of the layout (6 200 when there is none), never past the end of the
  rpm scale. Below them one segment per 200 rpm of the scale (30 to 55) over 200 degrees (lit like
  the RPM bar: the theme's needle colour, amber from the caution rpm, red from the redline), the speed as the hero numeral in the middle and the rpm under it. The coolant and the
  battery stand in the two bottom corners, where the arc leaves room (the spec does not show the
  coolant in this style, and a driver needs it).
- **Virtual cockpit**: two rings (tach left filling counter-clockwise from the bottom, speed right
  mirrored) centred 0.64 radius in from their screen edge, so the edge cuts them; the rpm and the
  speed in light numerals in their middles. The list in the middle holds the main page's third
  primary field (the coolant) and its first three small fields. The field row below shows the same
  small fields, so those three appear twice.
- A cluster style other than `bar` makes the Main page ask the poller for rpm, speed, coolant and
  battery on top of its own fields, whatever its primary fields are set to.
- The theme list under SETUP > Display shows a small still sketch of each theme's cluster
  (`ClusterPreview`): a few rectangles, circles and arcs, nothing like the real renderers.
