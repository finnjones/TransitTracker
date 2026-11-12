# Task: set up Firebase sign-in and saved data for TransitTracker (using the Firebase MCP server)

You are finishing the Firebase side of TransitTracker for me, mostly through the **Firebase MCP
server**, plus the TransitTracker server you deployed earlier on my Proxmox LXC
(`/opt/transittracker`, see `deploy/hermes-proxmox-deploy-prompt.md`). Work carefully. Before
each stage, say what you are about to do. Stop and ask me whenever something is unclear,
unexpected, or can't be undone. I'm here to answer questions and to click things in the
console when a tool can't do it.

## What it is

The app (`app.transittracker`, Android) no longer uses invite codes. It signs in with Google
through **Firebase Authentication** and sends Firebase's ID token to tt-server
(`POST /v1/join` with `idToken`). The server checks the token against the Firebase project and
an allow list (`tt-admin allow <email>`). While a user is signed in, the app also keeps their
saved trips, places, display settings and recent searches in **Cloud Firestore** at
`users/{uid}/prefs/{key}`. tt-server never touches Firestore.

- Firebase project: **`transittracker-633f552b`** (project number 763529417265).
- Android app: package `app.transittracker`, app id `1:763529417265:android:e4123f459f6b1628a7fb10`.
- Repo: `github.com/finnjones/TransitTracker` (private). The sign-in code is on branch
  **`claude/exciting-curie-cynorg`** until I merge it to `main`. **Ask me** which branch to use.
- Source of truth: read these in the repo before you start. They win if they differ from this prompt.
  - `androidApp/README.md`, section "Sign-in and saved data"
  - `deploy/README.md`, steps 5 and 10
  - `deploy/.env.example`
  - `firebase/firestore.rules` and `firebase/README.md`

## Ground rules

1. **Only this Firebase project.** Never create, change or delete anything in any other Firebase or
   Google Cloud project. Never delete Firebase apps, users, databases or keys.
2. **Ask before anything permanent.** Two things here can't be undone:
   - **the Firestore database location**, which can never change;
   - **creating a service-account key**, which is a secret.

   Show me exactly what you will run and wait for my OK.
3. **Secrets:** the Firebase service-account JSON and anything in `/opt/transittracker/deploy/.env`.
   Never print them, log them or put them in your notes, and never commit them.
   The following are **not** secret and can be shown:
   - `google-services.json` (it identifies the app and is meant to ship inside it);
   - SHA fingerprints;
   - the project id.
4. **Don't push to the repo** unless I say so (see step 5).
5. **Tool order:** prefer the Firebase MCP server's tools. When it has no tool for a step, use the
   Firebase CLI (`firebase …`, same login) or `gcloud`. Fall back to guiding me through the console
   only for what neither can do.
6. **Keep a log** of what you did (no secrets): append it to `/opt/transittracker/DEPLOYED.md`
   under a "Firebase" heading.

## Before you start, check with me

- **The MCP server:** that it's connected and logged in as an account that owns
  `transittracker-633f552b`. It's normally `npx -y firebase-tools@latest mcp`, after
  `firebase login`. List the MCP tools you have and tell me which steps below they cover. Tool
  names change between versions, so don't assume them.
- **The branch** to deploy on the server.
- **Who to let in:** my email address, and any friends' addresses.
- **SHA fingerprints:** the SHA-1 and SHA-256 of my **release signing key**. I'll run
  `keytool -list -v -keystore transittracker-release.jks -alias transittracker` myself and paste
  you the two fingerprint lines. They're public, but the keystore and its passwords are secret, so
  never ask for those. Optionally, also my debug key's fingerprints from
  `./gradlew :androidApp:signingReport`.
- **Firestore location:** propose **`australia-southeast1`** (Sydney) and wait for my OK.
- **Push notifications:** whether I want them set up now (step 6).

Then show me the plan and wait for my OK.

## Steps

### 1. Check the project
- With the MCP, get the project and list its apps. Confirm the Android app `app.transittracker`
  exists with the app id above. If it doesn't, stop and tell me; don't create a different one.
- Check which Authentication sign-in providers are enabled.

### 2. Turn on Google sign-in
- If **Google** is not enabled:
  - Enable it with an MCP tool if one exists.
  - Otherwise guide me in the console: **Authentication → Get started (if shown) → Sign-in method →
    Google → Enable**, with my email as the project support email → **Save**.

  The console creates the OAuth web client the app needs. Don't try to make OAuth clients by hand
  in Google Cloud.
- Afterwards, check again that Google is enabled.

### 3. Add the SHA fingerprints
- Add the release SHA-1 and SHA-256 (and the debug ones if I gave them) to the Android app
  `app.transittracker`. Use the MCP tool for Android SHA certificates, or
  `firebase apps:android:sha:create <appId> <sha>`.
- List the app's SHA certificates and show me they're all there.
- Sign-in only works for APKs signed with a listed key. CI debug APKs use a new key each run, so
  they won't work; tell me that.

### 4. Cloud Firestore
- If the project has no Firestore database yet, create the **(default)** database in **Native
  mode** at the location I approved. Use the MCP if it can, otherwise:
  - `firebase firestore:databases:create "(default)" --location <loc> --project transittracker-633f552b`, or
  - `gcloud firestore databases create --location=<loc> --project=transittracker-633f552b`.

  Stay on the free Spark plan: don't enable billing. If a step says billing is required, stop and
  ask me.
