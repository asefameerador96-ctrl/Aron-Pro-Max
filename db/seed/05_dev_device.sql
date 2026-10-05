-- An enrolled, active development phone bound to the seeded SR with bind_ordinal 0, so a dev login answers ok rather
-- than bind_required (docs/24 s8.1). Its key is a placeholder: it cannot sign device proofs, so it serves only the dev
-- database where cfg.device.require_enrolled is false. A real test phone enrols through POST /v1/devices/enrol.
-- Fixed device_uuid 00000000-0000-4000-8000-000000000001 so tests can send it as X-Device-Id.

INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level,
                        public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id,
                        device_info, app_version)
  SELECT '00000000-0000-4000-8000-000000000001', 'sr', 'com.aktcl.aron.sr', 'active', false, 'dev', 'low',
         '{"kty": "EC", "crv": "P-256", "x": "seed-placeholder", "y": "seed-placeholder"}', 'seed-dev-device-0001',
         '{"seed": true}', decode(repeat('00', 32), 'hex'), z.id,
         '{"manufacturer": "Samsung", "model": "SM-A065F", "os_api_level": 35, "os_version": "15", "abi": "arm64-v8a", "ram_mb": 4096}',
         '1.0.0+1'
    FROM app.zone z
   WHERE z.code = 'Z-MIR' AND NOT EXISTS (SELECT 1 FROM app.device WHERE device_uuid = '00000000-0000-4000-8000-000000000001');

INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via)
  SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u
   WHERE d.device_uuid = '00000000-0000-4000-8000-000000000001' AND u.username = 'sr1001'
     AND NOT EXISTS (SELECT 1 FROM app.device_binding b WHERE b.user_id = u.id AND b.status = 'active');
