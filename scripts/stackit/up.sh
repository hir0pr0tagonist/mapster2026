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

STACKIT_PG_NAME="${STACKIT_PG_NAME:-mapster-db}"
STACKIT_PG_CPU="${STACKIT_PG_CPU:-2}"
STACKIT_PG_RAM="${STACKIT_PG_RAM:-4}"
STACKIT_PG_STORAGE_SIZE="${STACKIT_PG_STORAGE_SIZE:-20}"
STACKIT_PG_STORAGE_CLASS="${STACKIT_PG_STORAGE_CLASS:-premium-perf2-stackit}"

STACKIT_PG_ACL_CIDRS="${STACKIT_PG_ACL_CIDRS:-}"
MAPSTER_DB_USER="${MAPSTER_DB_USER:-mapster}"

KUBE_NAMESPACE="${KUBE_NAMESPACE:-mapster}"
KUBE_DB_SECRET_NAME="${KUBE_DB_SECRET_NAME:-db-secret}"

KUBE_REGISTRY_SECRET_NAME="${KUBE_REGISTRY_SECRET_NAME:-regcred}"
KUBE_OBJECT_STORAGE_SECRET_NAME="${KUBE_OBJECT_STORAGE_SECRET_NAME:-object-storage-secret}"

echo "[1/4] Provisioning PostgresFlex instance (if needed)..." >&2
if instance_id=$(state_get postgresInstanceId 2>/dev/null); then
  echo "Using existing instance from state: $instance_id" >&2
else
  if [[ -z "$STACKIT_PG_ACL_CIDRS" ]]; then
    echo "STACKIT_PG_ACL_CIDRS is required." >&2
    echo "For internal-only access, set it to the CIDR(s) your cluster uses to reach managed services (NAT/egress)." >&2
    echo "Example: export STACKIT_PG_ACL_CIDRS=\"203.0.113.10/32\"" >&2
    exit 2
  fi

  # Convert comma-separated CIDRs to repeated --acl flags
  acl_args=()
  IFS=',' read -r -a cidrs <<<"$STACKIT_PG_ACL_CIDRS"
  for c in "${cidrs[@]}"; do
    c_trimmed="${c//[[:space:]]/}"
    [[ -n "$c_trimmed" ]] || continue
    acl_args+=("--acl" "$c_trimmed")
  done

  create_json=$(stackit_json postgresflex instance create \
    --name "$STACKIT_PG_NAME" \
    --cpu "$STACKIT_PG_CPU" \
    --ram "$STACKIT_PG_RAM" \
    --storage-size "$STACKIT_PG_STORAGE_SIZE" \
    --storage-class "$STACKIT_PG_STORAGE_CLASS" \
    "${acl_args[@]}")

  instance_id=$(json_find_first_string "$create_json" id instanceId instance_id)
  state_write createdAt '"'"$(date -Is)"'"'
  state_write projectId '"'"$STACKIT_PROJECT_ID"'"'
  state_write region '"'"$STACKIT_REGION"'"'
  state_write postgresInstanceId '"'"$instance_id"'"'
  state_write postgresInstanceCreateResponse "$create_json"
fi

echo "[2/4] Creating PostgresFlex user (captures password once)..." >&2
if user_id=$(state_get postgresUserId 2>/dev/null); then
  echo "Using existing user from state: $user_id" >&2
else
  user_json=$(stackit_json postgresflex user create --instance-id "$instance_id" --username "$MAPSTER_DB_USER")
  user_id=$(json_find_first_string "$user_json" id userId user_id)
  state_write postgresUserId '"'"$user_id"'"'
  state_write postgresUserCreateResponse "$user_json"
fi

echo "[3/4] Resolving DB endpoint + creating k8s secret..." >&2

describe_json=$(stackit_json postgresflex instance describe "$instance_id")
state_write postgresInstanceDescribeResponse "$describe_json"

# Best-effort: Stackit output structure can change; try several key names.
host=""
if host=$(json_find_first_string "$describe_json" host hostname endpoint fqdn address 2>/dev/null); then
  :
fi
port="5432"
if port_tmp=$(json_find_first_string "$describe_json" port 2>/dev/null); then
  port="$port_tmp"
fi

# Manual override in case the JSON structure changes or requires a nested lookup.
host="${STACKIT_PG_HOST:-$host}"
port="${STACKIT_PG_PORT:-$port}"

