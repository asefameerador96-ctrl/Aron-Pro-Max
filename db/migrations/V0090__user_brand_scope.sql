-- V0090 brands of BRAND users (owner directive 2026-10-22, docs/34 s1, DECISIONS D-ROLES-02). backend-authz's request,
-- migration C, as written (docs/requests/db-backend-authz-roles.md): one row per BRAND user and brand node; carried in
-- the web token as the `brands` claim; product-based reports and dashboard cards are filtered to them on the server.
-- A trigger refuses a product node that is not a brand. Revoked by DELETE.

SET lock_timeout = '5s';

CREATE TABLE app.user_brand_scope (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  brand_id    bigint NOT NULL REFERENCES app.product_node(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  granted_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (user_id, brand_id)
);
CREATE INDEX user_brand_scope_brand ON app.user_brand_scope (brand_id);
CREATE INDEX user_brand_scope_granted_by ON app.user_brand_scope (granted_by);

CREATE FUNCTION app.user_brand_scope_is_brand() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.product_node WHERE id = NEW.brand_id AND level = 'brand') THEN
    RAISE EXCEPTION 'product_node % is not a brand', NEW.brand_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER user_brand_scope_brand_level BEFORE INSERT OR UPDATE ON app.user_brand_scope
  FOR EACH ROW EXECUTE FUNCTION app.user_brand_scope_is_brand();

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw', 'app', 'user_brand_scope', 'DELETE', 'V0090: a revoked brand scope is deleted (history in audit_log)'),
  ('auth_rw', 'app', 'user_brand_scope', 'SELECT', 'V0090: token claim brands');

COMMENT ON TABLE app.user_brand_scope IS 'Brands a BRAND user may see (D-ROLES-02, docs/34 s1); carried in the web token as the brands claim; product-based reports and dashboard cards are filtered to them on the server.
owner: backend:authz | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.user_brand_scope.user_id IS 'BRAND user (app.app_user).';
COMMENT ON COLUMN app.user_brand_scope.brand_id IS 'Brand node of the product tree (app.product_node, level brand).';
COMMENT ON COLUMN app.user_brand_scope.granted_at IS 'UTC instant the row was inserted.';
COMMENT ON COLUMN app.user_brand_scope.granted_by IS 'SUPERADMIN who granted the brand.';

SELECT app.apply_db_role_grants();
