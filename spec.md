# Cascade — Technical Specification

**An Android alarm app for people who wake up using sequences of alarms.**

App name: Cascade. Application ID: `com.ttrevorreese.cascade` (decided 2026-10-24; the Kotlin package stays `com.cascade.alarm`).

---

## 1. Product thesis

Most alarm apps treat multiple alarms as user error. Cascade treats a sequence as the intended unit of use.

The core object is not an alarm. It is an **alarm group** — a named, labeled folder of consecutive alarms that are armed and disarmed together with one toggle. A user builds "Workday Wake" once (6:00, 6:10, 6:20, 6:35) and flips it on Sunday night. They build "Gym Day," "Travel," and "Late Shift" and switch between them.

Layered on top of that is **escalation**: each alarm's position in its sequence determines how hard it is to dismiss. The first alarm is a gentle nudge. The last one makes you scan a QR code taped to the bathroom mirror. No competitor does this well, and it is the feature that justifies the app's existence.

**Design principle:** the app must be more reliable than the stock Clock app. Every architectural decision defers to that. A missed alarm is a catastrophic bug, not a defect.

**Visual design lives in `design/DESIGN.md`**, with colour values in `design/assets/themes.json` and icon assets in `design/assets/icons/`. Read it before writing any UI. Three things from it affect architecture rather than styling, so they are repeated here: the app ships **fifty-two themes** in thirteen families (Forest default; Night and the four Clarity themes free; forty-six behind one non-consumable purchase; none tied to the system dark-mode setting), generated from the JSON rather than hand-transcribed; an alarm's position in its sequence maps to a five-stop colour ramp through a single shared function that needs unit tests; and **each alarm group can carry its own theme**, which adds a field to the data model and changes how the firing screen resolves colour — see `design/DESIGN.md` §11. The launcher icon is fixed and does not vary by theme. Run `design/assets/theme_audit.py` after any colour change; CI runs it too.

---

## 2. Scope

### In scope for v1
- Android phones, API 26 (Oreo) minimum, target current API level
- Alarm groups with single-toggle arm/disarm
- Per-position challenge escalation within a group
- Five dismissal challenge types
- Local audio alarm sounds with volume ramp
- Spotify playlist playback (feature-flagged, see §9)
- Reliability hardening and missed-alarm diagnostics

### Explicitly out of scope for v1
- iOS (platform constraints make it a separate project; revisit after Android ships)
- Web app (browsers cannot wake a sleeping device; a web companion is config-only and has no v1 value)
- Wear OS
- Sleep tracking, sleep cycle detection, smart wake windows
- Accounts, cloud sync, social features
- Subscriptions. Billing is limited to **one non-consumable in-app purchase** unlocking the forty-six pack themes (`design/DESIGN.md` §12). Build it at M7, not before, and never gate alarm behaviour behind it.
- A user-switchable launcher icon. Considered and dropped; `design/DESIGN.md` §12.1 says why, so it does not get reinvented.

### Deferred to v1.1
Local backup/restore via JSON export, widget, Tasker integration, additional challenge types.

---

## 3. Technology stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | Kotlin | Required for native alarm APIs |
| UI | Jetpack Compose + Material 3 | Modern, fast to build |
| Architecture | MVVM, unidirectional data flow | Standard, testable |
| DI | Hilt | Standard for this stack |
| Persistence | Room + DataStore (prefs) | Alarms must survive reboot |
| Async | Coroutines + Flow | Standard |
| Camera | CameraX | Photo and QR challenges |
| ML | ML Kit (barcode scanning, image labeling) | On-device, no network dependency |
| Audio | ExoPlayer (Media3) on `STREAM_ALARM` | Reliable, handles ramping |
| Navigation | Navigation Compose | Standard |
| Testing | JUnit5, Turbine, Robolectric, Compose UI test | — |

**Hard rule:** no network call may ever be on the critical path of an alarm firing. The alarm must work in airplane mode with no account.

---

## 4. The alarm engine

This is the heart of the app and the part most likely to be built wrong. Build it first, prove it works, then build everything else around it.

### 4.1 Scheduling

Use `AlarmManager.setAlarmClock()`. Not `setExactAndAllowWhileIdle()`, not `WorkManager`.

