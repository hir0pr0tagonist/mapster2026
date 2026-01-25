# Mapster Cloud

Mapster Cloud is a containerized mapping stack:

- **PostGIS** stores administrative boundaries (`admin_areas`).
- **Spring Boot API** serves boundaries as:
  - **Vector tiles (MVT)**: `GET /api/tiles/{z}/{x}/{y}.mvt` (fast, incremental)
  - **GeoJSON overlays**: `GET /api/overlays` (debug/inspection)
- **Metric shading** is served as values-only JSON:
  - `GET /api/area-metrics-values` (metrics for bbox + depth, no geometry)
  - The browser joins metrics onto vector-tile features by `area_key` using MapLibre `feature-state`.
- **MapLibre** frontend renders OSM raster base + boundary overlays.

This repo supports:

- local Docker Compose
- local Kubernetes (Minikube)
- managed Kubernetes (STACKIT) via a Kustomize overlay

## Local (Docker Compose)

Build and run:

```sh
docker compose up -d --build
```

Then open:

- UI: `http://localhost:8081/`
- API: `http://localhost:8080/`

Importing data is handled by the `import` service (see `docker-compose.yml`).

## Local Kubernetes (Minikube)

Kubernetes manifests live in `k8s/`.

Quick start: see [k8s/README.md](k8s/README.md).

Deploying to STACKIT (managed Kubernetes): see [DEPLOYMENT_STACKIT.md](DEPLOYMENT_STACKIT.md) and the overlay README at [k8s/overlays/stackit/README.md](k8s/overlays/stackit/README.md).

## Public URL contract

When deployed behind an Ingress on a real domain, the intended contract is:

- UI at `https://YOUR_DOMAIN/`
- API under `https://YOUR_DOMAIN/api/...`

In the Spring Boot API this is implemented via `server.servlet.context-path=/api`, so controllers map routes like `/tiles/...` and `/overlays`, and they are served externally as `/api/tiles/...` and `/api/overlays`.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for a detailed breakdown of components, data flow, and design choices.
