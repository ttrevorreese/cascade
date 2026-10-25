# Play Console: everything to paste

Answers and text for setting Cascade up in Play Console, in the order the Console asks. Written for
the launch build: **free, no purchases, no Spotify** (`./gradlew bundleRelease`). If that changes
(selling Cascade Plus, see `RELEASING.md`), the sections marked *revisit* change with it.

Character counts are Play's limits; every text here fits.

---

## Create app

| Field | Answer |
|---|---|
| App name | `Cascade: Alarm Groups` (21 of 30) |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free (this cannot be changed to paid later; purchases inside a free app are still allowed) |
| Declarations | Tick both (Developer Program Policies, US export laws) |

The name on the phone stays "Cascade" (the launcher label); only the store name is longer.

---

## Store listing (Grow users > Store presence > Main store listing)

**Short description** (61 of 80)

```
Alarm groups that get harder to dismiss, one alarm at a time.
```

**Full description** (2,158 of 4,000)

```
Cascade is an alarm clock built around groups. Instead of a pile of separate alarms, you set up a sequence (6:00, 6:10, 6:20, 6:35) and each alarm in it is harder to turn off than the one before.

The first alarm can be a gentle nudge. The next asks you to type a phrase. The last makes you get out of bed and scan the barcode on your shampoo bottle. You decide what each step asks for, once, and every group follows its own ladder.

ESCALATING CHALLENGES
• Sums, from easy to hard
• Type a phrase (no pasting)
• Scan a QR code or barcode you choose
• Photo match: take a picture of the same spot as your reference photo
• Walk a number of steps
• Repeat a sequence of lights
Each alarm can also have its own challenge instead of its rung on the ladder.

GROUPS THAT BEHAVE LIKE ONE ROUTINE
• Turn a whole group on or off with one switch
• Move the first alarm and the rest follow, or shift the whole group by five minutes
• Duplicate a group to make a weekend version
• Snooze that never applies to the last alarm in a group
• A wake-up check a few minutes after you dismiss, to make sure you stayed up

SOUND
• Eight alarm tones, or a sound file from your phone
• Starts quiet and rises to full volume over 30 to 60 seconds
• Read aloud: the time and the alarm's label, in the voice you pick

BUILT TO RING
Alarms that do not go off are the one thing an alarm app must never do. Cascade uses Android's alarm clock scheduling, rings after a restart even before you unlock the phone, and resumes an alarm that was interrupted by the phone switching off. The Reliability Check finds what could stop an alarm on your phone (battery settings, permissions, your phone maker's power saving) and says how to fix it. If an alarm ever rings late or is missed, Cascade tells you why.

PRIVATE BY DESIGN
No account, no ads, no analytics. Cascade does not even have permission to use the internet: everything you set up stays on your phone. You can export all of it as a file whenever you like.

ALSO
• Stats: how long you take to get up, which challenges you solve
• Six themes, including high-contrast themes made for low vision
• Designed for TalkBack and large text
```

**Graphics**

| Asset | File |
|---|---|
| App icon, 512 x 512 | `docs/store/icon-512.png` |
| Feature graphic, 1024 x 500 | `docs/store/feature-graphic.png` |
| Phone screenshots (2 to 8, 1080 x 2160) | `docs/screenshots/`, in this order: `home`, `group`, `escalation`, `ring`, `sums`, `reliability` |

Leave out `appearance.png`: it shows the Cascade Plus themes, which the launch build does not
offer. Regenerate the icon and feature graphic with `python scripts/make_store_graphics.py`.

**Contact details (Store settings)**

