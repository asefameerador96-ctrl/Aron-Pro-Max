#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Source it; never run it.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }
note() { echo "== $*"; }
need() { [ -n "${!1:-}" ] || die "environment variable $1 is required ($2)"; }
