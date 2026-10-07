#!/usr/bin/env python3
"""Fails when an npm lockfile contains a package with an install script that is not in the reviewed list
(AUD-SEC-05). With ignore-scripts=true (web/.npmrc) such scripts never run, but a new one may be needed for the
package to work, or may hide something: either way it is reviewed before it lands.

Usage: install-scripts-check.py <package-lock.json> <reviewed-list>
"""
import json
import sys


def main():
    lock = json.load(open(sys.argv[1], encoding="utf-8"))
    reviewed = set()
    with open(sys.argv[2], encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                reviewed.add(line.split()[0])
    found = {}
    for path, meta in (lock.get("packages") or {}).items():
        if meta.get("hasInstallScript") and path:
            name = meta.get("name") or path.rsplit("node_modules/", 1)[-1]
            found.setdefault(name, path)
    new = sorted(set(found) - reviewed)
    for name in sorted(found):
        print(f"{'NEW, not reviewed' if name in new else 'reviewed'}: {name} ({found[name]})")
    for name in new:
        print(f"::error::{name} has an install script and is not in {sys.argv[2]}; review it (does it work with "
              "ignore-scripts=true?) and add a dated line")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
