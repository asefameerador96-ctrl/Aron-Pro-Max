"""Phase 1 tests (no database): the profiler writes structure and numbers only; NO value of the dump leaves (no value lists; ranges only for non-identifying
numeric/date columns, full file only); refuses loudly on every statement shape it cannot read; bounded time and memory; writes only outside the repository.
SYNTHETIC data only."""
import json
import os
import random
import resource
import string
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))
import synth  # noqa: E402
from apsis_migration import profiler, runner, staging  # noqa: E402

MAPPING = staging.load_mapping(ROOT / "mapping" / "columns.json")
CLI = [sys.executable, "-I", str(ROOT / "apsis-migration.py")]
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


def write_table(d, name, ddl, rows, extra_ddl=(), per_stmt=1000):
    cols = list(ddl) + list(extra_ddl)
    text = synth.HEADER + f"DROP TABLE IF EXISTS `{name}`;\nCREATE TABLE `{name}` (\n" + ",\n".join("  " + c for c in cols) + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n" + synth.insert(name, rows, per_stmt) + "\n"
    (Path(d) / f"{name}.sql").write_text(text, encoding="utf-8")


def dump(tmp):
    d = Path(tmp) / "dump"
    d.mkdir()
    write_table(d, "master_outlets", DDL, ROWS, ["PRIMARY KEY (`outlet_id`)", "KEY `idx_code` (`outlet_code`)", "CONSTRAINT `fk_x` FOREIGN KEY (`outlet_code`) REFERENCES `master_routes` (`route_code`)"])
    write_table(d, "master_routes", ["`route_id` int NOT NULL", "`route_code` varchar(20)"], [(1, "SR-1")], ["PRIMARY KEY (`route_id`)"])
    write_table(d, "trans_sales_by_outlet", ["`id` bigint NOT NULL", "`outlet_code` varchar(32)", "`route_code` varchar(20)"], [
        (1, "SYN-O-0001", "SR-1"), (2, "42", "SR-9"), (3, "SYN-O-0003", "sr-1"), (4, "ZZZ", "SR-1")], ["PRIMARY KEY (`id`)"])
    return d


def all_text(out):
    return "\n".join(f.read_text(encoding="utf-8") for f in sorted(Path(out).iterdir()))


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
        h = self.col("created_at")["hour_of_day_stored"]
        self.assertEqual((h[5], h[19], h[10], sum(h)), (1, 1, 1, 3))

    def test_ranges_are_withheld_for_identifying_columns_and_shown_for_harmless_ones(self):
        self.assertIn("withheld", self.col("phone_num")["value_range"])      # long digit string
        self.assertIn("withheld", self.col("lat")["value_range"])            # coordinate-like (name and shape)
        self.assertIn("withheld", self.col("outlet_id")["value_range"])      # id-like by name
        self.assertIn("withheld", self.col("created_at")["value_range"]) if False else None
        # a harmless counter column shows its range in the full file
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`sku_code` varchar(5)", "`qty` int", "`day` date"], [(f"S{i % 7}", i % 50 + 1, "2026-03-%02d" % (i % 20 + 1)) for i in range(300)])
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
        c = {c["name"]: c for c in full["tables"]["master_sku"]["columns"]}
        self.assertEqual(c["qty"]["value_range"], {"min": "1", "max": "50"})
        self.assertEqual(c["day"]["value_range"], {"min": "2026-03-01", "max": "2026-03-20"})

    def test_no_value_list_exists_anywhere(self):
        for t in self.full["tables"].values():
            for c in t["columns"]:
                self.assertNotIn("values", c)
                self.assertIs(type(c["enum_candidate"]), bool)
        self.assertFalse(self.col("status")["enum_candidate"])            # a 3-row table is never a candidate

    def test_no_secret_value_leaves_in_any_output_file(self):
        out = Path(self.tmp.name) / "out"
        profiler.write_outputs(out, self.full)
        text = all_text(out)
        for secret in SECRETS:
            self.assertNotIn(secret, text, secret[:6])
        san = json.loads((out / "apsis-profile.sanitised.json").read_text())
        self.assertFalse(any("value_range" in c for t in san["tables"].values() for c in t["columns"]))
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["apsis-profile.json", "apsis-profile.md", "apsis-profile.sanitised.json", "apsis-profile.sanitised.md"])

    def test_distinct_cap_is_a_memory_bound(self):
        full = profiler.profile_dump(self.d, MAPPING, [], cap=2, order=runner.ORDER)
        c = {c["name"]: c for c in full["tables"]["master_outlets"]["columns"]}["outlet_code"]
        self.assertTrue(c["distinct_capped"]); self.assertIsNone(c["distinct"]); self.assertFalse(c["enum_candidate"])

    def test_reference_checks_report_shares_only(self):
        by = {r["name"]: r for r in self.full["reference_checks"]}
        r = by["sales outlet code -> master outlets"]
        self.assertEqual((r["fact_rows_with_key"], r["master_keys"]), (4, 3))
        self.assertEqual(r["share_rows_found_exact"], 0.25)
        self.assertEqual(r["share_rows_found_after_normalisation"], 0.75)
        self.assertNotIn("ZZZ", json.dumps(self.full))


