# Cascade — working notes for Claude Code

Android alarm app built around **groups of alarms** with **escalating dismissal challenges**.
Native Kotlin + Jetpack Compose, minSdk 26, targetSdk 36 (compileSdk 37). Solo project; the
owner tests on a Galaxy S22 Ultra (Android 16 / One UI 8) only once the app is complete.

**Update this file at the end of every milestone** (status table, "Resume here", anything new
under conventions). It is how the next session knows where to pick up.

## Resume here

- **Last finished:** M6 Audio (2026-10-23): sound per group (8 tones, a file from the phone copied
  into device-protected storage), 30–60 s ramp with a "Start at" level, preview, fallback chain
  with `SOUND_FALLBACK` events and a Home card. Decisions: `spec.md` §4.2.
- **Next:** M7 Polish, which now also holds Spotify (SPEC §9; needs the owner's Spotify Client ID
  first). Start with a plan for the owner.
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
| M6 Audio | done | per-group sound, tones from `scripts/make_tones.py`, picked files copied in, ramp 30–60 s + start level, fallback logged and shown on Home |
| M7 Polish | **next** | Spotify (moved from M6), stats, settings, Appearance screen + 52 themes picker, per-group themes, theme purchase, onboarding |
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
  `RING_POSITION --ei position N`, `SET_CHALLENGE --ei position N --es kind math|phrase|barcode|memory|steps|clear`,
  `SET_SOUND --es tone <key> | --es file <name> [--ei ramp S --ei start P]` (first group and the test
  alarm; a file name that was never copied in exercises the fallback). New debug actions also need
  an `<action>` line in `app/src/debug/AndroidManifest.xml`.
- Read the app's database: `adb exec-out run-as com.cascade.alarm cat .../databases/cascade.db`
  (and `-wal`, `-shm`) into a folder and open with Python's sqlite3. `adb shell cat` mangles binaries.
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
- Screens with the floating dots (Home, Reliability, Settings, Sound, alarm editor) are wrapped in
  `AmbientBackdrop` in `CascadeNavHost` and give their Scaffold a transparent container. Keep them
  off the group screen, escalation editor and firing screen. `LocalBackgroundMotion` defaults to
  off, so screen tests stay still; a test that turns it on needs `mainClock.autoAdvance = false`.
- Sound is the group's, never the alarm's. Tone keys are stored in the database: add tones, never
  rename one. Regenerate tones with `python scripts/make_tones.py` (numpy).
- Anything the ring path plays must be readable before first unlock: bundled, or copied into
  device-protected storage (`SoundFiles`).
