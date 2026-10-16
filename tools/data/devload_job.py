#!/usr/bin/env python3
"""Run a SQL file against the DEV database through the Container Apps psql job (no laptop reaches the server).

The dev PostgreSQL flexible server has public network access disabled (VNet-delegated, private DNS), so the only
path from a laptop is the environment's psql job `caj-aron-dev-dblogins` (infra/apps.bicep), the same job that runs
the per-app logins and the dev seed. A job execution cannot mount a new file, so the SQL travels through a private
blob: this script uploads it (your Entra login), starts one execution with a template override whose command fetches
the blob with the job's managed identity (a token from the Container Apps identity endpoint; no SAS, no key, nothing
secret in the execution record), runs psql with ON_ERROR_STOP, waits, prints the console log from Log Analytics and
deletes the blob. Roles needed while it runs (test account, removed afterwards): you = Storage Blob Data Contributor
on the account, the job identity = Storage Blob Data Reader on the container.

    python -P tools/data/devload_job.py FILE.sql [FILE2.sql ...] [--rg rg-aron-dev] [--keep-blob] [--no-wait]

Several files are concatenated in order into one execution (one psql session, one transaction per file's BEGIN/COMMIT).
Never put a password in a SQL file: the database URL comes from the job's Key Vault reference.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import time

CONTAINER = "devload"

FETCH_AND_RUN = (
    "set -e; "
    "tok=`wget -q -O - --header \"X-IDENTITY-HEADER: $IDENTITY_HEADER\" "
    "\"$IDENTITY_ENDPOINT?resource=https://storage.azure.com/&api-version=2019-08-01&client_id=$ARON_MI_CLIENT_ID\" "
    "| sed 's/.*\"access_token\":\"\\([^\"]*\\)\".*/\\1/'`; "
    "[ -n \"$tok\" ] || { echo 'devload: no identity token'; exit 2; }; "
    "wget -q -O /tmp/load.sql --header \"Authorization: Bearer $tok\" --header 'x-ms-version: 2020-04-08' \"$ARON_BLOB_URL\" "
    "|| { echo 'devload: blob download failed'; exit 3; }; "
    "echo \"devload: `wc -c < /tmp/load.sql` bytes of SQL\"; "
    "psql \"${ARON_DB_URL#jdbc:}\" -X -v ON_ERROR_STOP=1 -f /tmp/load.sql; "
    "echo devload: psql exit $?"
)


def az(*args, check=True, capture=True):
    cmd = ["az", *args]
    if os.name == "nt":
        cmd[0] = "az.cmd"
    r = subprocess.run(cmd, capture_output=capture, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"az {' '.join(args[:4])} failed: {r.stderr.strip()[-800:]}")
    return r.stdout


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("sql", nargs="+")
    ap.add_argument("--rg", default="rg-aron-dev")
    ap.add_argument("--job", default="caj-aron-dev-dblogins")
    ap.add_argument("--account", default=None, help="storage account (default: the one in the resource group)")
    ap.add_argument("--keep-blob", action="store_true")
    ap.add_argument("--no-wait", action="store_true")
    ap.add_argument("--timeout-s", type=int, default=420)
    a = ap.parse_args(argv)

    account = a.account or json.loads(az("storage", "account", "list", "-g", a.rg, "-o", "json"))[0]["name"]
    job = json.loads(az("containerapp", "job", "show", "-g", a.rg, "-n", a.job, "-o", "json"))
    container = job["properties"]["template"]["containers"][0]
    identity = next(iter(job["identity"]["userAssignedIdentities"].values()))
    client_id = identity["clientId"]

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    blob = f"load-{stamp}.sql"
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8", newline="\n") as f:
        for path in a.sql:
            f.write(f"\\echo devload: file {os.path.basename(path)}\n")
            f.write(open(path, encoding="utf-8").read())
            f.write("\n")
        merged = f.name
    try:
        size = os.path.getsize(merged)
        print(f"uploading {size:,} bytes as {CONTAINER}/{blob}")
        az("storage", "blob", "upload", "--account-name", account, "--container-name", CONTAINER, "--name", blob,
           "--file", merged, "--auth-mode", "login", "--overwrite", "-o", "none")
        blob_url = f"https://{account}.blob.core.windows.net/{CONTAINER}/{blob}"
        template = {"containers": [{
            "name": container["name"], "image": container["image"], "resources": container["resources"],
            "command": ["/bin/sh", "-c", FETCH_AND_RUN],
            "env": [{"name": "ARON_DB_URL", "secretRef": "db-direct-url"},
                    {"name": "ARON_BLOB_URL", "value": blob_url},
                    {"name": "ARON_MI_CLIENT_ID", "value": client_id}],
        }]}
        with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False, encoding="utf-8") as t:
            json.dump(template, t)
            tpl = t.name
        execution = az("containerapp", "job", "start", "-g", a.rg, "-n", a.job, "--yaml", tpl, "--query", "name", "-o", "tsv").strip()
        os.unlink(tpl)
        print(f"execution {execution} started")
        if a.no_wait:
            return 0
        status = wait(a, execution)
        print(f"execution {execution}: {status}")
        console_log(a.rg, execution)
        return 0 if status == "Succeeded" else 1
    finally:
        os.unlink(merged)
        if not a.keep_blob:
            az("storage", "blob", "delete", "--account-name", account, "--container-name", CONTAINER, "--name", blob,
               "--auth-mode", "login", "-o", "none", check=False)


def wait(a, execution):
    deadline = time.time() + a.timeout_s
    status = "unknown"
    while time.time() < deadline:
        status = az("containerapp", "job", "execution", "show", "-g", a.rg, "-n", a.job, "--job-execution-name", execution,
                    "--query", "properties.status", "-o", "tsv", check=False).strip() or "unknown"
        if status in ("Succeeded", "Failed", "Stopped", "Degraded"):
            return status
        time.sleep(10)
    return status


def console_log(rg, execution, attempts=14):
    """The execution's console lines from Log Analytics (ingestion takes 1 to 3 minutes; deploy.sh job_logs)."""
    ws = az("monitor", "log-analytics", "workspace", "list", "-g", rg, "--query", "[0].customerId", "-o", "tsv").strip()
    q = ("union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs "
         "| extend exec = coalesce(column_ifexists('ContainerGroupName_s', ''), column_ifexists('ContainerGroupName', '')), "
         "line = coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')) "
         f"| where exec startswith '{execution}' | order by TimeGenerated asc | project line | take 2000")
    for _ in range(attempts):
        out = az("monitor", "log-analytics", "query", "-w", ws, "--analytics-query", q, "--query", "[].line", "-o", "tsv", check=False)
        if out.strip():
            print("---- console log")
            print(out.rstrip())
            print("----")
            return
        time.sleep(30)
    print("(no console log in Log Analytics after 7 minutes)")


if __name__ == "__main__":
    sys.exit(main())
