#!/usr/bin/env python3
"""N-061: MASVS-style static checks on the release APKs (SR, AMO, TSO). Owner: qa; wired in CI by infra.

  python3 tools/ci/apk-security-check.py [--gate] <apk> [<apk> ...]

Per APK (manifest via `aapt2 dump xmltree`, signer via `apksigner verify`, all zip entries scanned):
  critical  debuggable application; signed with the Android debug certificate; a private key, a storage account key,
            a SAS signature, an AWS key or a JWT inside the APK (MASVS-CODE, MASVS-STORAGE, MASVS-RESILIENCE)
  high      allowBackup not false (app data, the per-user database key, the outbox could be copied); cleartext HTTP
            allowed (usesCleartextTraffic true); only the v1 signature scheme
  medium    an exported component without a permission (other than the launcher activity), a content provider
            exported at all
  info      Google API keys (the Maps key belongs in the APK; restrict it by package and certificate in the console),
            the minSdk and targetSdk, the network security config
`--gate` exits 1 on any critical or high finding (warning mode otherwise). aapt2 and apksigner come from the Android
SDK build-tools (ANDROID_HOME); without them the script says so and fails.
"""
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android:"
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")
SECRET_PATTERNS = [
    ("critical", "private key", re.compile(rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("critical", "Azure storage account key", re.compile(rb"AccountKey=[A-Za-z0-9+/]{40,}={0,2}")),
    ("critical", "SAS signature", re.compile(rb"[?&]sig=[A-Za-z0-9%+/]{30,}")),
    ("critical", "AWS access key", re.compile(rb"\bAKIA[0-9A-Z]{16}\b")),
    ("critical", "JWT", re.compile(rb"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}")),
    ("critical", "client secret or password assignment", re.compile(rb"(?i)\b(?:client_secret|password|passwd)\s*[=:]\s*['\"][^'\"\s]{8,}['\"]")),
    ("info", "Google API key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
]


class Element:
    def __init__(self, name, parent=None):
        self.name, self.parent, self.attrs, self.children = name, parent, {}, []


def parse_xmltree(text: str) -> Element:
    """The element tree of `aapt2 dump xmltree` output (indentation gives the nesting)."""
    root = Element("#root")
    stack = [(-1, root)]
    for line in text.splitlines():
        m = re.match(r"^(\s*)E: ([\w.:-]+)", line)
        if m:
            depth = len(m.group(1))
            while stack[-1][0] >= depth:
                stack.pop()
            e = Element(m.group(2), stack[-1][1])
            stack[-1][1].children.append(e)
            stack.append((depth, e))
            continue
        a = re.match(r"^\s*A: (?:" + re.escape(ANDROID_NS) + r")?([\w.:-]+)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
        if a:
            stack[-1][1].attrs[a.group(1)] = attr_value(a.group(2))
    return root


def attr_value(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith("(type 0x12)"):  # boolean
        return "false" if raw.endswith("0x0") else "true"
    m = re.match(r'^"(.*?)"', raw)
    if m:
        return m.group(1)
    m = re.match(r"^\(type 0x10\)0x([0-9a-f]+)", raw)  # int
    if m:
        return str(int(m.group(1), 16))
    return raw


def walk(e: Element):
    yield e
    for c in e.children:
        yield from walk(c)


def manifest_findings(root: Element) -> list:
    out = []
    app = next((e for e in walk(root) if e.name == "application"), None)
    if app is None:
        return [("critical", "no <application> element in the manifest")]
    if app.attrs.get("debuggable") == "true":
        out.append(("critical", "android:debuggable is true"))
    if app.attrs.get("allowBackup") != "false":
        out.append(("high", f"android:allowBackup is {app.attrs.get('allowBackup', 'not set (defaults to true)')}"))
    if app.attrs.get("usesCleartextTraffic") == "true":
        out.append(("high", "android:usesCleartextTraffic is true"))
    if "networkSecurityConfig" in app.attrs:
        out.append(("info", "networkSecurityConfig is set (review it for cleartext domains)"))
    sdk = next((e for e in walk(root) if e.name == "uses-sdk"), None)
    if sdk is not None:
        out.append(("info", f"minSdk {sdk.attrs.get('minSdkVersion')}, targetSdk {sdk.attrs.get('targetSdkVersion')}"))
    for c in (e for e in walk(app) if e.name in COMPONENTS):
        name = c.attrs.get("name", "?")
        filters = [f for f in c.children if f.name == "intent-filter"]
        exported = c.attrs.get("exported", "true" if filters else "false")
        if exported != "true":
            continue
        actions = {a.attrs.get("name") for f in filters for a in f.children if a.name == "action"}
        cats = {a.attrs.get("name") for f in filters for a in f.children if a.name == "category"}
        if c.name in ("activity", "activity-alias") and "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in cats:
            continue
        perm = c.attrs.get("permission") or (c.attrs.get("readPermission") if c.name == "provider" else None)
        if c.name == "provider":
            out.append(("medium" if perm else "high", f"exported provider {name}" + (f" (permission {perm})" if perm else " without a permission")))
        elif not perm:
            out.append(("medium", f"exported {c.name} {name} without a permission (actions: {', '.join(sorted(a for a in actions if a)) or 'none'})"))
    return out


def secret_findings(apk: Path) -> list:
    out = []
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            if info.file_size > 64 * 1024 * 1024:
                continue
            data = z.read(info)
            for severity, what, rx in SECRET_PATTERNS:
                if rx.search(data):
                    out.append((severity, f"{what} in {info.filename}"))
    return sorted(set(out))


def tool(name: str):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/opt/android-sdk"
    bt = Path(sdk) / "build-tools"
    if not bt.is_dir():
        return None
    for d in sorted(bt.iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)], reverse=True):
        for cand in (d / name, d / (name + ".bat"), d / (name + ".exe")):
            if cand.exists():
                return str(cand)
    return None


def signer_findings(apk: Path, apksigner: str) -> list:
    r = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(apk)], capture_output=True, text=True)
    text = r.stdout + r.stderr
    if r.returncode != 0:
        return [("critical", "apksigner does not verify the APK: " + text.strip().splitlines()[-1][:160] if text.strip() else "apksigner failed")]
    out = []
    if re.search(r"CN=Android Debug", text):
        out.append(("critical", "signed with the Android debug certificate"))
    v2 = re.search(r"Verified using v2 scheme \(APK Signature Scheme v2\): true", text)
    v3 = re.search(r"Verified using v3 scheme \(APK Signature Scheme v3\): true", text)
    if not (v2 or v3):
        out.append(("high", "only the v1 (JAR) signature scheme"))
    return out


def check(apk: Path, aapt2: str, apksigner: str) -> list:
    xml = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], capture_output=True, text=True)
    findings = manifest_findings(parse_xmltree(xml.stdout)) if xml.returncode == 0 else [("critical", "aapt2 cannot read the manifest: " + xml.stderr.strip()[:160])]
    return findings + signer_findings(apk, apksigner) + secret_findings(apk)


def main(argv) -> int:
    gate = "--gate" in argv
    apks = [Path(a) for a in argv if not a.startswith("--")]
    if not apks:
        print(__doc__)
        return 2
    aapt2, apksigner = tool("aapt2"), tool("apksigner")
    if not aapt2 or not apksigner:
        print("apk-security-check: Android SDK build-tools (aapt2, apksigner) not found; set ANDROID_HOME")
        return 2
    bad = 0
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    for apk in apks:
        findings = check(apk, aapt2, apksigner)
        blocking = [f for f in findings if f[0] in ("critical", "high")]
        bad += len(blocking)
        head = f"apk-security-check {apk.name}: {len(blocking)} critical/high, {len(findings)} findings ({'gate' if gate else 'warning'} mode)"
        print(head)
        for sev, msg in findings:
            print(f"  {sev:8} {msg}")
        if summary:
            with open(summary, "a", encoding="utf-8") as s:
                s.write(f"### {head}\n\n" + "".join(f"- **{sev}** {msg}\n" for sev, msg in findings) + "\n")
    return 1 if gate and bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
