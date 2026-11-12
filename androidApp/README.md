# TransitTracker for Android (`/androidApp`)

The Android shell around the shared Compose UI (`:app:ui`): one activity, the process's
`AppDeps`, permissions, location, notifications and push. Screens, navigation and the design
system live in `app/ui` (see `app/README.md`); this module only connects them to Android.

## Building

You need JDK 21 and an Android SDK (Android Studio's, or the command-line tools). The build
looks for the SDK in `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`
at the repository root:

```
sdk.dir=/home/you/Android/Sdk
```

With an SDK, `./gradlew build` builds the Android targets of `shared/*` and `app/ui`, runs their
Robolectric tests and lint, and assembles this app. Without one, the same command builds the
JVM parts only (the server image does exactly that), and `:androidApp` is not part of the
build. `-Ptt.android=false` turns Android off even when an SDK is present.

```
./gradlew :androidApp:assembleDemoDebug      # demo flavour, debug
./gradlew :androidApp:assembleLiveDebug      # live flavour, debug
./gradlew :androidApp:installLiveDebug       # onto a connected phone (adb)
```

APKs land in `androidApp/build/outputs/apk/<flavour>/<buildType>/`, e.g.
`androidApp/build/outputs/apk/live/debug/androidApp-live-debug.apk`.

**From CI.** Every push builds both debug APKs; open the workflow run on GitHub and download the
`android-debug-apks` artifact (kept 14 days). They are signed with the CI machine's debug key,
which changes between runs, so installing a newer one over an older one may need an uninstall
first. For a phone you keep using, install a release instead and let it update itself (below).

Versions: Android Gradle plugin 9.1.1 (Gradle 9.3.1), compileSdk 37, targetSdk 36, minSdk 26.
Compose Multiplatform 1.12.2 brings Jetpack Compose 1.12.1 on Android, which requires
compileSdk 37 and AGP 9.1; that is why the Gradle wrapper is on 9.x.

## Flavours

| Flavour | Application id | Name | What it runs |
|---|---|---|---|
| `live` | `app.transittracker` | TransitTracker | The real core against your tt-server |
| `demo` | `app.transittracker.demo` | TransitTracker Demo | `AppDeps.fake` on the real clock: sample trips, places, live data and journeys, no server, nothing saved between launches, no push |

Both install side by side. The demo is for trying the whole UI (and for screenshots on a real
phone); permissions, location, links and system motion settings are real in both.

## Server address (live)

The live app uses **https://tt.finnjones.me**. A build can point at another server with, first
match wins:

1. Gradle property: `./gradlew :androidApp:assembleLiveDebug -Ptt.baseUrl=https://tt.example.com`
2. Environment variable `TT_BASE_URL`
3. `tt.baseUrl=https://…` in `local.properties`

It must start with `https://`. There is no longer a first-start screen asking for it.

## Sign-in and saved data

Instead of an invite code, the app signs in with Google (Firebase Authentication):

1. Onboarding's first step is **SIGN IN WITH GOOGLE**: Android's Google account sheet (Credential
   Manager), then Firebase sign-in with that Google account.
2. The app sends Firebase's ID token to tt-server (`POST /v1/join` with `idToken`). The server
   checks the token and that the account is on its allow list (`tt-admin allow sam@gmail.com`;
   see deploy/README.md), then gives the phone its device token as before. An account that is
   not on the list sees "… is not allowed on this server" and can try another account.
3. While signed in, the user's own data is also kept in Cloud Firestore at
   `users/{uid}/prefs/{key}`: display settings (`ui.*`), saved places and their trip links
   (`places.*`), saved trips (`trips`) and the planner's recent searches (`plan.recent`). Writes
   go to the phone first, then to Firestore (which queues them while offline). On sign-in, and
   at each start while signed in, the account's copy is brought down (it wins for keys it has;
   keys only the phone has are uploaded) and the screens read it again. The device token, push
   token, caches and timetable packs never leave the phone.

Settings > About shows the account. **SIGN OUT** signs out and forgets the phone's join, so
onboarding shows again; saved data stays on the phone and in the account. A phone that joined
with an invite code before this change keeps working, and **SIGN IN WITH GOOGLE** there links
its saved data to the account without joining again.

Builds without `google-services.json` have no sign-in and fall back to the invite code.

### Firebase console, once

The project is `transittracker-633f552b`; its `google-services.json` is committed here (it
identifies the project and is not a secret).

1. **Authentication > Sign-in method**: enable **Google**.
2. **Project settings > Your apps > app.transittracker**: add the **SHA-1** (and SHA-256) of every
   key that signs an APK you install: the release key (`keytool -list -v -keystore
   transittracker-release.jks -alias transittracker`) and your debug key
   (`./gradlew :androidApp:signingReport`). Google sign-in fails with "Google sign-in didn't work"
   for an APK signed with a key that is not listed. CI debug APKs are signed with a different
   key each run, so use a release (or a local build) to sign in.
3. **Download `google-services.json` again** and replace `androidApp/google-services.json` (and the
   `GOOGLE_SERVICES_JSON` secret, if you set one). After step 1 it carries an `oauth_client` of
   type 3, the web client id the sign-in sheet needs; with the old file the app says "Google
   sign-in is not set up for this build of the app yet."
4. **Firestore Database**: create the database (production mode, a nearby region such as
   `australia-southeast1`), then publish the rules in [firebase/firestore.rules](../firebase/firestore.rules).

## Updates

