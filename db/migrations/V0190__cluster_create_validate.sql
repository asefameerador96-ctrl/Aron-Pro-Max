-- V0184 validates what V0183 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies the checks: no cluster_create request or created_cluster_id exists yet,
-- and the other-type rows keep the old rule (outlet_id IS NULL only for `new`).
SET lock_timeout = '5s';
ALTER TABLE app.outlet_change_request VALIDATE CONSTRAINT outlet_change_request_created_cluster_fk;
ALTER TABLE app.outlet_change_request VALIDATE CONSTRAINT outlet_change_request_request_type_check;
ALTER TABLE app.outlet_change_request VALIDATE CONSTRAINT outlet_change_request_outlet_rule;
ALTER TABLE app.sync_rejected VALIDATE CONSTRAINT sync_rejected_code_check;
