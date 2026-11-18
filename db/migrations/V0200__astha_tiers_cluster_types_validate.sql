-- V0200 validates what V0199 added NOT VALID (separate transaction, squawk rule). The rebuilt list_key check only adds one key.
SET lock_timeout = '5s';
ALTER TABLE app.code_list VALIDATE CONSTRAINT code_list_list_key_check;
ALTER TABLE app.outlet VALIDATE CONSTRAINT outlet_source_contact_number_len;
ALTER TABLE app.outlet VALIDATE CONSTRAINT outlet_source_sub_channel_len;
ALTER TABLE app.outlet_location_history VALIDATE CONSTRAINT outlet_location_history_basis_check;
