# Mapster Cloud

Mapster Cloud is a containerized mapping stack:

- **PostGIS** stores administrative boundaries (`admin_areas`).
- **Spring Boot API** serves boundaries as:
  - **Vector tiles (MVT)**: `GET /api/tiles/{z}/{x}/{y}.mvt` (fast, incremental)
  - **GeoJSON overlays**: `GET /api/overlays` (debug/inspection)
- **MapLibre** frontend renders OSM raster base + boundary overlays.

This repo supports both local Docker Compose and local Kubernetes (Minikube).

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

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for a detailed breakdown of components, data flow, and design choices.
