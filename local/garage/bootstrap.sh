#!/bin/sh
# Garage one-shot bootstrap.
#
# Garage starts up with no cluster layout — until a node is assigned a role
# and the layout is "applied", S3 calls return errors. This script:
#   1. Waits for Garage's Admin API to respond.
#   2. Reads the node ID from /v2/GetClusterStatus.
#   3. If layout version is 0 (uninitialized), assigns the node a role and
#      applies layout version 1.
#   4. Creates a `demo-dataset` bucket if it doesn't already exist.
#
# All steps are idempotent — safe to run on every `compose up`.

set -eu

ADMIN="http://garage:3903"
TOKEN="local-dev-garage-admin-token"
AUTH="Authorization: Bearer ${TOKEN}"
JSON="Content-Type: application/json"

echo "[bootstrap] installing curl + jq"
apk add --no-cache curl jq >/dev/null

echo "[bootstrap] waiting for garage admin api"
for i in $(seq 1 60); do
  if curl -fsS -H "${AUTH}" "${ADMIN}/v2/GetClusterStatus" >/dev/null 2>&1; then
    echo "[bootstrap] garage is up"
    break
  fi
  sleep 1
  if [ "${i}" = "60" ]; then
    echo "[bootstrap] garage never became reachable" >&2
    exit 1
  fi
done

STATUS=$(curl -fsS -H "${AUTH}" "${ADMIN}/v2/GetClusterStatus")
LAYOUT_VER=$(echo "${STATUS}" | jq -r '.layoutVersion // .layout.version // 0')

if [ "${LAYOUT_VER}" -gt 0 ]; then
  echo "[bootstrap] layout already at version ${LAYOUT_VER} — skipping init"
else
  NODE_ID=$(echo "${STATUS}" | jq -r '.nodes[0].id // empty')
  if [ -z "${NODE_ID}" ]; then
    echo "[bootstrap] could not determine node id from status:" >&2
    echo "${STATUS}" >&2
    exit 1
  fi
  echo "[bootstrap] assigning role to node ${NODE_ID}"
  curl -fsS -X POST -H "${AUTH}" -H "${JSON}" \
    -d "[{\"id\":\"${NODE_ID}\",\"zone\":\"dc1\",\"capacity\":1073741824,\"tags\":[\"local\"]}]" \
    "${ADMIN}/v2/UpdateClusterLayout" >/dev/null

  echo "[bootstrap] applying layout version 1"
  curl -fsS -X POST -H "${AUTH}" -H "${JSON}" \
    -d '{"version":1}' \
    "${ADMIN}/v2/ApplyClusterLayout" >/dev/null
  sleep 2
fi

echo "[bootstrap] ensuring demo-dataset bucket"
EXISTING=$(curl -fsS -H "${AUTH}" "${ADMIN}/v2/ListBuckets" | jq -r '.[].globalAliases[]?' | grep -cx 'demo-dataset' || true)
if [ "${EXISTING}" -eq 0 ]; then
  curl -fsS -X POST -H "${AUTH}" -H "${JSON}" \
    -d '{"globalAlias":"demo-dataset"}' \
    "${ADMIN}/v2/CreateBucket" >/dev/null
  echo "[bootstrap] created bucket demo-dataset"
else
  echo "[bootstrap] bucket demo-dataset already exists"
fi

echo "[bootstrap] done"