`setAlarmClock()` is the only API that is fully exempt from Doze mode restrictions, and it surfaces the system alarm icon in the status bar, which users expect. The tradeoff is that it is the most visible and most scrutinized API, which is correct for an app whose core function is alarms.

**Permissions required:**
- `USE_EXACT_ALARM` — granted at install for apps whose core function is alarms. Play Store review will verify the app genuinely is an alarm clock. Declare it and be ready to justify it in the Play Console.
- `SCHEDULE_EXACT_ALARM` — fallback for older API levels; requires a user grant flow.
- `USE_FULL_SCREEN_INTENT` — Android 14+ restricts this to calling and alarm apps. Same review scrutiny.
- `RECEIVE_BOOT_COMPLETED` — reschedule everything on boot.
- `POST_NOTIFICATIONS` — Android 13+.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- `ACTIVITY_RECOGNITION` — step challenge only, requested at the point of use.
- `CAMERA` — photo and QR challenges only, requested at the point of use.
- `WAKE_LOCK`.

**Scheduling model:** schedule only the *next* occurrence of each enabled alarm, never a batch of future ones. Reschedule on: alarm fired, alarm edited, group toggled, device boot, timezone change, DST transition, app update.

Register receivers for `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIMEZONE_CHANGED`, and `TIME_SET`. Each triggers a full reconciliation pass that reads every enabled alarm from Room and rebuilds the schedule from scratch. Idempotent reconciliation is far safer than incremental updates.

### 4.2 Firing

