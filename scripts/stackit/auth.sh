#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd stackit

require_env STACKIT_PROJECT_ID
require_env STACKIT_REGION

STACKIT_SERVICE_ACCOUNT_KEY_PATH="${STACKIT_SERVICE_ACCOUNT_KEY_PATH:-}"
STACKIT_PRIVATE_KEY_PATH="${STACKIT_PRIVATE_KEY_PATH:-}"
STACKIT_SERVICE_ACCOUNT_TOKEN="${STACKIT_SERVICE_ACCOUNT_TOKEN:-}"

if [[ -n "$STACKIT_SERVICE_ACCOUNT_KEY_PATH" ]]; then
  if [[ ! -f "$STACKIT_SERVICE_ACCOUNT_KEY_PATH" ]]; then
    echo "Service account key file not found: $STACKIT_SERVICE_ACCOUNT_KEY_PATH" >&2
    exit 2
  fi
  echo "Activating STACKIT service account from key file..." >&2
  args=(--service-account-key-path "$STACKIT_SERVICE_ACCOUNT_KEY_PATH")
  if [[ -n "$STACKIT_PRIVATE_KEY_PATH" ]]; then
    if [[ ! -f "$STACKIT_PRIVATE_KEY_PATH" ]]; then
      echo "Private key file not found: $STACKIT_PRIVATE_KEY_PATH" >&2
      exit 2
    fi
    args+=(--private-key-path "$STACKIT_PRIVATE_KEY_PATH")
  fi
  stackit auth activate-service-account "${args[@]}"
elif [[ -n "$STACKIT_SERVICE_ACCOUNT_TOKEN" ]]; then
  echo "Activating STACKIT service account from token..." >&2
  stackit auth activate-service-account --service-account-token "$STACKIT_SERVICE_ACCOUNT_TOKEN"
else
  echo "Missing auth input." >&2
  echo "Set one of:" >&2
  echo "  STACKIT_SERVICE_ACCOUNT_KEY_PATH=/path/to/service_account_key.json" >&2
  echo "  STACKIT_PRIVATE_KEY_PATH=/path/to/private.key  # optional" >&2
  echo "  STACKIT_SERVICE_ACCOUNT_TOKEN=..." >&2
  exit 2
fi

echo "Auth OK" >&2
