# Deployment Guide: STACKIT (Managed Kubernetes)

This guide walks you through deploying Mapster Cloud to STACKIT using a managed Kubernetes cluster.

It is written so you can follow it even if this is your first cloud/Kubernetes deployment. Where STACKIT has UI differences, I’ll point out what to look for; if a screen doesn’t match what you see, tell me what options you have and I’ll adapt the steps.

## What you’ll deploy

Mapster Cloud consists of:

- **PostGIS** (StatefulSet + PVC) storing administrative boundaries.
- **API** (Spring Boot) serving:
  - MVT tiles: `GET /api/tiles/{z}/{x}/{y}.mvt`
  - GeoJSON overlays: `GET /api/overlays`
- **Web** (Nginx + MapLibre) as the browser UI.
- **Import Job** (GDAL/ogr2ogr) that loads the GPKG into PostGIS.

Kubernetes manifests are in:

- [k8s/](k8s/)

## High-level plan (cloud-ready)

Minikube-specific parts you used locally must be replaced:

- **Minikube image load** → **Push images to a registry** accessible by the cluster.
- **minikube mount + hostPath** → **Object Storage download** (recommended) or a cloud PVC pre-population method.
- **Ingress host-only routing** → set DNS + TLS for a real domain.

## Prerequisites

### Accounts / access

- STACKIT account with access to create:
  - a Kubernetes cluster (managed K8s),
  - a container registry (or any registry the cluster can pull from),
  - optionally object storage (recommended for the large GPKG),
  - DNS for a domain you control (or at least a subdomain).

If you tell me which STACKIT products you have enabled (Kubernetes? Container Registry? Object Storage?), I can tailor this precisely.

### Local tools

You’ll need these locally:

- `kubectl`
- `kustomize` (or `kubectl apply -k` on newer kubectl)
- `docker`

Note: if you previously used minikube, some setups alias `kubectl` to `minikube kubectl --`.
That wrapper will keep targeting minikube even if you pass a different kubeconfig.
Run `type kubectl` and make sure it’s a real kubectl binary (not an alias), or `unalias kubectl` and install a standalone `kubectl`.

Optional but strongly recommended:

- `helm` (for installing cert-manager / external-dns if you choose)

### Source artifacts

- A GeoPackage file (GPKG), e.g. `gadm_410-levels.gpkg`.

## Step 1: Create a Kubernetes cluster on STACKIT

In STACKIT, look for something like:

- “Kubernetes” / “Managed Kubernetes” / “Kubernetes Engine”

Create a cluster with:

- at least **2 nodes** (for basic availability)
- enough RAM/CPU to run Postgres comfortably (start with 2–4 vCPU and 8–16GB RAM total across nodes if you can; you can scale later)

### Obtain kubeconfig

STACKIT should offer a kubeconfig download or a command to configure access. Once you have it:

```sh
kubectl get nodes
```

You should see your cluster nodes.

## Step 2: Decide how you will provide the GeoPackage in cloud

In Minikube you used `minikube mount` + hostPath. In cloud you generally want one of these patterns:

### Option A (recommended): Store GPKG in Object Storage

Pros:
- clean, cloud-native
- no huge container images
- easy to repeat imports

Approach:
- Upload `gadm_410-levels.gpkg` to object storage (bucket).
- Import job downloads it at runtime (initContainer or the job container itself).

### Option B: Bake GPKG into the import image

Pros:
- simplest runtime

Cons:
- very large image, slow pulls, expensive rebuilds

### Option C: Copy GPKG into a PVC using a helper pod

Pros:
- no object storage required

Cons:
- more manual steps

This guide uses **Option A**. If you prefer B or C, tell me and I’ll provide the exact manifest edits.

## Step 3: Build and push container images to a registry

Your Kubernetes cluster must be able to pull these images:

- `mapster-cloud-api`
- `mapster-cloud-web`
- `mapster-cloud-import`

### 3.1 Choose an image registry

Common choices:

- STACKIT Container Registry (if available)
- GitHub Container Registry
- Docker Hub

You need:

- a registry URL (example format): `registry.example.com/my-project`
- credentials (or a pull secret)

### 3.2 Build images

From the repo root:

```sh
docker compose build api web import
```

### 3.3 Tag images with a real version

