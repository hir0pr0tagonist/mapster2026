#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd stackit
require_cmd python3

require_env STACKIT_PROJECT_ID
require_env STACKIT_REGION

STACKIT_SKE_CLUSTER_NAME="${STACKIT_SKE_CLUSTER_NAME:-mapster}"

# Hard caps requested: 4 vCPU / 16GB RAM maximum per node (defaults can be overridden, but stay capped by default).
STACKIT_SKE_MAX_CPU="${STACKIT_SKE_MAX_CPU:-4}"
STACKIT_SKE_MAX_RAM_GB="${STACKIT_SKE_MAX_RAM_GB:-16}"
STACKIT_SKE_NODE_COUNT="${STACKIT_SKE_NODE_COUNT:-2}"

# Optional overrides.
STACKIT_SKE_MACHINE_TYPE="${STACKIT_SKE_MACHINE_TYPE:-}"
STACKIT_SKE_AVAILABILITY_ZONES="${STACKIT_SKE_AVAILABILITY_ZONES:-}"
STACKIT_SKE_K8S_VERSION="${STACKIT_SKE_K8S_VERSION:-}"

# Kubeconfig settings
STACKIT_SKE_KUBECONFIG_PATH="${STACKIT_SKE_KUBECONFIG_PATH:-$SCRIPT_DIR/kubeconfig.${STACKIT_SKE_CLUSTER_NAME}}"
STACKIT_SKE_KUBECONFIG_EXPIRATION="${STACKIT_SKE_KUBECONFIG_EXPIRATION:-30d}"

echo "Ensuring SKE is enabled for the project (best effort)..." >&2
set +e
stackit_cmd ske enable >/dev/null
set -e

echo "Checking if cluster '$STACKIT_SKE_CLUSTER_NAME' already exists..." >&2
clusters_json=$(stackit_json ske cluster list)
exists=$(python3 - "$clusters_json" "$STACKIT_SKE_CLUSTER_NAME" <<'PY'
import json, sys
doc = json.loads(sys.argv[1])
name = sys.argv[2]

def walk(obj):
    if isinstance(obj, dict):
        # Common shape: { "items": [ {"name": ...}, ... ] }
        if obj.get('name') == name:
            return True
        for v in obj.values():
            if walk(v):
                return True
    elif isinstance(obj, list):
        for it in obj:
            if walk(it):
                return True
    return False

print('true' if walk(doc) else 'false')
PY
)

