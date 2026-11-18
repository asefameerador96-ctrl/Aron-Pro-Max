# Lab exemption (ADB device walks), D-OWNER-MDM-02 iii

The apps refuse to run while Developer options, USB debugging or wireless debugging is on. A lab exemption lets ONE enrolled phone run with them on for at most 14 days (the phone also caps it at 7), so the device walks stay possible.

How it is protected: the server keeps a row (`app.device_lab_exemption`) and hands the phone a grant it SIGNS (ECDSA P-256) over the device uuid and the expiry. The phone verifies the signature offline with a PUBLIC key built into the app and re-verifies it on every read, so a file edited on the phone cannot create or extend an exemption. With no key in the build, or no signing key on the server, no exemption is ever honoured. Honest limit: a rep who roots the phone and patches the app defeats this like every other on-phone check.

Set-up (owner, once): `tools/make-lab-grant-key.sh <file>` prints the public key (put it in `android/core-system/src/main/res/values/lab_grant.xml`, a normal commit) and writes the private key to the file; store its content in the server secret `ARON_LAB_GRANT_KEY` and delete the file. Never commit or paste the private key.

## How an exemption reaches a phone (A-INT3, 2026-11-18)

1. Portal: Admin > Devices > the phone > "Developer options exemption (lab)": days (1 to 14) and a note, then "Grant exemption" (`PUT /v1/admin/devices/{id}/lab-exemption`; Super Admin or an Admin with the `mdm` write grant; audited; the token is never shown or logged). "Withdraw exemption" is `DELETE` on the same path. The card says "not set up on the server" until the signing secret exists.
2. The server signs the grant ONCE when it is granted and stores it with the row. The phone gets it in its next FULL day bundle as the device-scoped config value `cfg.integrity.lab_exemption` (a string). Phones that refresh by delta (AMO, TSO) are told by the delta to fetch the full bundle when a grant or a withdrawal happened after their snapshot, so it arrives within their normal refresh.
3. The phone verifies it offline (signature against the public key in the app, this device's uuid, expiry), stores it, and re-verifies it on every read. A full bundle WITHOUT the value withdraws it. A token that does not verify changes nothing (it never widens a grant, and it never narrows a standing one). Offline, an older, failed or prefetch bundle neither grants nor withdraws.
4. ORDER OF STEPS: the gate screen blocks the app while Developer options is on, and a phone that cannot open the app cannot load a bundle. So: grant while the phone is still clean, open the app so it loads the day bundle, THEN turn Developer options on. If Developer options is already on, turn it off, open the app, then on again. (The gate screen's "Sync now" button uploads queued records only; it is not a bundle download.)
5. What it lifts: ONLY the Developer options / USB debugging / wireless debugging gate. A selected mock-location app, a test provider and the mock flag are still refused. Honest limit: a rep who roots the phone and patches the app defeats this like every other on-phone check.

## What the owner or the deploy must supply

| What | Where | Notes |
|---|---|---|
| Signing key pair | run `tools/make-lab-grant-key.sh <file>` once, on a trusted machine | prints the PUBLIC key, writes the private key to `<file>` with mode 600; delete the file after step 2 |
| Private key (PKCS#8 base64) | server secret `ARON_LAB_GRANT_KEY` (GitHub environment secret, mapped to the API container; Key Vault name by the infra lane's convention, e.g. `aron-lab-grant-key`) | read through `Settings.labGrantKey` (also `ARON_LAB_GRANT_KEY_FILE`); never in the repository, a log, a fixture or a message. NOT yet in `infra/apps.bicep`: the infra lane adds the `secretRef` once the Key Vault secret exists (a reference to a missing secret would break the deploy). Unset or unreadable: the portal action answers 409 and records nothing |
| Public key | `android/core-system/src/main/res/values/lab_grant.xml`, string `aron_lab_grant_public_key` | a normal commit, not a secret. EMPTY in the repository: with the empty placeholder every build fails closed (no exemption is ever honoured) |

Unsetting the server key does not withdraw grants already issued (the phone holds them until expiry or the next bundle without them): withdraw with `DELETE`. Rotating the key pair: put the new public key in a release, set the new secret; old grants stop verifying on phones that have the new build.

## Status

Built and tested (A-INT2, A-INT3): table `app.device_lab_exemption` with the stored signed token (V0202, renumbered V0206 at landing), `LabGrantSigner` and `LabExemptionService`, the admin operations, the bundle value, the delta rule, the Android verifier, `VerifiedLabExemption`, the gate hook and the bundle hook, and the portal card. Not done on a device: see the device-walk list in `docs/status/android-integrity.md`.
