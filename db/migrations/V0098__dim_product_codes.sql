-- V0098 product codes on the product dimension (owner directive 2026-10-22, docs/34 s2; backend-reports' request
-- docs/requests/db-backend-reports-dashboard-summaries.md A4): the dashboard's Segment, Brand and Variant filters and
-- answers use codes. dw.dim_product and its SCD2 history dw.dim_product_version gain segment_code, brand_code and
-- variant_code, copied by the projector from app.product_node.code (nullable there, so nullable here).

SET lock_timeout = '5s';

ALTER TABLE dw.dim_product ADD COLUMN segment_code text, ADD COLUMN brand_code text, ADD COLUMN variant_code text;
ALTER TABLE dw.dim_product_version ADD COLUMN segment_code text, ADD COLUMN brand_code text, ADD COLUMN variant_code text;

COMMENT ON COLUMN dw.dim_product.segment_code IS 'Code of the SKU''s segment node (app.product_node.code); null when the node has none.';
COMMENT ON COLUMN dw.dim_product.brand_code IS 'Code of the SKU''s brand node; null when the node has none.';
COMMENT ON COLUMN dw.dim_product.variant_code IS 'Code of the SKU''s variant node; null when the node has none.';
COMMENT ON COLUMN dw.dim_product_version.segment_code IS 'Segment code in this version.';
COMMENT ON COLUMN dw.dim_product_version.brand_code IS 'Brand code in this version.';
COMMENT ON COLUMN dw.dim_product_version.variant_code IS 'Variant code in this version.';
