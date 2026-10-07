#!/usr/bin/env python3
"""Dependency vulnerability gate (AUD-SEC-05) over osv-scanner's JSON output (docs/31 s2).

Fails (exit 1) on any HIGH or CRITICAL vulnerability (CVSS >= 7.0) in a package that ships or runs in production.
A package only in the dev dependency group (build and test tools; never in the image) gets a warning annotation
instead, as does a production finding whose severity OSV does not know. An exception needs a dated line in the
allow file: "<vuln id> <YYYY-MM-DD expiry> <reason>"; an expired line no longer counts and is reported.

Usage: osv-gate.py <osv.json> [allow-file]     (osv-scanner scan source --lockfile ... --format json > osv.json)
"""
import datetime
import json
import sys

HIGH = 7.0


def load_allow(path):
    allow, expired = {}, []
    if not path:
        return allow, expired
    today = datetime.date.today()
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split(None, 2)
            if len(parts) < 3:
                sys.exit(f"{path}:{n}: expected '<id> <YYYY-MM-DD> <reason>'")
            until = datetime.date.fromisoformat(parts[1])
            if until >= today:
                allow[parts[0]] = until
            else:
                expired.append(parts[0])
    return allow, expired


def main():
    report = json.load(open(sys.argv[1], encoding="utf-8"))
    allow, expired = load_allow(sys.argv[2] if len(sys.argv) > 2 else None)
    for vid in expired:
        print(f"::warning::OSV allow-list entry {vid} has expired and no longer counts")
    failures = 0
    for result in report.get("results") or []:
        source = result.get("source", {}).get("path", "?")
        for pkg in result.get("packages") or []:
            p = pkg["package"]
            name = f"{p.get('name')}@{p.get('version')} ({p.get('ecosystem')}, {source})"
            dev_only = (pkg.get("dependency_groups") or []) == ["dev"]
            for g in pkg.get("groups") or []:
                ids = g.get("ids") or []
                label = ", ".join(ids)
                try:
                    score = float(g.get("max_severity") or "nan")
                except ValueError:
                    score = float("nan")
                allowed = [allow[i] for i in ids + (g.get("aliases") or []) if i in allow]
                if allowed:
                    print(f"allowed until {min(allowed)}: {label} in {name}")
                elif score != score:  # NaN: severity unknown
                    print(f"::warning::{label} in {name}: severity unknown, review it")
                elif score >= HIGH and not dev_only:
                    print(f"::error::{label} in {name}: severity {score} (high or critical) in a production dependency")
                    failures += 1
                elif score >= HIGH:
                    print(f"::warning::{label} in {name}: severity {score}, dev dependency only (not in any image)")
                else:
                    print(f"{label} in {name}: severity {score} (below high)")
    print(f"OSV gate: {failures} blocking finding(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
