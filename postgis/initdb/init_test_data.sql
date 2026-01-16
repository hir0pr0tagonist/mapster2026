CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE admin_areas (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    geom GEOMETRY(MultiPolygon, 4326)
);

-- Insert a sample multipolygon (rough bounding box around Berlin)
INSERT INTO admin_areas (name, geom) VALUES (
    'Berlin',
    ST_Multi(ST_GeomFromText('POLYGON((13.0884 52.3383, 13.7611 52.3383, 13.7611 52.6755, 13.0884 52.6755, 13.0884 52.3383))', 4326))
);
