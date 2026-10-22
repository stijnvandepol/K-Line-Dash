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
| `bar` | Race bar | The existing 45-segment bar with RPM / KM/H / WATER numerals. |
| `twin` | Twin dials | Tachometer left, speedometer right, round, about 500 dp across, 250 degree sweep from bottom-left; between them a slim coolant dial (90 degree arc) with the value under it. Digital RPM small inside the tach, digital speed inside the speedo. |
| `center` | Centre tach | One large tachometer in the middle (about 520 dp), digital speed large on its left, coolant and battery stacked on its right. |
| `arc` | Digital arc | A row of 10 shift lights across the top (green, then amber, then red, lighting up over the last 1 500 rpm before the shift point); below it a 200 degree segmented arc tachometer with the speed as the hero numeral in its centre and the RPM smaller under it. |
| `virtual` | Virtual cockpit | Two thin ring gauges cut by the screen's left and right edges (tach left, speed right) with large light-weight numerals in their centres; the middle shows four values as a calm list. |

Shared dial rules:

- Scale: tach 0 to 9 000 (major ticks every 1 000 with numerals, minor every 500), speedometer 0 to
  260 km/h (major every 20, numerals every 40). Redline zone from 7 800 rpm, caution zone from
  6 200 rpm, drawn as an arc band inside the ticks in the theme's caution/danger colours.
- Needles move with the same interpolation as the RPM bar (tween as long as the measured update
  interval, linear) so they glide instead of jumping; the needle is drawn in the draw phase only.
- A stale value parks nothing: the needle stays where it was and the digital value dims.
- Engine off: needles at zero, digital RPM "0".
- No needle bounce, no start-up sweep animation (a driver glances, it must never look busy).

## Theme tokens added

Optional keys in the theme JSON (see docs/themes.md), with defaults derived from the base tokens:

| Key | Default | Use |
|---|---|---|
| `cluster` | `bar` | The theme's own cluster style id. |
| `face` | `background` | Dial face fill. |
| `faceInk` | `ink` | Numerals and ticks on the dial face (checked for 4.5:1 on `face`). |
| `ring` | `rule` | Dial rim and the arc track. |
| `needle` | `accent` | Needles and the lit arc of the `arc` style. |
| `numerals` | `condensed` | `condensed` (Roboto Condensed), `serif` (Noto Serif, system) or `light` (Roboto Light). |

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
| **Classic** | oldtimer: cream dials, chrome rims | `#1B1712` / `#EFE6D2` / `#C1121F` | `#EFE6D2` / `#1A1A1A` / `#C1121F` | `twin` | serif |

Classic is the one style that suggests materials: the chrome rim is three concentric 2 dp strokes
(light, mid, dark grey) rather than a gradient, and the cream face sits on the dark "dashboard"
background. Every theme must pass the existing contrast rule (`inkMuted` on `background` 4.5:1)
plus `faceInk` on `face` 4.5:1.

## SETUP > Display additions

- The theme list shows each theme's swatches and a small cluster preview.
- A **Cluster** row with the five styles as 72 dp cells, "Theme default" first. The choice is one
  global override (simpler to understand than one per theme); "Theme default" follows the active
  theme, so day/night switching between, say, Autobahn and Day also switches the cluster.
