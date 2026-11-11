# Running tt-server at home

This folder runs the TransitTracker server on an always-on computer at home, reachable from
anywhere over HTTPS through a **Cloudflare Tunnel** (Cloudflare Zero Trust, free plan). Nothing on
your router changes, no ports are opened, and no other device on your network becomes reachable.

```
phone ──HTTPS──> Cloudflare (tt.example.com) ──tunnel──> [cloudflared container] ──http://tt-server:8080──> [tt-server container]
                                                                                                             └─ /data volume (SQLite db, timetables)
```

| File | What it is |
|---|---|
| `docker-compose.yml` | The two containers: `cloudflared` (the tunnel) and `tt-server` |
| `Dockerfile` | How the tt-server image is built (from the repository root) |
| `.env.example` | Every setting, with comments. Copy to `.env` and fill in |
| `tt-admin` | Runs admin commands (invites, devices, backups) inside the container |
| `backup.sh` | Nightly backup, keeps 14 days |
| `hermes-proxmox-deploy-prompt.md` | Hand-off prompt for an AI agent to do this whole setup on Proxmox |

Budget about an hour for the first setup. Commands below are typed in a terminal on the home
server (directly or over SSH).

---

## 1. What you need

- An always-on **Linux** computer: a mini PC, an old laptop, a NUC, a Raspberry Pi 4/5 with
  4 GB+ RAM, or a NAS that runs Docker. 64-bit (x86-64 or ARM64).
- About 1 GB of free RAM (2 GB once buses are added) and a few GB of disk.
- **A domain whose DNS is on Cloudflare** (about A$15/year if you don't already have one), and a
  free Cloudflare Zero Trust account.
- **Home Assistant OS cannot run this.** HA OS only runs Home Assistant add-ons, not Docker
  Compose stacks. Use a different machine, or create a separate Debian container or virtual
  machine on the same host and follow this guide inside it.
- **On Proxmox** either works:
  - **LXC container** (lighter): Debian 12 template, **unprivileged**, with
    *Options → Features → nesting* and *keyctl* ticked (`features: nesting=1,keyctl=1`), 2 cores,
    2 GB RAM + 1 GB swap (raise to 4 GB if the first image build runs out of memory), 16–32 GB disk.
    Proxmox's own guidance prefers VMs for Docker, and Docker or Proxmox updates have occasionally
    broken Docker inside LXC; if that happens, use a VM.
  - **VM** (most robust): Debian 12, 2 vCPU, 4 GB RAM, 32 GB disk, `qemu-guest-agent`.

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

## 6. Set up the Cloudflare Tunnel (Cloudflare Zero Trust, free)

You need a domain whose DNS is managed by Cloudflare. In the Cloudflare dashboard:

1. **Zero Trust → Networks → Tunnels → Create a tunnel.** Choose **Cloudflared**, name it
   `transittracker`, then pick **Docker** as the environment. Copy the long token after
   `--token` in the command it shows (don't run the command). It goes in `.env` as
   `CLOUDFLARE_TUNNEL_TOKEN`.
2. **Public hostname** (next page of the same wizard, or the tunnel's *Public Hostname* tab):
   subdomain `tt`, your domain, service type **HTTP**, URL **`tt-server:8080`**. Save.
3. **Protect the admin page with Access** (*Zero Trust → Access → Applications → Add →
   Self-hosted*): application domain `tt.<your-domain>`, path `status`; add a policy
   **Allow** → *Emails* → your email address. Login by one-time PIN is fine.
   **Do not** add an Access application for the whole hostname or for `/v1/*`: the phone app
   cannot do an Access login. The API has its own protection (invite codes and device tokens).
4. **Don't let bot protection block the app.** In the main dashboard for your domain:
   *Security → Bots*: keep **Bot Fight Mode** off, or add a WAF custom rule
   (*Security → WAF → Custom rules*) for hostname `tt.<your-domain>` and URI path starting with
   `/v1/` with action **Skip** (all remaining custom rules and Super Bot Fight Mode).
   *SSL/TLS → Overview*: **Full**. No caching rules are needed.

## 7. Configure

```bash
cp .env.example .env
chmod 600 .env
nano .env
```

Fill in at least `CLOUDFLARE_TUNNEL_TOKEN`, `TFNSW_API_KEY`, `TT_PUBLIC_BASE_URL`
(`https://tt.<your-domain>`) and, if you did step 5,
`FIREBASE_SERVICE_ACCOUNT=/secrets/firebase-service-account.json` and `FIREBASE_PROJECT_ID`.
Set `TT_ADMIN_TOKEN` to a long random value if you want to see `/status` over the internet:

```bash
openssl rand -base64 32
```

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
`tt-server listening on port 8080`, and the tunnel shows **Healthy** in Zero Trust → Networks →
Tunnels. With `TFNSW_API_KEY` set, the server builds the first timetable straight away (look for
`Timetable refresh (startup)` in the log); after that it checks for new timetables every night.

## 9. Check it from a phone

Turn off Wi-Fi on your phone (so you test from the internet, like your friends will) and open:

```
https://tt.<your-domain>/health
```

You should see `{"ok":true,"timetableLoaded":...}`. `timetableLoaded` becomes `true` after the
first timetable build. `https://tt.<your-domain>/status` should ask you to log in with Cloudflare
Access.

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

`tt-admin import-gtfs <pack> <zip-or-dir...>` builds a timetable pack from GTFS files you already
have (the files must be inside the container, e.g. copied into the `tt-data` volume). You normally
never need it: the server builds packs from TfNSW every night. A running server picks up an
imported pack at its next start (`docker compose restart tt-server`).

## 11. The status page

On the server itself:

```bash
docker compose exec tt-server curl -s -H 'Accept: application/json' localhost:8080/status
```

From anywhere: open `https://tt.<your-domain>/status` and log in through Cloudflare Access, then
the server also wants the `TT_ADMIN_TOKEN` from `.env` (two locks):

```bash
curl -H "Authorization: Bearer <TT_ADMIN_TOKEN>" https://tt.<your-domain>/status   # needs an Access session or service token too
```

It shows TfNSW calls used today, the age of each live feed, timetable packs, device count
and recent notifications. Requests through the tunnel without the token get `403`.

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
`https://tt.<your-domain>/health` every 5 minutes (UptimeRobot: type *Keyword*, keyword
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
(it restores `.env` and `secrets/`, including the tunnel token), stop the old machine so only one
copy runs the tunnel, run `docker compose up -d --build`, then restore the database as above. Friends' phones keep working because their device tokens are in the database.

## Troubleshooting

| Symptom | Check |
|---|---|
| `docker compose` says `Set CLOUDFLARE_TUNNEL_TOKEN` | Fill it in `.env` (step 6.1) |
| `/health` does not load from the phone | `docker compose logs cloudflared`; tunnel **Healthy** in Zero Trust; public hostname points to `http://tt-server:8080` |
| Phone app gets a Cloudflare challenge page or 403 | Bot Fight Mode / WAF rule (step 6.4); no Access application covering `/v1/*` (step 6.3) |
| Docker won't start inside a Proxmox LXC | Container is unprivileged with `nesting=1,keyctl=1`; if a Docker/Proxmox update broke it, move to a VM |
| Server keeps restarting | `docker compose logs tt-server` |
| `timetableLoaded` stays `false` | `TFNSW_API_KEY` set and the products added to your application (step 4); see the log, then `docker compose restart tt-server` |
| Friends get no notifications | `FIREBASE_*` settings, the file in `secrets/`, and `./tt-admin devices` shows `PUSH yes` |
