> **lead-backend, 2026-10-17:** not the same fix. The Jackson pin landed (see `qa-backend-jackson-cves.md`), so the step
> below now passes on the backend classpath; adding it to `ci.yml` stays with infra (owner of `.github/`). The qa scripts
> `tools/ci/gradle-lockfile.py` and `test_gradle_lockfile.py` are on `lane/qa` and must reach INT with or before the step.

# qa → infra: put the Gradle runtime classpaths under the OSV gate (N-061), 2026-10-17

ci.yml's dependency scan reads only `web/package-lock.json` ("the Gradle verification file joins it when it exists").
There is no Gradle lockfile yet, so the backend image and the three APKs ship unscanned. The first scan found 5 High
Jackson advisories in the backend (`docs/requests/qa-backend-jackson-cves.md`).

qa added `tools/ci/gradle-lockfile.py` and its self-test `tools/ci/test_gradle_lockfile.py`. The script turns
`gradle dependencies` output into a `gradle.lockfile` that osv-scanner reads. It keeps resolved versions only:
constraints, unresolved and failed lines are skipped, and rich versions are handled.

**Ask (infra owns ci.yml):** in a job that already has the Gradle and Android SDK setup, add:
```yaml
      - name: Dependency scan of the Gradle runtime classpaths (OSV; N-061)
        run: |
          python3 tools/ci/test_gradle_lockfile.py
          d="${RUNNER_TEMP}/gradle-deps"; mkdir -p "$d"
          ./gradlew -q :backend:app:dependencies --configuration runtimeClasspath > "$d/backend.txt"
          for a in sr amo tso; do ./gradlew -q ":android:app-$a:dependencies" --configuration releaseRuntimeClasspath > "$d/$a.txt"; done
          python3 tools/ci/gradle-lockfile.py "$d/lock/gradle.lockfile" "$d"/backend.txt "$d"/sr.txt "$d"/amo.txt "$d"/tso.txt
          set +e; osv-scanner scan source --lockfile "$d/lock/gradle.lockfile" --format json > "$d/osv.json"; rc=$?; set -e
          [ "${rc}" -le 1 ] || { echo "::error::osv-scanner failed (exit ${rc})"; exit 1; }
          python3 tools/ci/osv-gate.py "$d/osv.json" tools/ci/osv-allow.txt
```
This run: 462 modules, about 2 minutes with a warm Gradle cache. It is red until the Jackson pin lands, so either merge
it after that fix or add dated `osv-allow.txt` lines for the five ids.
