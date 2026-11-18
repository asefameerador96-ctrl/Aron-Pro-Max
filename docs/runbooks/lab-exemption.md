# Lab exemption (ADB device walks), D-OWNER-MDM-02 iii

The apps refuse to run while Developer options, USB debugging or wireless debugging is on. A lab exemption lets ONE enrolled phone run with them on for at most 14 days (the phone also caps it at 7), so the device walks stay possible.

How it is protected: the server keeps a row (`app.device_lab_exemption`) and hands the phone a grant it SIGNS (ECDSA P-256) over the device uuid, the API host it was signed for (the audience) and the grant and expiry times. The phone verifies the signature offline with a PUBLIC key built into the app and re-verifies it on every read, so a file edited on the phone cannot create or extend an exemption. With no key in the build, or no signing key on the server, no exemption is ever honoured. Honest limit: a rep who roots the phone and patches the app defeats this like every other on-phone check.

Set-up (owner, once): `tools/make-lab-grant-key.sh <file>` prints the public key (put it in `android/core-system/src/main/res/values/lab_grant.xml`, a normal commit) and writes the private key to the file; store its content in the server secret `ARON_LAB_GRANT_KEY` and delete the file. Never commit or paste the private key.

## How an exemption reaches a phone (A-INT3, 2026-11-18)

1. Portal: Admin > Devices > the phone > "Developer options exemption (lab)": days (1 to 14) and a note, then "Grant exemption" (`PUT /v1/admin/devices/{id}/lab-exemption`; Super Admin or an Admin with the `mdm` write grant; audited; the token is never shown or logged). "Withdraw exemption" is `DELETE` on the same path. The card says "not set up on the server" until the signing secret exists.
2. The server signs the grant ONCE when it is granted and stores it with the row. The phone gets it in its next FULL day bundle as the device-scoped config value `cfg.integrity.lab_exemption` (a string). Phones that refresh by delta (AMO, TSO) are told by the delta to fetch the full bundle when a grant or a withdrawal happened after their snapshot, so it arrives within their normal refresh.
3. The phone verifies it offline (signature in DER against the public key in the app, this device's uuid, the audience = the host of the API this build talks to, expiry), stores it, and re-verifies it on every read. It takes a grant ONLY from a fresh, authenticated day-bundle response: never from a stored or cached copy, never from a prefetch, and only when the response's own `server_time` is within 15 minutes of the phone's clock (a replayed response or a clock too wrong to trust is refused; set the phone's time automatically). The expiry is judged against the server's time, so setting the phone's clock back cannot revive an expired grant; the phone also keeps the highest grant time (`iat`) and the highest clock reading it has ever seen, and refuses an older grant or a clock that is behind. A full bundle WITHOUT the value withdraws it. A token that does not verify changes nothing (it never widens a grant, and it never narrows a standing one).
3a. OFFLINE WINDOW AFTER A WITHDRAWAL: the phone learns of a withdrawal only from a full day bundle. A lab phone that stays offline keeps the exemption until the earlier of the grant's expiry and 7 days after it last accepted the grant (the phone caps a grant locally at 7 days). The audit row and the portal show the withdrawal at once; the phone follows at its next online day bundle.
4. ORDER OF STEPS: the gate screen blocks the app while Developer options is on, and a phone that cannot open the app cannot load a bundle. So: grant while the phone is still clean, open the app so it loads the day bundle, THEN turn Developer options on. If Developer options is already on, turn it off, open the app, then on again. (The gate screen's "Sync now" button uploads queued records only; it is not a bundle download, and the gate screen makes no network call of its own.)
5. What it lifts: ONLY the Developer options / USB debugging / wireless debugging gate. A selected mock-location app, a test provider and the mock flag are still refused. Honest limit: a rep who roots the phone and patches the app defeats this like every other on-phone check.

## What the owner or the deploy must supply

ONE KEY PAIR PER ENVIRONMENT. The test account and the final account each get their own pair: the private half is the secret of that environment's server, the public half is the resource of the builds for that environment. As a second lock the grant names the API host it was signed for (`a` = the host of `ARON_PUBLIC_API_URL`, which must therefore be the host the phones of that environment call, `BuildConfig.API_BASE_URL`), and the phone refuses a grant for another host: a grant signed by the test server never verifies on a final-account build even if a key pair were shared by mistake.

| What | Where | Notes |
|---|---|---|
| Signing key pair | the laptop operator session runs the commands below, once per environment | the private key goes to a mode-600 file and is never printed; only the PUBLIC key is printed |
| Private key (PKCS#8 base64) | GitHub environment secret `ARON_LAB_GRANT_KEY` (set by the OWNER, in the GitHub environment of that account), mapped to the API container as the secret `ARON_LAB_GRANT_KEY` | read through `Settings.labGrantKey` (also `ARON_LAB_GRANT_KEY_FILE`); never in the repository, a log, a fixture or a message. NOT yet in `infra/apps.bicep`: the infra lane adds the `secretRef` once the Key Vault secret exists (a reference to a missing secret would break the deploy). Unset, unreadable or not an EC P-256 key: the portal action answers 409 and records nothing |
| Public key | `android/core-system/src/main/res/values/lab_grant.xml`, string `aron_lab_grant_public_key` | a normal commit, not a secret. EMPTY in the repository: with the empty placeholder every build fails closed (no exemption is ever honoured). A key that is not EC P-256 is refused at load (fails closed) |

Exact commands for the laptop operator session (per environment; replace `<env>`):

```bash
cd <repo>
umask 077
tools/make-lab-grant-key.sh /tmp/lab-grant-<env>.key      # prints ONLY the public key (one line of base64); refuses to overwrite
# 1. edit android/core-system/src/main/res/values/lab_grant.xml: put the printed public key between the <string name="aron_lab_grant_public_key"> tags, commit it normally.
# 2. hand the private key to the owner WITHOUT printing it into any chat or log, for example:
#      gh secret set ARON_LAB_GRANT_KEY --env <github-environment> < /tmp/lab-grant-<env>.key
#    (the owner runs it, or the operator session does with the owner's GitHub login), then:
shred -u /tmp/lab-grant-<env>.key
```
After the secret is set the infra lane maps it to the API container; until then the portal card says "not set up on the server".

Unsetting the server key does not withdraw grants already issued (the phone holds them until expiry or the next bundle without them): withdraw with `DELETE`. Rotating the key pair: put the new public key in a release, set the new secret; old grants stop verifying on phones that have the new build.

## Status

Built and tested (A-INT2, A-INT3): table `app.device_lab_exemption` (V0202, renumbered V0206 at landing) and its stored signed token column (V0203, renumbered V0207), `LabGrantSigner` and `LabExemptionService`, the admin operations, the bundle value, the delta rule, the Android verifier, `VerifiedLabExemption`, the gate hook and the bundle hook, and the portal card. Not done on a device: see the device-walk list in `docs/status/android-integrity.md`.
