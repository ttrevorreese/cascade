# Cascade — working notes for Claude Code

Android alarm app built around **groups of alarms** with **escalating dismissal challenges**.
Native Kotlin + Jetpack Compose, minSdk 26, targetSdk 36 (compileSdk 37). Solo project; the
owner tests on a Galaxy S22 Ultra (Android 16 / One UI 8) only once the app is complete.

**Update this file at the end of every milestone** (status table, "Resume here", anything new
under conventions). It is how the next session knows where to pick up.

## Resume here

- **Last finished:** M5 Reliability (2026-10-22): Reliability Check screen, missed/late-alarm cards
  on Home, test-alarm result, phone-maker guidance, shareable event log. Decisions: `spec.md` §4.3.
- **Next:** M6 Audio — built-in tones, device audio picker, volume ramp (exists since M1), then
  Spotify behind a feature flag (SPEC §9). Start with a plan for the owner.
- Before starting a milestone: give the owner a short plan and wait for approval (their kickoff
  notes in `design/CLAUDE_CODE_PROMPT.md`), unless they have said to go ahead.

## Status

| Milestone | State | Notes |
|---|---|---|
| M0 Skeleton | done | Hilt, Room, Compose nav, CI |
| M1 Alarm engine | done | setAlarmClock, systemExempted FGS, Direct Boot, reboot/power-off resume |
| M2 Groups + CRUD | done | master toggle, reconciliation on every edit |
| — Shift with first, wake-up check | done | owner-requested; spec §4.4, §5 |
| M2.5 Design system | done | tokens generated from `design/assets/themes.json` (52 themes), Sora, spine, firing palette |
| M3a/M3b Challenges | done | sums, phrase, QR/barcode, photo match, steps, memory; give-up; anti-circumvention |
| M4 Escalation | done | per-group ladder, resolved at fire time, escalation editor |
| M5 Reliability | done | Reliability Check (problems first), missed/late cards with cause from boot time, test result, OEM guidance, event log share |
| M6 Audio | **next** | |
| M7 Polish | | stats, settings, Appearance screen + 52 themes picker, per-group themes, theme purchase, onboarding |
| M8 Release prep | | Play listing, permission declarations, privacy policy |

## Sources of truth

- `spec.md` — what to build. Decisions made along the way are written into it (look for
  "added"/"decisions" with dates). Keep it current; a stale spec is worse than none.
- `design/DESIGN.md` — how it looks. Colours only from generated `CascadeColors`; ramp colours
  only mean difficulty; `rampStopFor` is the one position→colour mapping.
- `design/assets/themes.json` + `theme_audit.py` (runs in CI) — never hand-copy hex values.
- `spec.md` §10 "Real-phone checks collected along the way" — add device-only checks there
  instead of asking the owner mid-milestone.

## Environment (Windows)

- JDK: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (system java is 1.8).
- adb: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`; prefix adb calls with
  `MSYS_NO_PATHCONV=1` in Git Bash.
- Emulator: AVD `cascade_api36`, start headless with
  `emulator -avd cascade_api36 -no-snapshot -no-window -memory 2048`. Lock PIN 1234. It has an
  emulated back camera but **no step counter**. The machine is short on memory; close the
  emulator when done.
- After a cold boot the emulator is locked: unlock before instrumented tests
  (`input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; input text 1234; input keyevent KEYCODE_ENTER`).
- `connectedDebugAndroidTest` uninstalls the app afterwards, wiping demo data; re-seed with
  `SEED_DEMO`.
- Long bash heredocs containing Kotlin sometimes fail to parse in this shell; write edit scripts
  to a file (or use the Edit tool) instead.

## Commands

- Unit tests: `./gradlew testDebugUnitTest` (JUnit 5 + Robolectric; screen tests use
  `@Config(qualifiers = "w411dp-h914dp")` when the layout needs a phone-sized window).
- Instrumented (migrations, engine): `./gradlew connectedDebugAndroidTest`.
- End-to-end alarm path on a device: `bash scripts/e2e-alarm.sh --wake-check` (about 6 min);
  `--all` adds timeout, reboot and power-off (about 14 min). Run before calling engine work done.
- Debug-build adb hooks (`DebugCommandReceiver`), e.g.
  `adb shell am broadcast -a com.cascade.alarm.debug.SEED_DEMO -p com.cascade.alarm`:
  `RING_IN`, `SET_TIMEOUT`, `SET_DAILY`, `DISABLE`, `DISMISS`, `SET_WAKE_CHECK`, `SEED_DEMO`,
  `RING_POSITION --ei position N`, `SET_CHALLENGE --ei position N --es kind math|phrase|barcode|memory|steps|clear`.
- UI checks: screenshot with `adb exec-out screencap -p`, find elements with
  `uiautomator dump`. Screenshot every new screen before calling it done.
- Missed-alarm detector by hand: `RING_POSITION --ei seconds 20`, `am force-stop com.cascade.alarm`,
  wait 2.5 min, reopen: the catch-up rings late and Home shows the "rang late" card.
- The emulator sometimes puts a Google sign-in screen in front after installs; press Back/Home.

## Conventions

- Every schema change: bump `CascadeDatabase` version, add a migration in `Migrations.kt`, a
  `MigrationTest` case, and commit the exported `app/schemas/.../N.json`. Never destructive.
- Reliability first: anything on the ring path fails open (ring with defaults, fall back to sums)
  and must work before first unlock (device-protected DB, bundled assets).
- Permissions are asked at setup (editor), never at alarm time; missing ones fall back.
- Commit at each working checkpoint with a descriptive message; push only when the owner asks.
- Copy is plain and slightly dry, sentence case, buttons name their outcome (DESIGN.md §7).
