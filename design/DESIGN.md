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

Fifty-two themes in thirteen families of four. **Forest is the brand and the default.** Night ships free beside it so the picker is never a one-item list, and the four Clarity themes are free too (§2.5). The other forty-six are the premium pack (§12).

| Family | Themes | The idea |
|---|---|---|
| Landscape | Forest, Tide, Moss, Olive | Places, cool to dry |
| Earth | Ember, Cocoa, Canyon, Vineyard | Warm grounds, fire to wine |
| After dark | Night, Ink, Dusk, Aurora | Blues and violets, calm to loud |
| Stark | Eclipse, Tundra, Slate, Signal | Austere, minimal hue |
| Daylight | Paper, Sand, Chalk, Blush | Light grounds, plain |
| Pastel | Meadow, Mist, Linen, Apricot | Light grounds, softer and tinted |
| Terminal | Phosphor, Amber, Cathode, Plasma | **Single-hue ramps.** Difficulty by brightness alone, no hue shift at all |
| Neon | Arcade, Vapor, Citrus, Koi | **Highest chroma.** Hue jumps hard at every step |
| Quiet | Fog, Sage, Pewter, Loam | **Never spikes.** Low chroma throughout, pale last stop |
| Clarity | Beacon, Lantern, Hazard, Compass | **AAA text and maximum ramp separation.** See §2.5 |
| Retro | Betamax, Formica, Kodachrome, Atomic | Period palettes, 1950s to 1980s |
| Royal | Regalia, Sapphire, Emerald, Garnet | A jewel ground, always ending in gold |
| Mineral | Basalt, Verdigris, Marble, Pyrite | Stone and metal, oxidation and patina |

The last three families carry an idea rather than a palette, and each has a consequence worth knowing:

- **Terminal** ramps one hue from dark to bright. It is the purest expression of the rule in §1 — nothing but luminance is doing the work — and it is the family to reach for when testing whether the escalation still reads.
- **Neon** jumps hue at every step, so it depends entirely on the luminance ordering to stay legible. Do not reorder its stops.
- **Quiet** ends pale rather than bright: the last alarm does **not** flood the screen with colour. Escalation lives in the audio and the challenge instead. This is a deliberate option for people who find the bright themes tiring, and the picker should say so in a line rather than letting someone discover it at 6:35am.
- **Royal** shares its last two stops — the same gold — across all four themes. That is what makes them read as a set rather than four unrelated jewel tones.

**Pewter** and **Loam** are the only two themes with a mid-tone ground, neither near-black nor near-white. Check those two first on an OLED panel at low brightness, where mid greys band worst.

**Lantern** and **Marble** are the only two with a descending ramp (§2.4).

### 2.1 Where the values live

**All colour values are in `assets/themes.json`.** Generate the Kotlin colour table from that file — do not transcribe seven hundred hex values out of this document by hand. The JSON carries, per theme: `name`, `descriptor`, `tier` (`free` or `pack`), `light`, the nine surface and text tokens, a five-entry `ramp`, `onRamp5`, a three-stop `firing` gradient, and a `note` on the themes that behave unusually.

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

### 2.2 The ten light themes

Paper, Sand, Chalk, Blush, Meadow, Mist, Linen, Apricot, Lantern and Marble set `"light": true`. Two things change:

1. **The surface direction inverts.** `groundDeep` and `surface` are *lighter* than `ground`, not darker. Token names describe role, not brightness, so the same code works — but do not assume "deep" means "dark".
2. **Status colours darken.** Use `statusLightOverride` instead of `status`, or they fail 4.5:1 against the light ground.

### 2.2a Ramp direction

Most themes set no `rampDirection`, which means `ascend`: the ramp climbs in luminance, so the last alarm is the brightest.

**Lantern** and **Marble** set `"rampDirection": "descend"`. On a light ground, ascending compresses the usable range into nothing — `ramp5` ends up a pale wash a couple of points from the background. Descending inverts it: difficulty climbs by getting *darker*, so the last alarm is a deep, near-black screen with light text on an otherwise pale theme. It is the most abrupt escalation in the whole set, and it is the only honest way to build a high-contrast light theme.

Two consequences for the code:

- `onRamp5` is **light** text on a descending theme and dark text on an ascending one. Do not assume it is dark; read the token.
- The firing-screen interpolation runs the same way in both cases — first alarm from `firing`, last alarm full-bleed `ramp5` — so no special casing is needed there. Only the monotonicity check cares about direction.