if [[ -z "$host" ]]; then
  echo "Could not auto-detect Postgres endpoint from 'instance describe' output." >&2
  echo "Please set STACKIT_PG_HOST (and optionally STACKIT_PG_PORT) and re-run." >&2
  exit 2
fi

db_name="${MAPSTER_DB_NAME:-mapster}"
jdbc_url="jdbc:postgresql://$host:$port/$db_name"

state_write postgresHost '"'"$host"'"'
state_write postgresPort '"'"$port"'"'
state_write postgresDbName '"'"$db_name"'"'

# Extract password from user create response (password is only visible on creation).
user_create_json=$(state_get postgresUserCreateResponse)
password=""
if password=$(json_find_first_string "$user_create_json" password 2>/dev/null); then
  :
fi
if [[ -z "$password" ]]; then
  echo "Could not extract DB password from user create response in state." >&2
  echo "If the user already existed and you lost the password, run reset-password and update the k8s secret." >&2
  exit 2
fi

kubectl get ns "$KUBE_NAMESPACE" >/dev/null 2>&1 || kubectl create ns "$KUBE_NAMESPACE"

# Ensure supporting secrets exist (image pull + GPKG download).
if ! kubectl -n "$KUBE_NAMESPACE" get secret "$KUBE_REGISTRY_SECRET_NAME" >/dev/null 2>&1; then
  if [[ -z "${REGISTRY_SERVER:-}" || -z "${REGISTRY_USERNAME:-}" || -z "${REGISTRY_PASSWORD:-}" ]]; then
    echo "Missing image pull secret '$KUBE_REGISTRY_SECRET_NAME' in namespace '$KUBE_NAMESPACE'." >&2
    echo "Either create it manually, or export these env vars and re-run:" >&2
    echo "  REGISTRY_SERVER=... REGISTRY_USERNAME=... REGISTRY_PASSWORD=..." >&2
    exit 2
  fi
  kubectl -n "$KUBE_NAMESPACE" create secret docker-registry "$KUBE_REGISTRY_SECRET_NAME" \
    --docker-server="$REGISTRY_SERVER" \
    --docker-username="$REGISTRY_USERNAME" \
    --docker-password="$REGISTRY_PASSWORD"
fi

if ! kubectl -n "$KUBE_NAMESPACE" get secret "$KUBE_OBJECT_STORAGE_SECRET_NAME" >/dev/null 2>&1; then
  missing=()
  for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION S3_ENDPOINT S3_BUCKET S3_KEY; do
    [[ -n "${!v:-}" ]] || missing+=("$v")
  done
  if (( ${#missing[@]} )); then
    echo "Missing object storage secret '$KUBE_OBJECT_STORAGE_SECRET_NAME' in namespace '$KUBE_NAMESPACE'." >&2
    echo "Either create it manually, or export these env vars and re-run:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    exit 2
  fi
  kubectl -n "$KUBE_NAMESPACE" create secret generic "$KUBE_OBJECT_STORAGE_SECRET_NAME" \
    --from-literal=AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    --from-literal=AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    --from-literal=AWS_DEFAULT_REGION="$AWS_DEFAULT_REGION" \
    --from-literal=S3_ENDPOINT="$S3_ENDPOINT" \
    --from-literal=S3_BUCKET="$S3_BUCKET" \
    --from-literal=S3_KEY="$S3_KEY"
fi

kubectl -n "$KUBE_NAMESPACE" delete secret "$KUBE_DB_SECRET_NAME" >/dev/null 2>&1 || true
kubectl -n "$KUBE_NAMESPACE" create secret generic "$KUBE_DB_SECRET_NAME" \
  --from-literal=PGHOST="$host" \
  --from-literal=PGPORT="$port" \
  --from-literal=PGDATABASE="$db_name" \
  --from-literal=PGUSER="$MAPSTER_DB_USER" \
  --from-literal=PGPASSWORD="$password" \
  --from-literal=JDBC_URL="$jdbc_url"

echo "[4/4] Deploying Mapster (managed Postgres overlay)..." >&2
kubectl apply -k "$SCRIPT_DIR/../../k8s/overlays/stackit-managed-postgres"

echo "OK: provisioned instance=$instance_id, user=$user_id, host=$host" >&2