1. `AlarmManager` fires a `PendingIntent` into a `BroadcastReceiver`.
2. The receiver immediately acquires a wake lock and starts a foreground service. Do no other work in the receiver — it has a hard execution budget.
3. The foreground service (`type="mediaPlayback"`) owns the alarm's lifetime: audio playback, volume ramp, timeout handling, and the ongoing notification.
4. The service posts a notification with a **full-screen intent** pointing at `AlarmActivity`.
5. `AlarmActivity` sets `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and requests dismissal of the keyguard. It renders over the lock screen.

**Audio:** play on `AudioManager.STREAM_ALARM`. This stream bypasses silent mode and Do Not Disturb by design, which is exactly the intended behavior. Request `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`. Implement volume ramp as a gradual increase over a configurable 0–120 second window, starting at a configurable floor.

**Decided 2026-10-23 (M6):**
- **Sound is set per group, not per alarm**: every alarm in a sequence sounds the same. The group holds `sound`, `rampSeconds` and `rampStartPercent`; the alarm's old `volumeRampSeconds` column stays unused (SQLite cannot drop it before API 34).
- **Ramp: 30–60 s** to full volume (owner's choice; default 45 s), rising linearly in decibels. **Start at: 5–60% of full**, in *perceived* loudness (half as loud = −10 dB), default 15% (≈ the M1 floor). Full volume is the phone's alarm-stream volume; a muted alarm stream is raised to a third for the ring and restored.
- **Eight built-in tones**, gentlest first: Dawn, Chimes, Marimba, Classic (the M1 tone, default), Pulse, Digital, Rising, Klaxon. Synthesised by `scripts/make_tones.py` (no licensing), WAV so they loop without a gap. Keys are stored; never rename one.
- **A file from the phone** is picked with the system file picker and **copied** into device-protected storage (`files/sounds/`, max 20 MB, checked to play as audio), so it plays before the first unlock after a reboot and survives the original being moved. Copies no group uses are deleted on the next sound save, group delete, or visit to the sound screen.
- **Fallback chain at ring time:** the group's sound → Classic → the phone's default alarm sound, vibration throughout. Each step is logged as `SOUND_FALLBACK` (reason, what played instead) and shows once on Home as "Your 6:00 alarm could not play its sound", until acknowledged.
- **Preview** plays on the alarm stream through the real ramp (start level up to full), stops by itself 5 s after full volume and whenever the screen is left.

**Timeout:** if the user does not dismiss within N minutes (default 15, configurable), stop audio, log a `MISSED` event, and release the wake lock. Never let a service run indefinitely.

### 4.3 Reliability hardening

This is where Android alarm apps live or die. Aggressive OEM battery management — Xiaomi, Huawei, Oppo, Vivo, OnePlus, and to a lesser extent Samsung — will kill background apps and silently prevent alarms from firing. See dontkillmyapp.com for current per-OEM behavior.

Build a **Reliability Check** screen, reachable from onboarding and from settings, that:
1. Detects the device manufacturer.
2. Checks `isIgnoringBatteryOptimizations()` and prompts for the exemption.
3. For known-aggressive OEMs, shows manufacturer-specific instructions (autostart permission, protected apps list, "no restrictions" battery setting) with a deep link to the relevant settings screen where an intent exists.
4. Verifies notification permission, exact alarm permission, and full-screen intent permission.
5. Shows a clear pass/fail status per item.

Also implement a **missed alarm detector**: on every app foreground, compare the last-fired timestamps in the database against what should have fired. If an alarm should have gone off and no `FIRED` event exists, show a prominent diagnostic card explaining the likely cause and linking to the Reliability Check. This converts a silent catastrophic failure into a recoverable, explainable one.

Add a **Test Alarm** button in settings that schedules an alarm 60 seconds out and runs the full real path. Users should be able to verify the app works before trusting it.

**M5 decisions (2026-10-22):**
- **Battery exemption goes through the settings list, not the direct dialog.** `setAlarmClock()` alarms already fire through Doze, and Play policy forbids requesting a direct Doze exemption unless Doze breaks the core function. The Reliability Check opens the battery settings (no special permission), and the manufacturer cards cover what actually stops alarms: OEM app killing.
- **Extra check beyond the list above:** Android 11+ "pause app activity if unused" (permission auto-revoke), which can silently break an alarm app that is rarely opened.
- **Home banner only for real blockers:** notifications, full-screen alarms or exact alarms missing. Battery and manufacturer advice stay on the Reliability Check, marked with a dot on its link.
- **Missed-alarm cards say why:** the cause is inferred from the boot time (phone off or restarting at the time, versus Cascade being stopped while the phone was on). Alarms that rang more than 2 minutes late get a quieter card. "Got it" dismisses a card for good.
- The test alarm reports its result ("rang 0.4 s late"); the event log can be shared as text for device testing.

### 4.4 Wake-up check (added 2026-10-21)

Per group, off by default. When the user dismisses the group's **last** alarm (no enabled alarm later in the group), schedule a check 1–5 minutes later (per group, default 3). The check rides the same AlarmManager path as the alarm itself (`Alarm.wakeCheckAt`, treated like a one-time trigger), so it survives reboots and Doze.

The check is a full-screen "Are you awake?" prompt that only vibrates, with one "I'm awake" button and a 60-second countdown. Answering logs `WAKE_CHECK_PASSED` and ends the sequence. Missing it logs `WAKE_CHECK_FAILED` and rings the last alarm again with its full timeout. Dismissing that repeat ring schedules another check. Powering off during a check counts as a failed check (`CHEAT_DETECTED`), and the alarm rings after boot. A check that comes due while another alarm is ringing is skipped. Disarming the group, switching off the alarm or turning the check off cancels a pending check.

---

## 5. Data model

```kotlin
@Entity
data class AlarmGroup(
    @PrimaryKey val id: String,          // UUID
    val name: String,                     // "Workday Wake"
    val colorHex: String,
    val iconKey: String,
    val isArmed: Boolean,                 // master toggle for all child alarms
    val sound: SoundConfig,               // per group since 2026-10-23 (§4.2)
    val rampSeconds: Int,                 // 30–60
    val rampStartPercent: Int,            // 5–60, perceived loudness
    val themeId: String?,                 // null = follow the app theme; see design/DESIGN.md §11 (M7)
    val escalationPolicy: EscalationPolicy,
    val sortOrder: Int,
    val createdAt: Instant
)

@Entity
data class Alarm(
    @PrimaryKey val id: String,
    val groupId: String,                  // FK, cascade delete
    val positionInGroup: Int,             // 0-based; drives escalation
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<DayOfWeek>,        // empty = one-shot
    val label: String?,
    val isEnabled: Boolean,               // individual toggle, ANDed with group.isArmed
    val challengeOverride: ChallengeConfig?, // null = inherit from group escalation
    val snoozeConfig: SnoozeConfig,
    val timeoutMinutes: Int               // sound and ramp moved to the group (§4.2)
)

sealed interface SoundConfig {
    data class BuiltIn(val toneKey: String) : SoundConfig
    data class DeviceAudio(val fileName: String, val displayName: String) : SoundConfig  // app's own copy
    data class SpotifyPlaylist(
        val playlistUri: String,
        val playlistName: String,
        val fallback: SoundConfig       // REQUIRED, never null
    ) : SoundConfig
}

