#!/usr/bin/env python3
"""Self-tests for the money gates (docs/31 money row). Owner: qa lane.

  money-float-scan.py      a Double on a Kotlin money line, parseFloat and a division by 1000 on a TS money line fail;
                           a comment, a non-money line and a listed line pass; a stale entry and an allow-list over
                           CEILING fail; a new float line in an allow-listed file is caught (an entry is an exact
                           fragment covering its line's float uses and matches exactly one line, or N with xN);
                           only `*` block-comment continuations are skipped; 1e3/1.5E6 (Kotlin) and 1_000 (TS)
                           divisors are caught; `//` inside a string does not end the code; paid/total/cash/collect
                           are money words; the real checkout passes
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

    def test_a_new_float_line_in_an_allow_listed_file_is_caught(self):
        # The checker's mutation: an allowed file gains a new float line; a broad entry must not absorb it.
        allowed = "data class Row(val ratioPct: Double?, val netMtk: Long)\n"
        entry = "backend/x/src/main/kotlin/A.kt | val ratioPct: Double? | ok | a percentage"
        root = self.tree({"backend/x/src/main/kotlin/A.kt": allowed + "val leak = netMtk.toDouble() / 1000\n"})
        r = self.scan(root, [entry])
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("A.kt,line=2", r.stdout)
        # A new line that copies the allowed fragment makes the entry match two lines: fails too.
        root = self.tree({"backend/x/src/main/kotlin/A.kt": allowed + allowed})
        r = self.scan(root, [entry])
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("matches 2 lines", r.stdout)
        # Same for TS: Math.round(mtk / 1000) added to a file whose other Math line is listed.
        root = self.tree({"web/src/lib/admin/money.ts": "const abs = Math.abs(Math.trunc(mtk));\nconst t = Math.round(mtk / 1000);\n"})
        r = self.scan(root, ["web/src/lib/admin/money.ts | const abs = Math.abs(Math.trunc(mtk)); | ok | integer"])
        self.assertEqual(1, r.returncode, r.stdout)
        self.assertIn("money.ts,line=2", r.stdout)

    def test_a_fragment_must_cover_every_float_use_on_its_line(self):
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "val ratioPct: Double? = netMtk.toDouble()\n"})
        r = self.scan(root, ["backend/x/src/main/kotlin/A.kt | val ratioPct: Double? | ok | pct"])
        self.assertEqual(1, r.returncode, r.stdout)
        r = self.scan(root, ["backend/x/src/main/kotlin/A.kt | val ratioPct: Double? = netMtk.toDouble() | finding | both"])
        self.assertEqual(0, r.returncode, r.stdout)

    def test_a_count_suffix_needs_exactly_that_many_identical_lines(self):
        line = "const abs = Math.abs(Math.trunc(mtk));\n"
        entry = "web/src/lib/m.ts | const abs = Math.abs(Math.trunc(mtk)); | ok x2 | integer"
        self.assertEqual(0, self.scan(self.tree({"web/src/lib/m.ts": line * 2}), [entry]).returncode)
        r = self.scan(self.tree({"web/src/lib/m.ts": line * 3}), [entry])
        self.assertEqual(1, r.returncode)
        self.assertIn("want exactly 2", r.stdout)

    def test_only_block_comment_continuations_are_skipped(self):
        root = self.tree({"backend/x/src/main/kotlin/A.kt": " * netMtk.toDouble() in a KDoc\n *\n */\n"})
        self.assertEqual(0, self.scan(root).returncode)
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "    *amounts.map { it.toDouble() }.toTypedArray()\n"})
        self.assertEqual(1, self.scan(root).returncode, "a spread operator line is code, not a comment")
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "/* x */ val t = netMtk.toDouble()\n"})
        self.assertEqual(1, self.scan(root).returncode, "code after a closed block comment")

    def test_exponent_and_underscore_divisors_are_caught(self):
        for rel, text in [
            ("backend/x/src/main/kotlin/A.kt", "val taka = netMtk / 1e3\n"),
            ("backend/x/src/main/kotlin/A.kt", "val lakh = 1e5; val n = grossMtk / lakh\n"),
            ("backend/x/src/main/kotlin/A.kt", "val c = netMtk / 1.5E6\n"),
            ("web/src/lib/x.ts", "const t = net_mtk / 1_000;\n"),
            ("web/src/lib/x.ts", "const t = net_mtk / 1e3;\n"),
        ]:
            with self.subTest(text=text):
                self.assertEqual(1, self.scan(self.tree({rel: text})).returncode, text)
        self.assertEqual(0, self.scan(self.tree({"web/src/lib/x.ts": "const t = net_mtk / 1_000n;\n"})).returncode, "BigInt")

    def test_kotlin_float_literals_in_money_arithmetic_are_caught(self):
        for text in ["val a = netMtk / 1000f\n", "val b = netMtk / 1_000.0\n", "val c = netMtk * 1e-3f\n", "val d = 0.5F * grossMtk\n"]:
            with self.subTest(text=text):
                self.assertEqual(1, self.scan(self.tree({"backend/x/src/main/kotlin/A.kt": text})).returncode, text)
        # UI sizes in a money-named file (core-ui Money.kt) are not money arithmetic.
        ui = "val r = CornerRadius(size.height / 2f)\nval s = if (big) 24f else 18f\n"
        self.assertEqual(0, self.scan(self.tree({"android/core-ui/src/main/kotlin/Money.kt": ui})).returncode)

    def test_a_ts_line_starting_with_a_star_is_code_outside_a_block_comment(self):
        code = "const taka = netMtk\n  * 0.001;\n"
        self.assertEqual(1, self.scan(self.tree({"web/src/lib/money.ts": code})).returncode, "a continued expression")
        comment = "/* how it works\n * netMtk / 1000 in prose\n */\nconst x = 1;\n"
        self.assertEqual(0, self.scan(self.tree({"web/src/lib/money.ts": comment})).returncode, "inside a block comment")

    def test_a_double_slash_inside_a_string_does_not_end_the_code(self):
        root = self.tree({"backend/x/src/main/kotlin/A.kt": 'val u = "http://x"; val t = netMtk.toDouble()\n'})
        self.assertEqual(1, self.scan(root).returncode)
        root = self.tree({"web/src/lib/x.ts": "const u = `https://x/${id}`; const t = parseFloat(amount);\n"})
        self.assertEqual(1, self.scan(root).returncode)
        root = self.tree({"backend/x/src/main/kotlin/A.kt": "val ok = 1L // netMtk.toDouble() is forbidden\n"})
        self.assertEqual(0, self.scan(root).returncode, "a real trailing comment is skipped")

    def test_paid_total_cash_and_collect_are_money_words(self):
        for text in ["val paid = x.toDouble()\n", "val totalDay = y.toDouble()\n", "val cash = z.toFloat()\n", "val collected: Double = 0.0\n"]:
            with self.subTest(text=text):
                self.assertEqual(1, self.scan(self.tree({"backend/x/src/main/kotlin/A.kt": text})).returncode, text)

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
        questions = {a["mtk"] for a in data["amounts"] if "question" in a}
        self.assertEqual({9_999_499_999, 9_999_999_499}, questions, "the scale-before-rounding cases are recorded questions")
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
