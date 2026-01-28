# STACKIT CLI deployment scripts (IaC-by-script)

These scripts provision and tear down the STACKIT resources needed to run Mapster Cloud with a **managed PostgreSQL (PostgresFlex)**.

They are designed to support the “pods are ephemeral” model:

- Postgres data is **outside** Kubernetes.
- Kubernetes resources can be recreated freely.

## Prereqs

- `stackit` CLI installed and authenticated.
- `kubectl` configured to point at your target cluster.
- `python3` available locally (used for lightweight JSON parsing).

Optional but recommended:

- `docker` (only if you also build/push images as part of your workflow)

## Configure

Export required env vars (example):

```sh
export STACKIT_PROJECT_ID="eeadbd4a-148a-4005-b046-5362ef9ad86f"
export STACKIT_REGION="<STACKIT_REGION>"  # e.g. EU01 (Germany South) region identifier

# PostgresFlex instance settings
export STACKIT_PG_NAME="mapster-db"
export STACKIT_PG_CPU=2
export STACKIT_PG_RAM=4
export STACKIT_PG_STORAGE_SIZE=20

# Access control
# NOTE: PostgresFlex instance create requires ACLs. For “internal only”, you should
# provide the CIDR(s) that represent your cluster egress / NAT / VPC ranges.
export STACKIT_PG_ACL_CIDRS="<CIDR1>,<CIDR2>"  # comma-separated, e.g. "203.0.113.10/32"

# DB user
export MAPSTER_DB_USER="mapster"

# Kubernetes
export KUBE_NAMESPACE="mapster"
export KUBE_DB_SECRET_NAME="db-secret"

# Optional: have up.sh create the image pull secret (regcred) if missing
export REGISTRY_SERVER="registry.onstackit.cloud"
export REGISTRY_USERNAME="YOUR_USER"
export REGISTRY_PASSWORD="YOUR_PASSWORD"

# Optional: have up.sh create the object storage secret if missing
export AWS_ACCESS_KEY_ID="YOUR_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="YOUR_SECRET_KEY"
export AWS_DEFAULT_REGION="us-east-1"
export S3_ENDPOINT="https://YOUR_S3_ENDPOINT"
export S3_BUCKET="YOUR_BUCKET"
export S3_KEY="path/to/gadm_410-levels.gpkg"
```

Tip: to find the exact region string, run:

```sh
stackit project describe "$STACKIT_PROJECT_ID" --output-format json
```

## Authentication (service account key)

Recommended for automation is a service account key file (keep it out of git).

Create the service account:

```sh
stackit service-account create --name mapster-sa --project-id "$STACKIT_PROJECT_ID" --region "$STACKIT_REGION" --output-format json
```

The command returns an email like `...@sa.stackit.cloud`. Create a key for that email (this returns JSON that includes the private key once):

```sh
stackit service-account key create --email "<SA_EMAIL>" --project-id "$STACKIT_PROJECT_ID" --region "$STACKIT_REGION" --output-format json > service_account_key.json
chmod 600 service_account_key.json
```

Activate it for your shell:

```sh
export STACKIT_SERVICE_ACCOUNT_KEY_PATH="$PWD/service_account_key.json"
# If your UI generated a separate private key file too, you can (optionally) pass it explicitly:
# export STACKIT_PRIVATE_KEY_PATH="$PWD/private.key"
./scripts/stackit/auth.sh
```

Then run:

```sh
./scripts/stackit/preflight.sh
```

If `up.sh` cannot auto-detect the Postgres endpoint from `stackit postgresflex instance describe`, you can override:

```sh
export STACKIT_PG_HOST="your.db.endpoint"
export STACKIT_PG_PORT="5432"
```

## Bring everything up

```sh
./scripts/stackit/up.sh
```

### Optional: provision the Kubernetes cluster via CLI

If you want the scripts to also provision an SKE cluster (instead of doing it manually in the UI), set:

```sh
export STACKIT_CREATE_SKE_CLUSTER=true
export STACKIT_SKE_CLUSTER_NAME="mapster"

# Defaults are capped to match your request: max 4 vCPU / 16GB RAM per node
export STACKIT_SKE_MAX_CPU=4
export STACKIT_SKE_MAX_RAM_GB=16
export STACKIT_SKE_NODE_COUNT=2

# Optional: override machine type explicitly if auto-pick fails
# export STACKIT_SKE_MACHINE_TYPE="<machine-type>"
```

This uses [scripts/stackit/cluster_up.sh](scripts/stackit/cluster_up.sh) under the hood and writes a kubeconfig to:

- [scripts/stackit/kubeconfig.<cluster>](scripts/stackit)

You can run it standalone too:

```sh
./scripts/stackit/cluster_up.sh
```

This will:

1) Create a PostgresFlex instance.
2) Create a PostgresFlex user (password captured once).
3) Create/update a Kubernetes Secret (`db-secret`) with connection info.
4) Deploy Mapster using the managed-db overlay: `k8s/overlays/stackit-managed-postgres`.

## Tear everything down

```sh
./scripts/stackit/down.sh
```

To also delete the SKE cluster (only if you explicitly want that):

```sh
export STACKIT_SKE_CLUSTER_NAME="mapster"
export STACKIT_DELETE_SKE_CLUSTER=true
./scripts/stackit/down.sh
```

Or run the cluster teardown directly:

```sh
./scripts/stackit/cluster_down.sh
```

## Preflight

To get as close as possible to a “dry run” (auth + machine-type discovery + kubectl connectivity):

```sh
./scripts/stackit/preflight.sh
```

This will:

- Delete the Kubernetes namespace (default: `mapster`).
- Delete the PostgresFlex instance created by `up.sh`.

## State file

Scripts write state to:

- `scripts/stackit/state.json`

It stores instance/user IDs and the resolved endpoint (when available).