sealed interface ChallengeConfig {
    data object None : ChallengeConfig
    data class Math(val difficulty: Difficulty, val problemCount: Int) : ChallengeConfig
    data class PhotoMatch(val referencePhotoId: String, val threshold: Float) : ChallengeConfig
    data class Steps(val count: Int) : ChallengeConfig
    data class BarcodeScan(val expectedPayload: String, val label: String) : ChallengeConfig
    data class TypePhrase(val phrase: String) : ChallengeConfig
    data class Memory(val sequenceLength: Int) : ChallengeConfig
}

data class EscalationPolicy(
    val steps: List<ChallengeConfig>   // index maps to positionInGroup, last entry repeats
)

@Entity
data class AlarmEvent(                   // append-only audit log
    @PrimaryKey val id: String,
    val alarmId: String,
    val type: EventType,                 // SCHEDULED, FIRED, DISMISSED, SNOOZED, MISSED, CHEAT_DETECTED
    val timestamp: Instant,
    val metadata: String?                // JSON
)
```

**Effective-enabled rule:** an alarm fires only if `group.isArmed && alarm.isEnabled`. The group toggle does not mutate child `isEnabled` values, so a user who disabled one alarm inside a group keeps that state when re-arming the group. This is the behavior organized users expect and most apps get wrong.

**Shift with first (added 2026-10-21):** `AlarmGroup.shiftWithFirst` (default on). Changing the time of the alarm at position 0 moves every other alarm in the group by the same amount, taking the short way round the clock (23:50 → 00:10 is +20 min). An alarm moved across midnight takes its repeat days with it. Editing any other alarm moves only that alarm. The group screen also has −5 / +5 min buttons that shift the whole group.

**Theme resolution:** `group.themeId ?? appThemeId ?? "forest"`, in one shared function, resolved at fire time rather than cached. An unowned or unknown `themeId` falls back to the app theme silently — never block an alarm or show a paywall on the firing screen. `design/DESIGN.md` §11.1 has the full rules.

**Escalation resolution:** `alarm.challengeOverride ?: group.escalationPolicy.steps.getOrElse(alarm.positionInGroup) { steps.last() }`.

**Escalation decisions (2026-10-22, M4 planning):**
- The position used for escalation counts **only the alarms that are on** (the same position the firing screen's sequence dots show). Switching off the last alarm makes the one before it the last, so the final alarm that actually rings always gets the hardest rung.
- The policy is resolved when the alarm fires, never cached, so ladder edits apply to the next ring without rescheduling. If the group cannot be read, the alarm's own override applies, else plain Dismiss.
- New groups start with an empty ladder. The escalation editor offers a one-tap **suggested ladder** (easy sums → type a phrase → harder sums → steps) to edit from. Existing groups migrate with an empty ladder, so nothing changes for alarms already set up.

---

## 6. Dismissal challenges

Each challenge implements a common interface so new types can be added without touching the alarm engine:

```kotlin
interface Challenge {
    val config: ChallengeConfig
    @Composable fun Render(onComplete: () -> Unit, onAbort: () -> Unit)
    fun canAbort(): Boolean      // whether a "give up" escape hatch is offered
}
```

### 6.1 Math
Generate arithmetic problems at three difficulty tiers (single-digit addition / two-digit mixed operations / multi-step with multiplication). Configurable problem count, 1–10. Generate deterministically from a seed so a problem cannot be dodged by force-quitting and relaunching.

### 6.2 Barcode / QR scan
**Build this first — it is the most reliable and most effective challenge.** User registers a code during setup (generate a printable QR in-app, or scan any existing barcode such as a shampoo bottle). At alarm time they must scan the matching code. Physically relocating the user is what actually works; the code lives on the bathroom mirror or the coffee machine.

Use ML Kit barcode scanning with CameraX. Store the expected payload string and compare exactly.

### 6.3 Photo match
User registers a reference photo during setup (their sink, their desk). At alarm time they must take a photo of the same scene.

**Implementation note:** do not attempt object recognition. Use perceptual similarity — compute a perceptual hash (dHash or pHash) of both images, or use ML Kit image labeling and compare label sets with a confidence threshold. Perceptual hashing is simpler, fully offline, and sufficient. Tune the threshold generously and allow retries; a false rejection at 6am is a one-star review.

Store reference photos in app-private internal storage, never in MediaStore.

### 6.4 Steps
Use `Sensor.TYPE_STEP_COUNTER` (hardware, low power, survives screen-off) rather than Health Connect, which adds a dependency and a permission flow for no benefit here. Requires `ACTIVITY_RECOGNITION`.

Capture the baseline counter value when the challenge starts and require N additional steps. Display live progress. Default 20 steps, configurable 10–200.

### 6.5 Type a phrase
User-configured phrase, or a generated one. Case-insensitive, whitespace-normalized. Disable clipboard paste in the input field. Lowest-friction challenge; good for position 0 in a sequence.

### 6.6 Memory sequence
Simon-style color/tone sequence of configurable length. Included because it is fast and engaging without being frustrating.

### 6.7 Anti-circumvention

Scope this carefully — a fight against a determined user is unwinnable and the aggressive approaches get apps removed from Play.

**Do implement:**
- Hardware volume keys do not silence the alarm while a challenge is active (intercept in `AlarmActivity`).
- Back gesture and home do not dismiss; re-raise the activity if it loses focus while the alarm is active.
- Alarm resumes on app relaunch if dismissal never completed.
- Log a `CHEAT_DETECTED` event when the activity is backgrounded during an active alarm, and surface it in stats.

**Do not implement:** device admin APIs to block uninstall, accessibility service abuse, or overlay permissions to block system UI. These violate Play policy and will get the app rejected or removed.

**Always provide an escape hatch:** a "give up" affordance after a configurable number of failed attempts or a hard 3-minute cap. It should feel bad (confirmation dialog, logged to stats) but it must exist. Trapping a user who genuinely needs to answer a 3am emergency call is unacceptable.


### 6.8 Implementation decisions (added 2026-10-21, M3 planning)

These correct or sharpen the sections above, after checking them against current Android:

- **The ringing notification never dismisses an alarm that has a challenge.** Its action becomes "Open" and leads to the challenge. Only challenge-free alarms and the wake-up check keep a notification dismiss.
- **"Re-raise the activity" is not possible** on Android 14+ without the overlay permission, which §6.7 rules out. Instead the alarm keeps sounding, the ongoing notification returns the user with one tap, and leaving the alarm screen logs `CHEAT_DETECTED`.
- **Permissions (camera, activity recognition) are requested while configuring a challenge, never at alarm time.** If one is missing when the alarm fires, or the hardware is absent or fails (no step counter, a camera that will not open or shows no frame within 10 s), the alarm falls back to a math challenge. It never becomes undismissable.
- **Photo match compares a fingerprint, not the photo** (M3b): the lighting is removed (a local high-pass), then the reference records which neighbouring cells of a 17x17 grid are brighter, and which of those are clear edges. Only clear edges are compared, the reference is searched for within the new photo to tolerate framing, and a match needs 75% agreement. The fingerprint cannot be turned back into a picture, so it lives in the device-protected database and **photo match works before first unlock**; only the reference image itself stays in credential storage, and before first unlock the screen shows its name instead. A reference that is too plain (a blank wall) is refused at setup.
- **ML Kit barcode uses the bundled model**, which works offline and in Direct Boot. QR codes for printing are generated in-app with ZXing core.
- **Sound while solving:** the alarm drops to about 30% while the user interacts with a challenge, and returns to full volume if they leave the screen or stop for 30 seconds.
- **Give up** appears after a number of failed attempts or 3 minutes, asks for confirmation, and logs a `GAVE_UP` event, with "Recorded in your history" beneath it.
- **The alarm timeout still applies during a challenge** and logs `MISSED`. Waiting out a challenge is therefore possible but recorded. The 3-minute give-up is the intended exit.
- ChallengeConfig is stored as JSON (kotlinx-serialization) in `Alarm.challengeOverride`. Until M4 the per-alarm choice is the only source; M4 adds the group escalation policy beneath it.
---

## 7. Screens

1. **Home** — list of alarm groups as cards. Each shows name, color, next fire time, child alarm count, and a prominent master toggle. Reordering by drag. FAB to create a group.
2. **Group detail** — ordered list of alarms in the group, each showing time, resolved challenge type, and individual toggle. Alarms are always listed in the order they ring (decided 2026-10-25, replacing manual reordering): saving a time re-sorts the group (`AlarmRules.ringOrder`, the sequence starting after the longest gap round the clock), so a moved alarm takes its new place's rung; the alarm editor says so before saving. **Duplicate** (added 2026-10-25) copies the group with its alarms and settings, right after it in the list, named "… copy", and off, so the two never ring together by surprise; the copy opens at once. Edit escalation policy and the group's sound here.
3. **Alarm editor** — time picker, day-of-week selector, label, challenge override, snooze config, timeout. (Sound and ramp are the group's, on its own Sound screen: tones, a file from the phone, ramp length, start level, preview.)
4. **Escalation editor** — visual ladder showing position → challenge mapping, with the ability to set each rung. This screen is the product's signature; give it real design attention.
5. **Alarm firing screen** — full-screen over lock screen. Time, label, challenge UI, snooze button if permitted.
6. **Reliability Check** — per §4.3.
7. **Settings** — defaults, test alarm, theme, data export. **Built 2026-10-24:** Stats, Appearance, Read-aloud voice, Reliability Check (holds the test alarm), defaults for the first alarm of a new group (later alarms copy the one before), and Export everything as one JSON file saved through the system file picker (reference photos left out). **Since 2026-10-23:** "Background animation" (DESIGN.md §6): Match the theme (default; each theme family has its own animation), one of ten by name, or None, with a live preview; opened from the sliders icon on Home. The chosen theme is stored too, but only a debug command sets it until M7's Appearance screen.
8. **Stats** — dismissal times, snooze counts, challenge failure rates, give-up events. Organized people like data about themselves; this is cheap to build and drives retention.

Material 3 components, but **not** Material You dynamic colour — it would override the difficulty ramp, which is the one thing the design is doing. Colours, type and spacing all come from `design/DESIGN.md`. The firing screen must be legible to someone half-asleep: large type, high contrast, generous touch targets, and a first-alarm screen that does not blind at 6am.

---

## 8. Build order

Each milestone should end in a working, installable app.

| # | Milestone | Deliverable |
|---|---|---|
| M0 | Skeleton | Project setup, Hilt, Room, Compose nav, CI running tests |
| M1 | **Alarm engine** | One hardcoded alarm fires reliably, shows full-screen over lock screen, plays audio, dismisses, survives reboot. No UI polish. **Do not proceed until this is bulletproof.** |
| M2 | CRUD + groups | Create/edit/delete groups and alarms, master toggle, Room persistence, reconciliation on all triggers |
| M2.5 | Design system | Added 2026-10-21, after M2 shipped on stock Material. Retrofit `design/DESIGN.md` before M3 adds more screens: theme tokens for all twenty generated from `themes.json` at build time (Forest applied; the picker and Night wait for M7), Sora type scale, `rampStopFor`, flat chrome, re-skinned home / group (spine) / editor / firing screens, launcher and notification icons, voice pass. **Updated 2026-10-22 to the v2 design:** 52 themes in 13 families, ramp direction, Clarity themes free, theme audit in CI; per-group themes added to M7 |
| M3 | Challenges | Interface + math, type-phrase, barcode. Then steps, photo match, memory |
| M4 | Escalation | Policy resolution, escalation editor screen, reorder semantics |
| M5 | Reliability | Reliability Check, missed-alarm detector, test alarm, OEM guidance, event logging |
| M6 | Audio | Built-in tones, device audio picker, volume ramp. **Updated 2026-10-23:** sound is per group; Spotify moved to M7 |
| M7 | Polish | **Started 2026-10-23.** Done: Appearance screen, per-group themes, Cascade Plus via Play Billing (decisions in DESIGN.md §11–12 and §11 below; price decided 2026-10-24: $4.99 flat, one-time, set on the `cascade_plus` product in Play Console, which the app reads, so nothing is hard-coded). Added 2026-10-23/24 by the owner: snooze per alarm (never on the group's last), and the alarm's label read aloud with a choice of voice (the phone's own offline voices; app-wide voice in Settings, per-alarm switch). Idea for after M7 (owner, 2026-10-24): Cascade's own voices, AI-generated and identical on every phone, sold in Cascade Plus or as a separate voice add-on (not tied to themes). Must stay on-device (an on-device neural speech model bundled or delivered as a Play asset pack), never a network call at ring time; needs research on size, quality and voice licences first. Then: settings, stats, onboarding, accessibility pass, Spotify. Spotify behind a feature flag (§9, moved from M6 on 2026-10-23); the Appearance screen also sets the theme the background animation follows (DESIGN.md §6); stats, settings, all fifty-two themes generated from `design/assets/themes.json`, the Appearance screen (its grid generated from the JSON too), per-group themes, the theme-pack purchase, onboarding, accessibility pass |
| M8 | Release prep | Play Store listing, permission justifications, privacy policy, closed testing |

**Estimate:** 11–14 weeks of focused solo work to a shipped Play Store release. M1 and M5 together are roughly 40% of the total and are where the difficulty actually lives. The challenge screens look like the bulk of the work but are the easy part. Fifty-two themes cost almost nothing beyond the first two if the token system is built properly at M2 and both the colour table and the picker grid are generated from `design/assets/themes.json`; the single in-app purchase at M7 is a few days. The one real cost is visual QA, since the firing screens want eyeballing per theme.

---

## 9. Spotify integration

**Gate this behind a feature flag and build it last.** Spotify's developer terms require written approval for alarm functionality, obtained via a quota extension request that itself requires a working demo app. The app must be fully shippable without it.

**Design constraints:**
- `SoundConfig.SpotifyPlaylist` carries a **required** non-null `fallback` of local audio. If the Spotify app is missing, not logged in, the device is offline, or app-remote connection fails for any reason, fall back silently and immediately. The alarm always makes noise.
- Start the local fallback *first*, attempt the Spotify handoff, and cross-fade only on success. Never start silent and hope.
- Use the Spotify Android SDK's app-remote, which requires the Spotify app installed. Playback is offloaded to that app.
- ~~Playlist browsing and search use the Web API with OAuth PKCE~~ **Changed 2026-10-24:** no Web API. The user pastes a playlist or album link (Spotify: Share, Copy link) and Cascade turns it into a `spotify:` URI itself (`SpotifyLinks`), so Cascade holds no tokens and makes no network call. Short `spotify.link` links are explained, not resolved.
- Surface a clear setup state in the UI when Spotify is selected but not connected.
- **Built 2026-10-24.** Spotify's developer mode (Feb 2026) allows the owner (Premium) plus listed users, five in all; extended quota needs a registered business with 250k monthly users. So it is for the owner and a few friends: on in debug builds, off in release unless built with `-Pcascade.spotifyInRelease=true`. Client ID in `gradle.properties` (`cascade.spotifyClientId`, public by design), redirect URI `cascade://spotify-callback`, App Remote SDK 0.8.0 in `app/libs` (+ Gson). Ring path: `SpotifyHandoff` connects without the approval screen (10 s), sets the media volume to match the alarm and plays (10 s), then fades the tone out over 2 s; the tone stays running silently and returns if Spotify stops; the media volume is put back on dismiss. Failures (`spotify_locked` before first unlock, `_not_installed`, `_signed_out`, `_not_authorized`, `_offline`, `_timeout`, `_did_not_play`, `_stopped`) log `SOUND_FALLBACK` with next `backup`, and Home explains them. Sound page: switch, link field, "Try it in Spotify" (the one place Spotify's approval screen may appear; it also picks up the playlist's name).
- **Before starting (M7):** the owner registers an app on the Spotify developer dashboard and supplies the Client ID; they enter the redirect URI and debug-key SHA-1 we give them. Where it plugs in: `SoundPlan` (engine/ring) turns a group's sound into local sources to play; a Spotify config should resolve to its `fallback`'s sources, which start first, with the handoff attempted alongside. Testing the real handoff needs the S22 (the emulator has no logged-in Spotify), so it goes on the §10 list.

