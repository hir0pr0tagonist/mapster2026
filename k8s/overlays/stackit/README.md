# STACKIT overlay (Kustomize)

This overlay turns the base `k8s/` manifests into something you can deploy on a real Kubernetes cluster:

- swaps images from local `:latest` to your registry + version tag
- replaces the minikube `hostPath` import flow with an S3/Object-Storage download
- bumps the PostGIS PVC size (base default is too small for most imports)
- replaces ingress host `mapster.local` with your real domain
- pins the IngressClass (`nginx`) so ingress-nginx actually reconciles the Ingress
- provides opt-in tracing env vars (disabled by default)

Note: because this overlay uses object storage, it removes the unused `gpkg-data` PVC from the base manifests.

## 1) Configure registry images + domain

Edit `k8s/overlays/stackit/kustomization.yaml`:

- Set the image registry + tags you’ve pushed
- Set the Ingress host to your real hostname

Note: the repo may already contain concrete values (e.g. `registry.onstackit.cloud/...` and `mapster.info`). Treat those as examples and change them for your environment.

The API is intended to be served under `https://YOUR_DOMAIN/api/...` (implemented via `server.servlet.context-path=/api`).

## 2) Create the image pull secret (private registries)

If your registry is private, create a pull secret named `regcred` in the `mapster` namespace:

```sh
kubectl create namespace mapster
kubectl -n mapster create secret docker-registry regcred \
  --docker-server=REGISTRY \
  --docker-username=YOUR_USER \
  --docker-password=YOUR_PASSWORD
```

This overlay applies patches that set `imagePullSecrets: [{ name: regcred }]` for api/web/import.

## 3) Create the object storage secret

The import job expects a secret named `object-storage-secret` in the `mapster` namespace.

Create it like this (replace values):

```sh
kubectl -n mapster create secret generic object-storage-secret \
  --from-literal=AWS_ACCESS_KEY_ID='YOUR_ACCESS_KEY' \
  --from-literal=AWS_SECRET_ACCESS_KEY='YOUR_SECRET_KEY' \
  --from-literal=AWS_DEFAULT_REGION='us-east-1' \
  --from-literal=S3_ENDPOINT='https://YOUR_S3_ENDPOINT' \
  --from-literal=S3_BUCKET='YOUR_BUCKET' \
  --from-literal=S3_KEY='path/to/gadm_410-levels.gpkg'
```

Notes:
- For many S3-compatible providers, the region value is required by the AWS CLI even if it’s not meaningful.
- If you use pre-signed HTTPS URLs instead of S3 creds, we can switch the initContainer to `curl` instead.

Troubleshooting:

- If the import job fails with `Key ... does not exist`, your `S3_KEY` doesn’t match the object path you uploaded.
- You can validate the bucket/key from inside the cluster using a one-off `amazon/aws-cli` pod and `aws s3 ls`.

## 4) (Optional) Enable tracing

By default, tracing stays off because `TRACING_ENABLED=false` in `api-deployment-observability-patch.yaml`.

To enable it, edit that file and set:

- `TRACING_ENABLED=true`
- `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=.../v1/traces`
- `OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer ...` (if needed)

## 5) Apply

```sh
kubectl apply -k k8s/overlays/stackit/
```

Applying the overlay also applies the import job.

To re-run the import job:

```sh
kubectl -n mapster delete job import-admin-areas --ignore-not-found
kubectl apply -k k8s/overlays/stackit/
kubectl -n mapster logs -f job/import-admin-areas
```

## Notes: PostGIS on PVC-backed volumes

Some storage backends mount a filesystem root that contains `lost+found`, which breaks Postgres init if you mount it directly as the data directory.
The manifests set `PGDATA=/var/lib/postgresql/data/pgdata` to keep Postgres data in a subdirectory and avoid this failure mode.
