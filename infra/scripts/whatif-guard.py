#!/usr/bin/env python3
"""What-if guard for infra/deploy.sh (docs/28 exception: the existing PostgreSQL server must not change).

Reads the JSON of `az deployment group what-if --no-pretty-print -o json` and
  - prints one line per resource change (type, name, changeType, changed property paths);
  - FAILS (exit 1) when a PostgreSQL flexible server would be deleted, when one of its protected properties would
    change (sku, storage, high availability, backup, network, version, zones, administrator login), or when a new
    server would be created while another one already exists (EXISTING_POSTGRES_IDS, space-separated, from deploy.sh).
The administrator password is re-sent unchanged on every deploy (read back from Key Vault) and is not compared.
"""
import json
import os
import sys

PG = "microsoft.dbforpostgresql/flexibleservers"
PROTECTED = ("sku", "properties.sku", "properties.storage", "properties.highavailability", "properties.backup",
             "properties.network", "properties.version", "properties.availabilityzone", "properties.administratorlogin",
             "location", "zones")
IGNORED = ("properties.administratorloginpassword",)


def paths(delta, prefix=""):
    for d in delta or []:
        p = f"{prefix}.{d['path']}" if prefix else d["path"]
        if d.get("children"):
            yield from paths(d["children"], p)
        else:
            yield p, d.get("propertyChangeType")


def main(path):
    data = json.load(open(path))
    changes = data.get("changes") or (data.get("properties") or {}).get("changes") or []
    existing = {x.lower() for x in os.environ.get("EXISTING_POSTGRES_IDS", "").split() if x}
    problems = []
    for c in changes:
        rid = c.get("resourceId", "")
        rtype = "/".join(rid.split("/providers/")[-1].split("/")[0:2]).lower() if "/providers/" in rid else ""
        kind = c.get("changeType")
        changed = [f"{p} ({t})" for p, t in paths(c.get("delta")) if t not in (None, "NoEffect")]
        if kind not in ("NoChange", "Ignore"):
            print(f"what-if: {kind:9} {rtype:55} {rid.rsplit('/', 1)[-1]}  {', '.join(changed)[:300]}")
        is_server = rtype == PG and rid.lower().count("/") == rid.lower().split("/providers/")[0].count("/") + 4
        if not is_server:
            continue
        if kind == "Delete":
            problems.append(f"{rid}: would be deleted")
        if kind == "Create" and existing - {rid.lower()}:
            problems.append(f"{rid}: a new server would be created next to {sorted(existing)}")
        if kind == "Modify":
            for p, t in paths(c.get("delta")):
                pl = p.lower()
                if t in (None, "NoEffect") or pl.startswith(IGNORED):
                    continue
                if pl.startswith(PROTECTED):
                    problems.append(f"{rid}: protected property {p} would change ({t})")
    for p in problems:
        print(f"::error::what-if guard: {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
