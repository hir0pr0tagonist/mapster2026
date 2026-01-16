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

For STACKIT, create a small overlay directory:

- `k8s/overlays/stackit/`

This overlay will:

- set images to your pushed registry tags
- configure the import job to download the GPKG from object storage
- (optionally) configure ingress host + TLS

If you want, I can implement this overlay directly in the repo; for now this guide explains the edits.

### 5.1 Update image references

In [k8s/kustomization.yaml](k8s/kustomization.yaml), you can use `images:` overrides.

Conceptually:

- api image → `REGISTRY/PROJECT/mapster-cloud-api:0.1.0`
- web image → `REGISTRY/PROJECT/mapster-cloud-web:0.1.0`
- import image → `REGISTRY/PROJECT/mapster-cloud-import:0.1.0`

### 5.2 Add imagePullSecrets (if needed)

If you created `regcred`, add to `api-deployment.yaml` and `web-deployment.yaml` and `import-job.yaml`:

- `spec.template.spec.imagePullSecrets: [{ name: regcred }]`

### 5.3 Replace hostPath with object download

Approach:

- Add an `initContainer` to the import job that downloads the GPKG into an `emptyDir` volume.
- Set `GPKG_PATH` to the downloaded file location.

You’ll need:

- a downloader image (`curlimages/curl`, or `amazon/aws-cli` if S3-compatible, or another STACKIT-supported method)
- credentials mounted as env vars or a config file

Because every provider differs slightly, I need one detail from you:

- Is STACKIT Object Storage S3-compatible (endpoint URL + access key/secret)?

If yes, the import-job pattern looks like:

- initContainer runs `aws s3 cp s3://BUCKET/KEY /geodata/gadm.gpkg`

If not, we can use HTTPS pre-signed URLs and `curl`.

### 5.4 Configure the public hostname and TLS

In [k8s/ingress.yaml](k8s/ingress.yaml), currently the host is `mapster.local`.

For cloud, set this to your real domain, e.g.:

- `mapster.example.com`

TLS options:

- **Recommended**: cert-manager + Let’s Encrypt
- Or: upload a certificate into a TLS secret manually

This is the only part where UI differences matter. If you tell me whether STACKIT gives you a managed ingress + cert integration, I can pick the simplest.

## Step 6: Deploy to STACKIT

### 6.1 Apply base resources

From repo root:

```sh
kubectl apply -k k8s/
```

Then apply your STACKIT overlay (once created), e.g.:

```sh
kubectl apply -k k8s/overlays/stackit/
```

### 6.2 Wait for PostGIS

```sh
kubectl -n mapster get pods -w
```

Wait until PostGIS is `Running` and `Ready`.

### 6.3 Run the import job

Once the import job is configured for object storage, run it:

```sh
kubectl -n mapster delete job import-admin-areas --ignore-not-found
kubectl -n mapster apply -f k8s/import-job.yaml
kubectl -n mapster logs -f job/import-admin-areas
```

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
