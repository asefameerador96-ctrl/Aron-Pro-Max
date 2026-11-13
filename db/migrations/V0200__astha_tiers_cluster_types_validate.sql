-- V0200 validates what V0199 added NOT VALID (separate transaction, squawk rule). The rebuilt list_key check only adds one key.
SET lock_timeout = '5s';
ALTER TABLE app.code_list VALIDATE CONSTRAINT code_list_list_key_check;