Avoid `latest` in cloud deployments. Since you tagged code as `0.1.0`, use that.

Example (replace `REGISTRY/PROJECT` with your real registry path):

```sh
docker tag mapster-cloud-api:latest REGISTRY/PROJECT/mapster-cloud-api:0.1.0
docker tag mapster-cloud-web:latest REGISTRY/PROJECT/mapster-cloud-web:0.1.0
docker tag mapster-cloud-import:latest REGISTRY/PROJECT/mapster-cloud-import:0.1.0
```

### 3.4 Push images

```sh
docker push REGISTRY/PROJECT/mapster-cloud-api:0.1.0
docker push REGISTRY/PROJECT/mapster-cloud-web:0.1.0
docker push REGISTRY/PROJECT/mapster-cloud-import:0.1.0
```

### 3.5 (If required) Create an image pull secret

If your registry is private, create a Kubernetes secret in the `mapster` namespace.

Example:

```sh
kubectl create namespace mapster
kubectl -n mapster create secret docker-registry regcred \
  --docker-server=REGISTRY \
  --docker-username=YOUR_USER \
  --docker-password=YOUR_PASSWORD
```

Then you’ll reference `imagePullSecrets` in deployments (see Step 5).

## Step 4: Set up Object Storage for the GPKG (Option A)

### 4.1 Create a bucket

In STACKIT look for:

- “Object Storage” / “S3” / “Buckets”

Create a bucket, e.g. `mapster-data`.

### 4.2 Upload the GPKG

Upload:

- `gadm_410-levels.gpkg`

Record:

- bucket name
- object key/path (example: `gadm/gadm_410-levels.gpkg`)

### 4.3 Create access credentials

Create an access key/secret for the bucket.

We’ll store these in Kubernetes as a Secret.

## Step 5: Create a production overlay for Kubernetes manifests

Right now, [k8s/import-job.yaml](k8s/import-job.yaml) is minikube-oriented (hostPath `/host-geodata`).

For STACKIT, use the included overlay directory:

- [k8s/overlays/stackit/](k8s/overlays/stackit/)

This overlay will:

- set images to your pushed registry tags
- configure the import job to download the GPKG from object storage
- (optionally) configure ingress host + TLS

This overlay is already implemented in the repo; you only need to edit a few placeholders.

### 5.1 Update image references

Edit [k8s/overlays/stackit/kustomization.yaml](k8s/overlays/stackit/kustomization.yaml) and set:

- `REGISTRY/PROJECT` (your container registry path)
- image tags (keep `0.1.0` or bump as you release)
- `YOUR_DOMAIN` (ingress hostname)

Conceptually:

- api image → `REGISTRY/PROJECT/mapster-cloud-api:0.1.0`
- web image → `REGISTRY/PROJECT/mapster-cloud-web:0.1.0`
- import image → `REGISTRY/PROJECT/mapster-cloud-import:0.1.0`

### 5.2 Add imagePullSecrets (if needed)

If your registry is private, create `regcred` in the `mapster` namespace. The STACKIT overlay already includes patches that add `imagePullSecrets: [{ name: regcred }]` for api/web/import.

### 5.3 Replace hostPath with object download

The STACKIT overlay replaces the import job’s minikube-only `hostPath` with an `initContainer` that downloads the GPKG from S3-compatible object storage into an `emptyDir` volume.

Create the required secret (edit values):

```sh
kubectl -n mapster create secret generic object-storage-secret \
  --from-literal=AWS_ACCESS_KEY_ID='YOUR_ACCESS_KEY' \
  --from-literal=AWS_SECRET_ACCESS_KEY='YOUR_SECRET_KEY' \
  --from-literal=AWS_DEFAULT_REGION='us-east-1' \
  --from-literal=S3_ENDPOINT='https://YOUR_S3_ENDPOINT' \
  --from-literal=S3_BUCKET='YOUR_BUCKET' \
  --from-literal=S3_KEY='path/to/gadm_410-levels.gpkg'
```

If your object storage is *not* S3-compatible, we can switch the download initContainer to use a pre-signed HTTPS URL + `curl` instead.

### 5.4 Configure the public hostname and TLS

In [k8s/ingress.yaml](k8s/ingress.yaml), currently the host is `mapster.local`.

For cloud, set this to your real domain, e.g.:

