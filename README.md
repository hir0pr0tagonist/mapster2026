# Mapster TileServer GL Setup

## 1. Clean Start
- All previous solution files have been removed.
- This project is now ready for a fresh TileServer GL deployment.

## 2. How to Run TileServer GL
1. Place your `.mbtiles` files in the `mbtiles/` directory.
2. Run the shell script:
	```sh
	./run-tileserver.sh
	```
3. Access the TileServer GL web UI at [http://localhost:8080](http://localhost:8080).

## 3. Next Steps
- Generate or download a sample `.mbtiles` file and place it in `mbtiles/`.
- Verify that tiles are served and visible in the web UI.

## 4. Useful Links
- [TileServer GL Docker Hub](https://hub.docker.com/r/klokantech/tileserver-gl)
- [TileServer GL Documentation](https://tileserver.readthedocs.io/en/latest/)
- [Tippecanoe (MBTiles generator)](https://github.com/mapbox/tippecanoe)

---
This setup is ready for overlay tile hosting and testing.
# Mapster Cloud

This folder will contain the cloud-optimized version of Mapster, using Java (Spring Boot), MongoDB, Docker, and Kubernetes-ready deployment files.

## Structure
- backend/: Spring Boot REST API
- db/: MongoDB setup and migration scripts
- deployment/: Docker and Kubernetes manifests

Further scaffolding will be added in the next steps.