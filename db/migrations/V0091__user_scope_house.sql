-- V0091 house level in user scope (owner directive 2026-10-22, docs/34 s1; backend-authz's request, migration D):
-- app.user_scope.node_type gains 'house' (between territory and zone). The CHECK is replaced NOT VALID and validated in
-- V0092.

SET lock_timeout = '5s';

ALTER TABLE app.user_scope DROP CONSTRAINT user_scope_node_type_check;
ALTER TABLE app.user_scope ADD CONSTRAINT user_scope_node_type_check
  CHECK (node_type IN ('national','wing','division','territory','house','zone')) NOT VALID;

COMMENT ON COLUMN app.user_scope.node_type IS 'Type of the scope node: national, wing, division, territory, house (V0091) or zone.';
