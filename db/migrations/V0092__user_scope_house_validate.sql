-- V0092 validates the V0091 node_type CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.user_scope VALIDATE CONSTRAINT user_scope_node_type_check;
