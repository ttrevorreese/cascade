# Releasing Cascade

The steps from a working `main` to Cascade on Google Play, in order. Steps marked **(owner)** need
Trevor's accounts or phone; everything else is in the repo.

## 1. Upload key (owner, once)

Play signs the copies people install with its own key ("Play App Signing"). You sign uploads with
an **upload key**, which stays on your computer and never goes into git.

```sh
keytool -genkeypair -v -keystore %USERPROFILE%\keys\cascade-upload.jks ^
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Use a strong password and save it in a password manager. Then create `keystore.properties` in the
repo root (it is git-ignored):

```properties
storeFile=C:/Users/trevo/keys/cascade-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Without this file, release builds are signed with the debug key: fine for testing, refused by Play.
If the upload key is ever lost, Play Console can reset it; the app's real signing key is Google's.

## 2. Build

```sh
./gradlew testDebugUnitTest lintDebug          # what CI runs
./gradlew bundleRelease                        # app/build/outputs/bundle/release/app-release.aab
./gradlew bundleRelease -Pcascade.spotifyInRelease=true   # the testers' build, with Spotify
```

Bump `versionCode` (every upload) and `versionName` (every release) in `app/build.gradle.kts` first.
Release builds are shrunk with R8; keep `app/build/outputs/mapping/release/mapping.txt` with the
upload (Play takes it automatically from an `.aab`).

## 3. Before the first upload

- [ ] Real-phone checks in `spec.md` §10, on the S22 **(owner)**.
- [ ] GitHub Pages on: repo Settings > Pages > Deploy from branch > `main` / `docs` **(owner)**. The
      privacy policy is then at `https://ttrevorreese.github.io/cascade/privacy.html`.
- [ ] Play Console account (personal), identity verified, developer name set **(owner)**.

## 4. Play Console (owner)

- Create the app: name "Cascade: Alarm Groups", default language English (US), app, free.
- In-app product `cascade_plus`: one-time, $4.99, active.
- App content: privacy policy URL, ads (none), data safety (nothing collected or shared), content
  rating questionnaire, target audience (adults), and the declarations for exact alarms, full-screen
  intent and foreground service. Answers are drafted in `docs/play-console.md` (M8).
- Store listing: text and graphics in `docs/play-console.md` and `docs/screenshots/`.

## 5. Spotify fingerprints (owner, after the first upload)

Spotify only lets a copy of Cascade connect if the key that signed it is listed on the Spotify
dashboard (developer.spotify.com > Cascade > Settings > Android packages). Add a line for each:

- **Debug key** (already added): `79:DF:26:5D:6C:C4:CD:5E:9E:13:6F:C2:10:70:E8:09:AA:3B:EE:C8`.
- **Play's app signing key**: Play Console > Test and release > App integrity > App signing key
  certificate > SHA-1. Copies installed from Play, including testers' copies, carry this key, not
  yours; without it Spotify refuses them and alarms quietly fall back to the tone.
- **Upload key** (only if you install your own release builds directly):
  `keytool -list -v -keystore <path> -alias upload`.

Testers also need adding under User Management (five people in all, including you).

## 6. Closed test (owner, 14 days)

New personal accounts must run a closed test with at least **12 testers opted in for 14 days in a
row** before Play opens production. Create a closed testing track, add testers by email (a Google
Group is easiest), upload the testers' `.aab`, and share the opt-in link. Keep at least 12 opted in
for the whole 14 days, then apply for production access.

## 7. Production

Upload the `.aab` built **without** Spotify to production, roll out, and tag the commit
(`git tag v1.0.0`).
