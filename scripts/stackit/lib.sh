#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="${STATE_FILE:-$SCRIPT_DIR/state.json}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 2
  }
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required env var: $name" >&2
    exit 2
  fi
}

stackit_base_args() {
  # NOTE: kept for backward compatibility; returns JSON output args.
  stackit_base_args_json
}

stackit_base_args_common() {
  # project-id is mandatory for almost everything
  echo "--project-id" "${STACKIT_PROJECT_ID}" "--verbosity" "info" "--assume-yes" "--region" "${STACKIT_REGION}"
}

stackit_base_args_json() {
  echo "$(stackit_base_args_common)" "--output-format" "json"
}

stackit_json() {
  stackit "$(stackit_base_args_json)" "$@"
}

stackit_cmd() {
  # Like stackit_json, but doesn't force output format (needed for kubeconfig writing).
  stackit "$(stackit_base_args_common)" "$@"
}

state_init() {
  if [[ ! -f "$STATE_FILE" ]]; then
    printf '%s\n' '{"createdAt": null, "projectId": null, "region": null}' >"$STATE_FILE"
  fi
}

state_write() {
  local key="$1"
  local value_json="$2"  # must be JSON, e.g. '"abc"' or '123' or '{...}'
  state_init
  python3 - "$STATE_FILE" "$key" "$value_json" <<'PY'
import json
import sys

path, key, value_json = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, 'r', encoding='utf-8') as f:
    data = json.load(f)

try:
    value = json.loads(value_json)
except json.JSONDecodeError:
    # fallback: treat as string
    value = value_json

data[key] = value

with open(path, 'w', encoding='utf-8') as f:
    json.dump(data, f, indent=2, sort_keys=True)
    f.write('\n')
PY
}

state_get() {
  local key="$1"
  state_init
  python3 - "$STATE_FILE" "$key" <<'PY'
import json
import sys

path, key = sys.argv[1], sys.argv[2]
with open(path, 'r', encoding='utf-8') as f:
    data = json.load(f)

val = data.get(key)
if val is None:
    sys.exit(1)
if isinstance(val, (dict, list)):
    print(json.dumps(val))
else:
    print(val)
PY
}

json_find_first_string() {
  # Best-effort extraction of a hostname/endpoint from a JSON document.
  # Usage: json_find_first_string "$json" endpoint host hostname fqdn
  local json="$1"; shift
  python3 - "$json" "$@" <<'PY'
import json
import sys

doc = json.loads(sys.argv[1])
keys = sys.argv[2:]

found = None

def walk(obj):
    global found
    if found is not None:
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            if found is not None:
                return
            if isinstance(v, str) and k in keys and v:
                found = v
                return
            walk(v)
    elif isinstance(obj, list):
        for it in obj:
            if found is not None:
                return
            walk(it)

walk(doc)

if found is None:
    sys.exit(1)
print(found)
PY
}
