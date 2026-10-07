-- V0025 contract v1.2 device integrity columns (docs/24 s14a R12, R13, R18 item 3). backend-core stores into them from
-- DeviceStatusReport and EnrolDeviceRequest. NULL is part of the contract: a phone older than v1.2 sends no root_hints
-- and no Play Integrity marker, which means "unknown" (never failure and never a clean phone); an empty array means the
-- phone checked and found nothing.

SET lock_timeout = '5s';

ALTER TABLE app.device ADD COLUMN root_hints text[];
ALTER TABLE app.device ADD COLUMN root_hints_at timestamptz;
ALTER TABLE app.device ADD COLUMN integrity_unavailable_reason text;
ALTER TABLE app.device ADD COLUMN integrity_unavailable_at timestamptz;

-- Every existing row has NULL in the new columns, so the constraints hold; NOT VALID skips the scan under lock and
-- V0026 validates them.
ALTER TABLE app.device ADD CONSTRAINT device_root_hints_check CHECK (
  cardinality(root_hints) <= 16
  AND root_hints <@ ARRAY['su_binary','test_keys','ro_debuggable','ro_secure_off','root_app','hook_framework',
                          'root_mount','clone_app_installed','secondary_user','foreign_data_dir']::text[]) NOT VALID;
ALTER TABLE app.device ADD CONSTRAINT device_integrity_unavailable_reason_check CHECK (
  integrity_unavailable_reason IN ('no_play_services','not_configured','offline','api_error','timeout')) NOT VALID;
ALTER TABLE app.device ADD CONSTRAINT device_integrity_unavailable_pair CHECK (
  (integrity_unavailable_reason IS NULL) = (integrity_unavailable_at IS NULL)) NOT VALID;
ALTER TABLE app.device ADD CONSTRAINT device_root_hints_pair CHECK (
  root_hints_at IS NOT NULL OR root_hints IS NULL) NOT VALID;

COMMENT ON COLUMN app.device.root_hints IS 'Root and tamper hints of the last status report (contract v1.2 DeviceStatusReport.root_hints): NULL = unknown (the phone is older than v1.2 or never reported), empty array = checked and clean. Hints are evidence only, never a reason to block a sale alone.';
COMMENT ON COLUMN app.device.root_hints_at IS 'UTC time of the status report root_hints came from; NULL when root_hints was never reported.';
COMMENT ON COLUMN app.device.integrity_unavailable_reason IS 'Reason of the last Play Integrity unavailable marker (contract v1.2 play_integrity_unavailable.reason); NULL = never reported. Kept when a later report has a verdict; compare integrity_unavailable_at with integrity_checked_at for the newer one.';
COMMENT ON COLUMN app.device.integrity_unavailable_at IS 'UTC time of the report that carried the last Play Integrity unavailable marker; NULL = never reported.';
