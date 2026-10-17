#!/usr/bin/env python3
"""Self-test of tools/ci/done-rows-check.py (AUD-TP-8; owner qa). Each rule is proven to fire on a seeded violation
in a throwaway git repository and to stay quiet on a clean one. Run: python3 tools/ci/test_done_rows.py"""
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("done_rows", HERE / "done-rows-check.py")
done_rows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(done_rows)

HEADER = "row_id,size,started_utc,finished_utc,minutes,review_findings,notes\n"


class DoneRowsCheck(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="done-rows-"))
        (self.root / "docs/status").mkdir(parents=True)
        (self.root / "tools/ci").mkdir(parents=True)
        (self.root / "docs/25-build-backlog.csv").write_text("id,title\nF-SR-001,a\nF-SR-002,b\nN-014,c\nAUD-TP-8,d\n")
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def status(self, rows):
        (self.root / "docs/status/lane.csv").write_text(HEADER + "".join(f"{r},S,2026-10-17T00:00Z,{f},10,0,\n" for r, f in rows))

    def source(self, path, text):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        subprocess.run(["git", "add", path], cwd=self.root, check=True)

    def findings(self):
        return done_rows.check(self.root)

    def test_a_named_asserting_test_is_clean(self):
        self.status([("F-SR-001", "2026-10-17T01:00Z")])
        self.source("android/x/src/test/kotlin/ATest.kt", "/** F-SR-001: the rule. */\nclass ATest { @Test fun t() { assertEquals(1, 1) } }\n")
        self.assertEqual([], self.findings())

    def test_a_finished_row_without_a_test_is_untested(self):
        self.status([("F-SR-001", "2026-10-17T01:00Z"), ("F-SR-002", "")])  # F-SR-002 is not finished
        self.source("android/x/src/main/kotlin/A.kt", "// F-SR-001 named only in production code\n")
        f = self.findings()
        self.assertEqual(1, len(f))
        self.assertTrue(f[0].startswith("untested") and "F-SR-001" in f[0])

    def test_a_skipped_test_is_reported(self):
        self.status([("N-014", "2026-10-17T01:00Z")])
        self.source("backend/app/src/test/kotlin/BTest.kt", "// N-014\n@Disabled class BTest { @Test fun t() { assertTrue(true) } }\n")
        self.source("web/tests/c.test.ts", "// N-014\ntest.skip('x', () => { expect(1).toBe(1) })\n")
        f = self.findings()
        self.assertEqual(2, len([x for x in f if x.startswith("skipped")]), f)

    def test_a_placeholder_only_test_is_reported(self):
        self.status([("AUD-TP-8", "2026-10-17T01:00Z")])
        self.source("tools/ci/test_x.py", "# AUD-TP-8\nprint('nothing checked')\n")
        f = self.findings()
        self.assertTrue(any(x.startswith("placeholder") and "AUD-TP-8" in x for x in f), f)

    def test_untracked_and_non_test_files_do_not_count(self):
        self.status([("F-SR-001", "2026-10-17T01:00Z")])
        p = self.root / "android/x/src/test/kotlin/Untracked.kt"
        p.parent.mkdir(parents=True)
        p.write_text("// F-SR-001\nassertEquals(1, 1)\n")  # never git-added (a worktree copy, a build output)
        self.source("docs/notes.md", "F-SR-001 assert\n")
        self.assertTrue(self.findings()[0].startswith("untested"))

    def test_exemptions_need_a_reason_and_go_stale(self):
        self.status([("F-SR-001", "2026-10-17T01:00Z")])
        (self.root / "tools/ci/done-rows-exempt.txt").write_text("F-SR-001  written report, no code\nF-SR-002  device run only, row not finished\nN-014 short\n")
        f = self.findings()
        self.assertFalse(any("untested" in x for x in f), f)
        self.assertTrue(any(x.startswith("stale-exempt F-SR-002") for x in f), f)
        self.assertTrue(any("done-rows-exempt.txt:3" in x for x in f), f)

    def test_rows_outside_the_backlog_and_unfinished_rows_are_ignored(self):
        self.status([("F-XYZ-999", "2026-10-17T01:00Z"), ("F-SR-002", " ")])
        self.assertEqual([], self.findings())

    def test_gate_mode_exits_one_and_warning_mode_zero(self):
        self.status([("F-SR-001", "2026-10-17T01:00Z")])
        self.assertEqual(0, done_rows.main([f"--root={self.root}"]))
        self.assertEqual(1, done_rows.main([f"--root={self.root}", "--gate"]))


if __name__ == "__main__":
    unittest.main()
