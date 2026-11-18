"""Phase 1 tests (no database): the profiler writes structure, counts, lengths, shapes and ranges, never a row; refuses loudly on every statement shape it
cannot read; writes only outside the repository. SYNTHETIC data only."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))
import synth  # noqa: E402
from apsis_migration import profiler, runner, staging  # noqa: E402

MAPPING = staging.load_mapping(ROOT / "mapping" / "columns.json")
SECRETS = ["Syn Person One", "Syn Person Two", "01711000001", "01711000002", "8801711000009", "syn.one@example.invalid", "syn.two@example.invalid",
           "নমুনা", "Zed Nickname", "SYN-HASH-NOT-A-REAL-HASH-0000", "SYN-FREE-TEXT-NOTE"]

DDL = ["`outlet_id` int NOT NULL", "`outlet_code` varchar(32)", "`owner_name` varchar(80)", "`mobile_no` varchar(20)", "`email` varchar(60)", "`status` varchar(10)",
       "`tier` varchar(12)", "`nickname` varchar(20)", "`lat` decimal(10,7)", "`created_at` datetime", "`flag` tinyint(1)", "`phone_num` bigint", "`bn_text` varchar(40)",
       "`pw` varchar(80)", "`note` text"]
ROWS = [
    (1, "SYN-O-0001", "Syn Person One", "01711000001", "syn.one@example.invalid", "active", "Gold", "Zed Nickname", "23.7800000", "2026-03-05 05:00:00", 1, 8801711000009, "নমুনা", "SYN-HASH-NOT-A-REAL-HASH-0000", "SYN-FREE-TEXT-NOTE"),
    (2, "0042", "Syn Person Two", "01711000002", "syn.two@example.invalid", "closed", "Diamond", "Zed Nickname", "23.8100000", "2026-03-05 19:30:00", 0, 8801711000009, "নমুনা", "SYN-HASH-NOT-A-REAL-HASH-0000", None),
    (3, "syn-o-0003", None, None, None, "active", "Gold", "Zed Nickname", "0.0000000", "2026-03-06 10:00:00", 1, 8801711000009, "", "SYN-HASH-NOT-A-REAL-HASH-0000", "SYN-FREE-TEXT-NOTE"),
]


def dump(tmp, **extra):
    d = Path(tmp) / "dump"
    d.mkdir()
    ddl = DDL + ["PRIMARY KEY (`outlet_id`)", "KEY `idx_code` (`outlet_code`)", "CONSTRAINT `fk_x` FOREIGN KEY (`outlet_code`) REFERENCES `master_routes` (`route_code`)"]
    text = synth.HEADER + "DROP TABLE IF EXISTS `master_outlets`;\nCREATE TABLE `master_outlets` (\n" + ",\n".join("  " + c for c in ddl) + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n" + synth.insert("master_outlets", ROWS) + "\n"
    (d / "master_outlets.sql").write_text(text, encoding="utf-8")
    (d / "master_routes.sql").write_text(synth.table_file("master_routes", ["`route_id` int NOT NULL", "`route_code` varchar(20)"], "route_id", [(1, "SR-1")]), encoding="utf-8")
    (d / "trans_sales_by_outlet.sql").write_text(synth.table_file("trans_sales_by_outlet", ["`id` bigint NOT NULL", "`outlet_code` varchar(32)", "`route_code` varchar(20)"], "id", [
        (1, "SYN-O-0001", "SR-1"), (2, "42", "SR-9"), (3, "SYN-O-0003", "sr-1"), (4, "ZZZ", "SR-1")]), encoding="utf-8")
    for name, body in extra.items():
        (d / name).write_bytes(body if isinstance(body, bytes) else body.encode("utf-8"))
    return d


class Profile(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.d = dump(self.tmp.name)
        self.refs = profiler.load_refs(ROOT / "mapping" / "profile_refs.json")
        self.full = profiler.profile_dump(self.d, MAPPING, self.refs, order=runner.ORDER)

    def col(self, name, table="master_outlets"):
        return {c["name"]: c for c in self.full["tables"][table]["columns"]}[name]

    def test_structure_keys_and_counts(self):
        t = self.full["tables"]["master_outlets"]
        self.assertEqual((t["rows"], t["engine"], t["declared_charset"], t["primary_key"]), (3, "InnoDB", "utf8mb4", ["outlet_id"]))
        self.assertEqual([i["kind"] for i in t["indexes"]], ["key", "foreign"])
        self.assertEqual(t["indexes"][1]["references"], "master_routes")
        self.assertEqual([c["name"] for c in t["columns"]][:3], ["outlet_id", "outlet_code", "owner_name"])
        self.assertTrue(self.full["ok"])

    def test_column_statistics_shapes_and_ranges(self):
        self.assertEqual(self.col("owner_name")["null_share"], round(1 / 3, 6))
        self.assertEqual(self.col("outlet_code")["distinct"], 3)
        self.assertEqual((self.col("outlet_code")["min_length"], self.col("outlet_code")["max_length"]), (4, 10))
        self.assertEqual(self.col("mobile_no")["shapes"], {"phone-like": 2})
        self.assertEqual(self.col("email")["shapes"], {"email-like": 2})
        self.assertEqual(self.col("bn_text")["shapes"], {"bengali": 2, "empty": 1})
        self.assertEqual(self.col("pw")["shapes"], {"alnum-code": 3})
        self.assertEqual(self.col("lat")["value_range"], {"min": "0.0000000", "max": "23.8100000"})
        self.assertEqual(self.col("created_at")["value_range"], {"min": "2026-03-05 05:00:00", "max": "2026-03-06 10:00:00"})
        h = self.col("created_at")["hour_of_day_stored"]
        self.assertEqual((h[5], h[19], h[10], sum(h)), (1, 1, 1, 3))
        self.assertIn("withheld", self.col("phone_num")["value_range"])       # a phone-like numeric column shows no min or max

    def test_values_only_for_enum_like_code_columns(self):
        self.assertEqual(self.col("status")["values"], {"active": 2, "closed": 1})
        self.assertEqual(self.col("tier")["values"], {"Gold": 2, "Diamond": 1})      # Capitalised words only because the column name says tier
        self.assertEqual(self.col("flag")["values"], {"1": 2, "0": 1})
        for name in ("owner_name", "mobile_no", "email", "nickname", "bn_text", "pw", "note", "outlet_code", "phone_num"):
            self.assertNotIn("values", self.col(name), name)

    def test_no_secret_value_leaves_in_any_output_file(self):
        out = Path(self.tmp.name) / "out"
        profiler.write_outputs(out, self.full)
        for f in out.iterdir():
            text = f.read_text(encoding="utf-8")
            for secret in SECRETS:
                self.assertNotIn(secret, text, f"{f.name} leaks {secret[:6]}")
        san = json.loads((out / "apsis-profile.sanitised.json").read_text())
        self.assertFalse(any("value_range" in c for t in san["tables"].values() for c in t["columns"]))
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["apsis-profile.json", "apsis-profile.md", "apsis-profile.sanitised.json", "apsis-profile.sanitised.md"])

    def test_distinct_cap_is_a_memory_bound(self):
        full = profiler.profile_dump(self.d, MAPPING, [], cap=2, order=runner.ORDER)
        c = {c["name"]: c for c in full["tables"]["master_outlets"]["columns"]}["outlet_code"]
        self.assertTrue(c["distinct_capped"]); self.assertIsNone(c["distinct"]); self.assertNotIn("values", c)

    def test_reference_checks_report_shares_only(self):
        by = {r["name"]: r for r in self.full["reference_checks"]}
        r = by["sales outlet code -> master outlets"]
        self.assertEqual((r["fact_rows_with_key"], r["master_keys"]), (4, 3))
        self.assertEqual(r["share_rows_found_exact"], 0.25)                         # only SYN-O-0001 matches the master spelling exactly
        self.assertEqual(r["share_rows_found_after_normalisation"], 0.75)           # '42' = '0042', 'syn-o-0003' = 'SYN-O-0003' after the rule
        self.assertEqual(by["sales route code -> master routes"]["share_rows_found_after_normalisation"], 0.75)
        self.assertNotIn("ZZZ", json.dumps(self.full))


class Refusals(unittest.TestCase):
    CREATE = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\n"

    def run_case(self, name, body):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            (d / name).write_bytes(body if isinstance(body, bytes) else body.encode("utf-8"))
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            out = Path(t) / "out"
            env = dict(os.environ)
            p = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), "profile", "--dump-dir", str(d), "--out-dir", str(out)], capture_output=True, env=env)
            return full, p

    def assertRefused(self, name, body, table="master_sku", must=None):
        full, p = self.run_case(name, body)
        self.assertFalse(full["ok"])
        self.assertEqual(full["refused"][0]["table"], table)
        self.assertEqual(p.returncode, 3, p.stderr.decode()[:300])
        self.assertIn(table, p.stderr.decode())
        if must:
            self.assertIn(must, p.stderr.decode())
        self.assertNotIn("secret-row-value", p.stderr.decode() + p.stdout.decode())

    def test_replace_into(self):
        self.assertRefused("master_sku.sql", self.CREATE + "REPLACE INTO `master_sku` VALUES ('secret-row-value');\n", must="REPLACE")

    def test_insert_with_a_column_list(self):
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` (`sku_code`) VALUES ('secret-row-value');\n", must="column list")

    def test_row_spanning_insert(self):
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` VALUES ('secret-row-value'),\n('b');\n")
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` VALUES\n('secret-row-value'),\n('b');\n")

    def test_mydumper_data_only_file_and_split_layout(self):
        self.assertRefused("master_sku.sql", "INSERT INTO `master_sku` VALUES ('secret-row-value');\n", must="CREATE TABLE")
        self.assertRefused("master_sku.00000.sql", "INSERT INTO `master_sku` VALUES ('secret-row-value');\n", must="unsupported file shape")
        self.assertRefused("master_sku-schema.sql", self.CREATE, must="unsupported file shape")

    def test_compressed_parts(self):
        self.assertRefused("master_sku.sql.zst", b"\x28\xb5\x2f\xfd secret-row-value", must="unsupported file shape")
        self.assertRefused("master_sku.sql.bz2", b"BZh9 secret-row-value", must="unsupported file shape")

    def test_non_utf8_text_stops_with_the_character_set_message_and_no_bytes(self):
        full, p = self.run_case("master_sku.sql", (self.CREATE + "INSERT INTO `master_sku` VALUES ('caf\xe9-secret-row-value');\n").encode("latin-1"))
        self.assertEqual(p.returncode, 3)
        self.assertIn("character set", p.stderr.decode())
        self.assertNotIn("secret-row-value", p.stderr.decode())

    def test_a_hostile_table_name_is_never_printed_or_executed(self):
        full, p = self.run_case("master_sku.sql", "CREATE TABLE `x\"; DROP SCHEMA app CASCADE; --` (\n  `a` int\n) ENGINE=InnoDB;\nINSERT INTO `x\"; DROP SCHEMA app CASCADE; --` VALUES (1);\n")
        self.assertEqual(p.returncode, 3)
        self.assertNotIn("DROP SCHEMA", p.stderr.decode() + p.stdout.decode())
        full2, p2 = self.run_case("a;DROP TABLE x;--.sql.zst", b"x")
        self.assertEqual(p2.returncode, 3)
        self.assertNotIn("DROP TABLE", p2.stderr.decode() + p2.stdout.decode())
        self.assertEqual(full2["refused"][0]["table"], "<name not a plain identifier>")

    def test_the_profile_is_never_written_inside_the_repository(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            (d / "master_sku.sql").write_text(synth.table_file("master_sku", ["`sku_code` varchar(5)"], None, [("a",)]))
            inside = ROOT / "tests" / "should-not-exist"
            p = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), "profile", "--dump-dir", str(d), "--out-dir", str(inside)], capture_output=True)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("OUTSIDE the repository", p.stderr.decode())
            self.assertFalse(inside.exists())
            ok = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), "profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out")], capture_output=True)
            self.assertEqual(ok.returncode, 0, ok.stderr.decode())


class PhaseGate(unittest.TestCase):
    def test_nothing_but_profile_runs_in_phase_1(self):
        for cmd in ("run", "reconcile", "cleanup", "finalize", "reset-transform"):
            env = {k: v for k, v in os.environ.items() if k != "APSIS_PHASE"}
            p = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), cmd, "--dump-dir", "/nonexistent", "--db-role", "apsis_migrator", "--source-tz", "UTC"], capture_output=True, env=env)
            self.assertNotEqual(p.returncode, 0, cmd)
            self.assertIn("phase 1", p.stderr.decode(), cmd)

    def test_free_text_time_zones_are_refused(self):
        env = dict(os.environ, APSIS_PHASE="2")
        for tz in ("BST", "UTC+6", "Asia/Kolkata", "utc"):
            p = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), "run", "--dump-dir", "/nonexistent", "--db-role", "apsis_migrator", "--source-tz", tz], capture_output=True, env=env)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("--source-tz must be one of", p.stderr.decode(), tz)


if __name__ == "__main__":
    unittest.main(verbosity=2)
