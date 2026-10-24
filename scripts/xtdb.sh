#!/usr/bin/env bash
set -euo pipefail
name="${XTDB_CONTAINER_NAME:-bx-xtdb-dev}"
case "${1:-start}" in
  start)
    docker run --detach --name "$name" \
      --publish "127.0.0.1:${XTDB_PORT:-5432}:5432" \
      --publish "127.0.0.1:${XTDB_HEALTH_PORT:-8080}:8080" \
      --env XTDB_PASSWORD="${XTDB_PASSWORD:-xtdb}" \
      ghcr.io/xtdb/xtdb:2.2.0@sha256:a6a426505b95a36e4e0e1d0de88efcf4220cb6bde8f1f5e594b0a1f92a948a77
    for ((attempt=0; attempt<120; attempt++)); do
      if curl --fail --silent "http://127.0.0.1:${XTDB_HEALTH_PORT:-8080}/healthz/started" >/dev/null; then exit 0; fi
      sleep 1
    done
    docker logs "$name"
    docker rm --force "$name" >/dev/null
    echo 'XTDB did not become ready within 120 seconds' >&2
    exit 1
    ;;
  stop) docker rm --force "$name" ;;
  logs) docker logs "$name" ;;
  *) echo 'Usage: scripts/xtdb.sh start|stop|logs' >&2; exit 2 ;;
esac
