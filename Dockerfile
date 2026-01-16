# Custom image for data import with GDAL and ogr2ogr
FROM ghcr.io/osgeo/gdal:ubuntu-small-latest

# Install PostgreSQL client (for psql)
RUN apt-get update && apt-get install -y postgresql-client && rm -rf /var/lib/apt/lists/*

WORKDIR /data

# Copy the upload script into the image and set executable bit
COPY import/upload_geopackage.sh /data/upload_geopackage.sh
RUN chmod +x /data/upload_geopackage.sh

# Entrypoint for running import scripts
ENTRYPOINT ["/bin/bash"]
