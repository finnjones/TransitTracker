# Running tt-server at home

This folder runs the TransitTracker server on an always-on computer at home, reachable from
anywhere at a free HTTPS address through **Tailscale Funnel**. Nothing on your router changes
and no other device on your network becomes reachable.

```
phone ──HTTPS──> Tailscale Funnel ──> [tailscale container] ──127.0.0.1:8080──> [tt-server container]
                                                                                   └─ /data volume (SQLite db, timetables)
```

| File | What it is |
|---|---|
| `docker-compose.yml` | The two containers: `tailscale` (public HTTPS) and `tt-server` |
| `Dockerfile` | How the tt-server image is built (from the repository root) |
| `.env.example` | Every setting, with comments. Copy to `.env` and fill in |
| `tailscale/serve.json` | Tells Tailscale to publish port 443 with Funnel and forward it to tt-server |
| `tt-admin` | Runs admin commands (invites, devices, backups) inside the container |
| `backup.sh` | Nightly backup, keeps 14 days |

Budget about an hour for the first setup. Commands below are typed in a terminal on the home
server (directly or over SSH).

---

## 1. What you need

- An always-on **Linux** computer: a mini PC, an old laptop, a NUC, a Raspberry Pi 4/5 with
  4 GB+ RAM, or a NAS that runs Docker. 64-bit (x86-64 or ARM64).
- About 1 GB of free RAM (2 GB once buses are added) and a few GB of disk.
- **Home Assistant OS cannot run this.** HA OS only runs Home Assistant add-ons, not Docker
  Compose stacks. Use a different machine, or create a separate Debian/Ubuntu virtual machine
  on the same host (for example in Proxmox) and follow this guide inside it.

## 2. Install Docker

On Debian, Ubuntu or Raspberry Pi OS:

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker "$USER"     # lets you run docker without sudo
```

Log out and back in, then check both work:

```bash
docker run --rm hello-world
docker compose version
```

Docker starts automatically at boot, and the containers restart themselves after a reboot or crash.

## 3. Get the code

```bash
sudo apt-get install -y git
git clone https://github.com/<you>/TransitTracker.git
cd TransitTracker/deploy
```

The repository is private: GitHub will ask for your username and a
[personal access token](https://github.com/settings/tokens) (classic, `repo` scope) as the password.

## 4. Get a TfNSW Open Data Hub API key (free)

1. Register at <https://opendata.transport.nsw.gov.au> and sign in.
2. Go to **My account → Applications → Add application**. Give it a name ("TransitTracker home").
3. Add these products to the application (names on the portal may differ slightly; search for them):
   - **Public Transport - Timetables - For Realtime** (GTFS static: the nightly timetable build)
   - **Public Transport - Realtime Trip Update** (v2: delays and cancellations)
   - **Public Transport - Realtime Vehicle Positions** (v2: the map)
   - **Public Transport - Realtime Alerts** (v2: disruptions)
4. Copy the **API key** shown for the application. It goes in `.env` as `TFNSW_API_KEY` (step 7).
   Treat it like a password: it never goes on a phone or into git.

## 5. Create a Firebase project for push notifications (free)

Skip this section to start without push notifications; you can add it later.

1. Go to <https://console.firebase.google.com>, **Create a project** (e.g. "transittracker").
   Google Analytics is not needed. The project stays on the free **Spark** plan; no billing account.
2. Add the Android app (**Project overview → Add app → Android**) with the app's package name.
   The `google-services.json` it gives you belongs to the app build, not to this server.
3. **Project settings → Cloud Messaging**: check that **Firebase Cloud Messaging API (V1)** is
   *Enabled*.
4. **Project settings → Service accounts → Generate new private key**. A JSON file downloads.
   This file is a secret.
5. Put it on the server as `deploy/secrets/firebase-service-account.json`:

   ```bash
   mkdir -p secrets && chmod 700 secrets
   # copy the downloaded file into secrets/ (for example with scp), then:
   mv secrets/<downloaded-name>.json secrets/firebase-service-account.json
   chmod 644 secrets/firebase-service-account.json   # the folder stays private (700)
   ```

6. Note the **Project ID** (Project settings → General). It goes in `.env` as `FIREBASE_PROJECT_ID`.

## 6. Set up Tailscale and Funnel (free Personal plan)

1. Create an account at <https://login.tailscale.com> (sign in with Google, GitHub, etc.).
   You do **not** need to install Tailscale on the server itself; it runs in a container.
   Your friends never need Tailscale.
2. **DNS** page of the admin console: make sure **MagicDNS** is on, then under
   **HTTPS Certificates** click **Enable HTTPS**.
3. **Access controls** page: allow Funnel by adding a `nodeAttrs` entry to the policy file
   (keep everything else that is there) and save:

   ```jsonc
   "nodeAttrs": [
     { "target": ["autogroup:member"], "attr": ["funnel"] }
   ]
   ```

   If a `nodeAttrs` section already exists, add the `{ ... }` line inside it instead.
4. **Settings → Keys → Generate auth key**: leave *Reusable* and *Ephemeral* off, click
   **Generate**, and copy the key (`tskey-auth-...`). It goes in `.env` as `TS_AUTHKEY`.
   It is only used the first time the container logs in.

## 7. Configure

```bash
cp .env.example .env
chmod 600 .env
nano .env
```

Fill in at least `TS_AUTHKEY`, `TFNSW_API_KEY` and, if you did step 5,
`FIREBASE_SERVICE_ACCOUNT=/secrets/firebase-service-account.json` and `FIREBASE_PROJECT_ID`.
Set `TT_ADMIN_TOKEN` to a long random value if you want to see `/status` over the internet:

```bash
openssl rand -base64 32
```

Leave `TT_PUBLIC_BASE_URL` as it is for now; you will know the real address after step 8.
Everything else has sensible defaults and is explained in the file.

## 8. Start it

```bash
docker compose up -d --build
```

The first build downloads Gradle and dependencies and takes 5–15 minutes (longer on a Raspberry
Pi). Watch progress and the server log with:

```bash
docker compose logs -f
```

Press Ctrl+C to stop watching (the server keeps running). When it is up you will see
`tt-server listening on port 8080`.

Find your public address:

```bash
docker compose exec tailscale tailscale funnel status
```

It looks like `https://tt.tail1234.ts.net`. Put it in `.env` as `TT_PUBLIC_BASE_URL`, then apply:

