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


def assertProfiledWithTablesMissing(case, p):
    """The partial synthetic dumps here lack most of the 26 allow-listed tables: nothing refused, the missing tables listed, exit non-zero."""
    case.assertEqual(p.returncode, 3, p.stderr.decode()[:300])
    case.assertIn("MISSING", p.stderr.decode())
    case.assertNotIn("REFUSED", p.stderr.decode())


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
        self.assertFalse(self.full["refused"])

    def test_column_statistics_shapes_and_ranges(self):
        self.assertEqual(self.col("owner_name")["null_share"], round(1 / 3, 6))
        self.assertEqual((self.col("outlet_code")["min_length"], self.col("outlet_code")["max_length"]), (4, 10))
        self.assertEqual(self.col("mobile_no")["shapes"], {"phone-like": 2})
        self.assertEqual(self.col("email")["shapes"], {"email-like": 2})
        self.assertEqual(self.col("bn_text")["shapes"], {"bengali": 2, "empty": 1})
        self.assertEqual(self.col("pw")["shapes"], {"alnum-code": 3})
        self.assertEqual(self.col("created_at")["zero_dates"], 0)

    def test_small_tables_get_only_type_null_share_lengths_and_shapes(self):
        for c in self.full["tables"]["master_outlets"]["columns"]:                    # a 3-row table
            for k in ("number_descriptor", "year_month_range", "hour_of_day_stored"):
                self.assertNotIn(k, c, (c["name"], k))
            self.assertIsNone(c["distinct"])

    def test_large_tables_get_coarse_descriptors_never_an_exact_minimum_or_maximum(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            rows = [(f"S{i % 7}", i % 50 + 1, "2026-03-%02d" % (i % 20 + 1), "-%d.%03d" % (i % 9, i % 100), "2026-0%d-1%d 0%d:00:00" % (i % 3 + 1, i % 9, i % 9)) for i in range(1200)]
            write_table(d, "master_sku", ["`sku_code` varchar(5)", "`qty` int", "`day` date", "`delta` decimal(8,3)", "`at` datetime"], rows)
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            out = Path(t) / "out"
            profiler.write_outputs(out, full)
            text = all_text(out)
            san = (out / "apsis-profile.sanitised.json").read_text()
        c = {c["name"]: c for c in full["tables"]["master_sku"]["columns"]}
        self.assertEqual(c["qty"]["number_descriptor"], {"integer_digits": [1, 2], "sign": "non-negative", "kind": "integer", "decimal_places": None})
        self.assertEqual(c["delta"]["number_descriptor"], {"integer_digits": [1, 1], "sign": "mixed", "kind": "decimal", "decimal_places": [3, 3]})
        self.assertEqual(c["day"]["year_month_range"], ["2026-03", "2026-03"])
        self.assertEqual(c["at"]["year_month_range"], ["2026-01", "2026-03"])
        self.assertEqual(sum(c["at"]["hour_of_day_stored"]), 1200)          # the full file only ...
        self.assertNotIn("hour_of_day_stored", san)                         # ... never the sanitised one
        for exact in ('"min"', '"max"', "2026-03-01", "2026-03-20"):
            self.assertNotIn(exact, text)                                   # no exact minimum, maximum or date anywhere
        self.assertNotIn("number_descriptor", san); self.assertNotIn("year_month_range", san)       # the sanitised file has no descriptors at all

    def test_one_five_and_forty_row_tables_with_personal_numbers_and_dates_leak_nothing(self):
        for n in (1, 5, 40):
            with tempfile.TemporaryDirectory() as t:
                d = Path(t) / "d"
                d.mkdir()
                rows = [(f"P{i}", 19700000 + 31 * i, "1987-0%d-1%d" % (i % 9 + 1, i % 9), "gross_%d" % (7000000 + i * 13), 1700000000 + i * 977, i * 100003, 4821 + 17 * i) for i in range(n)]
                write_table(d, "master_sku", ["`sku_code` varchar(9)", "`dob_int` int", "`birthdate` date", "`gross` varchar(20)", "`epoch` int", "`scaled_lat` int", "`pin` int"], rows)
                full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
                out = Path(t) / "out"
                profiler.write_outputs(out, full)
                text = all_text(out)
            for r in rows:
                for v in r[1:]:
                    if len(str(v)) >= 4:                       # the text is searched AS IS (the row count is not stripped: that made n=1 vacuous); 4-digit values (PINs) too
                        self.assertNotIn(str(v), text, (n, v))
            self.assertNotIn("1987-", text)
            self.assertNotIn("number_descriptor", text)

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
        self.assertFalse(any(k in c for t in san["tables"].values() for c in t["columns"] for k in ("number_descriptor", "year_month_range")))
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["apsis-profile.json", "apsis-profile.md", "apsis-profile.sanitised.json", "apsis-profile.sanitised.md"])

    def test_distinct_cap_is_a_memory_bound(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`sku_code` varchar(12)"], [("C%05d" % i,) for i in range(1200)])
            full = profiler.profile_dump(d, MAPPING, [], cap=50, order=runner.ORDER)
        c = full["tables"]["master_sku"]["columns"][0]
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
            assertProfiledWithTablesMissing(self, p)
            text = all_text(out) + p.stdout.decode() + p.stderr.decode()
            leaked = [v for v in leak if v in text]
            self.assertEqual(leaked, [], "values found in the outputs: %d" % len(leaked))
            prof = json.loads((out / "apsis-profile.json").read_text())
            cols = {c["name"]: c for c in prof["tables"]["trans_by_outlet_promotion_data"]["columns"]}
            self.assertTrue(cols["type"]["enum_candidate"])               # 5 names, 1200 rows, each in >= 20 rows: a candidate, as a FLAG only
            self.assertTrue(cols["class"]["enum_candidate"])
            self.assertNotIn("number_descriptor", cols["id"])                   # near-unique: no descriptor
            self.assertNotIn("number_descriptor", cols["ref_no"])
            self.assertEqual(cols["counter"]["number_descriptor"]["integer_digits"], [1, 2])       # coarse only
            self.assertFalse({c["name"]: c for c in prof["tables"]["master_promotion"]["columns"]}["tier"]["enum_candidate"])   # a small master table is never a candidate

    def test_profile_values_rederives_the_test_asks_for_confirmation_and_is_owner_only(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            self.build(d)
            out = Path(t) / "out"
            base = CLI + ["profile-values", "--dump-dir", str(d), "--out-dir", str(out), "--table", "trans_by_outlet_promotion_data"]
            bad = subprocess.run(base + ["--column", "login", "--confirm-column", "login"], capture_output=True)
            self.assertNotEqual(bad.returncode, 0)
            self.assertIn("enum-candidate test", bad.stderr.decode())           # no profile file is trusted: login is refused
            self.assertFalse(out.exists())
            noconf = subprocess.run(base + ["--column", "class"], capture_output=True)
            self.assertNotEqual(noconf.returncode, 0)
            self.assertIn("--confirm-column", noconf.stderr.decode())
            wrong = subprocess.run(base + ["--column", "class", "--confirm-column", "type"], capture_output=True)
            self.assertNotEqual(wrong.returncode, 0)
            ok = subprocess.run(base + ["--column", "class", "--confirm-column", "class"], capture_output=True)
            self.assertEqual(ok.returncode, 0, ok.stderr.decode())
            self.assertIn("WARNING", ok.stderr.decode()); self.assertIn("class", ok.stderr.decode())          # a loud warning naming the column
            self.assertNotIn("qty0", ok.stdout.decode() + ok.stderr.decode())                                  # values only in the owner-only file
            f = out / "apsis-values-trans_by_outlet_promotion_data-class.OWNER-ONLY.json"
            self.assertEqual(sorted(json.loads(f.read_text())["value_counts"]), ["qty0", "qty1", "qty2"])
            self.assertEqual([p.name for p in out.iterdir()], [f.name])                                        # nothing else, never the sanitised files
            self.assertEqual(f.stat().st_mode & 0o777, 0o600)                                                  # owner-only
            self.assertIn("names, places, PINs and gender", ok.stderr.decode()); self.assertIn("PERSON must look", ok.stderr.decode())
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            profiler.write_outputs(out, full)                                                                  # the sanitised pair is now in that directory ...
            again = subprocess.run(base + ["--column", "class", "--confirm-column", "class"], capture_output=True)
            self.assertNotEqual(again.returncode, 0)                                                           # ... so the values file is refused there
            self.assertIn("DIFFERENT", again.stderr.decode())

    def test_a_small_table_never_yields_a_value_list_even_when_every_value_looks_like_a_code(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            self.build(d)
            p = subprocess.run(CLI + ["profile-values", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out"), "--table", "master_promotion", "--column", "tier", "--confirm-column", "tier"], capture_output=True)
            self.assertNotEqual(p.returncode, 0)


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
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` (`sku_code`,`zz`) VALUES ('secret-row-value',1);\n", must="column list")
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` (`sku_code`,`n`) VALUES ('secret-row-value',1,3);\n", must="column list names")
        self.assertRefused("master_sku.sql", self.ins("('secret-row-value',1),\n('b',2);"))
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` VALUES\n('secret-row-value',1),\n('b',2);\n")

    def test_every_literal_the_reader_does_not_model(self):
        for lit in ('"secret-row-value"', "TRUE", "X'41'", "zz"):
            self.assertRefused("master_sku.sql", self.ins(f"('a',{lit});"), must="unmodelled")
        for lit in ("0xAB", "_binary 'secret-row-value'", "b'101'"):               # binary literals are opaque, and only for columns declared binary
            self.assertRefused("master_sku.sql", self.ins(f"('a',{lit});"), must="not declared binary")
        self.assertRefused("master_sku.sql", self.ins("('a',1); trailing secret-row-value"))
        self.assertRefused("master_sku.sql", self.ins("('a',1) secret-row-value;"), must="unmodelled")

    def test_every_line_is_accounted_for(self):
        for body, must in (
                (self.CREATE + "/*!40101 INSERT INTO `master_sku` VALUES ('secret-row-value',1) */;\n", "does not recognise"),         # a conditional-comment INSERT
                (self.CREATE + "SET @a=1; INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n", "does not recognise"),            # INSERT preceded by anything
                (self.CREATE + "-- c\nINSERT INTO `master_sku` VALUES ('a',1); INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n", None),
                (self.CREATE + "UPDATE `master_sku` SET n = 1;\n", "does not recognise"), (self.CREATE + "DELETE FROM `master_sku`;\n", "does not recognise"),
                (self.CREATE + "ALTER TABLE `master_sku` ADD c int;\n", "does not recognise"), (self.CREATE + "TRUNCATE `master_sku`;\n", "does not recognise"),
                (self.CREATE + self.CREATE, "second CREATE TABLE"),
                (self.CREATE + "CREATE TABLE `master_routes` (\n `a` int\n) ENGINE=X;\n", "second CREATE TABLE"),
                ("CREATE TABLE `master_routes` (\n `a` int\n) ENGINE=X;\n", "not the file's name"),
                (self.ins("('a',1)('secret-row-value',2);"), "missing comma"), (self.ins("('a' 1);"), "unmodelled"),
                (self.ins("('a',1),,('secret-row-value',2);"), "misplaced comma"), (self.ins("('a',1),('secret-row-value',2),;"), "misplaced")):
            self.assertRefused("master_sku.sql", body, must=must)

    def test_a_trigger_body_is_skipped_unread_and_never_counted_as_rows(self):
        body = (self.CREATE + "INSERT INTO `master_sku` VALUES ('a',1);\nDELIMITER ;;\n"
                "CREATE TRIGGER t1 BEFORE INSERT ON `master_sku` FOR EACH ROW BEGIN INSERT INTO `master_sku` VALUES ('secret-row-value',9); UPDATE x SET y=1; END ;;\nDELIMITER ;\n"
                "INSERT INTO `master_sku` VALUES ('b',2);\n")
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            (d / "master_sku.sql").write_text(body)
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            out = Path(t) / "out"
            profiler.write_outputs(out, full)
            self.assertNotIn("secret-row-value", all_text(out))
        self.assertFalse(full["refused"])
        self.assertEqual(full["tables"]["master_sku"]["rows"], 2)           # the two real rows only: the trigger's INSERT is not a row
        self.assertRefused("master_sku.sql", self.CREATE + "DELIMITER ;;\nCREATE TRIGGER x ...\n", must="DELIMITER")      # an unclosed block refuses

    def test_the_real_mysqldump_8_directives_are_accepted(self):
        body = ("-- MySQL dump 10.13\n/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;\n/*!50503 SET NAMES utf8mb4 */;\nSET @saved = 1;\n\n"
                "DROP TABLE IF EXISTS `master_sku`;\n/*!40101 SET @saved_cs_client     = @@character_set_client */;\n" + self.CREATE.replace(");\n", ");\n") +
                "/*!40101 SET character_set_client = @saved_cs_client */;\nLOCK TABLES `master_sku` WRITE;\n/*!40000 ALTER TABLE `master_sku` DISABLE KEYS */;\n"
                "INSERT INTO `master_sku` VALUES ('a',1),('b',2);\n/*!40000 ALTER TABLE `master_sku` ENABLE KEYS */;\nUNLOCK TABLES;\n/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;\n-- Dump completed\n")
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            (d / "master_sku.sql").write_text(body)
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
        self.assertFalse(full["refused"], full["refused"])
        self.assertEqual(full["tables"]["master_sku"]["rows"], 2)

    def test_a_garbled_2_mb_line_of_escaped_quotes_is_refused_in_seconds(self):
        t0 = time.time()
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` VALUES ('a'," + "'" + "\\'" * 1_000_000 + ";\n", must="unterminated")
        self.assertLess(time.time() - t0, 20)
        t0 = time.time()
        self.assertRefused("master_sku.sql", self.CREATE + "INSERT INTO `master_sku` VALUES ('a','" + "''" * 1_000_000 + ";\n", must="unterminated")
        self.assertLess(time.time() - t0, 20)

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
        self.assertRefused("master_sku.00000.sql", "INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n", must="mydumper data chunk file")
        self.assertRefused("master_sku-schema.sql", self.CREATE, must="mydumper schema file")
        self.assertRefused("master_sku.sql.zst", b"\x28\xb5\x2f\xfd secret-row-value", must="compressed file")
        self.assertRefused("master_sku.sql.bz2", b"BZh9 secret-row-value", must="compressed file")

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
        self.assertEqual(full2["refused"][0]["table"], "<file not on the allow-list>")

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
        self.assertEqual(tab["indexes"][0]["columns"], ["[name-1]", "sku_code"])         # an index line with non-identifier names is shown with placeholders, not dropped

    def test_identifiers_with_a_long_digit_run_print_as_placeholders(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`col_01711000001` varchar(20)", "`ok_col` varchar(20)"], [("v", "w")])
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
        self.assertEqual([c["name"] for c in full["tables"]["master_sku"]["columns"]], ["[name-1]", "ok_col"])

    def test_the_profile_is_never_written_inside_the_repository(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`sku_code` varchar(5)"], [("a",)])
            inside = ROOT / "tests" / "should-not-exist"
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(inside)], capture_output=True)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn("OUTSIDE any git work tree", p.stderr.decode())
            self.assertFalse(inside.exists())
            ok = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out")], capture_output=True)
            self.assertEqual(ok.returncode, 3, ok.stderr.decode())          # one table of 26: the missing ones are listed and the exit is non-zero
            self.assertIn("MISSING", ok.stderr.decode())


PRE_57 = """-- MySQL dump 10.13  Distrib 5.7.44, for Linux (x86_64)
--
-- Host: localhost    Database: aron
-- ------------------------------------------------------
-- Server version	5.7.44-log

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED='aaaaaaaa-0000-0000-0000-000000000000:1-99';

"""
POST_57 = """
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;

-- Dump completed on 2026-11-18  3:00:00
"""
TRIGGER_SECTION = """/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8 */ ;
/*!50003 SET character_set_results = utf8 */ ;
/*!50003 SET collation_connection  = utf8_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/ /*!50003 TRIGGER `secret_trigger` BEFORE INSERT ON `master_sku` FOR EACH ROW BEGIN SET NEW.n = 'secret-row-value'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
"""
SKU_57 = """--
-- Table structure for table `master_sku`
--

DROP TABLE IF EXISTS `master_sku`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `master_sku` (
  `sku_code` varchar(20) DEFAULT NULL,
  `n` int(11) DEFAULT NULL,
  `raw` blob,
  `bits` bit(1) DEFAULT NULL,
  `gen` int(11) GENERATED ALWAYS AS (`n` + 1) VIRTUAL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `master_sku`
--

LOCK TABLES `master_sku` WRITE;
/*!40000 ALTER TABLE `master_sku` DISABLE KEYS */;
INSERT INTO `master_sku` (`sku_code`, `n`, `raw`, `bits`) VALUES ('a',1,_binary '\\0\\n\\'secret-row-value',b'1'),('b',2,0x41424344,NULL);
/*!40000 ALTER TABLE `master_sku` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Triggers for table `master_sku`
--

"""
SKU_80 = SKU_57.replace("/*!40101 SET character_set_client = utf8 */;", "/*!50503 SET character_set_client = utf8mb4 */;").replace("int(11)", "int").replace("DEFAULT CHARSET=utf8;", "DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;").replace(
    "VALUES ('a',1,_binary", "VALUES ('a',1,_binary").replace("utf8_general_ci", "utf8mb4_0900_ai_ci").replace("/*!50003 SET character_set_client  = utf8 */ ;", "/*!50003 SET character_set_client  = utf8mb4 */ ;")
PRE_80 = PRE_57.replace("5.7.44, for Linux (x86_64)", "8.0.36, for Linux (x86_64)").replace("5.7.44-log", "8.0.36").replace("SET NAMES utf8 */", "SET NAMES utf8mb4 */").replace(
    "/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;", "/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;") + "/*!50503 SET @OLD_CHARACTER_SET_CLIENT_X=@@character_set_client */;\n"


class RealFormats(unittest.TestCase):
    """Fixtures copied from the exact mysqldump 5.7 and 8.0 per-table and whole-database layouts (preamble, GTID, trigger section, postamble)."""

    def run_files(self, files, extra=()):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            for name, body in files.items():
                (d / name).write_bytes(body if isinstance(body, bytes) else body.encode("utf-8"))
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER, whole_database=bool(extra))
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out")] + list(extra), capture_output=True)
            text = all_text(Path(t) / "out") if (Path(t) / "out").exists() else ""
            return full, p, text

    def check_sku(self, full, p, text):
        self.assertFalse(full["refused"], full["refused"])
        c = {c["name"]: c for c in full["tables"]["master_sku"]["columns"]}
        self.assertEqual(full["tables"]["master_sku"]["rows"], 2)
        self.assertEqual(c["raw"]["shapes"], {"binary": 2}); self.assertEqual((c["raw"]["min_length"], c["raw"]["max_length"]), (4, 19))          # lengths in BYTES (never the bytes)
        self.assertEqual(c["bits"]["shapes"], {"binary": 1}); self.assertEqual(c["bits"]["null_share"], 0.5)
        self.assertEqual(c["gen"]["rows"], 0)                        # a generated column the INSERT does not name: nothing counted
        self.assertNotIn("secret-row-value", p.stderr.decode() + p.stdout.decode() + text)
        self.assertNotIn("secret_trigger", p.stderr.decode() + p.stdout.decode() + text)

    def test_mysqldump_5_7_per_table_file_with_trigger_section_gtid_binary_and_column_list(self):
        full, p, text = self.run_files({"master_sku.sql": PRE_57 + SKU_57 + TRIGGER_SECTION + POST_57})
        self.check_sku(full, p, text)

    def test_mysqldump_8_0_per_table_file(self):
        full, p, text = self.run_files({"master_sku.sql": PRE_80 + SKU_80 + TRIGGER_SECTION + POST_57})
        self.check_sku(full, p, text)

    def test_a_directive_that_is_not_a_set_is_still_refused(self):
        body = PRE_57 + SKU_57 + "/*!50003 DROP TABLE `master_sku` */ ;\n"
        full, p, _ = self.run_files({"master_sku.sql": body})
        self.assertTrue(full["refused"]); self.assertEqual(p.returncode, 3)

    def test_raw_non_utf8_bytes_are_opaque_in_a_blob_column_and_a_charset_refusal_in_a_text_column(self):
        c = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `raw` blob\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
        ok = (c + "INSERT INTO `master_sku` VALUES ('a','\xff\xfe\x80');\n").encode("latin-1")
        full, p, text = self.run_files({"master_sku.sql": ok})
        self.assertFalse(full["refused"], full["refused"])
        col = full["tables"]["master_sku"]["columns"][1]
        self.assertEqual((col["shapes"], col["min_length"], col["max_length"]), ({"binary": 1}, 3, 3))
        bad = (c + "INSERT INTO `master_sku` VALUES ('\xe9\xff','z');\n").encode("latin-1")
        full, p, _ = self.run_files({"master_sku.sql": bad})
        self.assertEqual(full["refused"][0].get("kind"), "charset")
        self.assertIn("not valid text in the stated character set", p.stderr.decode())

    def test_binary_literal_in_a_text_column_is_refused(self):
        c = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\n"
        for lit in ("0x4142", "_binary 'zz'", "b'1'"):
            full, p, _ = self.run_files({"master_sku.sql": c + f"INSERT INTO `master_sku` VALUES ({lit});\n"})
            self.assertTrue(full["refused"], lit)

    def test_partitioned_table_records_yes_and_the_count_never_the_bounds(self):
        multi = ("CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `n` int\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n"
                 "/*!50100 PARTITION BY RANGE (`n`)\n(PARTITION p_secret_bound VALUES LESS THAN (1234567) ENGINE = InnoDB,\n PARTITION p1 VALUES LESS THAN (7654321) ENGINE = InnoDB,\n"
                 " PARTITION pmax VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;\nINSERT INTO `master_sku` VALUES ('a',1);\n")
        full, p, text = self.run_files({"master_sku.sql": multi})
        self.assertFalse(full["refused"], full["refused"])
        t = full["tables"]["master_sku"]
        self.assertEqual((t["partitioned"], t["partitions"]), (True, 3))
        for secret in ("1234567", "7654321", "p_secret_bound"):
            self.assertNotIn(secret, text + p.stderr.decode())
        one = "CREATE TABLE `master_sku` (\n  `n` int\n) ENGINE=InnoDB /*!50100 PARTITION BY HASH (`n`) PARTITIONS 8 */;\nINSERT INTO `master_sku` VALUES (1);\n"
        full, _, _ = self.run_files({"master_sku.sql": one})
        self.assertEqual(full["tables"]["master_sku"]["partitions"], 8)

    def test_column_list_insert_maps_names_and_refuses_unknown_names_and_wrong_widths(self):
        c = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `n` int\n) ENGINE=InnoDB;\n"
        full, _, _ = self.run_files({"master_sku.sql": c + "INSERT INTO `master_sku` (`n`) VALUES (5),(6);\n"})
        cols = {x["name"]: x for x in full["tables"]["master_sku"]["columns"]}
        self.assertEqual((cols["n"]["rows"], cols["sku_code"]["rows"]), (2, 0))
        for bad in ("(`nope`) VALUES (1)", "(`n`,`n`) VALUES (1,2)", "(`n`) VALUES (1,2)", "(`sku_code`,`n`) VALUES ('x')"):
            full, p, _ = self.run_files({"master_sku.sql": c + f"INSERT INTO `master_sku` {bad};\n"})
            self.assertTrue(full["refused"], bad)
            self.assertNotIn("nope", p.stderr.decode() + p.stdout.decode())

    WHOLE = ("CREATE TABLE `master_routes` (\n  `route_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_routes` VALUES ('SR-1'),('SR-2');\n"
             "CREATE TABLE `secret_not_allowed` (\n  `a` varchar(9)\n) ENGINE=InnoDB;\nINSERT INTO `secret_not_allowed` VALUES ('secret-row-value');\n"
             "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES ('x');\n")

    def test_a_whole_database_file_is_read_one_table_per_create_section_when_asked(self):
        full, p, text = self.run_files({"everything.sql": PRE_57 + self.WHOLE + POST_57}, extra=["--whole-database"])
        self.assertFalse(full["refused"], full["refused"])
        self.assertEqual(sorted(full["tables"]), ["master_routes", "master_sku"])
        self.assertEqual(full["tables"]["master_routes"]["rows"], 2)
        self.assertEqual(full["tables_not_on_allow_list_in_whole_file"], 1)
        self.assertNotIn("secret", text + p.stderr.decode() + p.stdout.decode())
        full, p, _ = self.run_files({"everything.sql": PRE_57 + self.WHOLE + POST_57})          # without the switch the file is only counted, never read
        self.assertEqual(full["tables"], {})

    def test_whole_database_insert_must_target_the_current_table(self):
        body = self.WHOLE.replace("INSERT INTO `master_sku` VALUES ('x')", "INSERT INTO `master_routes` VALUES ('secret-row-value')")
        full, p, _ = self.run_files({"everything.sql": body}, extra=["--whole-database"])
        self.assertTrue(full["refused"]); self.assertNotIn("secret-row-value", p.stderr.decode() + p.stdout.decode())

    def test_missing_tables_are_listed_and_the_exit_is_non_zero(self):
        full, p, _ = self.run_files({"master_sku.sql": PRE_57 + SKU_57 + POST_57})
        self.assertIn("master_routes", full["tables_missing"]); self.assertEqual(p.returncode, 3)
        self.assertIn("MISSING", p.stderr.decode()); self.assertIn("master_routes", p.stderr.decode())

    def test_a_refused_file_name_is_printed_only_when_on_the_allow_list(self):
        full, p, text = self.run_files({"master_sku.sql.zst": b"x", "secret_customer_list.sql.zst": b"x", "another-secret.part1.sql": b"x"})
        out = p.stderr.decode() + text
        self.assertIn("master_sku", out); self.assertNotIn("secret_customer_list", out); self.assertNotIn("another-secret", out)
        self.assertEqual(full["refused_files_not_on_allow_list"], 2)

    def test_hours_and_descriptors_need_100_non_null_values_and_never_reach_the_sanitised_files(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            rows = [("S%d" % (i % 5), "2026-03-05 0%d:00:00" % (i % 9) if i < 99 else None, 777 if i == 0 else None, 1 if i < 100 else None, "2026-03-05 0%d:00:00" % (i % 9) if i < 100 else None) for i in range(1200)]
            write_table(d, "master_sku", ["`sku_code` varchar(5)", "`at99` datetime", "`one` int", "`n100` int", "`at100` datetime"], rows)
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER)
            profiler.write_outputs(Path(t) / "out", full)
            san = (Path(t) / "out" / "apsis-profile.sanitised.json").read_text()
        c = {c["name"]: c for c in full["tables"]["master_sku"]["columns"]}
        self.assertNotIn("hour_of_day_stored", c["at99"]); self.assertNotIn("year_month_range", c["at99"])        # 99 non-null: nothing
        self.assertNotIn("number_descriptor", c["one"])                                                         # a single non-null value: no descriptor
        self.assertIn("hour_of_day_stored", c["at100"])                                                          # 100 non-null: full file only
        self.assertNotIn("hour_of_day_stored", san)


C1 = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `n` int\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"


class _Runner:
    def run_files(self, files, extra=()):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            for name, body in files.items():
                (d / name).write_bytes(body if isinstance(body, bytes) else body.encode("utf-8"))
            full = profiler.profile_dump(d, MAPPING, [], order=runner.ORDER, whole_database=bool(extra))
            p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(Path(t) / "out")] + list(extra), capture_output=True)
            text = all_text(Path(t) / "out") if (Path(t) / "out").exists() else ""
            return full, p, text

    def one(self, body):
        return self.run_files({"master_sku.sql": body})


class LineAccounting(_Runner, unittest.TestCase):
    """Round 4: line accounting in skipped regions, silent skips, and the whole-database constructs."""

    def test_a_forged_delimiter_line_inside_a_string_does_not_end_the_block(self):
        body = (C1 + "INSERT INTO `master_sku` VALUES ('a',1);\nDELIMITER ;;\nCREATE TRIGGER t1 BEFORE INSERT ON `master_sku` FOR EACH ROW SET NEW.sku_code = 'x\nDELIMITER ;\n"
                "INSERT INTO `master_sku` VALUES ('secret-row-value',9);\n' ;;\nDELIMITER ;\n")
        full, p, text = self.one(body)
        self.assertFalse(full["refused"], full["refused"])
        self.assertEqual(full["tables"]["master_sku"]["rows"], 1)                      # the forged line and the INSERT inside the string were never read
        self.assertNotIn("secret-row-value", text + p.stderr.decode())

    def test_a_delimiter_block_that_ends_mid_statement_or_never_ends_is_refused(self):
        for tail in ("DELIMITER ;;\nCREATE TRIGGER t1 BEFORE INSERT ON x FOR EACH ROW SET a = 1\nDELIMITER ;\nINSERT INTO `master_sku` VALUES ('secret-row-value',1);\n",
                     "DELIMITER ;;\nDELIMITER ;\nINSERT INTO `master_sku` VALUES ('secret-row-value',1);\n",
                     "DELIMITER ;;\nCREATE TRIGGER t1 BEFORE INSERT ON x FOR EACH ROW SET a = 1 ;;\n",
                     "DELIMITER ;;\nCREATE TRIGGER t1 /* never closed ;;\nDELIMITER ;\n"):
            full, p, _ = self.one(C1 + tail)
            self.assertTrue(full["refused"], tail)
            self.assertNotIn("secret-row-value", p.stderr.decode() + p.stdout.decode())

    def test_partition_continuation_lines_must_match_the_grammar(self):
        head = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `n` int\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n/*!50100 PARTITION BY RANGE (`n`)\n"
        ok = head + "(PARTITION p0 VALUES LESS THAN (10) ENGINE = InnoDB,\n PARTITION p1 VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;\nINSERT INTO `master_sku` VALUES ('a',1);\n"
        full, _, _ = self.one(ok)
        self.assertEqual(full["tables"]["master_sku"]["partitions"], 2)
        full, _, _ = self.one(ok.replace("InnoDB) */;", "InnoDB)*/;"))
        self.assertFalse(full["refused"], full["refused"])
        for bad in ("(PARTITION p0 VALUES LESS THAN (10) ENGINE = InnoDB,\n INSERT INTO `master_sku` VALUES ('secret-row-value',1);\n PARTITION p1 VALUES LESS THAN MAXVALUE) */;\n",
                    "(PARTITION p0 VALUES LESS THAN (10),\n DROP TABLE x; PARTITION p1 VALUES LESS THAN MAXVALUE) */;\n"):
            full, p, _ = self.one(head + bad + "INSERT INTO `master_sku` VALUES ('a',1);\n")
            self.assertTrue(full["refused"]); self.assertNotIn("secret-row-value", p.stderr.decode())
        sub = head.replace("RANGE (`n`)", "RANGE (`n`) SUBPARTITION BY HASH (`n`) SUBPARTITIONS 2") + "(PARTITION p0 VALUES LESS THAN (10),\n PARTITION p1 VALUES LESS THAN MAXVALUE) */;\nINSERT INTO `master_sku` VALUES ('a',1);\n"
        full, _, _ = self.one(sub)
        self.assertEqual(full["tables"]["master_sku"]["partitions"], 2)
        cont = head.replace("RANGE (`n`)", "HASH (`n`)") + "PARTITIONS 6 */;\nINSERT INTO `master_sku` VALUES ('a',1);\n"
        full, _, _ = self.one(cont)
        self.assertEqual(full["tables"]["master_sku"]["partitions"], 6)

    def test_a_plain_comment_has_no_comment_end_inside(self):
        for line in ("/* c */ INSERT INTO `master_sku` VALUES ('secret-row-value',1); /* d */", "/* c */ SET @a=1; /* d */"):
            full, p, _ = self.one(C1 + line + "\n")
            self.assertTrue(full["refused"], line); self.assertNotIn("secret-row-value", p.stderr.decode())

    def test_a_schema_only_sql_next_to_a_tab_txt_and_a_sql_gz_pair_are_refused_not_skipped(self):
        full, p, _ = self.run_files({"master_sku.sql": C1, "master_sku.txt": "secret-row-value\t1\n"})
        self.assertEqual(full["refused"][0]["table"], "master_sku"); self.assertIn("--tab", full["refused"][0]["refused"]); self.assertEqual(p.returncode, 3)
        import gzip
        full, p, _ = self.run_files({"master_sku.sql": C1 + "INSERT INTO `master_sku` VALUES ('a',1);\n", "master_sku.sql.gz": gzip.compress((C1 + "INSERT INTO `master_sku` VALUES ('b',2);\n").encode())})
        self.assertEqual(full["refused"][0]["table"], "master_sku"); self.assertIn("two files", full["refused"][0]["refused"])
        self.assertNotIn("master_sku", full["tables"])

    def test_a_column_list_is_exactly_comma_separated_backticked_names(self):
        for lst in ("(`sku_code` , `n`)", "(`sku_code`;`n`)", "(sku_code,n)", "(`sku_code``x`,`n`)", "(`sku_code`,`n`,)"):
            full, _, _ = self.one(C1 + f"INSERT INTO `master_sku` {lst} VALUES ('a',1);\n")
            self.assertTrue(full["refused"], lst)
        full, _, _ = self.one(C1 + "INSERT INTO `master_sku` (`sku_code`,`n`) VALUES ('a',1);\nINSERT INTO `master_sku` (`sku_code`, `n`) VALUES ('b',2);\n")
        self.assertFalse(full["refused"], full["refused"])

    def test_a_multi_line_gtid_purged_header_is_a_named_construct(self):
        gtid = "SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ 'aaaaaaaa-0000-0000-0000-000000000000:1-99,\nbbbbbbbb-0000-0000-0000-000000000000:1-5';\n"
        full, _, _ = self.one(gtid + C1 + "INSERT INTO `master_sku` VALUES ('a',1);\n")
        self.assertFalse(full["refused"], full["refused"])
        full, p, _ = self.one("SET @@GLOBAL.GTID_PURGED='aaaaaaaa-0000:1-99,\nINSERT INTO `master_sku` VALUES ('secret-row-value',1);\n" + C1)
        self.assertTrue(full["refused"]); self.assertNotIn("secret-row-value", p.stderr.decode())

    ROCKS = ("/*!50717 SELECT COUNT(*) INTO @rocksdb_has_p_s_session_variables FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'performance_schema' AND TABLE_NAME = 'session_variables' */;\n"
             "/*!50717 SET @rocksdb_get_is_supported = IF (@rocksdb_has_p_s_session_variables, 'SELECT COUNT(*) FROM performance_schema.session_variables WHERE VARIABLE_NAME=\\'rocksdb_bulk_load\\'', 'SELECT 0') */;\n"
             "/*!50717 PREPARE s FROM @rocksdb_get_is_supported */;\n/*!50717 EXECUTE s */;\n/*!50717 DEALLOCATE PREPARE s */;\n"
             "/*!50717 SET @rocksdb_enable_bulk_load = IF (@rocksdb_is_supported, 'SET SESSION rocksdb_bulk_load = 1', 'SET @rocksdb_dummy_bulk_load = 0') */;\n")

    def test_the_mysql_8_rocksdb_preamble_lines_are_accepted(self):
        full, _, _ = self.one(self.ROCKS + C1 + "INSERT INTO `master_sku` VALUES ('a',1);\n")
        self.assertFalse(full["refused"], full["refused"])

    WHOLE_DB = ("CREATE DATABASE /*!32312 IF NOT EXISTS*/ `aron` /*!40100 DEFAULT CHARACTER SET utf8mb4 */ /*!80016 DEFAULT ENCRYPTION='N' */;\n\nUSE `aron`;\n"
                "DROP TABLE IF EXISTS `master_routes`;\nCREATE TABLE `master_routes` (\n  `route_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_routes` VALUES ('SR-1');\n"
                "--\n-- Temporary view structure for view `secret_view_name`\n--\n\nDROP TABLE IF EXISTS `secret_view_name`;\n/*!50001 DROP VIEW IF EXISTS `secret_view_name`*/;\n"
                "SET @saved_cs_client     = @@character_set_client;\nSET character_set_client = utf8;\n/*!50001 CREATE TABLE `secret_view_name` (\n  `a` int(11),\n  `b` varchar(5)\n) ENGINE=MyISAM */;\n"
                "SET character_set_client = @saved_cs_client;\n"
                "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES ('x');\n"
                "--\n-- Final view structure for view `secret_view_name`\n--\n\n/*!50001 DROP TABLE IF EXISTS `secret_view_name`*/;\n/*!50001 DROP VIEW IF EXISTS `secret_view_name`*/;\n"
                "/*!50001 SET @saved_cs_client          = @@character_set_client */;\n/*!50001 SET character_set_client      = utf8 */;\n"
                "/*!50001 CREATE ALGORITHM=UNDEFINED */\n/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */\n/*!50001 VIEW `secret_view_name` AS select `master_sku`.`sku_code` AS `a` from `master_sku` where `master_sku`.`sku_code` = 'secret-row-value' */;\n"
                "/*!50001 SET character_set_client      = @saved_cs_client */;\n"
                "--\n-- Dumping events for database 'aron'\n--\n/*!50106 SET @save_time_zone= @@TIME_ZONE */ ;\n/*!50106 DROP EVENT IF EXISTS `secret_event` */;\nDELIMITER ;;\n"
                "/*!50106 CREATE*/ /*!50117 DEFINER=`root`@`localhost`*/ /*!50106 EVENT `secret_event` ON SCHEDULE EVERY 1 DAY DO DELETE FROM master_sku */ ;;\nDELIMITER ;\n"
                "/*!50106 SET TIME_ZONE= @save_time_zone */ ;\n"
                "--\n-- Dumping routines for database 'aron'\n--\n/*!50003 DROP PROCEDURE IF EXISTS `secret_proc` */;\n/*!50003 SET @saved_cs_client      = @@character_set_client */ ;\nDELIMITER ;;\n"
                "CREATE DEFINER=`root`@`%` PROCEDURE `secret_proc`() BEGIN DELETE FROM `master_sku` WHERE sku_code = 'secret-row-value'; END ;;\nDELIMITER ;\n"
                "/*!50003 SET character_set_client  = @saved_cs_client */ ;\n")

    def test_whole_database_constructs_are_consumed_unread_and_never_profiled_or_printed(self):
        full, p, text = self.run_files({"all.sql": PRE_57 + self.WHOLE_DB + POST_57}, extra=["--whole-database"])
        self.assertFalse(full["refused"], full["refused"])
        self.assertEqual(sorted(full["tables"]), ["master_routes", "master_sku"])          # the view stand-in is NOT a table of the profile
        self.assertEqual(full["tables"]["master_sku"]["rows"], 1)
        out = text + p.stderr.decode() + p.stdout.decode()
        for secret in ("secret_view_name", "secret_event", "secret_proc", "secret-row-value", "aron"):
            self.assertNotIn(secret, out)

    def test_a_multi_line_view_body_or_stray_line_in_a_whole_database_file_is_refused_naming_the_right_table_and_the_remedy(self):
        bad = self.WHOLE_DB.replace("from `master_sku` where", "from `master_sku`\nwhere")
        full, p, _ = self.run_files({"all.sql": bad}, extra=["--whole-database"])
        self.assertTrue(full["refused"]); self.assertNotIn("secret-row-value", p.stderr.decode())
        two = ("CREATE TABLE `master_routes` (\n  `route_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_routes` VALUES ('SR-1');\n"
               "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\nUPDATE `master_sku` SET a = 'secret-row-value';\n")
        full, p, _ = self.run_files({"all.sql": two}, extra=["--whole-database"])
        self.assertEqual(full["refused"][0]["table"], "master_sku")                       # the section being read, not the previous one
        self.assertIn("mysqldump --skip-triggers", full["refused"][0]["refused"])
        self.assertIn("master_routes", full["tables"])
        self.assertNotIn("secret-row-value", p.stderr.decode() + p.stdout.decode())
        other = two.replace("`master_sku`", "`secret_other`").replace("INSERT INTO `master_routes` VALUES ('SR-1');\n", "")
        full, p, _ = self.run_files({"all.sql": other}, extra=["--whole-database"])
        self.assertTrue(full["refused"]); self.assertNotIn("secret_other", p.stderr.decode() + p.stdout.decode() + json.dumps(full))


class Round5Shapes(_Runner, unittest.TestCase):
    """The four genuine shapes of round 5, in the exact layouts mysqldump prints (the 8.0 form of a view stand-in, two spaces in RANGE  COLUMNS, multi-line
    SUBPARTITION clauses, ALTER DATABASE around routines), and the rule that a refusal between sections keeps the profile of a complete table."""
    ROUTES = "CREATE TABLE `master_routes` (\n  `route_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_routes` VALUES ('SR-1'),('SR-2');\n"
    SKU = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES ('x');\n"

    def whole(self, middle):
        return self.run_files({"all.sql": self.ROUTES + middle + self.SKU}, extra=["--whole-database"])

    def test_the_8_0_view_stand_in_in_both_layouts_and_the_5_7_table_form(self):
        multi = ("--\n-- Temporary view structure for view `secret_view`\n--\n\nSET @saved_cs_client     = @@character_set_client;\n/*!50503 SET character_set_client = utf8mb4 */;\n"
                 "/*!50001 CREATE VIEW `secret_view` AS SELECT\n 1 AS `a`,\n 1 AS `b`*/;\nSET character_set_client = @saved_cs_client;\n")
        single = "/*!50001 CREATE VIEW `secret_view` AS SELECT 1 AS `a`, 1 AS `b`*/;\n"
        table57 = ("DROP TABLE IF EXISTS `secret_view`;\n/*!50001 DROP VIEW IF EXISTS `secret_view`*/;\nSET @saved_cs_client     = @@character_set_client;\nSET character_set_client = utf8;\n"
                   "/*!50001 CREATE TABLE `secret_view` (\n  `a` tinyint NOT NULL,\n  `b` varchar(5) DEFAULT NULL\n) ENGINE=MyISAM */;\nSET character_set_client = @saved_cs_client;\n")
        for mid in (multi, single, table57):
            full, p, text = self.whole(mid)
            self.assertFalse(full["refused"], (mid[:40], full["refused"]))
            self.assertEqual(sorted(full["tables"]), ["master_routes", "master_sku"])
            self.assertNotIn("secret_view", text + p.stderr.decode() + json.dumps(full))

    def test_two_spaces_in_range_columns_and_list_columns_and_multi_line_subpartitions(self):
        c = "CREATE TABLE `master_sku` (\n  `sku_code` varchar(20),\n  `n` int\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n"
        ins = "INSERT INTO `master_sku` VALUES ('a',1);\n"
        for clause, n in (("/*!50500 PARTITION BY RANGE  COLUMNS(`n`,`sku_code`)\n(PARTITION p0 VALUES LESS THAN (10,'m') ENGINE = InnoDB,\n PARTITION p1 VALUES LESS THAN (MAXVALUE,MAXVALUE) ENGINE = InnoDB) */;\n", 2),
                          ("/*!50500 PARTITION BY LIST  COLUMNS(`n`)\n(PARTITION p0 VALUES IN (1,2,3) ENGINE = InnoDB,\n PARTITION p1 VALUES IN (4,5) ENGINE = InnoDB) */;\n", 2),
                          ("/*!50100 PARTITION BY RANGE (`n`)\nSUBPARTITION BY HASH (TO_DAYS(`n`))\nSUBPARTITIONS 2\n(PARTITION p0 VALUES LESS THAN (2000) ENGINE = InnoDB,\n"
                           " PARTITION p1 VALUES LESS THAN MAXVALUE\n (SUBPARTITION s0 ENGINE = InnoDB,\n  SUBPARTITION s1 ENGINE = InnoDB)) */;\n", 2)):
            full, p, text = self.one(c + clause + ins)
            self.assertFalse(full["refused"], (clause[:60], full["refused"]))
            self.assertEqual(full["tables"]["master_sku"]["partitions"], n)
            self.assertNotIn("MAXVALUE", text); self.assertNotIn("'m'", text)
        full, _, _ = self.one(c + "/*!50100 PARTITION BY RANGE (`n`)\n(PARTITION p0 VALUES LESS THAN (10) ENGINE = InnoDB,\n SUBPARTITION BY INSERT INTO x;\n PARTITION p1 VALUES LESS THAN MAXVALUE) */;\n" + ins)
        self.assertTrue(full["refused"])

    def test_alter_database_lines_around_routines(self):
        mid = ("ALTER DATABASE `aron` CHARACTER SET utf8 COLLATE utf8_general_ci ;\nDELIMITER ;;\nCREATE DEFINER=`root`@`%` PROCEDURE `secret_proc`() BEGIN SELECT 1; END ;;\nDELIMITER ;\n"
               "ALTER DATABASE `aron` CHARACTER SET latin1 COLLATE latin1_swedish_ci ;\n")
        full, p, text = self.whole(mid)
        self.assertFalse(full["refused"], full["refused"])
        self.assertNotIn("secret_proc", text + p.stderr.decode())
        full, _, _ = self.whole("ALTER DATABASE `aron` CHARACTER SET utf8 COLLATE utf8_general_ci DROP;\n")
        self.assertTrue(full["refused"])

    def test_a_refusal_between_sections_keeps_the_complete_tables_and_names_the_file_not_the_table(self):
        full, p, _ = self.whole("UPDATE `master_routes` SET route_code = 'secret-row-value';\n")
        self.assertEqual(sorted(full["tables"]), ["master_routes"])                         # the complete table keeps its profile; the unread rest is listed as missing
        self.assertEqual(full["tables"]["master_routes"]["rows"], 2)
        self.assertEqual(full["refused"][0]["table"], "<whole-database file>")
        self.assertIn("after the data of table master_routes", full["refused"][0]["refused"])
        self.assertIn("master_sku", full["tables_missing"])
        self.assertNotIn("secret-row-value", p.stderr.decode() + p.stdout.decode())

    def test_a_refusal_inside_a_section_drops_only_that_table(self):
        bad = "CREATE TABLE `master_field_forces` (\n  `a` int\n) ENGINE=InnoDB;\nINSERT INTO `master_field_forces` VALUES (1,2);\n"
        full, _, _ = self.whole(bad)
        self.assertEqual(full["refused"][0]["table"], "master_field_forces")
        self.assertIn("master_routes", full["tables"])


class OutDir(unittest.TestCase):
    def test_any_git_work_tree_is_refused_not_only_this_checkout(self):
        with tempfile.TemporaryDirectory() as t:
            other = Path(t) / "some-other-clone"
            (other / ".git").mkdir(parents=True)
            d = Path(t) / "d"
            d.mkdir()
            write_table(d, "master_sku", ["`sku_code` varchar(5)"], [("a",)])
            for out in (other / "out", other / "deep" / "er"):
                p = subprocess.run(CLI + ["profile", "--dump-dir", str(d), "--out-dir", str(out)], capture_output=True)
                self.assertNotEqual(p.returncode, 0)
                self.assertIn("git work tree", p.stderr.decode())
                self.assertFalse(out.exists())


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
            assertProfiledWithTablesMissing(self, p)
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
