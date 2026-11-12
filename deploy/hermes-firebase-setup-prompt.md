# Task: finish the Firebase setup for TransitTracker (using the Firebase MCP server)

You are finishing the Firebase side of TransitTracker for me, through the **Firebase MCP server**.
After that, if I say so, you will point my tt-server at it. That server is the one on my Proxmox
LXC at `/opt/transittracker`; see `deploy/hermes-proxmox-deploy-prompt.md`.

Work carefully. Before each stage, say what you are about to do. Stop and ask me whenever
something is unclear, unexpected, or can't be undone. I'm here to answer questions and to click
things in the console when a tool can't do it.

## What it is

The Android app `app.transittracker` signs in with Google through **Firebase Authentication**.
It sends Firebase's ID token to tt-server (`POST /v1/join`). The server checks the token against
the Firebase project and an allow list (`tt-admin allow <email>`).

While a user is signed in, the app also keeps their saved trips, places, display settings and
recent searches in **Cloud Firestore**, at `users/{uid}/prefs/{key}`. tt-server never touches
Firestore.

- **Firebase project:** `transittracker-633f552b` (project number 763529417265).
- **Android app:** package `app.transittracker`, app id `1:763529417265:android:e4123f459f6b1628a7fb10`.
- **Repo:** `github.com/finnjones/TransitTracker` (private). The sign-in code is on **`main`**.
- **Source of truth:** read these in the repo before you start. They win if they differ from
  this prompt.
  - `firebase/firestore.rules` and `firebase/README.md`
  - `androidApp/README.md`, section "Sign-in and saved data"
  - `deploy/README.md`, steps 5 and 10, and `deploy/.env.example`

## Already done (check these; don't redo them)

- **Google sign-in provider:** enabled in Authentication.
- **`google-services.json`:** the copy with the OAuth web client is committed in
  `androidApp/google-services.json` on `main`.
  - It has a `client_type: 3` client, `763529417265-nc3n9kq6hdvu32csfin36v0psc9lig7p.apps.googleusercontent.com`.
  - **Don't download, replace or commit it.**

## Left to do

1. Register the SHA fingerprints of my signing key(s) on the Android app.
2. Create the Firestore database and publish the security rules.
3. Optionally, push notifications: the FCM API and a service-account key for the server.
4. Optionally, the server: `FIREBASE_PROJECT_ID`, deploy `main`, the allow list.

## Ground rules

1. **Only this Firebase project.** Never create, change or delete anything in any other Firebase or
   Google Cloud project. Never delete Firebase apps, users, databases, OAuth clients or keys.
2. **Ask before anything that can't be undone.** Show me exactly what you will run and wait for my
   OK. In this task that means:
   - the Firestore database location, which can never change;
   - creating a service-account key, which is a secret;
   - anything that changes the server.
3. **Secrets.** These are secret:
   - the service-account JSON;
   - anything in `/opt/transittracker/deploy/.env`;
   - my keystore and its passwords. Never ask me for these.

   Never print secrets, log them, put them in your notes or commit them. These are public and fine
   to show: SHA fingerprints, the project id, OAuth client ids.
4. **Don't push to the repo.** Nothing in this task needs a commit.
5. **Tools.** Prefer the Firebase MCP server's tools. Where it has none, use the Firebase CLI
   (`firebase …`, same login) or `gcloud`. Fall back to walking me through the console only when
   neither works.
6. **Stay on the free Spark plan.** Never enable billing. If a step says billing is required, stop
   and ask me.
7. **Log.** If you touch the server, append what you did (no secrets) to
   `/opt/transittracker/DEPLOYED.md` under a "Firebase" heading.

## Before you start, check with me

- **The MCP server.** Confirm it's connected and logged in as an account that owns
  `transittracker-633f552b` (normally `npx -y firebase-tools@latest mcp` after `firebase login`).
  List the MCP tools you have and tell me which steps below they cover. Tool names change between
  versions, so don't assume them.
- **SHA fingerprints.** You need the SHA-1 and SHA-256 of my **release signing key**. I'll run
  `keytool -list -v -keystore transittracker-release.jks -alias transittracker` myself and paste
  you the two fingerprint lines. Ask whether I also want my **debug key** added (from
  `./gradlew :androidApp:signingReport`) for local debug builds.
- **Firestore location.** Propose **`australia-southeast1`** (Sydney) and wait for my OK.
- **Push notifications.** Do I want them set up now (step 4)?
- **The server.** Should you do step 5? If so, which emails go on the allow list?

Then show me the plan and wait for my OK.

## Steps

### 1. Check the starting point
- Get the project and list its apps. Confirm the Android app `app.transittracker` exists with the
  app id above. If it doesn't, stop and tell me; don't create another one.
- Confirm **Google** is an enabled sign-in provider. If it isn't, guide me: console →
  Authentication → Sign-in method → Google → Enable, with my email as support email → Save.
- Fetch the Android app's SDK config with the MCP (or
  `firebase apps:sdkconfig ANDROID 1:763529417265:android:e4123f459f6b1628a7fb10`). Check it
  contains the `client_type: 3` client id above. **Only compare; don't save it over the repo's
  file.** If it differs, tell me.
