# qa → infra: run the APK security check on the release APKs in CI (N-061), 2026-10-17

qa built `tools/ci/apk-security-check.py` (MASVS-style static checks) and its offline self-test
`tools/ci/test_apk_security.py` (6 cases). The checks:
- **critical:** debuggable; debug certificate; private key, storage account key, SAS signature, AWS key, JWT or a
  password literal inside any APK entry;
- **high:** allowBackup not false; cleartext traffic; v1-only signature; an exported provider without a permission;
- **medium:** other exported components without a permission;
- **info:** Google API keys, min and target SDK, network security config.

Verified on a real local `app-sr` release build (2026-10-17, unsigned because the signing key lives in CI). Manifest
and secrets are clean: not debuggable, backup off, no cleartext, no unprotected exported component, no secret. The
only finding is the missing signature. A debug-signed copy fails the gate, as it should.

**Ask (infra owns ci.yml):** in the job that builds the release APKs (SR, and AMO and TSO when their release builds
exist), after signing:

```yaml
      - name: APK security check (N-061)
        run: |
          python3 tools/ci/test_apk_security.py
          python3 tools/ci/apk-security-check.py --gate <path to each signed release apk>
```

It needs `ANDROID_HOME` with build-tools (aapt2, apksigner), which that job already has. It writes its findings to the
step summary.