The audit validates monotonicity in whichever direction a theme declares.

### 2.3 Contrast, and what was verified

Every theme is checked by `assets/theme_audit.py`. Run it after any colour change:

```
python3 assets/theme_audit.py assets/themes.json
```

It exits non-zero on failure, so it belongs in CI. It also suggests a hue-preserving replacement colour at the right luminance when a ramp stop breaks the ordering.

All of these hold across all fifty-two:

- `textPrimary` and `textSecondary` reach 4.5:1 against `ground`; `textQuiet` reaches 3:1.
- `onRamp5` reaches 4.5:1 against `ramp5`.
- The ramp climbs monotonically in **relative luminance**, not just hue, so difficulty survives colour blindness and a dimmed screen.
- Every theme appears in exactly one family, and every family entry names a real theme.

Two things do **not** pass, deliberately. Both matter when building:

- **`ramp1` sits at 2–3:1 against `ground` in most dark themes**, as low as 1.77:1 in Koi. That is the point — the first alarm should not glare at 6am. It is only ever drawn as a 7–16dp dot or bar, never as text, and it always has a text label and a position beside it. **Never let a ramp colour be the only thing carrying meaning.**
- **On the light themes, `ramp4` and `ramp5` land at 1.9–2.9:1 against `ground`.** The lightness climb has to compress on a light ground and there is no way around it while keeping the ramp monotonic. Acceptable for a fill; never for text or a thin line. The light and pastel families need looking at on a real screen before they ship.

The audit prints both of those as warnings rather than failures. Read the list; do not try to drive it to zero, or you will flatten the ramp.

The monotonic check earned its keep during design: it caught a luminance dip at `ramp4` in Forest, Paper and Chalk, which is why those three stops are lighter than you might expect.

**A fourth check, on perceptual evenness.** The audit also measures the gap between neighbouring ramp stops in **L\*** (perceived lightness) rather than relative luminance, because evenly spaced luminance does not look evenly spaced. It warns when any step falls under 6 L\* — close enough that two neighbouring alarms look the same brightness — or when the steps are lopsided by more than 14 L\*.

This caught a real defect in a shipping theme. **Night** originally ran 43.5, 45.3, 50.1, 54.5, 77.7 L\*: its first four stops sat inside eleven points and then leapt twenty-three to amber. The hue shifts carried it for anyone with normal colour vision, but for a colour-blind user Night's escalation was unreadable until the final alarm — in one of the two free themes. Forest had a milder version of the same squeeze at the top. Both were retuned to even steps (10 L\* and 8 L\*) with their hues and saturations preserved, so the change is barely visible side by side but the mechanic now works.

Nine pack themes still carry one tight step, between 4.4 and 5.8 L\*: **apricot, arcade, atomic, chalk, emerald, linen, meadow, paper, slate**. In each, a large hue shift does the work at that step, so they read correctly for most people. They are the weakest themes for a colour-blind user, which is what the Clarity family is for. Worth retuning after the build settles; not worth churning mid-build.

### 2.4 Firing screen gradients

Each theme's `firing` array is a three-stop radial gradient for the **first** alarm in a sequence, from 8% to 48% to the edge. The **last** alarm is full-bleed `ramp5` with `onRamp5` text. Alarms in between interpolate between the two.

Several themes behave unusually, and their `note` field says so:

- **Eclipse** ends on pure white, so its last alarm is a blank bright screen. Intentional. Honour the system brightness; do not force it up.
- **Slate** stays near-monochrome until the final stop. If it looks broken, that is the intent.
- **Signal** is tuned for maximum luminance separation rather than looks. Surface it in the accessibility settings as well as the theme picker.
- **The Quiet family** ends pale instead of bright, so the last-alarm screen is muted. Do not "fix" this by brightening `ramp5`.
- **The Terminal family** shifts no hue at all. Do not add an accent to make the last stop pop.
- **Lantern and Marble** descend, so their last-alarm screen is dark with light text (§2.2a).

### 2.5 The Clarity family

Four themes built for low vision and colour blindness rather than for looks. They are the only themes that clear **AAA (7:1)** on `textPrimary` and `textSecondary` rather than AA, and their ramp stops are spread as far apart in luminance as each hue sequence allows.