- List the SHA certificates already on the Android app.

### 2. SHA fingerprints
- Add each fingerprint I gave you that isn't there yet to `app.transittracker`: SHA-1 and SHA-256
  of the release key, plus the debug key's if I asked for it. Use the MCP's Android SHA tool, or
  `firebase apps:android:sha:create <appId> <sha>`.
- List the app's certificates again and show me they're all there.
- Tell me: Google sign-in only works in APKs signed with a listed key. CI debug APKs are signed
  with a fresh key each run, so I should use a release or a local build.

### 3. Cloud Firestore
- If the project has no Firestore database, create the **(default)** database in **Native mode**,
  at the location I approved. Use the MCP if it can. Otherwise use one of:
  - `firebase firestore:databases:create "(default)" --location <loc> --project transittracker-633f552b`
  - `gcloud firestore databases create --location=<loc> --project=transittracker-633f552b`
- **Security rules.** Use `firebase/firestore.rules` from `main`, exactly as it is.
  1. Validate it first; recent MCP versions have a rules-validation tool.
  2. Deploy it. The repo has no `firebase.json`, so work in a scratch directory **outside the
     repo**:
     - copy `firestore.rules` into it;
     - write a `firebase.json` containing `{"firestore": {"rules": "firestore.rules"}}`;
     - run `firebase deploy --only firestore:rules --project transittracker-633f552b`.
  3. Read the published rules back (MCP or console) and confirm they match the file.
- **Test the rules** if the MCP can. Show that:
  - signed-in user `u1` **can** write `users/u1/prefs/trips` with `{value: "[]", updatedMs: 1}`;
  - `u1` **cannot** read `users/u2/prefs/trips`;
  - an unauthenticated read is **denied**;
  - a document with an extra field is **denied**.

  If the MCP can't test rules, say so. Don't set up emulators.
- Don't create any documents. The app creates `users/{uid}/prefs/*` itself.

### 4. Push notifications (only if I said yes)
- Check that **Firebase Cloud Messaging API (V1)** is enabled
  (`gcloud services list --enabled --project transittracker-633f552b | grep fcm`). If it isn't,
  ask, then run `gcloud services enable fcm.googleapis.com --project transittracker-633f552b`.
- A service-account key is a secret, so **ask before creating one**.
  - Create it for the `firebase-adminsdk-…` service account, either in the console (Project
    settings → Service accounts → Generate new private key) or with
    `gcloud iam service-accounts keys create`.
  - Write it **straight** to `/opt/transittracker/deploy/secrets/firebase-service-account.json` in
    the LXC, mode 600, owned by `tt`.
  - Never print it. Delete any temporary copy.

### 5. Server (only if I said yes)
In the LXC, as `tt`, from `/opt/transittracker`:
1. **Back up first.** This version migrates the database to schema v2, and there's no way back.
   Run `deploy/backup.sh` and confirm the new backup file exists. Suggest a Proxmox snapshot of
   the container too (ask first).
2. `git checkout main && git pull`.
3. In `deploy/.env` (keep it mode 600), set `FIREBASE_PROJECT_ID=transittracker-633f552b`. If you
   did step 4, also set `FIREBASE_SERVICE_ACCOUNT=/secrets/firebase-service-account.json`. Leave
   everything else alone.
4. From `deploy/`, run `docker compose build && docker compose up -d`.
5. Check `docker compose logs tt-server` for these lines and no errors:
   - `Database migrated to schema v2`
   - `Sign-in with Firebase Authentication for project transittracker-633f552b`
   - with push: `Push notifications via FCM for project transittracker-633f552b`

   Redact anything secret-looking if you show me logs.
6. For each email I gave you, run `deploy/tt-admin allow <email> --note "<name>"`. Then show me
   `deploy/tt-admin allowed`. Never `allow '*'` unless I ask for it explicitly.
7. Check the server rejects bad sign-ins:

   ```bash
   curl -sS -X POST https://tt.finnjones.me/v1/join -H 'Content-Type: application/json' -d '{"idToken":"not-a-token"}'
   ```

   - **401 `sign_in_invalid`** is right: the server is checking sign-ins.
   - **503 `sign_in_unavailable`** means `FIREBASE_PROJECT_ID` didn't take effect.
   - Also confirm the container can reach `www.googleapis.com`; that's where the token keys come
     from.

### 6. Tell me how to test on my phone
You can't do a real Google sign-in, so give me these steps:
1. Install the newest release from the repo's Releases page. Or use CHECK FOR UPDATES in the app.
2. On first start (or after Settings → SIGN OUT), tap **SIGN IN WITH GOOGLE** and pick an allowed
   account. It should go on to "Getting the timetable".
3. Save a trip.
4. In the Firestore console, `users/<my uid>/prefs/trips` should appear.
5. `deploy/tt-admin devices` should show my email next to the new device.

### 7. Report back
Give me a short summary:
- the Google provider status, and the SHA fingerprints now registered;
- Firestore: location, mode, rules deployed, and how you verified them;
- push: done or not;
- server (if done): commit deployed, schema version, the sign-in log line you saw, who is on the
  allow list, and the `/v1/join` check result;
- anything I still need to do.