| Field | Answer |
|---|---|
| Email | cascadealarmgroup.app@gmail.com |
| Website | https://github.com/ttrevorreese/cascade |
| Category | App, **Tools** (where Google's own Clock is; Alarmy uses Lifestyle) |
| Tags | Alarm clock; Productivity |

---

## App content (Policy and programs > App content)

### Privacy policy
`https://ttrevorreese.github.io/cascade/privacy.html` (turn on GitHub Pages first; `RELEASING.md` step 3).

### App access
**All functionality is available without any special access.** There is no login.

### Ads
**No, my app does not contain ads.**

### Content rating
Start the questionnaire. Email: cascadealarmgroup.app@gmail.com. Category: **All other app types**
(the utility/productivity category). Answer **No** to every question: no violence, sexual content,
language, controlled substances, gambling; users cannot interact or share content with each other;
no location sharing; no purchase of digital goods (*revisit* when Plus is sold: Yes). The expected
result is the lowest rating everywhere (Everyone, PEGI 3, USK 0).

### Target audience and content
Age groups: **18 and over**. Any group under 13 brings in the Families policy and its extra review;
13 to 17 would be allowed without it, but nothing is gained by ticking it for an alarm clock. The app
is not designed to appeal to children: answer No.

### News apps
No.

### Health apps
**My app does not have any health features.** The walking challenge counts steps only while it is on
screen, to dismiss an alarm, and stores no step data; it is not activity tracking. (If Play's form
insists on the Activity Recognition permission, choose "Other" and paste: "Counts steps only to let
the user dismiss an alarm by walking. Nothing is stored or shared.")

### Financial features, Government apps
None. No.

### Advertising ID
**No.** The merged release manifest has no `AD_ID` permission (checked 2026-10-25).

### Data safety
- Does your app collect or share any of the required user data types? **No.**
- That is the whole form: with No, Play skips the rest and shows "No data collected" and "No data
  shared".

Why No is correct: Play defines "collect" as sending data off the device. Cascade cannot send
anything: the INTERNET permission is removed (manifest, `tools:node="remove"`), verified on the
emulator 2026-10-24. Camera frames, photos, steps and everything the user sets up are processed and
kept on the phone. The export is a file the user saves where they choose, which Play does not count
as collection.

*Revisit* when Plus is sold: Google Play Billing handles the payment; Play's guidance is that
purchase data handled by Google Play itself does not have to be declared by the app, but read the
form again then.

### Permissions declarations

Play asks for these when the first bundle is uploaded (Test and release > App content, or a prompt
on the release). Paste the text; the video is a phone screen recording uploaded to YouTube as
**Unlisted** (or a Google Drive link anyone can view).

**Exact alarms (`USE_EXACT_ALARM`)**
- Core functionality: **Alarm clock**.
- Description:
  ```
  Cascade is an alarm clock. Its main purpose is ringing alarms the user sets, at the exact minute
  they set them, in sequences (alarm groups). It schedules each alarm with AlarmManager.setAlarmClock,
  which Android shows to the user as an upcoming alarm. An alarm that rings late defeats the app's
  purpose.
  ```

**Full-screen intent (`USE_FULL_SCREEN_INTENT`)**
- Core functionality: **Alarm clock** (alarms and timers are a permitted use).
- Description:
  ```
  When an alarm rings, Cascade shows its alarm screen over the lock screen with a full-screen intent,
  so the user can see which alarm it is and complete the dismissal challenge. It is used only for
  ringing alarms set by the user.
  ```

**Foreground service**

Cascade's ring service declares two types; describe both if the form lists them separately.

- `systemExempted`:
  ```
  While an alarm rings, a foreground service plays the alarm sound, keeps the alarm screen and its
  notification up, and stops when the user dismisses the alarm or it times out. Cascade is an alarm
  clock app holding USE_EXACT_ALARM, for which Android allows the systemExempted type. The service
  runs only while an alarm is ringing (at most the user's chosen timeout, 60 minutes at the most).
  User impact if it were stopped or deferred: the alarm would fall silent and the user would not wake
  up.
  ```
- `mediaPlayback`:
  ```
  The same ring service plays the alarm's sound (a built-in tone or an audio file the user chose,
  rising in volume), and can read the alarm's label aloud. It starts when the alarm rings and stops
  when it is dismissed or times out.
  ```
- **Video** (one recording covers both): set an alarm a minute ahead in Cascade, lock the phone,
  record the screen from before it rings until after you dismiss it, showing the alarm screen over the
  lock screen, the notification shade with Cascade's ongoing alarm notification, and the sound
  stopping when you dismiss. About 30 to 60 seconds.

**Camera, Activity recognition, Notifications**: no declaration forms. They are asked for in the app
only when the user picks a challenge that needs them (or, for notifications, at setup), and the
privacy policy explains each.

---

## Before the first upload, check

- [ ] Version code bumped in `app/build.gradle.kts` (every upload; Play rejects a reused code).
- [ ] Built with the upload key (`keystore.properties` present), not the debug key.
- [ ] Privacy policy URL opens.
- [ ] The listing screenshots match the build: no Plus themes, no Spotify.
