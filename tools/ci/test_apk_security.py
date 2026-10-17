#!/usr/bin/env python3
"""Self-test of tools/ci/apk-security-check.py (N-061; owner qa): every rule fires on a seeded violation and stays
quiet on a clean manifest. Offline (no SDK needed): python3 tools/ci/test_apk_security.py"""
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("apksec", Path(__file__).resolve().parent / "apk-security-check.py")
apksec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apksec)

NS = "http://schemas.android.com/apk/res/android:"


def manifest(app_attrs: str, components: str = "") -> str:
    """An `aapt2 dump xmltree` text with the given application attribute lines and component lines."""
    return f"""N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: {NS}versionCode(0x0101021b)=(type 0x10)0x2d4
    A: package="com.aktcl.aron.sr" (Raw: "com.aktcl.aron.sr")
    E: uses-sdk (line=7)
      A: {NS}minSdkVersion(0x0101020c)=(type 0x10)0x1d
      A: {NS}targetSdkVersion(0x01010270)=(type 0x10)0x23
    E: application (line=20)
{app_attrs}
      E: activity (line=30)
        A: {NS}name(0x01010003)="com.aktcl.aron.sr.MainActivity" (Raw: "com.aktcl.aron.sr.MainActivity")
        A: {NS}exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=31)
          E: action (line=32)
            A: {NS}name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: category (line=33)
            A: {NS}name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
      E: receiver (line=40)
        A: {NS}name(0x01010003)="com.aktcl.aron.dpc.AronDeviceAdminReceiver" (Raw: "x")
        A: {NS}permission(0x01010006)="android.permission.BIND_DEVICE_ADMIN" (Raw: "x")
        A: {NS}exported(0x01010010)=(type 0x12)0xffffffff
{components}
"""


SAFE = f"""      A: {NS}allowBackup(0x01010280)=(type 0x12)0x0
      A: {NS}usesCleartextTraffic(0x010104ec)=(type 0x12)0x0"""


def kinds(findings):
    return [(sev, msg.split(" ")[0]) for sev, msg in findings if sev != "info"]


class ManifestRules(unittest.TestCase):
    def test_a_hardened_manifest_has_no_blocking_finding(self):
        f = apksec.manifest_findings(apksec.parse_xmltree(manifest(SAFE)))
        self.assertEqual([], [x for x in f if x[0] != "info"], f)
        self.assertTrue(any("minSdk 29, targetSdk 35" in m for _, m in f), f)

    def test_debuggable_is_critical(self):
        f = apksec.manifest_findings(apksec.parse_xmltree(manifest(SAFE + f"\n      A: {NS}debuggable(0x0101000f)=(type 0x12)0xffffffff")))
        self.assertIn(("critical", "android:debuggable is true"), f)

    def test_backup_not_disabled_is_high(self):
        f = apksec.manifest_findings(apksec.parse_xmltree(manifest(f"      A: {NS}usesCleartextTraffic(0x010104ec)=(type 0x12)0x0")))
        self.assertTrue(any(s == "high" and "allowBackup" in m for s, m in f), f)

    def test_cleartext_is_high(self):
        f = apksec.manifest_findings(apksec.parse_xmltree(manifest(f"      A: {NS}allowBackup(0x01010280)=(type 0x12)0x0\n      A: {NS}usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff")))
        self.assertIn(("high", "android:usesCleartextTraffic is true"), f)

    def test_unprotected_exported_components(self):
        extra = f"""      E: service (line=50)
        A: {NS}name(0x01010003)="com.x.OpenService" (Raw: "x")
        A: {NS}exported(0x01010010)=(type 0x12)0xffffffff
      E: receiver (line=60)
        A: {NS}name(0x01010003)="com.x.ImplicitReceiver" (Raw: "x")
        E: intent-filter (line=61)
          E: action (line=62)
            A: {NS}name(0x01010003)="com.x.PING" (Raw: "x")
      E: provider (line=70)
        A: {NS}name(0x01010003)="com.x.Files" (Raw: "x")
        A: {NS}exported(0x01010010)=(type 0x12)0xffffffff
      E: activity (line=80)
        A: {NS}name(0x01010003)="com.x.Hidden" (Raw: "x")
        A: {NS}exported(0x01010010)=(type 0x12)0x0"""
        f = apksec.manifest_findings(apksec.parse_xmltree(manifest(SAFE, extra)))
        msgs = [m for s, m in f if s != "info"]
        self.assertTrue(any("com.x.OpenService" in m for m in msgs), f)
        self.assertTrue(any("com.x.ImplicitReceiver" in m for m in msgs), "an intent-filter without exported is exported (old targetSdk)")
        self.assertTrue(any(s == "high" and "com.x.Files" in m for s, m in f), "an exported provider without a permission is high")
        self.assertFalse(any("Hidden" in m or "MainActivity" in m or "AronDeviceAdminReceiver" in m for m in msgs), "launcher, protected and unexported components pass")


class Secrets(unittest.TestCase):
    def test_secrets_in_any_entry_are_found_and_a_clean_apk_is_quiet(self):
        with tempfile.TemporaryDirectory() as d:
            clean = Path(d) / "clean.apk"
            with zipfile.ZipFile(clean, "w") as z:
                z.writestr("classes.dex", b"\x00dex\x00 nothing to see https://api.example.com/v1")
            self.assertEqual([], apksec.secret_findings(clean))
            dirty = Path(d) / "dirty.apk"
            pem = b"-----BEGIN " + b"PRIVATE KEY-----\nMIIE\n"
            with zipfile.ZipFile(dirty, "w") as z:
                z.writestr("assets/key.pem", pem)
                z.writestr("classes.dex", b"DefaultEndpointsProtocol=https;AccountName=x;AccountKey=" + b"A" * 86 + b"==")
                z.writestr("res/raw/a.txt", b"https://x.blob.core.windows.net/c/b?sv=2024&sig=" + b"Q" * 44)
                z.writestr("classes2.dex", b"AIza" + b"B" * 35)
            f = apksec.secret_findings(dirty)
            crit = {m for s, m in f if s == "critical"}
            self.assertIn("private key in assets/key.pem", crit)
            self.assertIn("Azure storage account key in classes.dex", crit)
            self.assertIn("SAS signature in res/raw/a.txt", crit)
            self.assertIn(("info", "Google API key in classes2.dex"), f)


if __name__ == "__main__":
    unittest.main()
