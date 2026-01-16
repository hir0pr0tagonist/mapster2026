# Mapster Cloud Solution Architecture

## Overview
This document describes the architecture of the Mapster Cloud solution, which provides a scalable, cloud-native platform for serving interactive maps with dynamic overlays and geospatial data aggregation.

## Components

### 1. postgis (Database Service)
- **Type:** Containerized PostgreSQL with PostGIS extension
- **Purpose:** Stores all geospatial data (boundaries, polygons, etc.) and supports spatial queries.
- **Persistence:** Uses a Docker volume for data durability.
- **Port:** 5432 (internal)

### 2. api (Spring Boot Backend Service)
- **Type:** Java 17+, Spring Boot, REST API
- **Purpose:**
  - Exposes REST endpoints (e.g., `/api/overlays`) for querying geospatial data from PostGIS.
  - Performs data aggregation, filtering, and business logic.
  - Returns GeoJSON overlays for frontend consumption.
- **Port:** 8080 (internal/external)
- **Build:** Multi-stage Dockerfile (Maven build, Temurin JDK runtime)

### 3. web (Frontend Service)
- **Type:** Nginx static file server
- **Purpose:**
  - Serves static frontend assets (HTML, JS, CSS) using Nginx.
  - Renders the OSM basemap and overlays using MapLibre GL JS.
  - Fetches overlay data from the api service via REST calls.
- **Port:** 8081 (external)

### 4. import (Data Import Service)
- **Type:** Custom GDAL/ogr2ogr container
- **Purpose:**
  - Automates loading of large GeoPackage or other geospatial data into PostGIS at startup.
  - Waits for PostGIS readiness before import.

## Networking
- All services are on the default Docker Compose network, allowing inter-service communication by container name (e.g., `api`, `postgis`).
- Frontend (web) calls backend (api) via HTTP (e.g., `http://api:8080/api/overlays`).

## Data Flow
1. **User loads map in browser (localhost:8081).**
2. **Frontend JS requests overlays from backend API (localhost:8080/api/overlays?bbox=...).**
3. **API queries PostGIS for polygons within the bounding box, returns GeoJSON.**
4. **Frontend renders overlays on the OSM basemap.**

## Scaling & Best Practices
- **Stateless services:** api and web containers are stateless and can be scaled horizontally.
- **Separation of concerns:** Each service has a single responsibility (DB, API, frontend, import).
- **Cloud-native:** All components are containerized and orchestrated via Docker Compose.
- **Extensible:** Easy to add new REST endpoints, frontend features, or data sources.

## File/Directory Structure (Key Parts)
- `docker-compose.yml` — Orchestrates all services.
- `api/` — Spring Boot backend (Dockerfile, pom.xml, source code).
- `web/` — Frontend (index.html, JS, Nginx Dockerfile).
- `initdb/` — PostGIS schema and test data.
- `import/` — Data import scripts and Dockerfile.

## Future Extensions
- Add authentication/authorization to API.
- Add caching (e.g., Redis) for heavy queries.
- Integrate with cloud storage for large datasets.
- Add monitoring/logging services.

---
This manifest is a living document. Update as the architecture evolves.