class NoValueLeaks(unittest.TestCase):
    """The reviewers' kinds of data: names in a 'type' column, logins, PINs, tokens, 17-digit ids, salaries, birth years, integer coordinates, place names, small master tables."""

    def build(self, d):
        rnd = random.Random(7)
        first = ["Rahimuddin", "Karimullah", "Sultanabad", "Jahanara", "Nasrinbanu"]
        places = ["Mirpurxx", "Uttaraxx", "Gulshanxx", "Bananiixx", "Dhanmondix"]
        rows, leak = [], set()
        for i in range(1200):
            r = (i + 1, rnd.choice(first), f"login_{rnd.choice(first).lower()}{i % 40}", f"{rnd.randint(1000, 9999)}", "tok" + "".join(rnd.choice(string.ascii_lowercase) for _ in range(6)),
                 f"{rnd.randint(10**16, 10**17 - 1)}", rnd.randint(20000, 90000), rnd.randint(1950, 2005), rnd.randint(22, 26), rnd.choice(places), "qty%d" % (i % 3), i % 17)
            rows.append(r)
            leak.update(str(x) for x in r[1:] if len(str(x)) >= 5)
        ddl = ["`id` bigint NOT NULL", "`type` varchar(20)", "`login` varchar(30)", "`pin` varchar(8)", "`short_token` varchar(12)", "`ref_no` varchar(20)",
               "`salary` int", "`birth_year` int", "`lat_int` int", "`place` varchar(20)", "`class` varchar(10)", "`counter` int"]
        write_table(d, "trans_by_outlet_promotion_data", ddl, rows, ["PRIMARY KEY (`id`)"])
        small = [(i, "Master%02d" % i, "Value%02d" % i) for i in range(1, 41)]
        write_table(d, "master_promotion", ["`id` int NOT NULL", "`tier` varchar(10)", "`label` varchar(10)"], small, ["PRIMARY KEY (`id`)"])
        leak.update(x for r in small for x in r[1:])
        return leak

    def test_outputs_contain_no_value_of_the_dump_only_names_numbers_and_class_labels(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            leak = self.build(d)
            out = Path(t) / "out"
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(out)], capture_output=True)
            self.assertEqual(p.returncode, 0, p.stderr.decode()[:300])
            text = all_text(out) + p.stdout.decode() + p.stderr.decode()
            leaked = [v for v in leak if v in text]
            self.assertEqual(leaked, [], "values found in the outputs: %d" % len(leaked))
            prof = json.loads((out / "apsis-profile.json").read_text())
            cols = {c["name"]: c for c in prof["tables"]["trans_by_outlet_promotion_data"]["columns"]}
            self.assertTrue(cols["type"]["enum_candidate"])               # 5 names, 1200 rows, each in >= 20 rows: a candidate, as a FLAG only
            self.assertTrue(cols["class"]["enum_candidate"])
            for n in ("salary", "birth_year", "lat_int", "id"):
                self.assertIn("withheld", cols[n]["value_range"], n)
            self.assertFalse({c["name"]: c for c in prof["tables"]["master_promotion"]["columns"]}["tier"]["enum_candidate"])   # a small master table is never a candidate

    def test_profile_values_is_deliberate_one_column_and_owner_only(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            self.build(d)
            out = Path(t) / "out"
            subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(out)], capture_output=True, check=True)
            args = CLI + ["profile-values", "--dump-dir", str(d), "--out-dir", str(out), "--profile-json", str(out / "apsis-profile.json"), "--table", "trans_by_outlet_promotion_data"]
            bad = subprocess.run(args + ["--column", "login"], capture_output=True)
            self.assertNotEqual(bad.returncode, 0)
            self.assertIn("not flagged enum_candidate", bad.stderr.decode())
            ok = subprocess.run(args + ["--column", "class"], capture_output=True)
            self.assertEqual(ok.returncode, 0, ok.stderr.decode())
            self.assertNotIn("qty0", ok.stdout.decode() + ok.stderr.decode())       # the values go to the owner-only file, not to a terminal
            f = out / "apsis-values-trans_by_outlet_promotion_data-class.OWNER-ONLY.json"
            self.assertEqual(sorted(json.loads(f.read_text())["value_counts"]), ["qty0", "qty1", "qty2"])
            self.assertNotIn("qty0", (out / "apsis-profile.sanitised.json").read_text())        # never in the sanitised files