- **Security rules:** use `firebase/firestore.rules` from the repo, exactly as it is.
  - Validate it first (the MCP has a rules-validation tool in recent versions).
  - Then deploy it. The repo has no `firebase.json`, so do this in a scratch directory outside the
    repo: copy `firestore.rules` there, write
    `{"firestore": {"rules": "firestore.rules"}}` to a `firebase.json`, and run
    `firebase deploy --only firestore:rules --project transittracker-633f552b`.
  - Read the published rules back (MCP or console) and confirm they match the file.
- Don't create any documents. The app creates `users/{uid}/prefs/*` itself.

### 5. Get the new `google-services.json`
- Download the Android app's config again: use the MCP's SDK-config tool for the Android app, or
  `firebase apps:sdkconfig ANDROID 1:763529417265:android:e4123f459f6b1628a7fb10`.
- **Check it:** `client[0].oauth_client` must now include an entry with `"client_type": 3` (the
  web client id). If it's still empty, Google sign-in isn't really enabled: go back to step 2.
- Show me a diff against `androidApp/google-services.json` in the repo. Then ask me whether to:
  - (a) commit it to the branch myself;
  - (b) have you commit and push it to the branch (only if I say so); or
  - (c) just hand it to me.

  If I use the `GOOGLE_SERVICES_JSON` GitHub Actions secret, remind me to update it too.

### 6. Push notifications (only if I said yes)
- Check **Project settings → Cloud Messaging**: **Firebase Cloud Messaging API (V1)** must be
  enabled. Enable it with
  `gcloud services enable fcm.googleapis.com --project transittracker-633f552b` if it isn't.
- Creating a service-account key makes a secret, so **ask first**.
  - Generate it for the `firebase-adminsdk-…` service account. Use the console (Project settings →
    Service accounts → Generate new private key) or `gcloud iam service-accounts keys create`.
    Write it **directly** to `/opt/transittracker/deploy/secrets/firebase-service-account.json` in
    the LXC.
  - Set mode 600, owned by `tt`. Never print it, and delete any temporary copies.

### 7. Server: update, configure, restart
In the LXC, as `tt`, from `/opt/transittracker`:
1. **Back up first.** The new server version migrates the database to schema v2 and there's no
   way back. Run `deploy/backup.sh` and confirm the backup file exists.
2. Take a Proxmox snapshot of the container too (ask first).
3. Check out the agreed branch and `git pull`.
4. In `deploy/.env` (keep it mode 600):
   - set `FIREBASE_PROJECT_ID=transittracker-633f552b`;
   - set `FIREBASE_SERVICE_ACCOUNT=/secrets/firebase-service-account.json` if step 6 was done;
   - leave everything else alone.
5. Run `docker compose build && docker compose up -d` from `deploy/`.
6. Check `docker compose logs tt-server`:
   - expect `Database migrated to schema v2`;
   - expect `Sign-in with Firebase Authentication for project transittracker-633f552b`;
   - with push, expect `Push notifications via FCM for project transittracker-633f552b`;
   - expect no errors.

   Redact anything that looks secret if you show me logs.
7. Let people in:
   - run `deploy/tt-admin allow <my email> --note "owner"`, and one per friend I named;
   - then `deploy/tt-admin allowed` and show me the list.

   Don't use `allow '*'` unless I explicitly ask.

### 8. Verify what you can
- `https://tt.<my-domain>/health` returns OK. The app now defaults to `https://tt.finnjones.me`,
  so confirm that's the tunnel's public hostname. If it isn't, tell me.
- **The server checks sign-ins:** run
  `curl -sS -X POST https://tt.finnjones.me/v1/join -H 'Content-Type: application/json' -d '{"idToken":"not-a-token"}'`.
  - Expected: **401** with code `sign_in_invalid`.
  - **503** `sign_in_unavailable` means `FIREBASE_PROJECT_ID` didn't take effect.
  - Also check that the container can reach `www.googleapis.com` (that's where the token keys
    come from).
- **Firestore rules:** if the MCP can test rules, show that:
  - a signed-in user `u1` can write `users/u1/prefs/trips` with `{value: "[]", updatedMs: 1}`;
  - `u1` can't read `users/u2/prefs/trips`;
  - an unauthenticated read is denied.

  If it can't, say so; don't set up emulators.
- **You can't do a real Google sign-in.** Tell me how to test it on my phone:
  1. Install the release APK built with the new `google-services.json`.
  2. On first start, tap **SIGN IN WITH GOOGLE** and pick my allowed account. It should move on
     to "Getting the timetable".
  3. Save a trip, then check in the Firestore console that `users/<my uid>/prefs/trips` appeared.
  4. Check `deploy/tt-admin devices` shows my email next to the new device.

### 9. Report back
Give me a short summary:
- Google provider status and the SHA fingerprints registered.
- Firestore: location, mode, and rules deployed (yes/no, and how verified).
- `google-services.json`: whether it now has the web client, and where it went.
- Push: set up or not.
- Server: commit deployed, schema version, and the sign-in log line seen.
- Who is on the allow list.
- What you verified and how.
- Anything left for me to do. At minimum, the on-phone sign-in test, plus updating the CI
  secret if one is used.
