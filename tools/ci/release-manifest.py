#!/usr/bin/env python3
"""Release manifest of the release-signed APKs (N-064, F-ADM-027): one entry per APK in the shape of the contract's
AppReleaseWrite (flavour, version_name, version_code, abi, sha256, size_bytes, signing_cert_sha256), so the release
store upload (POST /v1/admin/releases, once served) sends each entry's `release` object plus download_url. Also records the source commit and the
backend (api and worker) and web image tags the same commit deploys (deploy.sh tags images with the commit sha).

Checks, each a failure (exit 1, nothing written): a file name outside aron-<app>[-<abi>]-<version>-dev.apk, an ABI
the contract does not know, a version name outside the contract pattern, a size over the contract's 100 MiB, not
exactly one signer, two different signing certificates across the APKs (one release key: AUD-DG-03), an app
without its universal APK.

Usage: release-manifest.py <signed dir> <version name> <version code> <source sha>
Environment: APKSIGNER (path to apksigner; required). Writes <signed dir>/release-manifest.json.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ABIS = ("universal", "arm64-v8a", "armeabi-v7a")  # contract Abi
MAX_BYTES = 104857600  # contract AppReleaseWrite.size_bytes maximum
VERSION = re.compile(r"^\d{1,3}\.\d{1,3}\.\d{1,3}$")
NAME = re.compile(r"^aron-(sr|amo|tso)(?:-([a-z0-9_-]+?))?-(\d+\.\d+\.\d+)-dev\.apk$")
CERT = re.compile(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-f]{64})$", re.M)


def cert_of(apksigner, apk):
    out = subprocess.run([apksigner, "verify", "--print-certs", str(apk)], capture_output=True, text=True)
    if out.returncode != 0:
        return None, f"{apk.name}: apksigner verify failed"
    certs = CERT.findall(out.stdout)
    if len(certs) != 1:
        return None, f"{apk.name}: {len(certs)} signers (exactly one expected)"
    return certs[0][1], None


def main(argv):
    if len(argv) != 5:
        print(__doc__.strip().splitlines()[-3], file=sys.stderr)
        return 2
    folder, version_name, version_code, sha = Path(argv[1]), argv[2], argv[3], argv[4]
    apksigner = os.environ.get("APKSIGNER", "")
    errors, items = [], []
    if not VERSION.match(version_name):
        errors.append(f"version name {version_name} outside the contract pattern")
    if not version_code.isdigit() or not 1 <= int(version_code) <= 2100000000:
        errors.append(f"version code {version_code} out of range")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        errors.append("source sha must be a full 40-character commit sha")
    if not apksigner:
        errors.append("APKSIGNER is not set")
    apks = sorted(folder.glob("*.apk"))
    if not apks:
        errors.append(f"no APKs in {folder}")
    for apk in apks if apksigner else []:
        m = NAME.match(apk.name)
        if not m:
            errors.append(f"{apk.name}: unexpected file name")
            continue
        flavour, abi, ver = m.group(1), m.group(2) or "universal", m.group(3)
        if abi not in ABIS:
            errors.append(f"{apk.name}: ABI {abi} is not in the contract")
        if ver != version_name:
            errors.append(f"{apk.name}: version {ver} is not {version_name}")
        size = apk.stat().st_size
        if not 1 <= size <= MAX_BYTES:
            errors.append(f"{apk.name}: {size} bytes outside 1..{MAX_BYTES}")
        cert, err = cert_of(apksigner, apk)
        if err:
            errors.append(err)
        items.append({"file": apk.name, "release": {  # release: AppReleaseWrite without download_url (additionalProperties false)
            "flavour": flavour, "abi": abi, "version_name": version_name, "version_code": int(version_code) if version_code.isdigit() else 0,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "size_bytes": size, "signing_cert_sha256": cert}})
    if items:
        for app in ("sr", "amo", "tso"):
            if not any(i["release"]["flavour"] == app and i["release"]["abi"] == "universal" for i in items):
                errors.append(f"{app}: no universal APK (the device-owner provisioning file)")
        if len({i["release"]["signing_cert_sha256"] for i in items if i["release"]["signing_cert_sha256"]}) > 1:
            errors.append("the APKs are signed with different certificates")
    if errors:
        for e in errors:
            print(f"release-manifest: {e}", file=sys.stderr)
        return 1
    manifest = {"schema": 1, "source_sha": sha, "version_name": version_name, "version_code": int(version_code),
                "backend_image": f"aron-backend:{sha}", "web_image": f"aron-web:{sha}", "apks": items}
    (folder / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    for i in (x["release"] for x in items):
        print(f"{i['flavour']} {i['abi']}: {i['sha256']} {i['size_bytes']} bytes, cert {i['signing_cert_sha256']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
