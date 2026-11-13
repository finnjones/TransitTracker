# Task: deploy TransitTracker on my Proxmox homelab behind Cloudflare Zero Trust

You are deploying a small self-hosted server for me. Work carefully, explain what you are
about to do before each stage, and stop and ask me whenever something is unclear or
unexpected. I will be available to answer questions and paste secrets.

## What it is

TransitTracker is a personal NSW public-transport app for me and a few friends. The server
(`tt-server`, Kotlin/JVM, one process, SQLite) pulls Transport for NSW open data, builds
timetable files for phones each night, serves live delays, and sends push notifications via
Firebase. It runs with Docker Compose next to a `cloudflared` container: Cloudflare Tunnel is the
only way in from the internet. Nothing is published on the host or my router.

- Repo: `github.com/finnjones/TransitTracker` (private). Branch: **ask me** (normally `main`).
- Read `deploy/README.md`, `deploy/docker-compose.yml` and `deploy/.env.example` in the repo before
  you start; they are the source of truth if they differ from this prompt.
- Resource needs: about 1 GB RAM at runtime; building the image with Gradle needs ~2–4 GB.
- Target: a **Proxmox LXC container** running Docker (my preference). Fall back to a VM only if
  Docker can't run properly in the container (see step 1).

## Ground rules

1. **Don't touch anything else.** Never stop, modify or delete existing Proxmox VMs, containers,
   storage, networks, firewall rules or backup jobs. Never change my router. If a step would
   affect anything other than the new container, ask first.
2. **Ask before anything destructive or hard to undo** on the Proxmox host.
3. **Secrets** (TfNSW API key, Firebase service-account JSON, Cloudflare tunnel token, admin
   token): ask me for them only when needed; write them only to
   `/opt/transittracker/deploy/.env` and `/opt/transittracker/deploy/secrets/` with mode 600,
   owned by the service user. Never print them, put them in shell history, logs, commit
   messages or your notes, and never commit or push anything to the repo.
4. **No inbound ports.** The container needs no inbound access except SSH from my LAN. Do not add port
   forwards, publish container ports, or open the firewall for HTTP/HTTPS.
5. Prefer official sources: Debian cloud image from cloud.debian.org, Docker from Docker's own apt
   repo, cloudflared image from Cloudflare.
6. Keep a log of what you did (no secrets) in `/opt/transittracker/DEPLOYED.md` in the container.

## Before you start, check with me

- How you will reach Proxmox (SSH to the host or an API token), which node, which storage for the
  disk, which bridge (usually `vmbr0`), DHCP or static IP, and which container ID (CTID) to use.
- My SSH public key for the container user.
- The Git branch to deploy.
- The public hostname, e.g. `tt.<my-domain>` (the domain is already on Cloudflare).

Then show me the plan (container spec + steps) and wait for my OK.

## Steps

### 1. Create the LXC container
- Download the current **Debian 12** standard template (`pveam update`, `pveam available`,
  `pveam download <storage> <template>`) if it isn't already on the host.
- `pct create` with: hostname `transittracker`, **unprivileged** (`--unprivileged 1`),
  **`--features nesting=1,keyctl=1`** (required for Docker), 2 cores, **2048 MB RAM + 1024 MB
  swap**, 24 GB root disk on the agreed storage, NIC on the agreed bridge (DHCP or static as
  agreed), `--onboot 1`, my SSH public key (`--ssh-public-keys`), timezone `Australia/Sydney`.
  Do not make it privileged and do not add any device passthrough (cloudflared needs no TUN
  device).
- Start it, find its IP, confirm SSH as root with my key works, then create a normal user `tt`
  with my SSH key and sudo, and disable SSH password login.
- If the root disk storage is ZFS, check that Docker uses the `overlay2` storage driver after
  installation (`docker info`); ZFS 2.2+ (Proxmox 8.1+) supports it.
