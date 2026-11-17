# Lab exemption (ADB device walks), D-OWNER-MDM-02 iii

The apps refuse to run while Developer options, USB debugging or wireless debugging is on. A lab exemption lets ONE enrolled phone run with them on for at most 14 days (the phone also caps it at 7), so the device walks stay possible.

How it is protected: the server keeps a row (`app.device_lab_exemption`) and hands the phone a grant it SIGNS (ECDSA P-256) over the device uuid and the expiry. The phone verifies the signature offline with a PUBLIC key built into the app and re-verifies it on every read, so a file edited on the phone cannot create or extend an exemption. With no key in the build, or no signing key on the server, no exemption is ever honoured. Honest limit: a rep who roots the phone and patches the app defeats this like every other on-phone check.

Set-up (owner, once): `tools/make-lab-grant-key.sh <file>` prints the public key (put it in `android/core-system/src/main/res/values/lab_grant.xml`, a normal commit) and writes the private key to the file; store its content in the server secret `ARON_LAB_GRANT_KEY` and delete the file. Never commit or paste the private key.

Status (A-INT2, 2026-11-17): built and tested: the table (V0202), `LabGrantSigner` and `LabExemptionService` (grant, revoke, signed token for an unexpired row), the Android verifier and `VerifiedLabExemption`, and the gate hook. NOT yet built: the admin write `PUT/DELETE /v1/admin/devices/{id}/lab-exemption` (contract operation, `mdm` module grant, audit), the bundle field that carries the token to the phone, and the Android call `IntegrityGates.acceptLabGrant(context, token)` / `revokeLabGrant` from the bundle load. Until then no exemption can reach a phone.
