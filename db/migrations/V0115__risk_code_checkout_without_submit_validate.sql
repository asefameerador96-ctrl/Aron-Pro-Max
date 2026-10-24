-- V0115 validates the V0114 risk_signal code CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.risk_signal VALIDATE CONSTRAINT risk_signal_code_check;
