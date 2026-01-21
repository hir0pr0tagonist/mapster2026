Below is a “tear down + rebuild” runbook that’s intentionally command-line-first for everything you control locally (Docker/AWS CLI/kubectl). The only parts that may still require the STACKIT Portal (or Terraform/STACKIT CLI) are: creating the Kubernetes cluster + getting kubeconfig, creating the Container Registry project/robot creds, and creating Object Storage bucket + access keys. I’ll mark those clearly.

---

## 0) One-time local prerequisites

Set these once on your machine:

- `kubectl`, `docker`, `aws` (AWS CLI v2)
- Repo checked out (this folder): [mapster-cloud](mapster-cloud)

Optional but recommended:
- `jq`

---

## A) Tear down (stop spending money)

### A1) Verify you’re targeting STACKIT (safety)
```sh
kubectl --kubeconfig mapster.yml get nodes
kubectl --kubeconfig mapster.yml get ns | head
```

### A2) Delete the Mapster app (namespace-scoped)
This removes Deployments/Services/Ingress/Jobs/PVCs in `mapster`.
```sh
kubectl --kubeconfig mapster.yml delete namespace mapster --ignore-not-found
kubectl --kubeconfig mapster.yml get ns | grep mapster || true
```

If you want to be extra sure all storage is gone (sometimes PVC deletion lags):
```sh
kubectl --kubeconfig mapster.yml get pvc -A | grep mapster || true
kubectl --kubeconfig mapster.yml get pv | grep mapster || true
```

### A3) Remove ingress-nginx (LoadBalancer cost)
If you installed it in `ingress-nginx`:
```sh
kubectl --kubeconfig mapster.yml delete namespace ingress-nginx --ignore-not-found
kubectl --kubeconfig mapster.yml get svc -A | grep -i loadbalancer || true
```

### A4) (Optional) Delete cluster-level resources (biggest cost)
This depends on how you created the cluster:
- If you created it in the Portal: delete the cluster there.
- If you used Terraform: `terraform destroy`.
- If you used STACKIT CLI: use the CLI destroy/delete commands for the cluster.

### A5) (Optional) Delete Object Storage bucket + access keys
Only do this if you truly want to wipe it:
- Delete object(s) + bucket in Portal (or via S3 tooling).
- Revoke the access key you created.

### A6) (Optional) Delete Container Registry repos / robot creds
- Delete repos/images in Portal, revoke robot creds if you want a clean slate.

---

## B) Rebuild from scratch

### B1) Create infra (Portal/Terraform/STACKIT CLI)
You need three things:

1) **Kubernetes cluster** + download kubeconfig to `mapster.yml`  
2) **Container Registry project** + robot creds (username like `robot$...`, password/token)  
3) **Object Storage bucket** + S3 access key/secret + endpoint URL

Quick sanity checks once kubeconfig exists:
```sh
kubectl --kubeconfig mapster.yml get nodes
kubectl --kubeconfig mapster.yml get storageclass
```

---

## C) Configure local env vars (so commands are copy/paste)

### C1) Registry values
```sh
export REGISTRY_HOST='registry.onstackit.cloud'
export REGISTRY_PROJECT='mapster'
export IMAGE_PREFIX="$REGISTRY_HOST/$REGISTRY_PROJECT"
export VERSION_API='0.1.1'
export VERSION_WEB='0.1.0'
export VERSION_IMPORT='0.1.0'
```

### C2) Domain
```sh
export DOMAIN='mapster.info'
```

### C3) Object Storage values (match your STACKIT bucket)
```sh
export S3_ENDPOINT='https://object.storage.eu01.onstackit.cloud'
export S3_BUCKET='bucketmapster'
export S3_KEY='gadm_410-levels.gpkg'
export AWS_DEFAULT_REGION='us-east-1'
export AWS_ACCESS_KEY_ID='...'
export AWS_SECRET_ACCESS_KEY='...'
```

---

## D) Build + push images

### D1) Login to registry (robot account)
```sh
docker login "$REGISTRY_HOST"
```

### D2) Build images locally
From the repo root:
```sh
cd /home/vman/Development/mapster/mapster-cloud
docker compose build api web import
```

### D3) Tag images
```sh
docker tag mapster-cloud-api:latest    "$IMAGE_PREFIX/mapster-cloud-api:$VERSION_API"
docker tag mapster-cloud-web:latest    "$IMAGE_PREFIX/mapster-cloud-web:$VERSION_WEB"
docker tag mapster-cloud-import:latest "$IMAGE_PREFIX/mapster-cloud-import:$VERSION_IMPORT"
```