- `mapster.example.com`

TLS options:

- **Recommended**: cert-manager + Let’s Encrypt
- Or: upload a certificate into a TLS secret manually

This is the only part where UI differences matter. If you tell me whether STACKIT gives you a managed ingress + cert integration, I can pick the simplest.

Ingress controller note:

- The cluster must have an Ingress controller installed (e.g. ingress-nginx).
- The Ingress resource must include an IngressClass (for ingress-nginx: `spec.ingressClassName: nginx`) so the controller reconciles it.
- The STACKIT overlay patches the Ingress to set `ingressClassName: nginx`.

## Step 6: Deploy to STACKIT

### 6.1 Apply the STACKIT overlay

From repo root:

```sh
kubectl apply -k k8s/overlays/stackit/
```

Note: the STACKIT overlay is self-contained (it vendors the base manifests under the overlay directory) so you don't need to apply `k8s/` separately.

### 6.2 Wait for PostGIS

```sh
kubectl -n mapster get pods -w
```

Wait until PostGIS is `Running` and `Ready`.

If PostGIS crashes during init with an error mentioning `lost+found`, it usually means the PV mount contains a filesystem root. The manifests set `PGDATA` to a subdirectory to avoid this failure mode.

### 6.3 Run the import job

The overlay applies the import job too. To run (or re-run) it:

```sh
kubectl -n mapster delete job import-admin-areas --ignore-not-found
kubectl apply -k k8s/overlays/stackit/
kubectl -n mapster logs -f job/import-admin-areas
```

If the job fails in the `download-gpkg` initContainer with `Key ... does not exist`, your `S3_KEY` value does not match the object path you uploaded. Fix the secret and re-run the job.

### 6.4 Validate the API

Once api/web pods are ready, test:

```sh
curl -I https://YOUR_DOMAIN/api/tiles/6/33/20.mvt
```

You should see:

- `200` (or `304` on subsequent calls)
- `Content-Type: application/vnd.mapbox-vector-tile`

## Step 7: Production hardening (recommended)

These are the most impactful improvements for a real cloud deployment:

1) **Postgres backups**
- Prefer managed backup/snapshot if STACKIT offers it.
- Otherwise schedule pg_dump to object storage.

2) **Resource requests/limits**
- Add requests/limits for api/web/postgis.

3) **Database persistence**
- Ensure the PostGIS PVC uses a cloud storage class (not hostPath).

4) **TLS everywhere**
- Terminate TLS at the ingress.

5) **Observability**
- At minimum: container logs + basic CPU/memory metrics.
- API has Actuator enabled for:
  - Kubernetes probes: `/api/actuator/health/liveness` and `/api/actuator/health/readiness`
  - Prometheus scrape: `/api/actuator/prometheus`

  Quick checks (no ingress changes; uses port-forward):

  ```sh
  kubectl -n mapster port-forward deploy/api 8080:8080
  curl -fsS http://localhost:8080/api/actuator/health/readiness
  curl -fsS http://localhost:8080/api/actuator/prometheus | head
  ```

  Tracing (OpenTelemetry / OTLP) is supported but **off by default**.
  To enable it, set these environment variables on the API deployment:

  - `TRACING_ENABLED=true`
  - `TRACING_SAMPLING_PROBABILITY=0.1` (or `1.0` for debugging)
  - `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://YOUR_OTEL_ENDPOINT/v1/traces`
  - `OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer YOUR_TOKEN` (if your collector requires auth)

  Notes:
  - Prefer keeping actuator endpoints internal (cluster-only). If you need external access, add a dedicated ingress path for `/actuator` (or a separate management port) and secure it.
  - The provided Kubernetes manifest already adds Prometheus scrape annotations to the API pods.

## What I need from you to make this “STACKIT-exact”

Reply with:

1) Which STACKIT product you’re using for Kubernetes (name in the UI).
2) Do you see a built-in “Container Registry” product? If yes, what registry URL does it show?
3) Do you have “Object Storage” / S3, and does it show an endpoint URL?
4) What domain do you want to use (or do you need to use a provided LB hostname first)?

Once you give me those 4 items, I can:

- implement `k8s/overlays/stackit/` in the repo,
- wire the import job to object storage,
- and tell you the exact click-path in the STACKIT UI based on what you see.
