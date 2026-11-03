-- V0132 validates the V0131 code_list key CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.code_list VALIDATE CONSTRAINT code_list_list_key_check;
