# Cascade — Design System

Companion to `SPEC.md`. That file says what to build; this one says what it looks like. Read both before writing UI code.

---

## 1. The one idea

**Colour encodes difficulty. It never decorates.**

Every alarm sits at a position in its group's sequence, and that position picks its colour from a five-stop ramp running from half-light to full sun. The first alarm is deep and cool. The last is bright and warm. A user can read a group's difficulty curve without reading a word of it.

Three rules follow from this, and they are not negotiable:

1. Ramp colours appear **only** where they mean difficulty — alarm nodes, the spine, escalation rungs, the firing screen. Never on a button, a heading, a divider, or an empty state.
2. Difficulty climbs in **lightness as well as hue**. Hue alone fails for colour-blind users and fails again on a dim screen at 6am. The first alarm's firing screen is nearly dark; the last one is full-bleed bright.
3. Chrome stays quiet. The spine on the group screen is the only ornament in the app. Everything around it is flat: no shadows, no gradients, no decorative borders.

---

## 2. Themes

Twenty themes in five families of four. **Forest is the brand and the default.** Night ships free beside it so the picker is never a one-item list. The other eighteen are the premium pack (§11).

| Family | Themes |
|---|---|
| Landscape | Forest, Tide, Moss, Olive |
| Earth | Ember, Cocoa, Canyon, Vineyard |
| After dark | Night, Ink, Dusk, Aurora |
| Stark | Eclipse, Tundra, Slate, Signal |
| Daylight | Paper, Sand, Chalk, Blush |

### 2.1 Where the values live

**All colour values are in `assets/themes.json`.** Generate the Kotlin colour table from that file — do not transcribe 300 hex values out of this document by hand. The JSON carries, per theme: `name`, `descriptor`, `tier` (`free` or `pack`), `light`, the nine surface and text tokens, a five-entry `ramp`, `onRamp5`, a three-stop `firing` gradient, and a `note` on the three themes that behave unusually.

The file also carries `default`, `free`, `families`, `status` and `statusLightOverride` at the top level.

Every theme defines the same token set, so a screen built against the tokens needs no per-theme code.

| Token | Role |
|---|---|
| `ground` | App background |
| `groundDeep` | Scrims, sheets, the dimmest surface |
| `surface` | Cards, rows, raised panels |
| `surfaceHigh` | Selected and pressed states |
| `surfaceMuted` | Disarmed group cards |
| `line` | Borders, dividers, outlined buttons |
| `textPrimary` | Body and headings |
| `textSecondary` | Supporting text |
| `textQuiet` | Timestamps, hints, disabled |
| `ramp1`–`ramp5` | Difficulty, by position in the sequence |
| `onRamp5` | Dark text that sits on top of `ramp5` |

Define these as a `CascadeColors` data class provided via `CompositionLocal`. Do not try to fit them into Material 3's `ColorScheme` — the five-stop ramp has no equivalent there, and forcing it into `primary`/`secondary`/`tertiary` breaks as soon as a theme is added.

Do not auto-switch by system dark mode. Sixteen of the twenty are dark, so the choice is aesthetic rather than functional, and the four light ones are picked deliberately.

### 2.2 The four light themes

Paper, Sand, Chalk and Blush set `"light": true`. Two things change:

1. **The surface direction inverts.** `groundDeep` and `surface` are *lighter* than `ground`, not darker. Token names describe role, not brightness, so the same code works — but do not assume "deep" means "dark".
2. **Status colours darken.** Use `statusLightOverride` instead of `status`, or they fail 4.5:1 against the light ground.

### 2.3 Contrast, and what was verified

Every theme was checked programmatically. All of these hold across all twenty:

- `textPrimary` and `textSecondary` reach 4.5:1 against `ground`; `textQuiet` reaches 3:1.
- `onRamp5` reaches 4.5:1 against `ramp5`.
- The ramp climbs monotonically in **relative luminance**, not just hue, so difficulty survives colour blindness and a dimmed screen.

Two things did **not** pass and were left deliberately. Both matter when building:

- **`ramp1` sits at 2–3:1 against `ground` in most dark themes** (as low as 1.88:1 in Vineyard). That is the point — the first alarm should not glare at 6am. It is only ever drawn as a 7–16dp dot or bar, never as text, and it always has a text label and a position beside it. **Never let a ramp colour be the only thing carrying meaning.**
- **On the light themes, `ramp4` and `ramp5` land at 2–2.9:1 against `ground`.** The lightness climb has to compress on a light ground and there is no way around it while keeping the ramp monotonic. Acceptable for a fill; never for text or a thin line. The light themes need looking at on a real screen before they ship.

If you change any colour, re-run those three checks. The monotonic one in particular caught a dip at `ramp4` in Forest, Paper and Chalk during design, which is why those three stops look slightly lighter than you might expect.

### 2.4 Firing screen gradients

Each theme's `firing` array is a three-stop radial gradient for the **first** alarm in a sequence, from 8% to 48% to the edge. The **last** alarm is full-bleed `ramp5` with `onRamp5` text. Alarms in between interpolate between the two.

