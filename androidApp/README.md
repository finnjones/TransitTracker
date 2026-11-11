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

The address is never committed. It is read at build time, first match wins:

1. Gradle property: `./gradlew :androidApp:assembleLiveDebug -Ptt.baseUrl=https://tt.example.ts.net`
2. Environment variable `TT_BASE_URL`
3. `tt.baseUrl=https://…` in `local.properties`

When none is set (CI builds, for instance), the app asks for the address once on first start,
on a small screen of its own. It must start with `https://`; it is stored on the phone (in the
app's no-backup storage) and used to create the core. To change it later, clear the app's data.

## Updates

Settings > About has **CHECK FOR UPDATES**. It asks the GitHub releases of this repository for a
newer build, shows its version, size and notes, and **INSTALL UPDATE** downloads it (with
progress), checks it (size, SHA-256, package name and version code) and hands it to Android's
installer. Android asks to confirm the first time; on Android 12 and later, once the app has
installed itself, later updates go in without a prompt. The app closes while Android replaces
it; with notifications on, a "TransitTracker updated" notification opens it again. Settings, saved trips, the
server address and the device's join all stay, as with any app update.

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
4. On the phone, once: uninstall any debug build (it has a different key; you will need a new
   invite code, `tt-admin invite`), open the repository's Releases page, install the APK and
   allow your browser or file manager to install apps. From then on, use CHECK FOR UPDATES.

The release APK has no server address or secrets in it (the app asks for the address on first
start); releases of a public repository can be downloaded by anyone, but are no use without your
server and an invite code.

Settings: `tt.updateRepo` / `TT_UPDATE_REPO` names the repository the app checks (`owner/name`;
in GitHub Actions it defaults to the repository being built; blank turns the button off). A local
build can be signed with the same key through `tt.signing.storeFile`, `tt.signing.storePassword`,
`tt.signing.keyAlias` and `tt.signing.keyPassword` in `local.properties` (or `TT_SIGNING_*`
environment variables); `tt.versionCode` / `TT_VERSION_CODE` sets the build number. The demo
flavour does not update itself.

## Push notifications (optional)

Push needs a Firebase project (backend plan section 7.1). Without `androidApp/google-services.json`
the app builds and runs normally with push off.

1. In the Firebase console, add an Android app with package name `app.transittracker`.
2. Download `google-services.json` into `androidApp/` (it identifies the project; it is not a
   secret, so committing it is fine, but you may keep it local).
3. Build again. The build applies the Google services plugin when the file is there (for the live
   flavour; the demo flavour skips it). The app then registers its FCM token with the server
   (`TransitCore.updatePushToken`) at start and whenever Firebase issues a new one.

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
  ServerAddressScreen.kt        first-start server address (live, when not built in)
  AppUpdates.kt                 in-app updates: GitHub releases, download and checks,
                                PackageInstaller session, installer and "updated" receivers
  NotificationData.kt           tap extras -> data map
src/live/…/FlavorGraph.kt       real core (TransitCoreFactory) + stores; the journey planner is
                                the core's own client (`core.planner`: tt-server's /v1/places
                                and /v1/plan)
src/demo/…/FlavorGraph.kt       AppDeps.fake on the real clock
```

Android glue for the core is in `shared/core/src/androidMain` (OkHttp engine, properties-file
key-value store, file store with atomic rename, zstd, network status) and for packs in
`shared/timetable/src/androidMain` (a read-only framework-SQLite driver: packs are opened with
`OPEN_READONLY` and never modified). Their Robolectric tests are in `src/androidHostTest`.

**Backup.** Auto backup includes only `files/kv/backup.properties` (display settings, saved
trips and places). The device token and id, push token, server address, caches and timetable
packs are in `noBackupFilesDir` and are never backed up; a restored phone joins again.

## Not built yet

- GO mode (plan 5.6): following a planned journey leg by leg; nothing Android-specific for it
  exists yet (no ongoing notification or foreground service).
- Alarms (plan 5.10).
- The home-screen widget (plan 5.12, Jetpack Glance).
- iOS (`/iosApp`): the shared UI already uses multiplatform window insets so it carries over.
- R8: release builds are unminified for now (and signed only when the signing key is given; see Updates).
- An in-app banner for messages that arrive while the app is open (the plan's 5.12); for now
  they are posted as ordinary notifications.