if [[ "$exists" != "true" ]]; then
  echo "Creating SKE cluster '$STACKIT_SKE_CLUSTER_NAME' (node cap: ${STACKIT_SKE_MAX_CPU} vCPU / ${STACKIT_SKE_MAX_RAM_GB}GB)..." >&2

  options_json=$(stackit_json ske options --machine-types)
  payload_json=$(stackit_json ske cluster generate-payload)

  tmp_payload="$SCRIPT_DIR/.ske-payload.${STACKIT_SKE_CLUSTER_NAME}.json"

    chosen_machine=$(python3 - "$payload_json" "$options_json" "$tmp_payload" <<'PY'
import json
import os
import sys

payload = json.loads(sys.argv[1])
options = json.loads(sys.argv[2])
out_path = sys.argv[3]

max_cpu = int(os.environ.get('STACKIT_SKE_MAX_CPU', '4'))
max_ram = int(os.environ.get('STACKIT_SKE_MAX_RAM_GB', '16'))
node_count = int(os.environ.get('STACKIT_SKE_NODE_COUNT', '2'))
machine_override = os.environ.get('STACKIT_SKE_MACHINE_TYPE', '').strip() or None
zones_raw = os.environ.get('STACKIT_SKE_AVAILABILITY_ZONES', '').strip()
zones = [z.strip() for z in zones_raw.split(',') if z.strip()] if zones_raw else None
k8s_version = os.environ.get('STACKIT_SKE_K8S_VERSION', '').strip() or None

def iter_dicts(obj):
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from iter_dicts(v)
    elif isinstance(obj, list):
        for it in obj:
            yield from iter_dicts(it)

def select_machine_type():
    if machine_override:
        return machine_override

    candidates = []
    for d in iter_dicts(options):
        # Heuristics: machine type entries typically contain a name/id plus cpu+memory fields.
        name = d.get('name') or d.get('machineType') or d.get('type') or d.get('id')
        cpu = d.get('cpu') or d.get('vcpus') or d.get('cores')
        ram = d.get('ram') or d.get('memory') or d.get('memoryGb') or d.get('memory_gb')
        if not name or cpu is None or ram is None:
            continue
        try:
            cpu_i = int(cpu)
            ram_i = int(ram)
        except Exception:
            continue
        if cpu_i <= max_cpu and ram_i <= max_ram:
            candidates.append((cpu_i, ram_i, str(name)))

    if not candidates:
        raise SystemExit(f"No machine types found within {max_cpu} vCPU / {max_ram}GB RAM. Set STACKIT_SKE_MACHINE_TYPE explicitly.")

    # Pick the largest within cap (prefer more CPU, then more RAM).
    candidates.sort(key=lambda t: (t[0], t[1], t[2]))
    return candidates[-1][2]

chosen_machine = select_machine_type()

def patch_k8s_version(obj):
    if not k8s_version:
        return False
    changed = False
    for d in iter_dicts(obj):
        if 'kubernetesVersion' in d and isinstance(d['kubernetesVersion'], str):
            d['kubernetesVersion'] = k8s_version
            return True
        if 'version' in d and isinstance(d['version'], str) and d.get('kind') == 'Kubernetes':
            d['version'] = k8s_version
            changed = True
    return changed

def find_node_pools(obj):
    for d in iter_dicts(obj):
        if 'nodePools' in d and isinstance(d['nodePools'], list) and d['nodePools']:
            return d['nodePools']
    return None

node_pools = find_node_pools(payload)
if not node_pools:
    raise SystemExit("Could not find nodePools in generated payload; please inspect 'stackit ske cluster generate-payload' output.")

patched_any = False
for np in node_pools:
    if isinstance(np, dict) and 'machineType' in np:
        np['machineType'] = chosen_machine
        patched_any = True
    # Common autoscaling size keys
    for min_key in ('minSize', 'minimum', 'min', 'min_count'):
        if min_key in np and isinstance(np[min_key], int):
            np[min_key] = node_count
    for max_key in ('maxSize', 'maximum', 'max', 'max_count'):
        if max_key in np and isinstance(np[max_key], int):
            np[max_key] = node_count
    for size_key in ('size', 'count', 'replicas'):
        if size_key in np and isinstance(np[size_key], int):
            np[size_key] = node_count
    if zones and 'availabilityZones' in np and isinstance(np['availabilityZones'], list):
        np['availabilityZones'] = zones

if not patched_any:
    raise SystemExit("Could not patch node pool machineType in payload; set STACKIT_SKE_MACHINE_TYPE explicitly.")

patch_k8s_version(payload)

with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(payload, f, indent=2)
    f.write('\n')

print(chosen_machine)
PY

    )

  echo "Creating cluster from payload file: $tmp_payload" >&2
  stackit_cmd ske cluster create "$STACKIT_SKE_CLUSTER_NAME" --payload "@$tmp_payload" --async

  state_write skeClusterName '"'"$STACKIT_SKE_CLUSTER_NAME"'"'
  state_write skeKubeconfigPath '"'"$STACKIT_SKE_KUBECONFIG_PATH"'"'
fi

echo "Creating/updating kubeconfig at: $STACKIT_SKE_KUBECONFIG_PATH" >&2
stackit_cmd ske kubeconfig create "$STACKIT_SKE_CLUSTER_NAME" --filepath "$STACKIT_SKE_KUBECONFIG_PATH" --expiration "$STACKIT_SKE_KUBECONFIG_EXPIRATION"

export STACKIT_SKE_KUBECONFIG_PATH
echo "OK: SKE cluster '$STACKIT_SKE_CLUSTER_NAME' kubeconfig ready." >&2