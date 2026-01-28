#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd stackit
require_cmd kubectl
require_cmd python3

require_env STACKIT_PROJECT_ID
require_env STACKIT_REGION

KUBE_NAMESPACE="${KUBE_NAMESPACE:-mapster}"
STATE_FILE="${STATE_FILE:-$SCRIPT_DIR/state.json}"

STACKIT_DELETE_SKE_CLUSTER="${STACKIT_DELETE_SKE_CLUSTER:-false}"
STACKIT_SKE_CLUSTER_NAME="${STACKIT_SKE_CLUSTER_NAME:-}"

echo "[1/2] Deleting Kubernetes namespace '$KUBE_NAMESPACE' (best effort)..." >&2
kubectl delete namespace "$KUBE_NAMESPACE" --ignore-not-found=true

echo "[2/2] Deleting PostgresFlex instance from state (best effort)..." >&2
if instance_id=$(state_get postgresInstanceId 2>/dev/null); then
  # Deleting the instance should also remove contained resources; if it fails, show a helpful hint.
  set +e
  stackit_json postgresflex instance delete "$instance_id" >/dev/null
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    echo "WARN: failed to delete PostgresFlex instance $instance_id. You can inspect via:" >&2
    echo "  stackit postgresflex instance describe $instance_id --project-id $STACKIT_PROJECT_ID --region $STACKIT_REGION" >&2
    exit "$rc"
  fi
  echo "Deleted PostgresFlex instance: $instance_id" >&2

  # If we've torn everything down successfully, remove the local state file.
  rm -f "$STATE_FILE"
else
  echo "No postgresInstanceId in state; skipping DB deletion." >&2
fi

echo "OK" >&2

if [[ "$STACKIT_DELETE_SKE_CLUSTER" == "true" && -n "$STACKIT_SKE_CLUSTER_NAME" ]]; then
  echo "Deleting SKE cluster '$STACKIT_SKE_CLUSTER_NAME' (requested)..." >&2
  "$SCRIPT_DIR/cluster_down.sh"
fi