```bash
docker compose up -d
```

In the Tailscale admin console (**Machines**), open the `tt` machine's menu and choose
**Disable key expiry**, otherwise it is logged out after 180 days. You can now delete the
`TS_AUTHKEY` value from `.env`.

## 9. Check it from a phone

Turn off Wi-Fi on your phone (so you test from the internet, like your friends will) and open:

```
https://tt.<your-tailnet>.ts.net/health
```

You should see `{"ok":true,"timetableLoaded":...}`. A brand-new Funnel address can take up to
about 10 minutes to start working. `timetableLoaded` becomes `true` after the first timetable build.

## 10. Invite a friend

```bash
./tt-admin invite --expires-days 7 --note "Sam"
```

It prints a code like `K7QM-3XWP`. Send it privately; it works once. Friends type it on the
app's first screen. Other commands:

```bash
./tt-admin devices                 # who has joined, last seen
./tt-admin revoke <deviceId>       # cut a device off and delete its data
./tt-admin watches                 # everyone's push notification watches
./tt-admin notifications --limit 20
./tt-admin help
```

## 11. The status page

On the server itself:

```bash
docker compose exec tt-server curl -s -H 'Accept: application/json' localhost:8080/status
```

From anywhere, with the `TT_ADMIN_TOKEN` from `.env`:

```bash
curl -H "Authorization: Bearer <TT_ADMIN_TOKEN>" https://tt.<your-tailnet>.ts.net/status
```

It shows TfNSW calls used today, the age of each live feed, timetable packs, device count
and recent notifications. Without the token, requests through the public address get `403`.

## 12. Nightly backups

`backup.sh` writes a consistent copy of the database (safe while the server runs) plus your
`.env` and `secrets/` to `deploy/backups/`, and deletes backups older than 14 days. Try it once:

```bash
./backup.sh
```

Then schedule it every night at 2:30 with `crontab -e` and this line (fix the path):

```
30 2 * * * /home/<you>/TransitTracker/deploy/backup.sh >> /home/<you>/tt-backup.log 2>&1
```

Backups on the same disk do not survive a dead disk. Copy `deploy/backups/` somewhere else too,
for example a USB drive (`BACKUP_DIR=/mnt/usb/tt ./backup.sh`) or a free cloud drive with
[rclone](https://rclone.org). The backups contain your API keys: keep them private.
Timetables are not backed up; the server rebuilds them.

## 13. Free uptime monitoring

Create a free account at [UptimeRobot](https://uptimerobot.com) or
[Healthchecks.io](https://healthchecks.io) and add an **HTTP(S) monitor** for
`https://tt.<your-tailnet>.ts.net/health` every 5 minutes (UptimeRobot: type *Keyword*, keyword
`"ok":true`). You get an email or app notification if your server or home internet goes down.

## 14. Updating

From the repository:

```bash
cd ~/TransitTracker
git pull
cd deploy
docker compose up -d --build
```

Or, to use the image GitHub Actions publishes on every push to `main` (no build on the server):

1. Create a GitHub [personal access token](https://github.com/settings/tokens) (classic) with
   only `read:packages`, then `docker login ghcr.io -u <github-username>` and paste it.
2. Set `TT_IMAGE=ghcr.io/<github-owner>/<repo>/tt-server:latest` (all lower case) in `.env`.
3. Each update: `docker compose pull && docker compose up -d`.

The database is migrated automatically on start. Old images can be removed with
`docker image prune`.

## 15. Restoring from a backup

On the same machine:

```bash
cd ~/TransitTracker/deploy
docker compose stop tt-server
gunzip -k backups/tt-server-20261111-023000.db.gz          # pick the backup you want
docker compose run --rm --no-deps --user root -v "$PWD/backups:/restore:ro" --entrypoint sh tt-server -c \
  'cp /restore/tt-server-20261111-023000.db /data/tt-server.db && rm -f /data/tt-server.db-wal /data/tt-server.db-shm && chown tt:tt /data/tt-server.db'
docker compose start tt-server
```

On a new machine: do steps 2–3, unpack the matching `tt-config-*.tar.gz` inside `deploy/`
(it restores `.env` and `secrets/`), remove the old machine in the Tailscale admin console,
put a fresh `TS_AUTHKEY` in `.env`, run `docker compose up -d --build`, then restore the
database as above. Friends' phones keep working because their device tokens are in the database.

## Troubleshooting

| Symptom | Check |
|---|---|
| `docker compose up` fails with `/dev/net/tun` | Run `sudo modprobe tun`, or add `sys_module` under `cap_add` in `docker-compose.yml` |
| `/health` does not load from the phone | `docker compose logs tailscale`; Funnel enabled in the policy (step 6.3) and HTTPS certificates on (6.2); wait 10 minutes for a new address |
| Server keeps restarting | `docker compose logs tt-server` |
| `timetableLoaded` stays `false` | `TFNSW_API_KEY` set and the products added to your application (step 4); see the log |
| Friends get no notifications | `FIREBASE_*` settings, the file in `secrets/`, and `./tt-admin devices` shows `PUSH yes` |
| Lost the public address | `docker compose exec tailscale tailscale funnel status` |
