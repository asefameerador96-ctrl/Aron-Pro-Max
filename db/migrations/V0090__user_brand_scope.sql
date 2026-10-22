-- V0090 brand scope of BRAND users (owner directive 2026-10-22, docs/34 s1, DECISIONS D-ROLES-02). One row per BRAND
-- user and brand node (app.product_node level 'brand'); the token claim `brands` is built from it (backend-authz) and
-- every product-based card, report and export of a BRAND user is filtered to those brands on the server. Only BRAND users
-- hold rows and only brand nodes can be named: enforced on insert and update by trigger. Revoked by delete; the API
-- writes every change to the audit chain. A BRAND user with no row sees no product data (default-deny).

SET lock_timeout = '5s';

CREATE TABLE app.user_brand_scope (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  brand_id    bigint NOT NULL REFERENCES app.product_node(id),
  granted_by  bigint NOT NULL REFERENCES app.app_user(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, brand_id)
);
CREATE INDEX user_brand_scope_brand ON app.user_brand_scope (brand_id);
CREATE INDEX user_brand_scope_granted_by ON app.user_brand_scope (granted_by);

CREATE FUNCTION app.user_brand_scope_check() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.app_user WHERE id = NEW.user_id AND role = 'BRAND') THEN
    RAISE EXCEPTION 'user %: brand scope is for BRAND users only', NEW.user_id USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.product_node WHERE id = NEW.brand_id AND level = 'brand') THEN
    RAISE EXCEPTION 'product node % is not a brand', NEW.brand_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER user_brand_scope_check BEFORE INSERT OR UPDATE ON app.user_brand_scope
  FOR EACH ROW EXECUTE FUNCTION app.user_brand_scope_check();

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw', 'app', 'user_brand_scope', 'DELETE', 'V0090: a revoked brand scope is deleted (history in audit_log)'),
  ('auth_rw', 'app', 'user_brand_scope', 'SELECT', 'V0090: token claim brands');

COMMENT ON TABLE app.user_brand_scope IS 'Brands a BRAND user may see; product data of other brands is filtered out on the server (docs/34 s1).
owner: backend:authz | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.user_brand_scope.user_id IS 'BRAND user (app.app_user).';
COMMENT ON COLUMN app.user_brand_scope.brand_id IS 'Brand node of the product tree (app.product_node, level brand).';
COMMENT ON COLUMN app.user_brand_scope.granted_by IS 'SUPERADMIN who set the scope, from the token.';
COMMENT ON COLUMN app.user_brand_scope.granted_at IS 'UTC instant the row was inserted.';

SELECT app.apply_db_role_grants();