Three themes behave unusually, and their `note` field says so:

- **Eclipse** ends on pure white, so its last alarm is a blank bright screen. Intentional. Honour the system brightness; do not force it up.
- **Slate** stays near-monochrome until the final stop. If it looks broken, that is the intent.
- **Signal** is tuned for maximum luminance separation rather than looks. Surface it in the accessibility settings as well as the theme picker.
## 3. Mapping alarms to ramp stops

A group rarely has exactly five alarms, so distribute across the ramp, always anchoring the first and last:

| Alarms | Stops used |
|---|---|
| 1 | `ramp5` |
| 2 | `ramp1`, `ramp5` |
| 3 | `ramp1`, `ramp3`, `ramp5` |
| 4 | `ramp1`, `ramp2`, `ramp4`, `ramp5` |
| 5 | `ramp1` … `ramp5` |
| 6+ | Distribute evenly, round to nearest stop, repeat `ramp5` for any overflow |

A single-alarm group gets `ramp5` because it *is* the last alarm — there is nothing after it to escalate to.

Put this in one pure function, `rampStopFor(position: Int, total: Int): RampStop`, and unit test it. It is used in at least four places and must agree with itself everywhere.

---

## 4. Typography

**Sora** throughout, from Google Fonts. Weights 300, 400, 500, 600. No second family.

Chosen for its numerals — the time is the hero of three screens, so it carries more weight than usual. Set times with `fontFeatureSettings = "tnum"` so digits do not jitter as they change.

| Role | Size | Weight | Tracking | Notes |
|---|---|---|---|---|
| Firing time | 92sp | 300 | −0.045em | First-alarm screen, light weight |
| Firing time, late | 68sp | 600 | −0.04em | Last-alarm screen, heavier |
| Home next-alarm | 62sp | 600 | −0.035em | |
| Screen title | 26sp | 600 | −0.015em | |
| Alarm row time | 27sp | 600 | −0.02em | Tabular |
| Card title | 16sp | 600 | normal | |
| Row label | 14–15sp | 500 | normal | |
| Body | 13sp | 400 | normal | `textSecondary` |
| Caption | 11.5–12.5sp | 400 | normal | `textQuiet` |

Sentence case everywhere. No all-caps labels, no letter-spaced eyebrows above headings.

---

## 5. Shape and spacing

Spacing scale: 4, 8, 12, 16, 18, 22, 24, 30, 34, 44. Screen gutter is 24dp.

| Radius | Value | Use |
|---|---|---|
| Small | 9–11dp | Chips, steppers, small buttons |
| Medium | 14–16dp | Rows, list items, primary buttons |
| Large | 18dp | Group cards |
| Firing | 22dp | Firing-screen buttons |
| Pill | 50% | Toggles, nodes |

Touch targets are never below 48dp, including the chevrons on alarm rows.

---

## 6. Component rules

**Group card.** Name, schedule summary, master toggle, and a miniature of the difficulty ramp — dots joined by gradient segments, one dot per alarm, plus a step count. Disarmed groups drop to `surfaceMuted` with `textSecondary` on the name and lose the ramp preview entirely.

**The spine.** On the group detail screen a 2dp vertical line runs behind the alarm rows, filled with a gradient through that group's ramp stops. Each alarm is a 16dp node on it, with a 5dp ring in the ground colour so the line appears to pass behind. This is the signature of the app — give it room and do not add anything else competing for attention on that screen.

**Toggles.** Armed is `ramp1`; disarmed is `line`. The knob is `textPrimary` armed, `textSecondary` disarmed.

**Firing screens.** Two elements above the fold: the time and what is being asked of you. The first alarm in a sequence is dark with a soft glow; the last is full-bleed `ramp5` with dark text. Between them, interpolate. Every firing screen shows the sequence position as a row of dots, so a half-asleep user knows whether they can go back to sleep.

**Escalation editor.** A 5dp gradient rail down the left with the rungs beside it. Each rung shows its challenge, its position, and a five-bar difficulty meter. The selected rung gets a 2dp outline in its own ramp colour.

**Reliability Check.** Problems first, in a bordered warning card with an action button. Passes below as a quiet checklist. Never a wall of green ticks with the one failure buried.

---

## 7. Voice

Plain and slightly dry. The app does not cheer at you before dawn.

- **Say what is happening.** "Last one. No snooze." not "Time to rise and shine!"
- **Errors give direction, not apology.** "Battery saver can stop alarms on this phone. Let Cascade run unrestricted." not "Sorry, something went wrong."
- **Be sympathetic, not peppy.** "You have another thirty-five minutes. Go back to sleep if you want to."
- **Buttons name their outcome.** "Dismiss", "Save escalation", "Open the scanner". Never "OK", "Submit", "Confirm".
- Sentence case. No exclamation marks. No emoji anywhere in the UI.

---

## 8. Icons and the app mark

Three marks are finished and in `assets/icons/`. The primary has not been chosen yet — build with `cascade-steps.svg` as a placeholder and swap later. All are drawn on a 40×40 viewBox and sit inside the 20-unit radius a circular launcher mask allows.

