#!/usr/bin/env python3
"""Self-tests for the money gates (docs/31 money row). Owner: qa lane.

  money-float-scan.py      a Double on a Kotlin money line, parseFloat and a division by 1000 on a TS money line fail;
                           a comment, a non-money line and a listed line pass; a stale entry and an allow-list over
                           CEILING fail; the real checkout passes
  money-format-corpus.py   the committed corpus is what the generator produces; 200 distinct amounts; the oracle
                           rounds half away from zero; a hand edit is caught
Run: python3 tools/ci/test_money_gates.py
"""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SCAN = HERE / "money-float-scan.py"
CORPUS = HERE / "money-format-corpus.py"


def run(*args):
    return subprocess.run([sys.executable, *map(str, args)], capture_output=True, text=True)


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class MoneyFloatScanTest(unittest.TestCase):
    def tree(self, files):
        d = Path(tempfile.mkdtemp(prefix="money-scan-"))
        for rel, text in files.items():
            p = d / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
        return d

    def scan(self, root, allow_lines=()):
        allow = root / "allow.txt"
        allow.write_text("\n".join(allow_lines) + "\n", encoding="utf-8")
        return run(SCAN, root, allow)

    def test_a_double_on_a_kotlin_money_line_fails(self):
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "val netMtk: Double = 0.0\n"})
        r = self.scan(root)
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("A.kt,line=1", r.stdout)

    def test_ts_parse_float_and_division_fail(self):
        root = self.tree({"web/src/lib/x.ts": "const a = parseFloat(amountText);\nconst t = net_mtk / 1000;\nconst ok = net_mtk / 1000n;\n"})
        r = self.scan(root)
        self.assertEqual(1, r.returncode)
        self.assertIn("x.ts,line=1", r.stdout)
        self.assertIn("x.ts,line=2", r.stdout)
        self.assertNotIn("x.ts,line=3", r.stdout, "a BigInt division is integer maths")

    def test_comments_and_non_money_lines_pass(self):
        root = self.tree({
            "backend/x/src/main/kotlin/B.kt": "// never use Double for netMtk\nval ratio: Double = 0.5\n",
            "web/src/contract/types.ts": "const price = parseFloat(x);\n",  # generated contract is skipped
        })
        self.assertEqual(0, self.scan(root).returncode)

    def test_a_money_file_counts_every_line(self):
        root = self.tree({"shared/rules/src/commonMain/kotlin/Money.kt": "fun half(x: Long) = x.toDouble() / 2\n"})
        self.assertEqual(1, self.scan(root).returncode)

    def test_a_listed_line_passes_and_a_stale_entry_fails(self):
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "val netMtk: Double = 0.0\n"})
        listed = "backend/x/src/main/kotlin/A.kt | netMtk: Double | finding | test | with a pipe"
        r = self.scan(root, [listed])
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("1 open finding", r.stdout)
        r = self.scan(root, [listed, "backend/x/src/main/kotlin/A.kt | nothing like this | ok | stale"])
        self.assertEqual(1, r.returncode)
        self.assertIn("stale allow entry", r.stdout)

    def test_a_malformed_entry_fails(self):
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "val x = 1\n"})
        self.assertNotEqual(0, self.scan(root, ["A.kt | x | maybe | why"]).returncode)

    def test_the_allow_list_cannot_grow_past_the_ceiling(self):
        scan = load(SCAN, "money_float_scan")
        n = scan.CEILING + 1
        files = {f"backend/x/src/main/kotlin/F{i}.kt": "val netMtk: Double = 0.0\n" for i in range(n)}
        root = self.tree(files)
        r = self.scan(root, [f"backend/x/src/main/kotlin/F{i}.kt | netMtk | ok | test" for i in range(n)])
        self.assertEqual(1, r.returncode)
        self.assertIn("only shrinks", r.stdout)

    def test_the_checkout_passes(self):
        r = run(SCAN)
        self.assertEqual(0, r.returncode, r.stdout)


class MoneyFormatCorpusTest(unittest.TestCase):
    def test_the_committed_corpus_is_current(self):
        r = run(CORPUS)
        self.assertEqual(0, r.returncode, r.stdout)

    def test_two_hundred_distinct_amounts_with_the_required_edges(self):
        data = json.loads((ROOT / "contract/fixtures/money-format-corpus.json").read_text(encoding="utf-8"))
        mtk = [a["mtk"] for a in data["amounts"]]
        self.assertEqual(200, len(mtk))
        self.assertEqual(200, len(set(mtk)))
        for edge in (0, 10, 5, -5, 10_000_000_000, 9_007_199_254_740_991):
            self.assertIn(edge, mtk)
        self.assertTrue(any(m < 0 for m in mtk))
        self.assertTrue(sum(1 for m in mtk if m % 10 == 5 or m % 10 == -5) >= 20, "half-paisa amounts")

    def test_the_oracle(self):
        gen = load(CORPUS, "money_format_corpus")
        self.assertEqual(1, gen.half_away(5, 10))
        self.assertEqual(-1, gen.half_away(-5, 10))
        self.assertEqual(0, gen.half_away(-4, 10))
        e = gen.expected(1_164_200)
        self.assertEqual("1,164.20 ৳", e["money2"]["en"])
        self.assertEqual("১,১৬৪.২০ ৳", e["money2"]["bn"])
        self.assertEqual("3.62 Cr", gen.expected(36_150_000_000)["lakh_crore"]["en"])
        self.assertEqual("1.00 Lakh", gen.expected(99_999_500)["lakh_crore"]["en"])
        self.assertEqual("-0.01 ৳", gen.expected(-5)["money2"]["en"])
        self.assertEqual("0.00 ৳", gen.expected(-4)["money2"]["en"])

    def test_a_hand_edit_is_caught(self):
        gen = load(CORPUS, "money_format_corpus2")
        with tempfile.TemporaryDirectory() as d:
            gen.OUT = Path(d) / "c.json"
            gen.ROOT = Path(d)
            gen.OUT.write_text(gen.render().replace("1,164.20", "1,164.21"), encoding="utf-8")
            sys_argv = sys.argv
            try:
                sys.argv = ["x"]
                self.assertEqual(1, gen.main())
            finally:
                sys.argv = sys_argv


if __name__ == "__main__":
    unittest.main(verbosity=2)
