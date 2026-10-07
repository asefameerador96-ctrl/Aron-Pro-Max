#!/usr/bin/env python3
"""Tests for the CI gates in tools/ci/ (run by infra/validate.sh and the ci.yml gates job). Owner: infra lane.

Each gate is proven to FAIL on a deliberate violation and to pass on a clean input:
  apk-size-gate.py      absolute 30 MB per ABI, +15 % fails, +5 % warns, a clean APK passes
  migrations-check.sh   an edited, a deleted, a duplicate and an out-of-order migration fail; a new one passes
  contract-breaking.sh  a breaking change fails; with an info.version bump and a request file it passes (needs oasdiff)
  gitleaks.toml         a token fails anywhere except the generated contract/slices/ (needs gitleaks)
Binaries: OASDIFF and SQUAWK (paths) or on PATH; a test that needs a missing binary is skipped, never faked.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
MB = 1024 * 1024


def tool(name):
    return os.environ.get(name.upper()) or shutil.which(name)


def make_apk(path, lib_bytes_per_abi=0, dex_bytes=1000, abis=("arm64-v8a",)):
    # Stored (uncompressed) random-ish data, so the file size is predictable.
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("classes.dex", os.urandom(dex_bytes))
        z.writestr("AndroidManifest.xml", b"x" * 100)
        for a in abis:
            if lib_bytes_per_abi:
                z.writestr(f"lib/{a}/libx.so", os.urandom(lib_bytes_per_abi))
    return path


class ApkSizeGate(unittest.TestCase):
    def run_gate(self, apk, baseline):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d)
        shutil.copy(HERE / "apk-size-gate.py", d)
        if baseline is not None:
            Path(d, "apk-size-baseline.json").write_text(json.dumps(baseline))
        r = subprocess.run([sys.executable, str(Path(d, "apk-size-gate.py")), f"sr={apk}"],
                           capture_output=True, text=True, env={k: v for k, v in os.environ.items() if k != "GITHUB_STEP_SUMMARY"})
        return r.returncode, r.stdout

    def apk(self, **kw):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d)
        return make_apk(os.path.join(d, "a.apk"), **kw)

    def test_above_the_absolute_budget_fails(self):
        rc, out = self.run_gate(self.apk(lib_bytes_per_abi=31 * MB), None)
        self.assertEqual(rc, 1, out)
        self.assertIn("above the 30 MB budget", out)

    def test_more_than_15_percent_above_baseline_fails(self):
        a = self.apk(lib_bytes_per_abi=2 * MB)
        size = os.path.getsize(a)
        rc, out = self.run_gate(a, {"sr": {"arm64-v8a": int(size / 1.16)}})
        self.assertEqual(rc, 1, out)
        self.assertIn("fail above +15 %", out)

    def test_between_5_and_15_percent_warns_only(self):
        a = self.apk(lib_bytes_per_abi=2 * MB)
        size = os.path.getsize(a)
        rc, out = self.run_gate(a, {"sr": {"arm64-v8a": int(size / 1.08)}})
        self.assertEqual(rc, 0, out)
        self.assertIn("::warning::", out)

    def test_clean_apk_passes(self):
        a = self.apk(lib_bytes_per_abi=2 * MB)
        rc, out = self.run_gate(a, {"sr": {"arm64-v8a": os.path.getsize(a)}})
        self.assertEqual(rc, 0, out)
        self.assertNotIn("::error::", out)

    def test_per_abi_size_excludes_other_abis(self):
        a = self.apk(lib_bytes_per_abi=12 * MB, abis=("arm64-v8a", "armeabi-v7a", "x86_64"))
        rc, out = self.run_gate(a, None)
        # 36 MB universal file fails; each ABI alone (about 12 MB) is inside the budget.
        self.assertEqual(rc, 1, out)
        self.assertIn("::error::sr universal", out)
        self.assertNotIn("::error::sr arm64-v8a", out)


class MigrationsCheck(unittest.TestCase):
    def setUp(self):
        self.squawk = tool("squawk")
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo)
        os.makedirs(os.path.join(self.repo, "db/migrations"))
        shutil.copytree(HERE, os.path.join(self.repo, "tools/ci"))
        self.git("init", "-q")
        for v in (1, 2):
            self.write(f"V000{v}__t{v}.sql", f"SET lock_timeout = '5s';\nCREATE TABLE t{v} (id bigint PRIMARY KEY);\n")
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *a):
        return subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false", *a],
                              cwd=self.repo, check=True, capture_output=True, text=True).stdout

    def write(self, name, text):
        Path(self.repo, "db/migrations", name).write_text(text)

    def check(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "change", "--allow-empty")
        r = subprocess.run(["bash", "tools/ci/migrations-check.sh", self.base, self.squawk or "false"],
                           cwd=self.repo, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr

    def test_editing_a_shipped_migration_fails(self):
        self.write("V0001__t1.sql", "SET lock_timeout = '5s';\nCREATE TABLE t1 (id bigint PRIMARY KEY, x int);\n")
        rc, out = self.check()
        self.assertEqual(rc, 1, out)
        self.assertIn("V0001__t1.sql was shipped", out)

    def test_deleting_a_shipped_migration_fails(self):
        os.remove(os.path.join(self.repo, "db/migrations/V0002__t2.sql"))
        rc, out = self.check()
        self.assertEqual(rc, 1, out)
        self.assertIn("is gone at HEAD", out)

    def test_duplicate_or_lower_version_fails(self):
        self.write("V0002__again.sql", "SET lock_timeout = '5s';\nCREATE TABLE t9 (id bigint PRIMARY KEY);\n")
        rc, out = self.check()
        self.assertEqual(rc, 1, out)
        self.assertIn("duplicate migration version", out)
        self.assertIn("not above the highest shipped version 2", out)

    @unittest.skipUnless(tool("squawk"), "squawk binary not available")
    def test_new_migration_without_lock_timeout_fails_squawk(self):
        self.write("V0003__alter.sql", "ALTER TABLE t1 ADD COLUMN note text;\nCREATE INDEX t1_note ON t1 (note);\n")
        rc, out = self.check()
        self.assertEqual(rc, 1, out)
        self.assertIn("squawk found migration risks", out)

    @unittest.skipUnless(tool("squawk"), "squawk binary not available")
    def test_clean_new_migration_passes(self):
        self.write("V0003__t3.sql", "SET lock_timeout = '5s';\nCREATE TABLE t3 (id bigint PRIMARY KEY);\n")
        rc, out = self.check()
        self.assertEqual(rc, 0, out)
        self.assertIn("migrations: ok", out)


@unittest.skipUnless(tool("gitleaks"), "gitleaks binary not available")
class SecretScan(unittest.TestCase):
    # A planted, made-up GitHub token shape (built at run time so this file itself holds no token).
    FAKE = "ghp_" + "Ab3dE6gH9jK2mN5pQ8sT1vW4yZ7bC0eF3hJ6"

    def scan(self, rel_path):
        repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, repo)
        git = ["git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false"]
        subprocess.run(git + ["init", "-q"], cwd=repo, check=True)
        f = Path(repo, rel_path)
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(f"token: {self.FAKE}\n")
        subprocess.run(git + ["add", "-A"], cwd=repo, check=True)
        subprocess.run(git + ["commit", "-qm", "x"], cwd=repo, check=True)
        r = subprocess.run([tool("gitleaks"), "git", "--no-banner", "--redact", "--exit-code", "1",
                            "--config", str(HERE / "gitleaks.toml"), "--gitleaks-ignore-path", str(HERE / "gitleaksignore"), "."],
                           cwd=repo, capture_output=True, text=True)
        return r.returncode

    def test_a_token_anywhere_else_fails(self):
        self.assertEqual(self.scan("backend/app/src/main/resources/app.yaml"), 1)
        self.assertEqual(self.scan("contract/openapi.yaml"), 1, "the contract source is scanned")

    def test_only_the_generated_contract_slices_are_skipped(self):
        self.assertEqual(self.scan("contract/slices/operations/login.yaml"), 0)


@unittest.skipUnless(tool("oasdiff"), "oasdiff binary not available")
class ContractBreaking(unittest.TestCase):
    SPEC = """openapi: 3.1.0