Settings > About has **CHECK FOR UPDATES**. It asks the GitHub releases of this repository for a
newer build, shows its version, size and notes, and **INSTALL UPDATE** downloads it (with
progress), checks it (size, SHA-256, package name and version code) and hands it to Android's
installer. Android asks to confirm the first time; on Android 12 and later, once the app has
installed itself, later updates go in without a prompt. The app closes while Android replaces
it; with notifications on, a "TransitTracker updated" notification opens it again. Settings, saved trips, the
sign-in and the device's join all stay, as with any app update.

Android only installs an update signed with the same key as the installed app, so releases are
signed with one long-lived key that CI keeps as secrets:

1. Make the key once and keep the file and passwords somewhere safe (a password manager). If it
   is lost, every phone has to uninstall and reinstall to change to a new one.

   ```bash
   keytool -genkeypair -v -keystore transittracker-release.jks -alias transittracker -keyalg RSA -keysize 4096 -validity 10000
   ```

2. In the repository's Settings > Secrets and variables > Actions, add:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 transittracker-release.jks` (macOS: `base64 -i transittracker-release.jks`) |
   | `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
   | `ANDROID_KEY_ALIAS` | `transittracker` |
   | `ANDROID_KEY_PASSWORD` | the key password (the keystore password unless you set another) |
   | `GOOGLE_SERVICES_JSON` | optional: the contents of `google-services.json`, so releases have push |

3. Each push to `main` then publishes a release `android-<run number>` ("TransitTracker 0.1.<n>")
   with the signed live APK and the commits since the last one as notes. Without the secrets
   the job only warns.
4. On the phone, once: uninstall any debug build (it has a different key; you will sign in
   again), open the repository's Releases page, install the APK and allow your browser or file
   manager to install apps. From then on, use CHECK FOR UPDATES.

The release APK has no secrets in it; releases of a public repository can be downloaded by
anyone, but the server only lets in the accounts on its allow list.

Settings: `tt.updateRepo` / `TT_UPDATE_REPO` names the repository the app checks (`owner/name`;
in GitHub Actions it defaults to the repository being built; blank turns the button off). A local
build can be signed with the same key through `tt.signing.storeFile`, `tt.signing.storePassword`,
`tt.signing.keyAlias` and `tt.signing.keyPassword` in `local.properties` (or `TT_SIGNING_*`
environment variables); `tt.versionCode` / `TT_VERSION_CODE` sets the build number. The demo
flavour does not update itself.

## Push notifications

Push uses the same Firebase project as sign-in (`androidApp/google-services.json`, above). The
build applies the Google services plugin when the file is there (for the live flavour; the demo
flavour skips it). The app then registers its FCM token with the server
(`TransitCore.updatePushToken`) at start and whenever Firebase issues a new one. Without the
file the app builds and runs with push and sign-in off.

Notification channels are created at start with the ids the server uses: `delays`,
`cancellations`, `alerts`, `general`. Messages that arrive while the app is open are posted on
their channel by the app; background ones are posted by FCM. Tapping either opens the app on the
service (or Alerts) through `AppController.openFromNotification`.

## What lives where

```
src/main/kotlin/app/transittracker/android/
  TransitTrackerApplication.kt  the process: channels, the flavour's AppGraph (one AppDeps)
  MainActivity.kt               edge to edge, back, foreground/background, notification taps
  AndroidPlatformServices.kt    PlatformServices: permissions, location (LocationManager),
                                notification settings, links, version, device id,
                                reduce motion (animator scale 0), battery saver
  Notifications.kt              channels and in-app posting
  PushMessagingService.kt       FCM token and messages
  ServerAddress.kt              checks the build's server address
  FirebaseAccounts.kt           sign-in with Google (Credential Manager + Firebase Auth) and
                                CloudKeyValueStore (the user's saved data in Firestore)
  AppUpdates.kt                 in-app updates: GitHub releases, download and checks,
                                PackageInstaller session, installer and "updated" receivers
  NotificationData.kt           tap extras -> data map
src/live/…/FlavorGraph.kt       real core (TransitCoreFactory) + stores (through CloudKeyValueStore
                                with Firebase) + sign-in; the journey planner is
                                the core's own client (`core.planner`: tt-server's /v1/places
                                and /v1/plan)
src/demo/…/FlavorGraph.kt       AppDeps.fake on the real clock
```

Android glue for the core is in `shared/core/src/androidMain` (OkHttp engine, properties-file
key-value store, file store with atomic rename, zstd, network status) and for packs in
`shared/timetable/src/androidMain` (a read-only framework-SQLite driver: packs are opened with
`OPEN_READONLY` and never modified). Their Robolectric tests are in `src/androidHostTest`.

**Backup.** Auto backup includes only `files/kv/backup.properties` (display settings, saved
trips and places). The device token and id, push token, caches and timetable packs are in
`noBackupFilesDir` and are never backed up; a restored phone signs in and joins again (and its
saved data also comes back from the account).

## Not built yet

- GO mode (plan 5.6): following a planned journey leg by leg; nothing Android-specific for it
  exists yet (no ongoing notification or foreground service).
- Alarms (plan 5.10).
- The home-screen widget (plan 5.12, Jetpack Glance).
- iOS (`/iosApp`): the shared UI already uses multiplatform window insets so it carries over.
- R8: release builds are unminified for now (and signed only when the signing key is given; see Updates).
- An in-app banner for messages that arrive while the app is open (the plan's 5.12); for now
  they are posted as ordinary notifications.
