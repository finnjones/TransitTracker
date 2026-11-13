#!/usr/bin/env bash
# Starts a published tt-server image on this machine and waits until it is healthy.
#
# Run by the `deploy` job in .github/workflows/ci.yml on the self-hosted runner (deploy/README.md,
# section 14). Also works by hand, e.g. to roll back to an earlier image:
#
#   TT_DEPLOY_DIR=/opt/transittracker/deploy deploy/deploy.sh ghcr.io/<owner>/<repo>/tt-server@sha256:...
#
# TT_DEPLOY_DIR is the server's own deploy folder: it holds .env and secrets/, and its compose
# project ("transittracker") is the one that is updated. The compose file used is the one next to
# this script, so a deploy also picks up changes to docker-compose.yml in the same commit.
#
# If the new container is not healthy within TT_DEPLOY_WAIT_SECONDS, the image that was running
# before is started again and the script exits non-zero, so a red pipeline means the old server
# is still the one running.
set -euo pipefail

image="${1:?usage: deploy.sh <image reference>}"
deploy_dir="${TT_DEPLOY_DIR:?Set TT_DEPLOY_DIR to the folder that holds .env and secrets/}"
wait_seconds="${TT_DEPLOY_WAIT_SECONDS:-240}"
here="$(cd "$(dirname "$0")" && pwd)"

[ -f "$deploy_dir/.env" ] || { echo "::error::No .env in $deploy_dir" >&2; exit 1; }

# Relative paths in the compose file (.env, ./secrets) resolve against the server's deploy folder.
# TT_IMAGE from the environment wins over any TT_IMAGE in .env.
compose() {
  docker compose --project-directory "$deploy_dir" -f "$here/docker-compose.yml" "$@"
}

running_image() {
  local id
  id="$(compose ps -q tt-server 2>/dev/null || true)"
  [ -n "$id" ] && docker inspect --format '{{.Config.Image}}' "$id"
}

start() {
  TT_IMAGE="$1" compose up -d --no-build --wait --wait-timeout "$wait_seconds"
}

previous="$(running_image || true)"
echo "Running now: ${previous:-nothing}"
echo "Deploying:   $image"

TT_IMAGE="$image" compose pull --quiet tt-server

if start "$image"; then
  now="$(running_image)"
  if [ "$now" != "$image" ]; then
    echo "::error::tt-server is running $now, expected $image" >&2
    exit 1
  fi
  echo "Healthy: $(compose exec -T tt-server curl -fsS localhost:8080/health)"
  # Untagged images left over from earlier deploys. They can be pulled again by digest.
  docker image prune -f >/dev/null
  exit 0
fi

echo "::error::The new tt-server did not become healthy within ${wait_seconds}s" >&2
compose logs --no-color --tail=100 tt-server >&2 || true

if [ -n "$previous" ] && [ "$previous" != "$image" ]; then
  echo "Starting the previous image again: $previous" >&2
  if start "$previous"; then
    echo "::warning::Rolled back to $previous" >&2
  else
    echo "::error::The previous image did not come back healthy either" >&2
  fi
fi
exit 1
