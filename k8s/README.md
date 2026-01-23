# Mapster on Minikube (quick start)

This document is for local development on Minikube.

For managed Kubernetes (STACKIT), use the overlay docs:

- [k8s/overlays/stackit/README.md](k8s/overlays/stackit/README.md)
- [DEPLOYMENT_STACKIT.md](../DEPLOYMENT_STACKIT.md)

## 1) Prereqs
- `minikube start`
- Enable ingress: `minikube addons enable ingress`

## 2) Build images and load into minikube
This repo currently builds images via Docker Compose. In minikube you have two common options:

Option A (simple): build on the host, then load into minikube
- `docker compose build api web import`
- `minikube image load mapster-cloud-api:latest`
- `minikube image load mapster-cloud-web:latest`
- `minikube image load mapster-cloud-import:latest`

If you rebuild an image but minikube still seems to run an older digest, use the most reliable method:

- `docker save mapster-cloud-api:latest | minikube image load - --overwrite=true`
- `docker save mapster-cloud-web:latest | minikube image load - --overwrite=true`
- `docker save mapster-cloud-import:latest | minikube image load - --overwrite=true`

Then restart the workloads so they pick up the refreshed image:

- `kubectl -n mapster rollout restart deployment/api deployment/web`

Option B: build directly inside the minikube docker daemon
- `eval $(minikube -p minikube docker-env)`
- `docker compose build api web import`

## 3) Provide the GeoPackage to the import job
The import job reads a GeoPackage path from `GPKG_PATH`.

In Kubernetes, volume mounts can hide files baked into the image. The import job therefore runs the
script from `/usr/local/bin/upload_geopackage.sh` (not under `/data`).

Because the GPKG is large, the recommended minikube workflow is to mount your local directory into the
minikube node using `minikube mount` (no huge copy).

1) Apply the base resources: `kubectl apply -k k8s/`
2) In a separate terminal, run (keep it running):
   - `minikube mount $(pwd)/postgis/geodata:/host/geodata`

Then (re)run the import job:
- `kubectl -n mapster delete job import-admin-areas --ignore-not-found`
- `kubectl -n mapster apply -f k8s/import-job.yaml`

If you prefer a "pure k8s" approach (more cloud-like), replace the `minikube mount` step with:
- downloading the file from object storage in an initContainer, or
- pre-populating the PVC using a one-off helper pod.

## 4) Access the app
- Add `mapster.local` to your hosts file:
  - `echo "$(minikube ip) mapster.local" | sudo tee -a /etc/hosts`
- Browse: `http://mapster.local/`

## 5) (Optional) Seed demo shading data (no point-in-polygon)
If you want the UI to show metric shading without ingesting real observations (and without any point-in-polygon work),
run the demo seed job. It assigns a deterministic, spatially clustered pseudo-random value to every admin area and
stores it in `facts_agg.area_metric_daily`.

- `kubectl -n mapster apply -f k8s/demo-seed/seed-demo-shading-configmap.yaml`
- `kubectl -n mapster delete job seed-demo-shading --ignore-not-found`
- `kubectl -n mapster apply -f k8s/demo-seed/seed-demo-shading-job.yaml`

Watch the job:
- `kubectl -n mapster logs -f job/seed-demo-shading`

Then refresh `http://mapster.local/` and pick `price_eur_per_m2_land`.

## Notes for later cloud deployment (STACKIT or any managed K8s)
- Replace local image loading with a real container registry (CI builds + versioned tags).
- Replace the gpkg PVC population step with one of:
  - baking the GPKG into the import image (bigger image), or
  - downloading from object storage in an initContainer, or
  - using a CSI driver for object storage.
- Use Secrets for DB credentials (already done) and consider ExternalSecrets/Vault later.
- Use pinned image tags (no `latest`) and define resource requests/limits.
- Prefer an Ingress controller + cert-manager for TLS.
