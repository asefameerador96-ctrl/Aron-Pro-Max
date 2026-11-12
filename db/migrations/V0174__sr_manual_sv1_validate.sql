-- V0174 validates what V0173 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies the checks:
-- the new visit columns are NULL, no request event has a flag kind, no media row has the new purpose, every code_list key was already allowed.
SET lock_timeout = '5s';
ALTER TABLE app.visit VALIDATE CONSTRAINT visit_zero_sale_reason_needs_zero_sale;
ALTER TABLE app.visit VALIDATE CONSTRAINT visit_call_photo_skip_check;
ALTER TABLE app.media VALIDATE CONSTRAINT media_purpose_check;
ALTER TABLE app.code_list VALIDATE CONSTRAINT code_list_list_key_check;
ALTER TABLE app.outlet_request_event VALIDATE CONSTRAINT outlet_request_event_event_check;
