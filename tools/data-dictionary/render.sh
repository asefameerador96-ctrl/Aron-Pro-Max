#!/usr/bin/env bash
# Regenerates docs/data-dictionary.md and docs/data-events.md from the PostgreSQL catalogue of a freshly migrated database (owner: db lane).
# Needs ARON_TEST_PG_URL (a PostgreSQL 16 the test role may create databases on). CI does not run this script: the
# db module's DataDictionaryTest fails when the committed file is stale or a column has no COMMENT.
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew --console=plain :db:test --tests 'com.aktcl.aron.db.DataDictionaryTest' --tests 'com.aktcl.aron.db.DataEventsTest' -Paron.writeDictionary=true --rerun-tasks -q
git diff --stat -- docs/data-dictionary.md docs/data-events.md