| File | What it is |
|---|---|
| `cascade-steps.svg` | Three circles falling and growing. Works at every size with no variant needed, and needs no mono version — the circles already read as a silhouette. |
| `cascade-rungs.svg` | Left-aligned ladder. For the wordmark lockup only — the long bar crosses the mask boundary. |
| `cascade-rungs-icon.svg` | Centred and lifted version of the same. Use this one as an app icon. |
| `cascade-eyes.svg` | Four eyes opening, one per alarm. Use at 64px and above. |
| `cascade-eyes-small.svg` | Two eyes side by side on one baseline, shut and wide open. Use below 64px. |
| `cascade-eyes-mono.svg` | The same two eyes in `currentColor`, pupil as a true cut-out, for the notification silhouette. |

The Eyes pupil is filled with the **ground colour of the active theme**, not a fixed hex — the SVGs ship with Forest's `#123524` as a placeholder. In the mono asset the pupil is a real `fill-rule="evenodd"` cut-out instead, because Android discards colour on notification icons and a coloured pupil would fill solid.

**Android notification icons are alpha silhouettes.** Every ramp colour collapses to one. Use the `-mono` asset for `setSmallIcon`, and never a multi-colour drawable.

**Adaptive icons** need a foreground and background layer on a 108dp canvas with only the centre 66dp guaranteed visible. Put Forest's `ground` in the background layer and the mark in the foreground, scaled to sit inside that 66dp circle — in practice the mark fills about 56% of the tile. Samsung and Xiaomi crop more aggressively than Pixel; check on a real device of each before shipping. The icon does not vary by theme (§11.1).

UI icons elsewhere are 1.7–2dp stroke, rounded caps and joins, drawn on a 24×24 grid, in `textSecondary` unless they carry state.

---

## 9. Accessibility floor

- Text hits 4.5:1 against its background, 3:1 at 24sp and above. The token pairs above are checked; new combinations are not.
- Anything distinguished by colour is also distinguished by shape, icon, or position. The difficulty meter uses bar height as well as ramp colour for this reason.
- Every icon-only button has a `contentDescription`. Decorative SVGs are marked null.
- Respect the system font scale. The firing screen must still work at 200% — test it, since the large numerals are where it breaks.
- Respect reduced motion. The volume ramp is audio, not animation, and continues regardless.

---

## 10. Things not to do

- Do not use ramp colours for anything except difficulty.
- Do not add a card shadow. The app is flat; depth comes from surface lightness.
- Do not put a second ornament on the group detail screen. The spine is the whole idea.
- Do not use Material 3 dynamic colour. It would override the ramp and destroy the one thing the design is doing.
- Do not make the firing screen cheerful.
- Do not hide the "give up" escape hatch on the hardest challenge. It stays visible, with "Recorded in your history" beneath it.
- Do not lock a theme behind the paywall in a way that changes an alarm's behaviour. Themes are cosmetic; every alarm feature works on Forest.

---

## 11. Themes as a premium pack

**Free:** Forest and Night. Two options means the picker is a real feature rather than a teaser, and Night is already fully built by Milestone 7 anyway.

**Cascade Plus:** the other eighteen themes. One-time purchase, not a subscription — this is a cosmetic unlock on an app people use once a day, and a recurring charge for colours invites resentment. Use Play Billing's `INAPP` product type, not `SUBS`.

The Appearance screen groups themes by family with a row of family chips, then a two-column grid of previews. Each preview is drawn in its own theme — ground, a raised surface, a time in `textPrimary`, and the ramp — so the grid sells itself without a screenshot. Twenty flat rows would be unusable; the families are what make it navigable.

Rules for the paywall:

- Locked themes are **visible and labelled**, never hidden. A padlock icon plus the theme's real preview. Seeing what you would get is the whole sell.
- Tapping a locked theme shows the purchase sheet. It does **not** apply the theme temporarily, and it does not nag.
- The unlock button sits at the bottom of the Appearance screen, below the list. Nowhere else in the app.
- Never gate anything that affects whether an alarm fires or how it behaves. Reliability is not a premium feature, and selling it would be indefensible in a wake-up app.
- Restore purchases must work offline against the cached Play entitlement, because a user reinstalling on a new phone at midnight should still get their theme.

### 11.1 The app icon is fixed

**The launcher icon does not change with the theme, and the user cannot switch it.** This was considered and dropped deliberately — the reasoning is worth keeping, because it will come up again:

Launcher icons cannot be tinted at runtime; they resolve outside the app's theme context, so every mark-and-theme combination would need its own static drawable and its own `<activity-alias>`. With twenty themes that is a manifest full of aliases. Worse, switching an alias makes the launcher treat the app as uninstalled and reinstalled, which can lose home screen placement, and some OEMs kill the process when a component is toggled — in an app whose entire job is firing on time, that is a bad trade for a cosmetic win.

So: one icon, in Forest colours, for everyone. The mark has not been finally chosen (§8); build with `cascade-steps.svg` and swap the asset later if the choice lands elsewhere.

The notification icon is the mono silhouette, tinted by the system, so it needs no per-theme variant either.
