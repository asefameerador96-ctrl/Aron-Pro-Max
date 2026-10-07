# Request to web lane: two small follow-ups from the supply-chain gates (AUD-SEC-05)

From: infra lane, 2026-10-07.

## What infra changed in web/ (row AUD-SEC-05 assigns these to infra)

- `web/.npmrc` sets `ignore-scripts=true`: no dependency runs an install script. Tested: `npm ci`, typecheck, vitest
  and `next build` all pass with it (esbuild, unrs-resolver and the Next SWC binary come from optional platform
  packages). A new package with an install script fails CI until it is reviewed into `tools/ci/npm-install-scripts.txt`.
- Side effect to know: with `ignore-scripts`, `npm run build` no longer runs the `prebuild` script (lint). CI still
  runs `npm run lint` as its own step, so nothing is lost in CI; run `npm run lint` yourself before pushing.
- CI now runs `npm audit --omit=dev --audit-level=high` after install, OSV-Scanner on `web/package-lock.json`, Trivy
  on the web image and Semgrep (TypeScript, React, Next.js packs). Semgrep fails only on findings NEW in a push.

## Ask 1: GCM tag length in `web/src/lib/auth/seal.ts` (Semgrep `gcm-no-tag-length`, existing, not blocking)

`createDecipheriv("aes-256-gcm", ...)` is called without `authTagLength`. Today the code slices exactly 16 bytes, so it
is not exploitable as written, but the explicit option makes Node reject a short tag whatever the caller does:

```ts
const decipher = createDecipheriv("aes-256-gcm", key(secret, purpose), raw.subarray(0, 12), { authTagLength: 16 });
```

(and the same option on `createCipheriv` for symmetry). Your existing seal/open tests cover it.

## Ask 2: stale comment

`web/scripts/ci.sh` says `build` "runs lint first via prebuild"; with `ignore-scripts` it no longer does (see above).
Please update the comment (or call `npm run lint` from `step_build`).

`@redocly/cli` (used by the contract job in ci.yml, not by web/) is now pinned there to an exact version, run without
install scripts.
