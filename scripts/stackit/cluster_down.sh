#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd stackit
require_cmd python3

require_env STACKIT_PROJECT_ID
require_env STACKIT_REGION

STACKIT_SKE_CLUSTER_NAME="${STACKIT_SKE_CLUSTER_NAME:-}"
if [[ -z "$STACKIT_SKE_CLUSTER_NAME" ]]; then
  echo "Missing STACKIT_SKE_CLUSTER_NAME" >&2
  exit 2
fi

echo "Deleting SKE cluster '$STACKIT_SKE_CLUSTER_NAME' (async)..." >&2
stackit_cmd ske cluster delete "$STACKIT_SKE_CLUSTER_NAME" --async

echo "OK" >&2
