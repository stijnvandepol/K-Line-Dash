# Themes

A theme is a JSON file that sets the colour tokens of `docs/design-system.md` and, optionally, how
the main page draws its dials (`docs/cluster-styles.md`). Grid, type scale, shapes and the meaning
of colour do not change. Nine themes ship with the app (Race, Minimal, Retro, Day, Rosso, R-Spec,
Autobahn, Virtual, Classic, in `app/src/main/assets/themes/`); you can add your own. The
shipped ones are the best examples: `rosso.json` has every optional key.

## Where to put your own themes

Copy `*.json` files into the app's `themes` folder, shown under SETUP > Display > Your themes
(on most units `/storage/emulated/0/Android/data/nl.stijnvdpol.klinedash/files/themes`). The list
reloads when SETUP > Display opens, and with the **Reload themes** button. A file that is not a
valid theme is ignored and listed on that page with the reason. At most 50 files of up to 64 KB are read.

## Format

```json
{
  "name": "Sunset",
  "colors": {
    "background": "#0A0603",
    "surface": "#1F1409",
    "rule": "#34220F",
    "ink": "#FFB54A",
    "inkMuted": "#B87A2E",
    "inkFaint": "#5C3F1A",
    "ok": "#6FCB4B",
    "caution": "#FFD60A",
    "danger": "#FF3B2F",
    "accent": "#FF8A1F"
  }
}
```

A theme that brings its own dials adds a cluster style, a numerals face and the dial colours:

```json
{
  "name": "Sunset dials",
  "cluster": "twin",
  "numerals": "serif",
  "colors": {
    "...": "the ten colours above",
    "face": "#EFE6D2",
    "faceInk": "#1A1A1A",
    "ring": "#B5B5B5",
    "needle": "#CC1520"
  }
}
```

| Key | Required | Meaning |
|---|---|---|
| `name` | yes | Shown in the list; 1 to 24 characters. |
| `colors.background` | yes | Screen. |
| `colors.surface` | yes | Unlit RPM segments, input fields, disabled filled buttons. |
| `colors.rule` | yes | 1 dp grid lines and outlines. |
| `colors.ink` | yes | Values and primary text. |
| `colors.inkMuted` | yes | Labels, units, secondary text. |
| `colors.inkFaint` | yes | Stale values, disabled controls, minor marks. |
| `colors.ok` | yes | Connected lamp only. |
| `colors.caution` | yes | Amber state: VVTL-i zone, reconnecting, values nearing a limit. |
| `colors.danger` | yes | Red state: redline, failed, over-limit. |
| `colors.accent` | no | Lit RPM segments below the caution zone and the active-page marker. Defaults to `ink`. |
| `cluster` | no | How the main page draws engine speed, road speed and coolant: `bar` (the RPM bar), `twin`, `center`, `arc` or `virtual`. Defaults to `bar`. A key of the file itself, not of `colors`. |
| `numerals` | no | The typeface of the numerals on the dials: `condensed` (Roboto Condensed, the app's own), `serif` (the system serif, Noto Serif) or `light` (Roboto Light). Defaults to `condensed`. Also a key of the file itself. |
| `colors.face` | no | Dial face fill (`twin` and `center`). Defaults to `background`. |
| `colors.faceInk` | no | Ticks and numerals on the dial face. Defaults to `ink`. |
| `colors.ring` | no | Dial rim and the arc and ring tracks. Defaults to `rule`. A `ring` that stands out from `background` (3:1 or more) is drawn as a chrome rim, see below. |
| `colors.needle` | no | Needles and the lit part of the arc and ring gauges. Defaults to `accent`, and follows an accent chosen under SETUP > Display unless the theme sets it. |

`face`, `faceInk`, `ring` and `needle` and the cluster styles are explained in `docs/cluster-styles.md`.
`cluster` can be overridden for every theme at once under SETUP > Display > Cluster.

Every colour is a string `#RRGGBB` (six hex digits, upper or lower case, no alpha, no short form).
Unknown keys are ignored. Status colours keep their meaning in every theme (green connected, amber
caution, red danger); a theme may only change their shade, for example a darker red on a light
background.

## Validation

A file is rejected, with the reason shown in the Display section, when:

- it is not valid JSON, or `name` or `colors` is missing, or `name` is longer than 24 characters;
- a required colour is missing ("Missing colour "caution"");
- a colour is not `#RRGGBB` ("Colour "ink" is "red": write it as #RRGGBB");
- `cluster` or `numerals` is not one of the values above ("Unknown cluster "round": use bar, twin,
  center, arc, virtual.");
- `face` or `faceInk` is set and `faceInk` on `face` has a contrast below **4.5:1**, because the
  numerals on a dial are read at a glance too ("Dial numbers are too faint: faceInk on face has a
  contrast of 1.5:1, ..."). A theme that sets neither is not checked: its dials are drawn on the
  background in `ink`;
- objects or lists are nested more than 8 levels deep (a theme needs 2; the parser would run out of
  stack on a file nested thousands of levels deep);
- `inkMuted` on `background` has a contrast ratio (WCAG 2.x) below **4.5:1**, because labels and
  units are read at a glance ("Labels are too faint: inkMuted on background has a contrast of 3.2:1").

The built-in themes are checked by unit tests against the same rules, plus 3:1 for `accent`, `ok`,
`caution` and `danger` on the background, 3:1 for `needle` and 2.5:1 for the `caution` and `danger`
bands on `face` (a band is a wide stroke, so it may be a little quieter than text).

Pick the `caution` and `danger` colours with the face in mind: a light face needs darker bands
than the amber and red a black face takes, which is why Rosso and Classic have their own.

## Dials and the chrome rim

On the round dials (`twin` and `center`) the face is a disc of `face`, the ticks and numerals are
`faceInk`, the redline and caution bands inside the ticks are `danger` and `caution`, and the needle
is `needle` with a thin halo in the `face` colour so it stays visible where it crosses a numeral or
band of its own colour. The rim is one 5 dp line in `ring`. When `ring` has a contrast of 3:1 or
more against `background` (a light grey, as in Classic) the rim is drawn as three concentric
2 dp strokes instead, `ring` lightened, `ring` and `ring` darkened: a chrome edge without a
gradient. The `arc` and `virtual` styles have no face: they draw on the background in `ink`, with
`needle` for their lit parts.

Text on a warning banner is `background` on `caution` and `ink` on `danger`; where a theme makes
that hard to read (under 3:1), the other of the two is used instead.

## Day and night

SETUP > Display > Day and night:

- **Manual**: the theme chosen in the list, always.
- **Schedule**: the day theme from the "Day starts" time until the "Night starts" time, the night
  theme otherwise. Times move in 15-minute steps; a schedule may cross midnight (day from 14:00 to
  02:00). Checked once a minute.
- **Follow head unit**: the Android night-mode flag (`Configuration.uiMode`), which many head units
  set from the headlight input. Night theme when it is on.

In the two automatic modes the Day theme / Night theme switch above the list chooses which of the
two the list picks. The accent override (8 swatches or "Theme default") applies on top of
whichever theme is active.
