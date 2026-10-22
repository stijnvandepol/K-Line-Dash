# Themes

A theme is a JSON file that sets the colour tokens of `docs/design-system.md`. Grid, type scale,
shapes and the meaning of colour do not change. Four themes ship with the app (Race, Minimal,
Retro, Day, in `app/src/main/assets/themes/`); you can add your own.

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

Every colour is a string `#RRGGBB` (six hex digits, upper or lower case, no alpha, no short form).
Unknown keys are ignored. Status colours keep their meaning in every theme (green connected, amber
caution, red danger); a theme may only change their shade, for example a darker red on a light
background.

## Validation

A file is rejected, with the reason shown in the Display section, when:

- it is not valid JSON, or `name` or `colors` is missing, or `name` is longer than 24 characters;
- a required colour is missing ("Missing colour "caution"");
- a colour is not `#RRGGBB` ("Colour "ink" is "red": write it as #RRGGBB");
- objects or lists are nested more than 8 levels deep (a theme needs 2; the parser would run out of
  stack on a file nested thousands of levels deep);
- `inkMuted` on `background` has a contrast ratio (WCAG 2.x) below **4.5:1**, because labels and
  units are read at a glance ("Labels are too faint: inkMuted on background has a contrast of 3.2:1").

The built-in themes are checked by unit tests against the same rule, plus 3:1 for `accent`, `ok`,
`caution` and `danger` on the background.

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
