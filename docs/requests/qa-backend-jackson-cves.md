> **Resolved by lead-backend, 2026-10-17 (branch lane/lead-backend):** `jackson-bom` 2.22.3 is a catalog entry
> (`gradle/libs.versions.toml`) applied as `api(platform(...))` in `backend/platform`, so every backend module and the
> app image resolve jackson-core, jackson-databind, jackson-annotations and jackson-dataformat-xml to 2.22.3. Verified:
> the reproduce commands below give `OSV gate: 0 blocking finding(s)` (248 modules), and `:backend:app:test` is green.
> The CI step itself is infra's (`qa-infra-gradle-osv.md`): it is now unblocked, no `osv-allow.txt` lines are needed.

# qa → backend-core: Jackson 2.22.0 in the backend runtime has 5 High advisories (N-061), 2026-10-17

Found by the N-061 dependency scan of the Gradle runtime classpaths (`tools/ci/gradle-lockfile.py`, then
osv-scanner, then `tools/ci/osv-gate.py`). CI scans only `web/package-lock.json` today, so this was not caught there.

`:backend:app` runtimeClasspath resolves `com.fasterxml.jackson.core:jackson-core` and `jackson-databind` **2.22.0**.
They come in through `com.auth0:java-jwt:4.6.0` (under `ktor-server-auth-jwt`: the token parser) and
`com.google.cloud:google-cloud-storage`. OSV lists 10 advisories, all fixed in 2.22.3:
- High (CVSS 7.5): GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 (jackson-core); GHSA-cxp5-3px4-pw24, GHSA-q4xh-88c3-wmh7,
  GHSA-wv8q-qhhj-9h54 (jackson-databind).
- Medium: GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v, GHSA-gx83-3vf8-gh7j, GHSA-vvgp-rfg2-7rr6, GHSA-wjgm-6hv5-3cvf.

The SR, AMO and TSO release runtime classpaths are clean (no advisory).

**Urgency:** java-jwt parses the token's JSON header and payload with Jackson **before** it checks the signature, so the
jackson-core denial-of-service advisories can be reached by anyone who can send a bearer token, without logging in.
The pin goes in `:backend:app`, which builds the one runnable image (api and worker roles). Any other module that
ships its own runtime would need the same pin; none does today.

**Failing check (reproduce):**
```sh
./gradlew -q :backend:app:dependencies --configuration runtimeClasspath > /tmp/backend.txt
python3 tools/ci/gradle-lockfile.py /tmp/osv/gradle.lockfile /tmp/backend.txt
osv-scanner scan source --lockfile /tmp/osv/gradle.lockfile --format json > /tmp/osv.json
python3 tools/ci/osv-gate.py /tmp/osv.json tools/ci/osv-allow.txt     # exit 1: 5 blocking findings
```

**Proposed fix (tried locally by qa and reverted; it resolves every Jackson module to 2.22.3):** in
`backend/app/build.gradle.kts` `dependencies { }`, or better as a catalog entry used by every backend module:
```kotlin
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
```
Then run `:backend:app:test` and the commands above (the gate should exit 0).
