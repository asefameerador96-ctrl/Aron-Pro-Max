# Seed data

- `sku_catalog.csv` — the real Aron product catalog: 4 categories, 6 segments, 17 brands, 30 variants, 42 SKUs, with the five price types (outlet, C&C, distributor, reporting, NTO) and pack size/type. Load into `product_*`, `sku`, `sku_price`. This lets the build start with the true product tree before the Apsis dump arrives.

Geography, outlets, routes, users, targets, dues and loyalty come from the Apsis dump (see docs/11).