---

## 10. Testing

- **Unit:** schedule computation (next occurrence across DST, timezone changes, week boundaries, one-shot vs recurring), escalation resolution, challenge validation, effective-enabled logic.
- **Instrumented:** Room migrations, boot receiver reconciliation, alarm firing path.
- **Manual matrix, mandatory before release:** fire an alarm with the device locked, screen off, in Doze (`adb shell dumpsys deviceidle force-idle`), in Do Not Disturb, in silent mode, after reboot, after app update, with battery optimization enabled and disabled, and on at least one Xiaomi or Samsung device in addition to Pixel.
- Write a scripted end-to-end test for the full alarm path early (M1) and run it on every milestone.
- **Real-phone checks collected along the way** (the emulator cannot do these; run them on the Galaxy S22 Ultra once the app is complete):
  - QR/barcode: scan a printed Cascade code and an existing product barcode; a different code is refused.
  - Photo match: reference of a real object (toothbrush), match in morning light, rejection of a different object, behaviour right after a reboot before unlock (name shown, matching still works). Tune `PhotoHash.DEFAULT_THRESHOLD` from these.
  - Steps: the counter reports promptly with the screen on; 20 steps completes; permission flow, including the dialog when "Use the suggested ladder" adds a walking rung, and the app-settings route after a refusal (the emulator has no step counter).
  - Reliability Check on One UI 8: the Samsung card's steps match the real settings (Never sleeping apps), "Open the settings" reaches the battery screen rather than app info, and the Fix this buttons land where their text says.
  - Sound (M6): listen to all eight tones at a low and a high alarm volume (levels differ by design, gentle ones are quieter; none should clip or click at the loop point); pick a song from the phone and let it ring; reboot without unlocking and check the picked song still plays (it is copied into device-protected storage); the 30–60 s ramp and the "Start at" level sound right on the S22's speaker.
  - Read aloud (M7): in Settings > Read-aloud voice, check Samsung's and Google's voices are both listed and each "Hear it" plays at alarm volume; set an alarm to read aloud and let it ring: tone first, then the time and label with the tone lowered, again every 30 s, quiet while a challenge is open; reboot without unlocking and check a read-aloud alarm still speaks (from the speech made earlier); try a voice that needs a download and check it is not offered until installed.
  - Spotify (M7, needs the owner's Spotify account and the app on the phone): on a group's Sound page paste a playlist link, Try it in Spotify, approve Cascade when Spotify asks, check it plays and the name appears on the group screen; ring the group: tone first, then Spotify fades in at about the alarm's loudness, and dismiss pauses Spotify and puts the media volume back; pause Spotify from its notification mid-alarm and check the tone returns; sign out of Spotify (or turn on airplane mode) and check the tone rings and Home explains; reboot without unlocking and check the tone rings with the "not unlocked" card; check a friend added under User Management can do the same, and someone not added gets the "not on the list of testers" message.
  - Accessibility (M7): with TalkBack on, make a group in onboarding, switch a group off on Home, edit an alarm, and dismiss a ringing alarm with sums and with the memory sequence (pads should be named as they light); with the system font at its largest, check Home, the editor and the alarm screen still fit.
  - Cascade Plus, once the app is in Play Console with the `cascade_plus` INAPP product and a licence tester: buy it, see every theme unlock at once; refund it in Play Console and see the app return to free themes on the next Appearance visit; reinstall offline after buying and check the themes come back once Play has synced; a group set to a pack theme rings in the app theme while unowned, never blocked.
  - Themes on the firing screen: ring a group in a light theme (Paper) and one in a dark theme (Night) from the lock screen, before and after the first unlock after a reboot; the notification's accent is the group's last ramp stop.
  - Background animations: each of the ten smooth on the S22 at 120 Hz, no noticeable battery drain with Home left open for 10 minutes, faint but visible at low brightness; then once the theme picker lands (M7), walk one theme per family and judge whether the family's animation suits it.
  - Launcher icon crop on One UI; the light and pastel themes, and Pewter/Loam on OLED at low brightness (DESIGN.md §2.1, §8).
  - The manual matrix above: Doze, DND, silent mode, after reboot, after update, battery optimisation on and off.

---

## 11. Privacy

No account, no analytics SDK, no network calls except Spotify when explicitly configured. **Since 2026-10-24 the app has no INTERNET permission at all** (removed from the merged manifest): ML Kit and Play Billing bring Google's datatransport, which tried to upload usage statistics; Android now blocks it. Billing, Spotify App Remote and text-to-speech are IPC to other apps and need none. (Cascade Plus is bought through the Play Store app's own billing service; Cascade itself makes no network call for it, added 2026-10-23.) Reference photos and all alarm data stay in app-private storage. Be able to state this plainly in the Play listing — it is a genuine differentiator in a category full of data-harvesting apps.