- **Fallback to a VM** (ask me first): if Docker fails to run containers in the LXC (e.g. AppArmor,
  sysctl or cgroup permission errors that the features above don't fix), stop, show me the error,
  and propose a Debian 12 VM instead (cloud image via `qm` + cloud-init, 2 vCPU, 4 GB RAM, 32 GB
  disk, `qemu-guest-agent`, same user/SSH setup). Don't make the container privileged to work
  around it.

### 2. Base setup inside the container
- `apt full-upgrade`, install `unattended-upgrades` (security updates on), `ufw`, `git`, `curl`,
  `jq`, `sqlite3`, `ca-certificates`, `openssl`.
- If `ufw` can't load its rules inside the unprivileged container, don't force it: tell me, and
  use the Proxmox firewall for this container instead (inbound: allow SSH from my LAN only, drop
  everything else) — ask before enabling it.
- ufw: default deny incoming, allow outgoing, allow SSH from my LAN subnet only (ask me for it),
  enable.
- Time comes from the Proxmox host in a container; just confirm `date` is right in Sydney time.

### 3. Docker
- Install Docker Engine and the Compose plugin from Docker's official apt repository.
- Add `tt` to the `docker` group. Enable the service. Check `docker run --rm hello-world` and
  `docker info` (storage driver, no warnings about cgroups). If this fails, see the fallback in step 1.

### 4. Get the code
- Create a read-only deploy key in the container (`ssh-keygen -t ed25519`), show me the **public** key and
  ask me to add it in GitHub → repo → Settings → Deploy keys (read-only).
- Clone to `/opt/transittracker` (owned by `tt`), check out the agreed branch.

### 5. Cloudflare Zero Trust (guide me through the dashboard parts I need to click)
- **Tunnel:** Zero Trust → Networks → Tunnels → create a `cloudflared` tunnel named
  `transittracker`, choose Docker, and give me the place to copy the **tunnel token** (I will
  paste it to you for `.env`; don't run the `docker run` command it shows).
- **Public hostname:** `tt.<my-domain>` → service `http://tt-server:8080`.
- **Access application** (protects only the admin page): self-hosted app for
  `tt.<my-domain>/status`, policy "Allow" for my email only (one-time PIN login is fine).
  **Do not** put Access in front of `/v1/*` or `/health`: the phone app cannot do an Access login;
  the server has its own invite-code and device-token authentication.
- **Make sure Cloudflare doesn't block the app:** for this hostname, Bot Fight Mode must not
  challenge API calls (turn it off, or add a WAF custom rule that skips bot protections for
  `/v1/*`). No caching rules are needed. SSL/TLS mode: Full.

### 6. Configure
- `cp deploy/.env.example deploy/.env`, `chmod 600`. Fill in, asking me for secret values:
  `TFNSW_API_KEY`, `CLOUDFLARE_TUNNEL_TOKEN`, `FIREBASE_PROJECT_ID`,
  `FIREBASE_SERVICE_ACCOUNT=/secrets/firebase-service-account.json`,
  `TT_PUBLIC_BASE_URL=https://tt.<my-domain>`, `TT_TRUSTED_PROXY=cloudflare`, and a strong random
  `TT_ADMIN_TOKEN` (generate it with `openssl rand -base64 32`; tell me where it is stored, don't
  display it). Leave everything else at its default unless the README says otherwise.
- Put the Firebase service-account JSON I give you at `deploy/secrets/firebase-service-account.json`
  (mode 600). If I don't have Firebase set up yet, leave those two variables empty: the server then
  logs notifications instead of sending them.

### 7. Build and start
- From `/opt/transittracker/deploy`: `docker compose build`, then `docker compose up -d`.
- `docker compose ps` should show `tt-server` healthy and `cloudflared` running; check
  `docker compose logs --tail=100` for errors (redact anything secret-looking if you show me logs).
- In the Cloudflare dashboard the tunnel should show **Healthy**.

### 8. First timetable and checks
- With `TFNSW_API_KEY` set, the server builds the first timetable by itself on start (log line
  `Timetable refresh (startup)`). `/health` should then report `timetableLoaded: true`. If it
  failed, show me the (redacted) error, fix it, and `docker compose restart tt-server`.
- From **outside my network** (e.g. `curl` from your own environment, not via the LAN):
  - `https://tt.<my-domain>/health` returns OK.
  - `https://tt.<my-domain>/status` asks for a Cloudflare Access login (not the server page).
- Create an invite: `deploy/tt-admin invite --note "owner"`. Use it to test sign-up with curl:
  `POST /v1/join` with JSON `{"inviteCode":"…","platform":"android","appVersion":"test"}`, then
  call `/v1/config` and `/v1/packs` with `Authorization: Bearer <deviceToken>`. Then revoke that
  test device (`deploy/tt-admin devices`, `deploy/tt-admin revoke <id>`) and give me a **fresh**
  invite for my phone.
- If the TfNSW key is set, call `/v1/rt?routes=<a route id from the pack>` and confirm `feeds`
  show a recent `dataTimeMs`.

### 9. Operations
- **Backups:** cron job for `tt` at 03:45 running `deploy/backup.sh` (keeps 14 days locally). Ask me
  whether to copy backups off the container (e.g. `rclone` to my cloud drive or my NAS) and set that up
  if I say yes. Also suggest adding the container to my existing Proxmox backup job (ask before changing
  any job).
- **Snapshots:** suggest a Proxmox snapshot of the container before each update (ask first).
- **Updates:** CI deploys every push to `main` through a self-hosted GitHub Actions runner on this
  container (`deploy/README.md`, section 14). Walk me through registering the runner as user `tt`
  with label `transittracker` (I'll create the registration token on GitHub), install it as a
  service, and set `TT_IMAGE` in `.env`. I set the `TT_DEPLOY_DIR` repository variable myself.
  Then trigger a run and confirm the *Deploy server* job goes green.
- **Monitoring:** explain how to add a free uptime check on `https://tt.<my-domain>/health`
  (e.g. Healthchecks.io or UptimeRobot) and offer to help me set it up.
- Restart test: reboot the container (`pct reboot`) and confirm everything comes back on its own.

### 10. Report back
A short summary: container ID (or VMID if we fell back), node, IP, resources, LXC features, hostname, Git commit deployed, container status,
tunnel status, what you verified and how, where the secrets and backups are (paths only),
anything you could not finish and what I need to do next.
