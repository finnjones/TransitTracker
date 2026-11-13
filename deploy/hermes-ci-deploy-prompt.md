# Task: let GitHub Actions deploy TransitTracker to my server

TransitTracker's server (`tt-server` plus `cloudflared`, Docker Compose) already runs in my Proxmox
LXC container `transittracker`, set up from `deploy/hermes-proxmox-deploy-prompt.md`. Today I update
it by hand with `git pull` and a local image build. I want GitHub Actions to deploy it instead: every
push to `main` should start the image CI published and wait until it is healthy, so a green
pipeline means the server is running that commit.

That needs a **GitHub Actions self-hosted runner** in this container. The runner only makes
outbound connections to GitHub, like cloudflared does. The workflow side is already written:
`.github/workflows/ci.yml` (job *Deploy server*) and `deploy/deploy.sh`. Read
`deploy/README.md` section 14 ("Automatic deploys from GitHub Actions") first. It is the source
of truth if it differs from this prompt.

Work carefully, say what you are about to do before each stage, and stop and ask me whenever
something is unclear or unexpected.

## Ground rules

1. **Only touch this container.** Don't change other Proxmox guests, storage, networks, firewall
   rules, backup jobs or my router. No inbound ports: don't publish container ports or open the
   firewall. The runner needs none.
2. **Ask before anything destructive or hard to undo.** That includes stopping the running
   server, deleting images or volumes, and changing `.env`.
3. **Secrets:** the runner registration token I give you is a secret, even though it is
   short-lived. Never print it, put it in shell history, logs or notes. Pass it with
   `read -rs` into a variable. The same rules as before apply to `.env` and `secrets/`.
4. **Never commit or push anything to the repository**, and never change GitHub settings
   yourself. I do the GitHub parts. Tell me exactly what to click and what to enter.
5. Append what you did (no secrets) to `/opt/transittracker/DEPLOYED.md`.

## Steps

### 1. Check the starting point and make it safe
- Confirm that `main` contains `deploy/deploy.sh`. Run `git -C /opt/transittracker fetch origin main`
  and `git -C /opt/transittracker ls-tree origin/main deploy/deploy.sh`. If the file is missing,
  stop and tell me: the pull request with the deploy job isn't merged yet.
- If the checkout has no local changes, update it with `git pull --ff-only` so the README,
  `tt-admin` and `backup.sh` are current. If it has local changes, show me `git status` and ask.
- `docker compose ls` must show exactly one project, named **`transittracker`**, with its config
  file in `/opt/transittracker/deploy`. The CI deploy updates the project with that name. If the
  name or folder differs, **stop and tell me**: otherwise a deploy would start a second copy with
  a second tunnel connector and an empty database.
- Note the deploy folder path (normally `/opt/transittracker/deploy`). I need it for
  `TT_DEPLOY_DIR`.
- Run `deploy/backup.sh` once. Then ask me whether to take a Proxmox snapshot of the container
  before going on.

### 2. Install the runner as user `tt`
- Confirm `tt` is in the `docker` group (`id tt`) and that `docker ps` works as `tt` without sudo.
- Ask me for the registration token. I get it from GitHub: repository `finnjones/TransitTracker` →
  *Settings → Actions → Runners → New self-hosted runner* → Linux, x64 (or ARM64 if
  `uname -m` says aarch64). The token in the `./config.sh` line on that page is valid for one hour.
  Also ask me to read you the runner version and download URL from that page, or use the
  latest release from `github.com/actions/runner/releases` and verify its SHA-256 checksum
  against the release notes.
- As `tt`, in `/home/tt/actions-runner`: download and unpack the runner, then run
  `sudo ./bin/installdependencies.sh` if it is needed on Debian 12.
- Configure it without prompts. The label **`transittracker`** is required, because the deploy
  job only runs on a runner with that label:

  ```bash
  read -rs RUNNER_TOKEN   # paste the token; nothing is shown
  ./config.sh --unattended --url https://github.com/finnjones/TransitTracker \
    --token "$RUNNER_TOKEN" --name transittracker-lxc --labels transittracker --replace
  unset RUNNER_TOKEN
  ```

- Install and start the service as `tt`: `sudo ./svc.sh install tt`, `sudo ./svc.sh start`,
  `sudo ./svc.sh status`. Check that it is enabled at boot.
- Ask me to confirm that the runner shows **Idle** on GitHub's Runners page.

### 3. Point manual commands at the published image
- Ask me before editing `.env`. In `/opt/transittracker/deploy/.env`, set
  `TT_IMAGE=ghcr.io/finnjones/transittracker/tt-server:latest` (all lower case). Keep the file at
  mode 600. Deploys ignore this value and start the exact image CI pushed. It only stops
  `docker compose up -d` run by hand from building an image on the server.
- Don't restart or recreate the server yet. The first CI deploy does that.

### 4. GitHub settings (I do these; tell me exactly what to enter)
- Repository → *Settings → Secrets and variables → Actions → Variables*:
  - `TT_DEPLOY_DIR` = the deploy folder from step 1.
  - `TT_PUBLIC_BASE_URL` = `https://tt.<my-domain>` (optional; it adds a check through Cloudflare).

### 5. First deploy and checks
- Ask me to re-run the latest CI run on `main` (*Actions → CI → latest run → Re-run all jobs*),
  or to push to `main`.
- While it runs, follow the runner with
  `journalctl -u 'actions.runner.*' -f` and the stack with `docker compose ps`.
- Then confirm all of these:
  - The *Deploy server* job is green. Ask me, or read the runner's `_diag` logs.
  - `docker compose ps` shows `tt-server` **healthy** and `cloudflared` running.
  - The running image is the CI one:
    `docker inspect --format '{{.Config.Image}}' $(docker compose ps -q tt-server)` shows
    `ghcr.io/finnjones/transittracker/tt-server@sha256:...`.
  - `/health` answers from outside my network (`https://tt.<my-domain>/health`), and
    `timetableLoaded` becomes `true` again.
  - `./tt-admin devices` still lists the existing devices, which shows the data volume was kept.
- If the job fails, show me the redacted error. `deploy.sh` starts the previous image again on
  its own, so confirm the server is healthy. If the image pull fails with `denied`, tell me to
  open GitHub → my profile → *Packages → tt-server → Package settings → Manage Actions access*
  and give the TransitTracker repository *Read*.
- Restart test: reboot the container, then confirm the stack and the runner both come back
  without help.

### 6. Tidy up (ask first)
- The server no longer needs `git pull` for deploys. Ask me whether to remove
  `/opt/transittracker/update.sh` if it exists. Keep the read-only deploy key: it is still used
  to pull new versions of `tt-admin` and `backup.sh`.
- Ask before running `docker image prune` to remove the old locally built image
  `transittracker/tt-server:local`. Keep it until one CI deploy has succeeded.

### 7. Report back
A short summary covering:
- the runner name, label, version, install folder and service name
- the `TT_DEPLOY_DIR` value
- the image digest now running
- what you verified and how
- how to undo it: `sudo ./svc.sh stop && sudo ./svc.sh uninstall`, then
  `./config.sh remove --token <new token from GitHub>`, and delete the `TT_DEPLOY_DIR` variable
- anything you couldn't finish
