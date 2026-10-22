# Kickoff prompt for Claude Code

Put `SPEC.md`, `DESIGN.md` and the `assets/` folder into an empty project directory, start Claude Code there, and paste the prompt below.

---

## The prompt

```
I'm building an Android alarm app called Cascade. The full specification is in
SPEC.md in this directory — read it completely before doing anything else.
DESIGN.md holds the visual design system, assets/themes.json holds all twenty
themes' colour values, and assets/icons/ holds the app marks. You won't need
those until we build UI, so skim DESIGN.md now and read it properly at
Milestone 2.

Short version: it's an alarm app for people who wake up using sequences of
alarms. Alarms live in named groups that act like folders and arm/disarm with
one toggle, and each alarm's position in its group determines how hard it is
to dismiss (math problems, QR scans, step counts, photo matching). Think
Alarmy, but organized around sequences instead of single alarms.

Android only. Native Kotlin + Jetpack Compose. No cross-platform framework.

Before writing any code, do these three things:

1. Read SPEC.md in full.

2. Check the current state of the Android APIs the spec depends on, because my
   spec was written from research that may be out of date and these APIs change
   with each platform release. Specifically verify: the current requirements and
   Play Store policy for USE_EXACT_ALARM and USE_FULL_SCREEN_INTENT, the required
   foregroundServiceType declarations for the current target SDK, and anything
   that has changed about AlarmManager.setAlarmClock() or full-screen intent
   behavior on recent Android versions. Tell me what you find that contradicts
   or updates the spec before you act on it.

3. Give me a short implementation plan for Milestone 0 and Milestone 1 only
   (project skeleton and the core alarm engine). Include the file structure
   you intend to create and the key classes. Wait for my approval before
   writing code.

Then build Milestone 0 and Milestone 1, and only those.

Milestone 1 is the entire foundation of this app and I want it correct before
anything else exists. Its definition of done:

- A single hardcoded alarm, scheduled via AlarmManager.setAlarmClock(), fires
  at the correct time
- It fires with the device locked and the screen off
- It fires while the device is in Doze mode (verify with
  `adb shell dumpsys deviceidle force-idle`)
- It shows a full-screen activity over the lock screen
- It plays audio on STREAM_ALARM with a volume ramp
- It can be dismissed, and dismissal stops audio and releases the wake lock
- It reschedules correctly after a device reboot
- It times out safely if never dismissed
- There are unit tests for next-occurrence computation covering DST
  transitions, timezone changes, and week boundaries

Do not build any UI beyond what Milestone 1 requires. Do not build challenges,
groups, Spotify, or settings. I'd rather have a bulletproof alarm engine and
nothing else than a complete-looking app that occasionally fails to wake me up.

Working style I want from you:

- Tell me when the spec is wrong. I wrote it from research, not from having
  built this before. If an approach won't work on current Android, say so
  and propose the alternative instead of implementing something broken.
- Ask before adding dependencies not named in the spec.
- Write tests alongside the code, not after.
- Commit at each working checkpoint with clear messages.
- When a milestone is done, tell me exactly how to verify it on a real device
  before we move on.

Start with step 1.
```

---

## Notes on using this

**Run Milestone 1 on a physical device, not just the emulator.** Doze behavior, OEM battery management, and lock-screen rendering all differ on real hardware. The emulator will lie to you about reliability.

**Between milestones**, start a fresh Claude Code session and point it at SPEC.md plus the existing code rather than continuing one very long conversation. Context stays cleaner and output stays better.

**For later milestones**, the follow-up prompt is short:

```
Read SPEC.md and review the existing code. Implement Milestone [N] only.
Give me a plan first and wait for approval. Tell me if anything in the
existing code needs refactoring to support this milestone cleanly.
```

**From Milestone 2 onward, add one line:** `Read DESIGN.md in full first and build the theme system from it before any screens, generating the colour table from assets/themes.json rather than transcribing hex values.` Getting the colour tokens wrong early means repainting every screen later, and hand-copying 300 hex values guarantees a typo.

**Keep SPEC.md and DESIGN.md updated as you go.** When you and Claude Code decide something differs from them, edit them. A stale spec is worse than no spec, because every future session reads it as truth.

**Milestone 5 deserves the same discipline as Milestone 1.** Reliability hardening is unglamorous and easy to skip, and it is the difference between an app people trust and an app people uninstall after one missed alarm.
