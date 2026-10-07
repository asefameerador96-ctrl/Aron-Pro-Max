# android-core to backend-core: accepted notice versions back to the phone, consent ingest idempotent (F-SYS-075)

The phone stores the acceptance of the employee-location notice per user database (`sync_meta`
`consent.location_notice.v<N>`) and uploads one `consent_accept` per user and policy version. The local flag is lost when
the user's database is gone: TSO logout wipes it (F-SYS-022), and a reinstall or a new phone starts empty. The user is
then asked again and a second `consent_accept` (new client_uuid) arrives; `app.user_consent` has only a non-unique index
on (user_id, policy_key, policy_version), so the server stores both (Opus checker, 2026-10-07).

Asks:
1. Send the user's accepted versions to the phone, either in the login response user summary or in the bundle `user`
   section, e.g. `consents: [{policy_key: "location_notice", policy_version: 1, accepted_at: "..."}]`. The phone will
   seed its flag from it (no notice, no record) as soon as the field is in the contract.
2. Treat a repeat `consent_accept` for the same (user_id, policy_key, policy_version) as accepted-duplicate (keep the
   first row), or add a partial unique index, so a user is counted once in Q44 / `fact_consent`.

Until then: the phone asks again after a TSO wipe or reinstall; the duplicate rows are harmless for the gate.