info:
  title: t
  version: {version}
paths:
  /v1/x:
    get:
      operationId: getX
      responses:
        '200':
          description: ok
          content:
            application/json:
              schema:
                type: object
                properties:
                  status: {{ type: string, enum: [{values}] }}
"""

    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo)
        os.makedirs(os.path.join(self.repo, "contract"))
        shutil.copytree(HERE, os.path.join(self.repo, "tools/ci"))
        self.git("init", "-q")
        self.spec("1.0.0", "a, b")
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *a):
        return subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false", *a],
                              cwd=self.repo, check=True, capture_output=True, text=True).stdout

    def spec(self, version, values):
        Path(self.repo, "contract/openapi.yaml").write_text(self.SPEC.format(version=version, values=values))

    def check(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "change")
        r = subprocess.run(["bash", "tools/ci/contract-breaking.sh", self.base, tool("oasdiff")],
                           cwd=self.repo, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr

    def test_new_response_enum_value_fails(self):
        self.spec("1.0.0", "a, b, c")
        rc, out = self.check()
        self.assertEqual(rc, 1, out)
        self.assertIn("breaking contract change", out)

    def test_version_bump_alone_is_not_enough(self):
        self.spec("1.1.0", "a, b, c")
        rc, out = self.check()
        self.assertEqual(rc, 1, out)

    def test_version_bump_and_request_file_pass(self):
        self.spec("1.1.0", "a, b, c")
        os.makedirs(os.path.join(self.repo, "docs/requests"))
        Path(self.repo, "docs/requests/contract-status-c.md").write_text("approved by the lead\n")
        rc, out = self.check()
        self.assertEqual(rc, 0, out)
        self.assertIn("approved by version bump and request file", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