### D4) Push images
```sh
docker push "$IMAGE_PREFIX/mapster-cloud-api:$VERSION_API"
docker push "$IMAGE_PREFIX/mapster-cloud-web:$VERSION_WEB"
docker push "$IMAGE_PREFIX/mapster-cloud-import:$VERSION_IMPORT"
```

---

## E) Install ingress-nginx (creates the public LoadBalancer)

```sh
kubectl --kubeconfig mapster.yml apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/cloud/deploy.yaml
kubectl --kubeconfig mapster.yml -n ingress-nginx rollout status deploy/ingress-nginx-controller --timeout=180s
kubectl --kubeconfig mapster.yml -n ingress-nginx get svc ingress-nginx-controller
```

Grab the external IP from that Service and set your DNS A record:
- `mapster.info` → `<EXTERNAL_IP>`

Wait until:
```sh
dig +short mapster.info
```
matches the LB IP.

---

## F) Upload the GPKG to Object Storage

```sh
aws --endpoint-url "$S3_ENDPOINT" s3 cp "/path/to/gadm_410-levels.gpkg" "s3://$S3_BUCKET/$S3_KEY"
aws --endpoint-url "$S3_ENDPOINT" s3 ls "s3://$S3_BUCKET/" | head
```

---

## G) Deploy Mapster to the cluster (STACKIT overlay)

### G1) Create namespace + registry pull secret
```sh
kubectl --kubeconfig mapster.yml create namespace mapster --dry-run=client -o yaml | kubectl --kubeconfig mapster.yml apply -f -

kubectl --kubeconfig mapster.yml -n mapster create secret docker-registry regcred \
  --docker-server="$REGISTRY_HOST" \
  --docker-username='robot$YOUR_ROBOT_USER' \
  --docker-password='YOUR_ROBOT_PASSWORD_OR_TOKEN'
```

### G2) Create object storage secret (used by initContainer)
```sh
kubectl --kubeconfig mapster.yml -n mapster create secret generic object-storage-secret \
  --from-literal=AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
  --from-literal=AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
  --from-literal=AWS_DEFAULT_REGION="$AWS_DEFAULT_REGION" \
  --from-literal=S3_ENDPOINT="$S3_ENDPOINT" \
  --from-literal=S3_BUCKET="$S3_BUCKET" \
  --from-literal=S3_KEY="$S3_KEY" \
  --dry-run=client -o yaml | kubectl --kubeconfig mapster.yml apply -f -
```

### G3) Ensure overlay is set to your domain + image tags
Edit [k8s/overlays/stackit/kustomization.yaml](k8s/overlays/stackit/kustomization.yaml):
- host should be `mapster.info`
- image tags should match what you pushed (`0.1.1`, `0.1.0`, etc.)

### G4) Apply the overlay
```sh
kubectl --kubeconfig mapster.yml apply -k k8s/overlays/stackit
```

### G5) Wait for core pods
```sh
kubectl --kubeconfig mapster.yml -n mapster get pods -w
```

---

## H) Run the import job (loads PostGIS)

The overlay creates the Job; if you want to force a clean run:
```sh
kubectl --kubeconfig mapster.yml -n mapster delete job import-admin-areas --ignore-not-found
kubectl --kubeconfig mapster.yml apply -k k8s/overlays/stackit
```

Watch the important logs:
```sh
kubectl --kubeconfig mapster.yml -n mapster logs -f job/import-admin-areas -c download-gpkg
kubectl --kubeconfig mapster.yml -n mapster logs -f job/import-admin-areas -c import
```

Wait for completion:
```sh
kubectl --kubeconfig mapster.yml -n mapster wait --for=condition=complete job/import-admin-areas --timeout=1800s
```

---

## I) Validate end-to-end (public)

```sh
curl -s -o /dev/null -w "%{http_code}\n" "http://mapster.info/"
curl -s -o /dev/null -w "%{http_code}\n" "http://mapster.info/api/actuator/health/readiness"
curl -s -o /dev/null -w "%{http_code}\n" "http://mapster.info/api/tiles/6/33/20.mvt"
curl -s "http://mapster.info/api/overlays?minLon=13.38&minLat=52.51&maxLon=13.40&maxLat=52.52&zoom=10" | head -c 200 && echo
```

---

If you tell me how you created the STACKIT resources (Portal vs Terraform vs STACKIT CLI), I can extend this runbook with the exact “create cluster / create registry / create bucket / create robot” commands for that toolchain too.
