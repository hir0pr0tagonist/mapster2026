# ERD diagrams

This folder contains ERD-like diagrams generated from the local Postgres container.

- `mapster.*` describes the **application database** used by Mapster.
- `postgres.*` describes the default `postgres` database in the same server. In this project it typically has **no non-system tables**, but it exists by default in Postgres.

## Notes

- PK/FK edges shown as solid relationships are backed by real Postgres constraints.
- Some relationships are **logical** (join keys used by queries / application logic) because they are not declared as FKs (e.g. `area_key`, `ancestor_key`, `assigned_area_key`).
- `public.admin_areas` has many descriptive columns; the diagram lists only the most relevant ones.

## Regenerate (local)

If you have Docker running, you can re-render the PNG/PDF from the Mermaid sources:

```sh
cd mapster-cloud

docker run --rm \
  -u "$(id -u):$(id -g)" \
  -v "$PWD/docs/erd:/data" \
  ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:latest \
  -i /data/mapster.mmd -o /data/mapster.png

docker run --rm \
  -u "$(id -u):$(id -g)" \
  -v "$PWD/docs/erd:/data" \
  ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:latest \
  -i /data/mapster.mmd -o /data/mapster.pdf
```
