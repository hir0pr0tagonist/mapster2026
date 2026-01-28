#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

require_cmd stackit
require_cmd python3

require_env STACKIT_PROJECT_ID
require_env STACKIT_REGION

STACKIT_SKE_MAX_CPU="${STACKIT_SKE_MAX_CPU:-4}"
STACKIT_SKE_MAX_RAM_GB="${STACKIT_SKE_MAX_RAM_GB:-16}"

echo "[1/4] Checking STACKIT authentication..." >&2
set +e
stackit auth get-access-token --output-format none >/dev/null 2>&1
rc=$?
set -e
if [[ "$rc" -ne 0 ]]; then
  echo "Not authenticated. Run: stackit auth login" >&2
  exit 2
fi

echo "[2/4] Checking SKE is enabled / reachable..." >&2
set +e
stackit_cmd ske describe >/dev/null 2>&1
rc=$?
set -e
if [[ "$rc" -ne 0 ]]; then
  echo "SKE not enabled or not reachable. You can enable with: stackit ske enable" >&2
fi

echo "[3/4] Listing machine types within cap (${STACKIT_SKE_MAX_CPU} vCPU / ${STACKIT_SKE_MAX_RAM_GB}GB)..." >&2
opts_json=$(stackit_json ske options --machine-types)
python3 - "$opts_json" <<'PY'
import json, os, sys

doc = json.loads(sys.argv[1])
max_cpu = int(os.environ.get('STACKIT_SKE_MAX_CPU', '4'))
max_ram = int(os.environ.get('STACKIT_SKE_MAX_RAM_GB', '16'))

def iter_dicts(obj):
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from iter_dicts(v)
    elif isinstance(obj, list):
        for it in obj:
            yield from iter_dicts(it)

seen = set()
rows = []
for d in iter_dicts(doc):
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
        key = (str(name), cpu_i, ram_i)
        if key in seen:
            continue
        seen.add(key)
        rows.append(key)

rows.sort(key=lambda x: (x[1], x[2], x[0]))
if not rows:
    print("No machine types found within cap; set STACKIT_SKE_MACHINE_TYPE explicitly.")
else:
    for name, cpu_i, ram_i in rows[-10:]:
        print(f"{name}\t{cpu_i} vCPU\t{ram_i} GB")
PY

echo "[4/4] Checking kubectl context (optional)..." >&2
if command -v kubectl >/dev/null 2>&1; then
  set +e
  kubectl cluster-info >/dev/null 2>&1
  rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    echo "kubectl is not connected to a cluster in this shell." >&2
    echo "If you plan to deploy, ensure kubeconfig is set (e.g., via: stackit ske kubeconfig create ...)." >&2
  else
    echo "kubectl connectivity: OK" >&2
  fi
else
  echo "kubectl not installed (ok if you only want to provision cloud resources)." >&2
fi

echo "Preflight OK" >&2
