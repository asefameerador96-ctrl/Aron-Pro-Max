-- Dev only (lead 2026-10-07, device day): one PUBLISHED app release per flavour, so an admin can mint enrolment
-- tokens on dev (POST /v1/admin/enrolment-tokens needs a published release of the flavour; POST /v1/admin/releases is
-- not built yet) and enrolment's signing-certificate check matches the CI release-signed APKs.
-- signing_cert_sha256: the CI release key's certificate digest (public; printed by the ci.yml signing step, run 516:
-- "Signer #1 certificate SHA-256 digest: 468d9b4e...a4fb", the same for sr, amo and tso).
-- sha256 and version: the run-516 APKs (aron-<flavour>-0.1.516-dev.apk). size_bytes is a placeholder (the CI log does
-- not print sizes). download_url is the CI run page (an artifact zip, not a direct APK link: device-owner QR
-- provisioning from it is not possible; D-04 installs with adb).
-- Publish is maker-checker: created by superadmin1001, published by admin1001. Idempotent (flavour, version_code, abi).
-- Loaded by the dev seed image after db/seed (infra/deploy.sh); never in stage or prod.
INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url,
                             signing_cert_sha256, status, rollout_pct, notes_en, created_by, published_at, published_by)
SELECT r.flavour, '0.1.516', 516, 'universal', decode(r.apk_sha256, 'hex'), 20000000,
       'https://github.com/asefameerador96-ctrl/Aron-Pro-Max/actions/runs/37652960567',
       decode('468d9b4eccb776e9f40e20e20aaf0f14627e2316e901d53af3fc9aa4e393a4fb', 'hex'), 'published', 100,
       'Dev seed release (CI run 516) so enrolment tokens can be minted on dev; size is a placeholder.',
       maker.id, TIMESTAMPTZ '2026-10-07 23:00:00+00', checker.id
  FROM (VALUES ('sr',  '95bee1899e5e0c94b2a440e4c8de658ad33380f5175ea50e6559963623e180ea'),
               ('amo', '4dcc453fbfd300fc6789a837e7a5933dd435dc5a20040782b2acd51dd6255031'),
               ('tso', '7a70c3ce4785b6c6f04ea5d1256e657e7b497758fda094cc9ace688b8f406b0d')) AS r(flavour, apk_sha256)
  JOIN app.app_user maker ON maker.username = 'superadmin1001'
  JOIN app.app_user checker ON checker.username = 'admin1001'
ON CONFLICT (flavour, version_code, abi) DO NOTHING;

-- Self-check (infra 2026-10-08): the INSERT above inserts nothing, silently, when a maker or checker account is
-- missing. Fail the dev seed (deploy summary "Dev seed Failed") unless each flavour has a published release with the
-- CI certificate, so token minting on dev is proven by every deploy, not assumed.
DO $$
DECLARE missing text;
BEGIN
  SELECT string_agg(f, ', ') INTO missing
    FROM unnest(ARRAY['sr', 'amo', 'tso']) AS f
   WHERE NOT EXISTS (SELECT 1 FROM app.app_release r
                      WHERE r.flavour = f AND r.status = 'published'
                        AND r.signing_cert_sha256 = decode('468d9b4eccb776e9f40e20e20aaf0f14627e2316e901d53af3fc9aa4e393a4fb', 'hex'));
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'dev seed: no published app_release with the CI certificate for: %', missing;
  END IF;
END $$;
