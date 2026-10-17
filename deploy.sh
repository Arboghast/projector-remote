#!/bin/sh
# Brings the server up to date with GitHub: pull, rebuild, restart.
set -e
cd "$(dirname "$0")"
git pull --ff-only
docker compose up -d --build
docker image prune -f > /dev/null
docker compose ps