| Theme | What it is for |
|---|---|
| **Beacon** | Pure black ground, blue through to white. Maximum separation, dark. |
| **Lantern** | Pure white ground, descending to deep navy. Maximum separation, light. |
| **Hazard** | Single-hue amber on black — the classic low-vision pairing. No hue discrimination required at all. |
| **Compass** | Blue to yellow only, with **no red or green anywhere in the ramp.** The blue–yellow axis survives protanopia, deuteranopia and tritanopia. |

**Surface these four from the accessibility settings as well as the theme picker**, and offer them free rather than behind the pack. Charging for legibility is a bad look, and the people who need Compass are not a segment to monetise. **Decided 2026-10-22: all four are free** (`tier: free`, and listed in the JSON's top-level `free`).

When a user says the colours all look the same, Compass is the answer.
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

**Background animation.** Added 2026-10-23 at the owner's request. A quiet animation plays behind Home, Reliability, Sound and the alarm editor; Settings shows it in a preview window instead. Never on the group screen or the escalation editor (§10: their spine and rail are the only ornament), never on the firing screen. Every animation draws in `textSecondary` at low alpha (about a third more on light grounds, where faint grey vanishes), never a ramp colour; stands still when Android's animations are off (§9); repaints at most 30 times a second, only the canvas.

Each theme family has its own, and **Match the theme** is the default; the user can pick any one instead, or None:

| Family | Animation | |
|---|---|---|
| Landscape | Drift | dots rising slowly, like pollen |
| Earth | Embers | sparks that rise, flicker and burn out |
| After dark | Stars | still points that twinkle |
| Quiet | Snow | soft flakes falling |
| Stark, Daylight, Mineral | Dust | a few motes wandering |
| Pastel | Bubbles | rings floating up |
| Terminal | Rain | thin streaks falling |
| Neon | Pulse | rings that swell and fade, sonar-like |
| Retro | Orbits | dots circling faint tilted ellipses |
| Royal | Glints | brief four-point sparkles |
| Clarity | none | the accessibility family: motion distracts the people it is for |

The table lives in code (`AmbientAnimation.forFamily`); a test fails if a family in `themes.json` has no decision.

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

**Adaptive icons** need a foreground and background layer on a 108dp canvas with only the centre 66dp guaranteed visible. Put Forest's `ground` in the background layer and the mark in the foreground, scaled to sit inside that 66dp circle — in practice the mark fills about 56% of the tile. Samsung and Xiaomi crop more aggressively than Pixel; check on a real device of each before shipping. The icon does not vary by theme (§12.1).

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

## 11. A theme per group

Each alarm group may carry its own theme. Workday Wake in Forest, Gym Day in Citrus, Late Shift in Ink. This is the feature that makes a large theme library worth owning — the themes stop being a wallpaper you pick once and become a way of telling your mornings apart.

**The capability is free. Only the themes are paid.** A user with the two free themes can still give two groups different looks.

### 11.1 Data and resolution

`AlarmGroup` gains one nullable field: `themeId: String?`, where null means "follow the app theme".

Resolution order, in one shared function: `group.themeId ?? appThemeId ?? "forest"`. Three rules around it:

1. **Resolve at fire time, never cache.** The foreground service looks the theme up when the alarm fires, so changing a theme at 11pm applies to the 6am alarm without rescheduling anything.
2. **An unowned theme falls back silently.** If `themeId` names a pack theme and the pack is not owned — a refund, a reinstall, a restore that has not completed — resolve to the app theme and carry on. Never block the alarm, never show a paywall on the firing screen. Show the lock in the group editor instead, where there is time to deal with it.
3. **An unknown `themeId` falls back the same way.** Treat a missing key as null rather than throwing; a typo in a backup file must not stop an alarm.

**Built 2026-10-23.** `ThemeResolution.resolve` is the one function; the ring carries the group's `themeId` (read at fire time) and the firing screen and notification resolve it. The app theme and the Plus entitlement are kept in device-protected storage so the firing screen can theme itself before the first unlock. The group's theme picker opens in the app theme, so every preview is judged on the same page.

### 11.2 How far the theme reaches

| Surface | Theme used |
|---|---|
| Home background, header, FAB | App theme |
| Group card on Home | Group theme, with the polarity rule below |
| Group detail | Group theme, fully |
| Alarm editor, escalation editor | Group theme, fully |
| **Firing screen** | **Group theme, fully** |
| Alarm notification accent | Group theme's `ramp5`, via `setColor()` |
| Settings, Stats, Reliability Check, Appearance | App theme, always |

**The polarity rule.** A group card on Home uses the group theme's `surface` **only when the group theme's `light` flag matches the app theme's.** When they differ — a light group theme under a dark app theme — the card keeps the app theme's surface and shows the group's identity through the ramp strip plus a small theme swatch. A white card on a dark page reads as a rendering error, not a choice. The artboard shows both cases side by side.

**Added 2026-10-23 at the owner's request:** a switch on the Appearance screen, *Group themes on Home* (on by default), keeps every card in the app theme, ramp included, for anyone who prefers one calm page. The group's own screens and its firing screen keep its theme either way.

Home keeps one background colour regardless. Twenty cards in twenty themes would be a patchwork; the page stays calm and the cards carry the difference.

### 11.3 Transitions

Navigating from Home into a group changes the theme. Crossfade the background and surfaces over about 200ms rather than cutting, or the change reads as a glitch. Respect `prefers-reduced-motion` by cutting instead.

The firing screen does **not** animate in. It appears already in its theme, at full opacity. Nothing about an alarm should feel gradual.

### 11.4 What this is worth

Fifty-two themes across five groups is not fifty-two choices, it is a combination per group, and that is the honest answer to "is the pack worth it". Put a line in the purchase sheet to that effect — something plain like *a different look for every group* — rather than leading with the number.

---

## 12. Themes as a premium pack

**Free:** Forest and Night, plus the four Clarity themes (§2.5). Two everyday options means the picker is a real feature rather than a teaser, and legibility is never sold.

**Cascade Plus:** the other forty-six themes. One-time purchase, not a subscription — this is a cosmetic unlock on an app people use once a day, and a recurring charge for colours invites resentment. Use Play Billing's `INAPP` product type, not `SUBS`.

The Appearance screen groups themes by family with a horizontally scrolling row of family chips, then a two-column grid of previews for the selected family. Each preview is drawn in its own theme — ground, a raised surface, a time in `textPrimary`, and the ramp — so the grid sells itself without a screenshot. Fifty-two flat rows would be unusable; the thirteen families are what make it navigable, and the family labels are what make the library feel curated rather than padded.

**Build the grid from `assets/themes.json` at runtime, not as fifty-two hand-written composables.** One preview composable taking a theme as a parameter, driven by the families list. Otherwise every future theme is a UI change as well as a data change.

Rules for the paywall:

- Locked themes are **visible and labelled**, never hidden. A padlock icon plus the theme's real preview. Seeing what you would get is the whole sell.
- Tapping a locked theme shows the purchase sheet. It does **not** apply the theme temporarily, and it does not nag.
- The unlock button sits at the bottom of the Appearance screen, below the list. Nowhere else in the app.
- Never gate anything that affects whether an alarm fires or how it behaves. Reliability is not a premium feature, and selling it would be indefensible in a wake-up app.
- Restore purchases must work offline against the cached Play entitlement, because a user reinstalling on a new phone at midnight should still get their theme.

**Built 2026-10-23.** Product id `cascade_plus` (INAPP). Only Play's own answer changes ownership: a failed connection keeps the cached answer, a full query without Plus means a refund. Purchases are acknowledged at once (Play refunds unacknowledged ones after three days). Families with an idea get one line under the chips (Quiet, Terminal, Neon, Clarity). The Background animation picker lives on the Appearance screen too.

### 12.1 The app icon is fixed

**The launcher icon does not change with the theme, and the user cannot switch it.** This was considered and dropped deliberately — the reasoning is worth keeping, because it will come up again:

Launcher icons cannot be tinted at runtime; they resolve outside the app's theme context, so every mark-and-theme combination would need its own static drawable and its own `<activity-alias>`. With twenty themes that is a manifest full of aliases. Worse, switching an alias makes the launcher treat the app as uninstalled and reinstalled, which can lose home screen placement, and some OEMs kill the process when a component is toggled — in an app whose entire job is firing on time, that is a bad trade for a cosmetic win.

So: one icon, in Forest colours, for everyone. The mark has not been finally chosen (§8); build with `cascade-steps.svg` and swap the asset later if the choice lands elsewhere.

The notification icon is the mono silhouette, tinted by the system, so it needs no per-theme variant either.