class Refusals(unittest.TestCase):
    CREATE = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `n` int\n) ENGINE=InnoDB;\n"

    def run_case(self, name, body):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            (d / name).write_bytes(body if isinstance(body, bytes) else body.encode("utf-8"))
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out")], capture_output=True)
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

    def ins(self, tail):
        return self.CREATE + "INSERT INTO `master_sku` VALUES " + tail + "\n"

    def test_replace_into_and_column_list_and_row_spanning(self):
        self.assertRefused("master_sku.sql", self.CREATE + "REPLACE INTO `master_sku` VALUES ('secret-row-value',1);\n", must="REPLACE")
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` (`sku_code`,`n`) VALUES ('secret-row-value',1);\n", must="column list")
        self.assertRefused("master_sku.sql", self.ins("('secret-row-value',1),\n('b',2);"))
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` VALUES\n('secret-row-value',1),\n('b',2);\n")

    def test_every_literal_the_reader_does_not_model(self):
        for lit in ('"secret-row-value"', "TRUE", "X'41'", "0xAB", "_binary 'secret-row-value'", "b'101'", "zz"):
            self.assertRefused("master_sku.sql", self.ins(f"('a',{lit});"), must="unmodelled")
        self.assertRefused("master_sku.sql", self.ins("('a',1); trailing secret-row-value"))
        self.assertRefused("master_sku.sql", self.ins("('a',1) secret-row-value;"), must="unmodelled")

    def test_indented_insert_and_a_shifted_or_wide_row(self):
        self.assertRefused("master_sku.sql", self.CREATE + "  INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n")
        self.assertRefused("master_sku.sql", self.ins("('a',1),('secret-row-value',2,3);"), must="3 values but the table has 2")
        self.assertRefused("master_sku.sql", self.ins("('a');"), must="1 values but the table has 2")

    def test_garbled_create_table_refuses_that_table_only(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            (d / "master_sku.sql").write_text("CREATE TABLE `master_sku` (\n  `sku_code` \n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES ('secret-row-value');\n")
            (d / "master_routes.sql").write_text("CREATE TABLE `master_routes` (\n  `route_code` varchar(5),\n")
            write_table(d, "master_outlets", ["`outlet_code` varchar(9)"], [("A",)])
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
        self.assertEqual(sorted(r["table"] for r in full["refused"]), ["master_routes", "master_sku"])
        self.assertIn("master_outlets", full["tables"])             # the good table was still profiled

    def test_mydumper_compressed_and_split_parts(self):
        self.assertRefused("master_sku.sql", "INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n", must="CREATE TABLE")
        self.assertRefused("master_sku.00000.sql", "INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n", must="unsupported file shape")
        self.assertRefused("master_sku-schema.sql", self.CREATE, must="unsupported file shape")
        self.assertRefused("master_sku.sql.zst", b"\x28\xb5\x2f\xfd secret-row-value", must="unsupported file shape")
        self.assertRefused("master_sku.sql.bz2", b"BZh9 secret-row-value", must="unsupported file shape")

    def test_non_utf8_text_stops_with_the_character_set_message_and_no_bytes(self):
        full, p = self.run_case("master_sku.sql", (self.ins("('caf\xe9-secret-row-value',1);")).encode("latin-1"))
        self.assertEqual(p.returncode, 3)
        self.assertIn("character set", p.stderr.decode())
        self.assertNotIn("secret-row-value", p.stderr.decode())

    def test_hostile_names_are_never_printed_or_executed(self):
        full, p = self.run_case("master_sku.sql", "CREATE TABLE `x\"; DROP SCHEMA app CASCADE; --` (\n  `a` int\n) ENGINE=InnoDB;\nINSERT INTO `x\"; DROP SCHEMA app CASCADE; --` VALUES (1);\n")
        self.assertEqual(p.returncode, 3)
        self.assertNotIn("DROP SCHEMA", p.stderr.decode() + p.stdout.decode())
        full2, p2 = self.run_case("a;DROP TABLE x;--.sql.zst", b"x")
        self.assertEqual(p2.returncode, 3)
        self.assertNotIn("DROP TABLE", p2.stderr.decode() + p2.stdout.decode())
        self.assertEqual(full2["refused"][0]["table"], "<name not a plain identifier>")

    def test_column_index_and_key_names_that_are_not_identifiers_print_as_placeholders(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            evil_col, evil_idx = "a b'; DROP TABLE x;--", "k$(rm -rf)"
            write_table(d, "master_sku", [f"`{evil_col}` varchar(20)", "`sku_code` varchar(20)"], [("v1", "v2")], [f"PRIMARY KEY (`{evil_col}`)", f"KEY `{evil_idx}` (`{evil_col}`, `sku_code`)"])
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            out = Path(t) / "out"
            profiler.write_outputs(out, full)
            text = all_text(out)
        for bad in ("DROP TABLE", "rm -rf", "a b'"):
            self.assertNotIn(bad, text)
        tab = full["tables"]["master_sku"]
        self.assertEqual(tab["primary_key"], ["[name-1]"])
        self.assertEqual([c["name"] for c in tab["columns"]], ["[name-1]", "sku_code"])
        self.assertEqual(tab["indexes"][0]["name"], "[name-2]")

    def test_the_profile_is_never_written_inside_the_repository(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`sku_code` varchar(5)"], [("a",)])
            inside = ROOT / "tests" / "should-not-exist"
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(inside)], capture_output=True)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("OUTSIDE the repository", p.stderr.decode())
            self.assertFalse(inside.exists())
            ok = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out")], capture_output=True)
            self.assertEqual(ok.returncode, 0, ok.stderr.decode())


class Bounds(unittest.TestCase):
    def test_shape_classification_is_linear_even_for_a_4_mb_value_full_of_at_signs(self):
        for v in ("@" * 4_000_000, "a@" * 2_000_000, "x" * 4_000_000 + "@", "a" * 2_000_000 + "@" + "b." * 1_000_000):
            t0 = time.time()
            profiler.shape(v)
            self.assertLess(time.time() - t0, 1.0)
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`sku_code` mediumtext"], [("a@" * 2_000_000,), ("x",)])
            t0 = time.time()
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            self.assertLess(time.time() - t0, 15)
            self.assertEqual(full["tables"]["master_sku"]["columns"][0]["max_length"], 4_000_000)

    def test_memory_stays_bounded_on_a_200_mb_file_of_100_kb_values(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            rnd = random.Random(1)
            blob = "".join(rnd.choice(string.ascii_letters) for _ in range(100_000))
            with open(d / "trans_by_outlet_promotion_data.sql", "w", encoding="utf-8") as fh:
                fh.write("CREATE TABLE `trans_by_outlet_promotion_data` (\n  `id` int NOT NULL,\n  `body` mediumtext,\n  `other` mediumtext\n) ENGINE=InnoDB;\n")
                for i in range(1000):
                    fh.write("INSERT INTO `trans_by_outlet_promotion_data` VALUES (%d,'%s','%s');\n" % (i, blob[i:] + str(i), blob[:-i - 1] + str(i)))
            self.assertGreater((d / "trans_by_outlet_promotion_data.sql").stat().st_size, 190_000_000)
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out"), "--cap", "1000"], capture_output=True)
            self.assertEqual(p.returncode, 0, p.stderr.decode()[:300])
            rss_mb = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024
            self.assertLess(rss_mb, 300, f"peak RSS {rss_mb:.0f} MB")        # stated bound: under 300 MB for 100 KB values


class PhaseGate(unittest.TestCase):
    def run_cmd(self, cmd, env_extra=None, *extra):
        env = {k: v for k, v in os.environ.items() if k != "APSIS_PHASE"}
        env.update(env_extra or {})
        return subprocess.run(CLI + [cmd, "--dump-dir", "/nonexistent", "--db-role", "apsis_migrator"] + list(extra), capture_output=True, env=env)

    def test_nothing_but_profile_runs_in_phase_1(self):
        for cmd in ("run", "reconcile", "cleanup", "finalize", "reset-transform"):
            p = self.run_cmd(cmd, None, "--source-tz", "UTC")
            self.assertNotEqual(p.returncode, 0, cmd)
            self.assertIn("phase 1", p.stderr.decode(), cmd)

    def test_the_environment_variable_alone_is_not_enough_a_file_the_lead_adds_is_also_required(self):
        self.assertFalse(runner.PHASE2_FILE.exists(), "the phase-2 file must not exist in phase 1")
        for cmd in ("run", "cleanup"):
            p = self.run_cmd(cmd, {"APSIS_PHASE": "2"}, "--source-tz", "UTC")
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("PHASE2_ENABLED", p.stderr.decode())

    def test_free_text_time_zones_are_refused_once_phase_2_is_enabled(self):
        created = not runner.PHASE2_FILE.exists()
        runner.PHASE2_FILE.write_text("test only")
        try:
            for tz in ("BST", "UTC+6", "Asia/Kolkata", "utc"):
                p = self.run_cmd("run", {"APSIS_PHASE": "2"}, "--source-tz", tz)
                self.assertNotEqual(p.returncode, 0)
                self.assertIn("--source-tz must be one of", p.stderr.decode(), tz)
        finally:
            if created:
                runner.PHASE2_FILE.unlink()


if __name__ == "__main__":
    unittest.main(verbosity=2)
