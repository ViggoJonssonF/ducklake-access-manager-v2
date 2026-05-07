#!/bin/sh
# Garage one-shot bootstrap.
#
# A fresh Garage node accepts admin API calls but reports `Ring not yet ready`
# until one node is assigned a role and the layout is applied. This script:
#   1. Polls /health (no auth, no cluster-state required) for readiness.
#   2. Reads the node id from /v1/status.
#   3. If layoutVersion == 0, assigns the node a role and applies layout v1.
#   4. Creates a `demo-dataset` bucket if it doesn't already exist.
#
# Uses the V1 admin API throughout — the V2 API exposes bucket/key CRUD
# (which the access-manager uses in production) but its layout endpoints are
# less stable/under-documented across Garage releases.
#
# All steps are idempotent — safe to run on every `compose up`.

set -eu

ADMIN="http://garage:3903"
TOKEN="local-dev-garage-admin-token"
AUTH="Authorization: Bearer ${TOKEN}"
JSON="Content-Type: application/json"

echo "[bootstrap] installing curl + jq"
apk add --no-cache curl jq >/dev/null

echo "[bootstrap] waiting for garage /health"
for i in $(seq 1 60); do
  if curl -fsS "${ADMIN}/health" >/dev/null 2>&1; then
    echo "[bootstrap] garage /health is up"
    break
  fi
  sleep 1
  if [ "${i}" = "60" ]; then
    echo "[bootstrap] FAIL: garage /health never responded" >&2
    echo "[bootstrap] verbose curl for diagnosis:" >&2
    curl -v "${ADMIN}/health" 2>&1 | head -30 >&2 || true
    exit 1
  fi
done

echo "[bootstrap] fetching /v1/status"
STATUS=$(curl -fsS -H "${AUTH}" "${ADMIN}/v1/status") || {
  echo "[bootstrap] FAIL: /v1/status returned an error" >&2
  curl -v -H "${AUTH}" "${ADMIN}/v1/status" 2>&1 | head -40 >&2
  exit 1
}

LAYOUT_VER=$(echo "${STATUS}" | jq -r '.layoutVersion // 0')

if [ "${LAYOUT_VER}" -gt 0 ]; then
  echo "[bootstrap] layout already at version ${LAYOUT_VER} — skipping init"
else
  NODE_ID=$(echo "${STATUS}" | jq -r '.node // .nodes[0].id // empty')
  if [ -z "${NODE_ID}" ]; then
    echo "[bootstrap] FAIL: could not extract node id from status" >&2
    echo "${STATUS}" | jq . >&2 || echo "${STATUS}" >&2
    exit 1
  fi
  echo "[bootstrap] assigning role to node ${NODE_ID}"
  curl -fsS -X POST -H "${AUTH}" -H "${JSON}" \
    -d "{\"${NODE_ID}\":{\"zone\":\"dc1\",\"capacity\":1073741824,\"tags\":[\"local\"]}}" \
    "${ADMIN}/v1/layout" >/dev/null || {
    echo "[bootstrap] FAIL: /v1/layout (assign role) errored" >&2
    curl -v -X POST -H "${AUTH}" -H "${JSON}" \
      -d "{\"${NODE_ID}\":{\"zone\":\"dc1\",\"capacity\":1073741824,\"tags\":[\"local\"]}}" \
      "${ADMIN}/v1/layout" 2>&1 | head -40 >&2
    exit 1
  }

  echo "[bootstrap] applying layout version 1"
  curl -fsS -X POST -H "${AUTH}" -H "${JSON}" \
    -d '{"version":1}' \
    "${ADMIN}/v1/layout/apply" >/dev/null || {
    echo "[bootstrap] FAIL: /v1/layout/apply errored" >&2
    curl -v -X POST -H "${AUTH}" -H "${JSON}" \
      -d '{"version":1}' \
      "${ADMIN}/v1/layout/apply" 2>&1 | head -40 >&2
    exit 1
  }
  sleep 2
fi

echo "[bootstrap] ensuring demo-dataset bucket"
BUCKET_LIST=$(curl -fsS -H "${AUTH}" "${ADMIN}/v1/bucket?list" || echo '[]')
EXISTING=$(echo "${BUCKET_LIST}" | jq -r '.[].globalAliases[]?' 2>/dev/null | grep -cx 'demo-dataset' || true)

if [ "${EXISTING}" -eq 0 ]; then
  curl -fsS -X POST -H "${AUTH}" -H "${JSON}" \
    -d '{"globalAlias":"demo-dataset"}' \
    "${ADMIN}/v1/bucket" >/dev/null || {
    echo "[bootstrap] FAIL: /v1/bucket create errored" >&2
    curl -v -X POST -H "${AUTH}" -H "${JSON}" \
      -d '{"globalAlias":"demo-dataset"}' \
      "${ADMIN}/v1/bucket" 2>&1 | head -40 >&2
    exit 1
  }
  echo "[bootstrap] created bucket demo-dataset"
else
  echo "[bootstrap] bucket demo-dataset already exists"
fi

echo "[bootstrap] done"
