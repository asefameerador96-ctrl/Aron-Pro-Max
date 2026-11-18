"""Tests with SYNTHETIC data only. Needs PostgreSQL 16 + psql: ARON_TEST_PG_URL (postgresql://user:pw@host:port/db, role with CREATEDB)
or the libpq environment (PGHOST, PGPORT, PGUSER). Builds one template database (db/migrations + provisional migrations) per run; every test gets
its own database cloned from it and runs the tool as the restricted role apsis_migrator (the owner is used only to arrange and to assert)."""
import copy
import gzip
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))
import synth  # noqa: E402
from apsis_migration import gates, keys, runner, safety, staging  # noqa: E402
from apsis_migration.db import Db, DbError  # noqa: E402
from apsis_migration.mysqldump import events, parse_values  # noqa: E402

REPO = ROOT.parent.parent
MIGRATIONS = REPO / "db" / "migrations"
PROVISIONAL = ROOT / "provisional-migrations"
LENIENT = {"max_skipped_share": 0.5, "max_reject_share": 0.5, "max_unmatched_outlet_rows_share": 0.5, "max_unmatched_sku_rows_share": 0.5,
           "max_no_scope_rows_share": 0.5, "accept_full_row_key": ["trans_by_outlet_promotion_data"]}


def conninfo(dbname):
    url = os.environ.get("ARON_TEST_PG_URL")
    if url:
        u = urlparse(url)
        parts = [f"dbname={dbname}", f"host={u.hostname}", f"port={u.port or 5432}"]
        if u.username:
            parts.append(f"user={u.username}")
        if u.password:
            parts.append(f"password={u.password}")
        return " ".join(parts)
    return f"dbname={dbname}"


def admin():
    return Db(conninfo("postgres"))


TEMPLATE = "apsis_tpl_%d" % os.getpid()


def setUpModule():
    a = admin()
    a.run(f"DROP DATABASE IF EXISTS {TEMPLATE}")
    a.run(f"CREATE DATABASE {TEMPLATE}")
    t = Db(conninfo(TEMPLATE))
    for f in sorted(MIGRATIONS.glob("V*.sql")) + sorted(PROVISIONAL.glob("V*.sql")):
        t.file(f)
    t.run(synth.MASTER_SQL)


def tearDownModule():
    admin().run(f"DROP DATABASE IF EXISTS {TEMPLATE}")


class Base(unittest.TestCase):
    dump_kwargs = {}
    thresholds = LENIENT

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dump = Path(cls.tmp.name) / "dump"
        synth.build(cls.dump, **cls.dump_kwargs)
        base = staging.load_mapping(ROOT / "mapping" / "columns.json")
        cls.mapping = dict(base, thresholds=cls.thresholds)
        prof = runner.profile(cls.dump, cls.mapping)
        cls.expected = {t: i["rows"] for t, i in prof["tables"].items()}
        cls.review = runner.suggest_review(prof)
        r = cls.review["tables"]["master_promotion"]          # a programme setting that merely looks like a credential: reviewed and kept
        r["drop"].remove("token_limit"); r["keep"].append("token_limit"); r["ack_keep_credential_like"] = ["token_limit"]

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.name = "apsis_t_%d_%s" % (os.getpid(), uuid.uuid4().hex[:8])
        admin().run(f"CREATE DATABASE {self.name} TEMPLATE {TEMPLATE}")
        self.owner = Db(conninfo(self.name))                          # arranges and asserts
        self.db = Db(conninfo(self.name), role="apsis_migrator")      # the job: restricted role
        self.addCleanup(lambda: admin().run(f"DROP DATABASE IF EXISTS {self.name} WITH (FORCE)"))

    def full_run(self, tz="UTC", qty_unit="base", **kw):
        rid = runner.start_run(self.db, tz, False, qty_unit)
        runner.stage_all(self.db, self.dump, self.mapping, self.review, rid, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid, tz, qty_unit, log=lambda m: None, **kw)
        return rid

    def recon(self, rid, expected=None):
        return runner.reconcile(self.db, rid, self.mapping, self.expected if expected is None else expected)

    def snapshot(self):
        q = """SELECT jsonb_build_object(
          'sales', (SELECT jsonb_build_object('n', count(*), 'h', md5(string_agg(row_uuid::text || business_date || qty_base || gross_mtk, ',' ORDER BY row_uuid))) FROM apsis_hist.sales_line),
          'geo', (SELECT count(*) FROM apsis_hist.visit_geo), 'att', (SELECT count(*) FROM apsis_hist.attendance),
          'summary', (SELECT count(*) FROM apsis_hist.route_day_vendor), 'stub', (SELECT count(*) FROM apsis_hist.outlet_stub),
          'raw', (SELECT count(*) FROM apsis_hist.raw_archive), 'outlet_route', (SELECT count(*) FROM apsis_hist.outlet_route), 'idmap', (SELECT count(*) FROM apsis_map.id_map))"""
        return json.loads(self.db.scalar(q))

    def not_ok(self, rep):
        return rep["problems"]


class Unit(unittest.TestCase):
    def test_parser_escapes_nulls_negatives_hex_and_doubled_quotes(self):
        rows = parse_values("(1,'a''b\\n\\\\',NULL,-2.50,0xAB),(2,'x,(y);',NULL,1e3,''),(3,'it\\'\\'s','p''''q');")
        self.assertEqual(rows[0], ("1", "a'b\n\\", None, "-2.50", "0xAB"))
        self.assertEqual(rows[1], ("2", "x,(y);", None, "1e3", ""))
        self.assertEqual(rows[2], ("3", "it''s", "p''q"))      # an escaped quote pair and a doubled pair each decode once

    def test_uuid_v5_is_the_standard_one_and_pinned(self):
        ns = uuid.uuid5(uuid.NAMESPACE_URL, "urn:aktcl:aron:apsis-migration:v1")
        self.assertEqual(keys.NAMESPACE, ns)
        self.assertEqual(keys.row_uuid("trans_sales_by_outlet", [42]), str(uuid.uuid5(ns, "trans_sales_by_outlet:42")))
        self.assertEqual(keys.row_uuid("master_outlets", [1]), "d88de838-cb1b-5ac0-980d-856b87da95ea")
        self.assertEqual(keys.row_uuid("trans_sales_by_outlet", [42]), "924697c4-3f6a-5768-86bd-3827d1b4a8dc")
        self.assertEqual(keys.row_uuid("x", [1]), keys.row_uuid("x", ["1"]))

    def test_name_patterns_only_suggest(self):
        self.assertEqual(staging.suggest("password_hash"), "credential")
        self.assertEqual(staging.suggest("owner_name"), "personal")
        self.assertEqual(staging.suggest("promotion_name"), "personal")       # suggested for the review; the review decides
        self.assertEqual(staging.suggest("outlet_code"), "keep")

    def test_gz_is_read(self):
        with tempfile.TemporaryDirectory() as t:
            d = synth.build(Path(t) / "d")
            with gzip.open(Path(t) / "master_sku.sql.gz", "wb") as g:
                g.write((d / "master_sku.sql").read_bytes())
            evs = list(events(Path(t) / "master_sku.sql.gz"))
            self.assertEqual(evs[0][1].name, "master_sku")
            self.assertEqual(sum(len(e[2]) for e in evs if e[0] == "rows"), 3)

    def test_safety_refuses_hostile_names(self):
        for bad in ('x"; DROP SCHEMA app CASCADE; --', "a b", "1abc", "", "a" * 70, "a;b"):
            with self.assertRaises(safety.Refused):
                safety.ident(bad)
        with self.assertRaises(safety.Refused):
            safety.table("pg_user", {"master_sku"})
        with self.assertRaises(safety.Refused):
            safety.chunk("2026-03'; --")
        self.assertEqual(safety.quote("master_sku"), '"master_sku"')

    def test_db_errors_carry_only_the_sqlstate(self):
        try:
            Db(conninfo("postgres")).run("SELECT 'secret-value-123'::int")
        except DbError as e:
            self.assertIn("22P02", str(e))
            self.assertNotIn("secret-value-123", str(e))
        else:
            self.fail("expected an error")

    def test_stray_text_never_aborts_the_helpers(self):
        a = admin()
        a.run("CREATE DATABASE apsis_fn_%d TEMPLATE %s" % (os.getpid(), TEMPLATE))
        try:
            d = Db(conninfo("apsis_fn_%d" % os.getpid()))
            self.assertEqual(d.scalar("SELECT apsis_map.mysql_date('0000-00-00') IS NULL AND apsis_map.mysql_date('2026-02-31') IS NULL AND apsis_map.mysql_date('') IS NULL"), "t")
            self.assertEqual(d.scalar("SELECT apsis_map.mysql_ts('0000-00-00 00:00:00', 'UTC') IS NULL AND apsis_map.mysql_ts('2026-13-01 00:00:00', 'UTC') IS NULL"), "t")
            self.assertEqual(d.scalar("SELECT apsis_map.num('abc') IS NULL AND apsis_map.num(' 12.50 ') = 12.5"), "t")
            self.assertEqual(d.scalar("SELECT apsis_map.norm_code(' 00123 ') = '123' AND apsis_map.norm_code('sy 01') = 'SY01' AND apsis_map.norm_code('000') = '0' AND apsis_map.norm_code('A007') = 'A007'"), "t")
            self.assertEqual(d.scalar("SELECT apsis_map.business_date('2026-03-05 18:00:00', 'UTC') = DATE '2026-03-06' AND apsis_map.business_date('2026-03-05', 'UTC') = DATE '2026-03-05'"), "t")
        finally:
            a.run("DROP DATABASE IF EXISTS apsis_fn_%d WITH (FORCE)" % os.getpid())


class DryRun(unittest.TestCase):
    def test_profile_counts_only_histogram_and_suggestions(self):
        with tempfile.TemporaryDirectory() as t:
            synth.build(Path(t) / "d")
            mapping = staging.load_mapping(ROOT / "mapping" / "columns.json")
            rep = runner.profile(Path(t) / "d", mapping)
        text = json.dumps(rep)
        for secret in ("SYN-O-0001", "Syn Owner", "00000000001", synth.PASSWORD_MARKER, synth.DEVICE_MARKER, "SYN-TOKEN-1", "SYN-M-1", "SYN-PROMO"):
            self.assertNotIn(secret, text)
        s = rep["tables"]["trans_sales_by_outlet"]
        self.assertEqual(s["rows"], 7)
        self.assertEqual(s["chunks"], {"2026-03": 4, "2026-04": 2, "unknown": 1})
        self.assertEqual(s["zero_dates"], 1)
        self.assertTrue(s["chunk_date_column_found"])
        self.assertEqual(sum(s["hour_histogram_stored"]), 6)            # the six rows with a usable time of day
        self.assertIn(s["time_zone_hint"]["likely"], ("utc", "dhaka_local", "unclear"))
        sug = {c["name"]: c["suggested"] for c in rep["tables"]["master_field_forces"]["columns"]}
        self.assertEqual(sug["password_hash"], "credential"); self.assertEqual(sug["ff_name"], "personal"); self.assertEqual(sug["ff_code"], "keep")
        self.assertIn("master_sku", rep["tables"]); self.assertGreater(len(rep["tables_missing"]), 10)

    def test_the_hour_histogram_tells_utc_from_dhaka_local(self):
        utc_like = [0, 0, 5, 9, 12, 12, 10, 8, 7, 6, 4, 3, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]       # active 02:00-14:00 as stored
        local_like = [0] * 8 + utc_like[2:14] + [0] * 4
        self.assertEqual(runner._hour_shares(utc_like)["likely"], "utc")
        self.assertEqual(runner._hour_shares(local_like)["likely"], "dhaka_local")

    def test_cli_profile_needs_no_database_and_writes_four_files_outside_the_repository(self):
        with tempfile.TemporaryDirectory() as t:
            synth.build(Path(t) / "d")
            out = Path(t) / "out"
            env = dict(os.environ, PGHOST="/nonexistent", PGPORT="1")
            p = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), "profile", "--dump-dir", str(Path(t) / "d"), "--out-dir", str(out)], capture_output=True, env=env)
            self.assertEqual(p.returncode, 0, p.stderr.decode()[:300])
            self.assertEqual(len(list(out.iterdir())), 4)
            self.assertIn("trans_sales_by_outlet", (out / "apsis-profile.json").read_text())

    def test_a_review_suggestion_comes_from_the_legacy_profile_function(self):
        with tempfile.TemporaryDirectory() as t:
            synth.build(Path(t) / "d")
            rev = runner.suggest_review(runner.profile(Path(t) / "d", staging.load_mapping(ROOT / "mapping" / "columns.json")))
            self.assertIn("password_hash", rev["tables"]["master_field_forces"]["drop"])


class ColumnReview(Base):
    def test_a_table_is_refused_when_a_column_is_unclassified(self):
        rv = copy.deepcopy(self.review)
        rv["tables"]["master_sku"]["keep"].remove("pack_size")
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises(staging.ReviewError) as cm:
            runner.stage_all(self.db, self.dump, self.mapping, rv, rid, only={"master_sku"}, log=lambda m: None)
        self.assertIn("unclassified", str(cm.exception))
        self.assertIsNone(runner._stage_count(self.db, "master_sku"))      # nothing was staged

    def test_a_credential_looking_column_in_keep_needs_an_explicit_ack(self):
        rv = copy.deepcopy(self.review)
        rv["tables"]["master_promotion"].pop("ack_keep_credential_like")
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises(staging.ReviewError):
            runner.stage_all(self.db, self.dump, self.mapping, rv, rid, only={"master_promotion"}, log=lambda m: None)

    def test_a_missing_date_column_stops_without_falling_back_to_created_at(self):
        m = copy.deepcopy(self.mapping)
        m["tables"]["trans_sales_by_outlet"]["chunk_date"] = ["no_such_column"]
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises(staging.ReviewError) as cm:
            runner.stage_all(self.db, self.dump, m, self.review, rid, only={"trans_sales_by_outlet"}, log=lambda x: None)
        self.assertIn("no fallback", str(cm.exception))


class HostileNames(Base):
    def test_a_table_name_inside_the_dump_is_data_not_sql(self):
        d = Path(self.tmp.name) / "hostile"
        d.mkdir()
        evil = 'x"; DROP SCHEMA app CASCADE; --'
        (d / "master_sku.sql").write_text(f"CREATE TABLE `{evil}` (\n  `sku_code` varchar(5)\n) ENGINE=InnoDB;\nINSERT INTO `{evil}` VALUES ('a');\n")
        (d / "a;DROP TABLE app.outlet;--.sql").write_text("CREATE TABLE `master_sku` (`sku_code` varchar(5));\n")
        files, refused = runner.find_files(d, set(self.mapping["allowed_tables"]))
        self.assertEqual(refused, 1)                      # the hostile file name is ignored (counted, never printed)
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises(safety.Refused):
            runner.stage_all(self.db, d, self.mapping, self.review, rid, log=lambda m: None)
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM pg_namespace WHERE nspname = 'app'"), "1")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'app' AND table_name = 'outlet'"), "1")

    def test_a_column_name_inside_the_dump_is_checked_too(self):
        d = Path(self.tmp.name) / "hostile2"
        d.mkdir()
        (d / "master_sku.sql").write_text('CREATE TABLE `master_sku` (\n  `a"; DROP TABLE app.sku; --` varchar(5)\n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES (\'a\');\n')
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises((safety.Refused, staging.ReviewError)):
            runner.stage_all(self.db, d, self.mapping, self.review, rid, log=lambda m: None)
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'app' AND table_name = 'sku'"), "1")


class FullRun(Base):
    def test_a_exact_money_quantities_and_dhaka_dates(self):
        self.full_run()
        got = [r.split("|") for r in self.db.run("SELECT business_date, outlet_code, sku_code, qty_base, gross_mtk, is_zero_sale, outlet_id IS NOT NULL, sku_id IS NOT NULL, "
                                                 "zone_id IS NOT NULL, user_id IS NOT NULL FROM apsis_hist.sales_line ORDER BY business_date, outlet_code, gross_mtk").strip().splitlines()]
        self.assertEqual(len(got), 5)        # 7 vendor rows: one exact duplicate collapses, one zero date is skipped (counted)
        by = {(r[0], r[1], r[2]): r for r in got}
        self.assertEqual(by[("2026-03-05", "SYN-O-0001", "SYN-SKU-1")][3:5], ["3", "123455"])     # 17:59:59 UTC = 23:59:59 Dhaka
        self.assertEqual(by[("2026-03-06", "SYN-O-0001", "SYN-SKU-2")][3:5], ["2", "1"])         # 18:00:00 UTC = next Dhaka day; 0.0005 taka = 0.5 mtk -> 1
        self.assertEqual(by[("2026-04-01", "SYN-O-0002", "SYN-SKU-1")][3:5], ["1", "-10000"])    # month end: chunk 2026-03, business date April
        self.assertEqual(by[("2026-04-02", "SYN-O-0002", "")][5], "t")
        self.assertEqual(by[("2026-04-03", "SYN-O-0003", "SYN-SKU-9")][6:], ["f", "f", "f", "t"])      # not in our master: ids NULL; the user matches
        self.assertEqual(by[("2026-03-05", "SYN-O-0001", "SYN-SKU-1")][9], "t")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line_default"), "0")

    def test_b_second_run_changes_nothing_also_after_wiping_control_and_staging(self):
        self.full_run()
        first = self.snapshot()
        self.full_run()
        self.assertEqual(self.snapshot(), first)
        self.owner.run("TRUNCATE apsis_ctl.chunk; DROP TABLE apsis_stage.trans_sales_by_outlet CASCADE")
        self.full_run()
        self.assertEqual(self.snapshot(), first)

    def test_c_duplicates_collapse_and_are_counted(self):
        rid = self.full_run()
        s = self.snapshot()
        self.assertEqual(s["sales"]["n"], 5)
        self.assertEqual(s["raw"], 19)      # every table that is not a typed fact is archived raw: masters 3+3+3+1+3+3, master_promotion 1, programme rows 2 (the exact duplicate collapsed; the zero-date row is rejected)
        self.assertEqual(self.db.scalar("SELECT count(*) - count(DISTINCT row_uuid) FROM apsis_hist.sales_line"), "0")
        rep = self.recon(rid)
        self.assertEqual(rep["tables"]["by_table"]["trans_sales_by_outlet"]["collapsed_duplicate_rows"], 1)
        self.assertEqual(rep["tables"]["by_table"]["trans_by_outlet_promotion_data"]["collapsed_duplicate_rows"], 1)

    def test_d_reviewed_columns_only_no_secret_anywhere_and_non_personal_stays_in_raw(self):
        self.full_run()
        for tbl, cols in (("master_field_forces", ("ff_name", "password_hash", "device_id", "api_token")), ("master_outlets", ("outlet_name", "owner_name", "mobile_no")),
                          ("trans_sales_by_outlet", ("remarks",))):
            have = self.db.run(f"SELECT column_name FROM information_schema.columns WHERE table_schema='apsis_stage' AND table_name='{tbl}'").split() if False else \
                self.owner.run(f"SELECT column_name FROM information_schema.columns WHERE table_schema='apsis_stage' AND table_name='{tbl}'").split()
            for c in cols:
                self.assertNotIn(c, have)
        dump = subprocess.run(["pg_dump", "-d", conninfo(self.name), "-n", "apsis_stage", "-n", "apsis_hist", "-n", "apsis_map", "-n", "apsis_ctl"], capture_output=True)
        self.assertEqual(dump.returncode, 0)
        text = dump.stdout.decode("utf-8", "replace")
        for secret in (synth.PASSWORD_MARKER, synth.DEVICE_MARKER, "SYN-TOKEN-1", "Syn Owner", "00000000001", "fine\\nline2"):
            self.assertFalse(secret in text, "found a synthetic secret: " + secret[:8])
        # the reviewed non-personal programme columns are in the raw archive, whole (token_limit was reviewed and kept)
        row = json.loads(self.db.scalar("SELECT data::text FROM apsis_hist.raw_archive WHERE vendor_table = 'master_promotion'"))
        self.assertEqual(row["promotion_code"], "SYN-PROMO-1"); self.assertEqual(row["token_limit"], 5)
        dropped = json.loads(self.db.scalar("SELECT value::text FROM apsis_ctl.report WHERE name = 'dropped_columns:master_field_forces'"))["dropped"]
        self.assertEqual(sorted(dropped), ["api_token", "device_id", "ff_name", "password_hash"])      # names only

    def test_e_the_job_role_cannot_touch_live_masters_or_transactions(self):
        before = self.owner.scalar("SELECT md5(string_agg(o::text, ',' ORDER BY id)) FROM app.outlet o")
        self.full_run()
        self.assertEqual(self.owner.scalar("SELECT md5(string_agg(o::text, ',' ORDER BY id)) FROM app.outlet o"), before)
        for sql in ("UPDATE apsis_ctl.setting SET value = '2026-12-31' WHERE key = 'history_until'", "UPDATE app.outlet SET lat = 1", "INSERT INTO app.wing (code, name) VALUES ('x', 'y')", "SELECT count(*) FROM app.memo", "SELECT owner_name FROM app.outlet",
                    "SELECT password_hash FROM app.app_user", "DELETE FROM app.route", "UPDATE apsis_ctl.run SET note = 'x'; DROP TABLE apsis_hist.sales_line"):
            with self.assertRaises(DbError, msg=sql) as cm:
                self.db.run(sql)
            self.assertIn("42501", str(cm.exception), sql)          # insufficient_privilege
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_hist.outlet_stub"), "1")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_map.id_map WHERE vendor_table='master_outlets'"), "2")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='apsis_hist' AND table_name='outlet_stub' AND column_name ~ 'name|phone|mobile'"), "0")

    def test_f_reconciliation_passes_with_numbers_only(self):
        rep = self.recon(self.full_run())
        self.assertTrue(rep["ok"], "; ".join(rep["problems"]))
        self.assertEqual(rep["daily_money"]["days_differing"], 0)
        self.assertEqual(rep["daily_money"]["total_loaded_mtk"], 123455 + 1 - 10000 + 7000)
        self.assertEqual(rep["tables"]["by_table"]["trans_sales_by_outlet"]["rows_skipped"], 1)      # the zero-date row, counted
        self.assertEqual(rep["time_zone"]["route_days_equal"], 2)
        text = json.dumps(rep)
        for secret in ("SYN-O-0001", "SYN-SKU", "SY01", "Syn Owner", "SYN-M-"):
            self.assertNotIn(secret, text)

    def test_g_history_is_invisible_to_the_sync_roles_even_after_the_grant_refresh(self):
        self.full_run()
        self.owner.run("SELECT app.apply_db_role_grants()")        # the routine refresh that every grant-touching migration calls
        views = ["dw.v_hist_sales_line", "dw.v_hist_sales_day", "dw.v_hist_visit_geo", "dw.v_hist_attendance", "dw.v_sales_with_history"]
        for role in ("api_rw", "worker_rw", "jobs_rw"):
            for rel in views + ["apsis_hist.sales_line", "apsis_hist.attendance", "apsis_ctl.chunk", "apsis_map.id_map"]:
                for priv in ("SELECT", "INSERT", "UPDATE", "DELETE"):
                    self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('{role}', '{rel}', '{priv}')"), "f", f"{role} {priv} {rel}")
            for sch in ("apsis_hist", "apsis_stage", "apsis_ctl", "apsis_map"):
                self.assertEqual(self.owner.scalar(f"SELECT has_schema_privilege('{role}', '{sch}', 'USAGE')"), "f", f"{role} {sch}")
        for v in ("dw.v_hist_sales_line", "dw.v_hist_sales_day", "dw.v_hist_visit_geo", "dw.v_hist_attendance", "dw.v_sales_with_history"):
            self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('hist_ro', '{v}', 'SELECT')"), "t", f"hist_ro {v}")     # the scoped reader of history
            self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('hist_ro', '{v}', 'INSERT')"), "f", f"hist_ro {v}")
        for role in ("web_ro", "bi_reader"):
            for v in ("dw.v_hist_sales_line", "dw.v_hist_sales_day", "dw.v_sales_with_history"):
                self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('{role}', '{v}', 'SELECT')"), "t", f"{role} {v}")
            for v in ("dw.v_hist_visit_geo", "dw.v_hist_attendance"):
                self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('{role}', '{v}', 'SELECT')"), "f", f"{role} {v}")
            self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('{role}', 'apsis_hist.sales_line', 'SELECT')"), "f")
            self.assertEqual(self.owner.scalar(f"SELECT has_table_privilege('{role}', 'dw.v_hist_sales_line', 'INSERT')"), "f")

    def test_h_views_carry_scope_columns_and_join_history_to_live(self):
        self.full_run()
        for v in ("v_hist_sales_line", "v_hist_sales_day", "v_hist_visit_geo", "v_hist_attendance", "v_sales_with_history"):
            cols = self.owner.run(f"SELECT column_name FROM information_schema.columns WHERE table_schema='dw' AND table_name='{v}' ORDER BY ordinal_position").split()
            for c in ("zone_id", "territory_id", "division_id", "wing_id"):
                self.assertIn(c, cols, f"{v} lacks {c}")
            self.assertEqual(cols[-2:], ["data_origin", "is_effective"], v)
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM dw.v_hist_attendance WHERE zone_id IS NOT NULL OR user_id IS NULL"), "0")      # SY01 has no home zone: user matched, scope NULL
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM dw.v_sales_with_history WHERE source = 'apsis'"), "5")
        self.owner.run("UPDATE apsis_ctl.setting SET value = '2026-03-31' WHERE key = 'history_until'")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM dw.v_sales_with_history WHERE source = 'apsis' AND business_date > DATE '2026-03-31'"), "0")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM dw.v_hist_sales_line WHERE NOT is_effective"), "0")

    def test_i_pack_unit_switch_multiplies_by_pack_factor(self):
        rid = self.full_run(qty_unit="pack")
        self.assertEqual(self.db.scalar("SELECT qty_base FROM apsis_hist.sales_line WHERE sku_code='SYN-SKU-1' AND outlet_code='SYN-O-0001'"), "60")      # 3 packs x 20
        self.assertTrue(self.recon(rid)["ok"])

    def test_j_fill_needs_the_time_boxed_grant_fills_only_null_and_undoes_exactly(self):
        with self.assertRaises(DbError):             # no grant: the job cannot write a live master
            self.full_run(fill_locations=True, confirm_locations=True)
        self.owner.run("SELECT apsis_ctl.allow_fill_locations()")
        self.owner.run("UPDATE app.outlet SET lat = 23.5, lng = 90.5, location_basis = 'provisional' WHERE code = 'SYN-O-0004'")
        self.full_run(fill_locations=True, confirm_locations=True)
        self.owner.run("SELECT apsis_ctl.disallow_fill_locations()")
        r = dict(l.split("|") for l in self.owner.run("SELECT code, coalesce(lat::text,'null') FROM app.outlet").split())
        self.assertEqual(r["SYN-O-0001"], "23.78")
        self.assertEqual(r["SYN-O-0002"], "null")        # 0.0 / 0.0 is outside the Bangladesh box
        self.assertEqual(r["SYN-O-0004"], "23.5")        # a field-captured (provisional) point is never replaced
        self.assertEqual(self.owner.scalar("SELECT location_confirmed FROM app.outlet WHERE code='SYN-O-0001'"), "t")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_map.fill_log"), "1")
        self.owner.run("UPDATE app.outlet SET lat = 23.99 WHERE code = 'SYN-O-0001'")      # someone corrected it after the fill
        self.owner.run("UPDATE app.outlet o SET lat = f.prev_lat, lng = f.prev_lng, location_basis = f.prev_location_basis, location_confirmed = COALESCE(f.prev_location_confirmed, false) "
                       "FROM apsis_map.fill_log f WHERE f.outlet_id = o.id AND o.lat IS NOT DISTINCT FROM f.new_lat AND o.lng IS NOT DISTINCT FROM f.new_lng")
        self.assertEqual(self.owner.scalar("SELECT lat FROM app.outlet WHERE code='SYN-O-0001'"), "23.99")      # the undo leaves the later correction alone

    def test_k_time_zone_is_evidence_for_the_owner_not_an_automatic_gate(self):
        rep = self.recon(self.full_run(tz="UTC"))
        tz = rep["time_zone"]
        self.assertTrue(tz["informational"] and tz["owner_signs_the_zone"])
        self.assertEqual((tz["chosen_zone"], tz["vendor_route_days"], tz["route_days_equal"]), ("UTC", 2, 2))
        self.assertTrue(tz["geo_has_route_column"])
        # the tool never decides or switches the zone: it refuses to run without an explicit one
        os.environ["APSIS_PHASE"] = "2"
        self.addCleanup(lambda: os.environ.pop("APSIS_PHASE", None))
        for argv in (["run", "--dump-dir", str(self.dump), "--db-role", "apsis_migrator"],
                     ["run", "--dump-dir", str(self.dump), "--db-role", "apsis_migrator", "--source-tz", "UTC", "--dsn", "dbname=x password=secret"]):
            with self.assertRaises(SystemExit) as cm:
                runner.main(argv)
            self.assertIn("refused", str(cm.exception))

    def test_l_settings_cannot_change_silently_while_verified_chunks_exist(self):
        self.full_run(tz="UTC")
        with self.assertRaises(ValueError):
            runner.start_run(self.db, "Asia/Dhaka", False, "base")
        with self.assertRaises(ValueError):
            runner.start_run(self.db, "UTC", False, "pack")
        runner.reset_transform(self.db, self.mapping)
        runner.start_run(self.db, "Asia/Dhaka", False, "base")      # allowed after the explicit reset

    def test_m_vendor_total_mismatch_and_strict_thresholds_fail_the_report(self):
        rid = self.full_run()
        bad = dict(self.expected, trans_sales_by_outlet=self.expected["trans_sales_by_outlet"] + 1)
        rep = self.recon(rid, expected=bad)
        self.assertFalse(rep["ok"])
        self.assertTrue(any("vendor's count" in p for p in rep["problems"]))
        self.assertFalse(self.recon(rid, expected={})["ok"])          # no expected counts at all is not a pass
        strict = dict(self.mapping, thresholds={"accept_full_row_key": []})
        rep = runner.reconcile(self.db, rid, strict, self.expected)
        self.assertFalse(rep["ok"])
        txt = " | ".join(rep["problems"])
        self.assertIn("skipped share", txt); self.assertIn("without outlet match", txt); self.assertIn("no primary key", txt)

    def test_n_staging_lost_after_a_restart_is_detected_never_verified_as_empty(self):
        rid = runner.start_run(self.db, "UTC", False)
        runner.stage_all(self.db, self.dump, self.mapping, self.review, rid, log=lambda m: None)
        self.db.run("TRUNCATE apsis_stage.trans_sales_by_outlet")        # what an UNLOGGED table looks like after a crash or failover
        with self.assertRaises(DbError) as cm:
            runner.transform_all(self.db, self.mapping, rid, "UTC", "base", log=lambda m: None)
        self.assertIn("differ from the recorded count", str(cm.exception))
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "0")
        rid2 = runner.start_run(self.db, "UTC", False)                    # the same command again: re-stages from the dump
        runner.stage_all(self.db, self.dump, self.mapping, self.review, rid2, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid2, "UTC", "base", log=lambda m: None)
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "5")
        self.assertTrue(self.recon(rid2)["ok"])

    def test_o_cleanup_needs_a_passing_reconciliation_and_the_owners_signoff_of_every_open_question(self):
        rid = self.full_run()
        so = Path(self.tmp.name) / "signoff.json"
        full = dict({f"q{n}": "waived" for n in range(21, 34)}, signed_by="owner")
        so.write_text(json.dumps(full))
        with self.assertRaises(DbError):
            runner.cleanup(self.db, so)                 # no passing report yet
        self.assertTrue(self.recon(rid)["ok"])
        for bad in ({}, dict(full, q25="open"), {k: v for k, v in full.items() if k != "q30"}, dict(full, signed_by="")):
            so.write_text(json.dumps(bad))
            with self.assertRaises(DbError):
                runner.cleanup(self.db, so)             # an open or missing question blocks the deletion of the staging copy
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='apsis_stage'") != "0", True)
        so.write_text(json.dumps(full))
        runner.cleanup(self.db, so)
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='apsis_stage'"), "0")
        rid2 = runner.start_run(self.db, "UTC", False)          # a later run still works: control rows say verified, nothing to re-stage
        runner.stage_all(self.db, self.dump, self.mapping, self.review, rid2, log=lambda m: None)

    def test_t_only_two_time_zones_are_accepted(self):
        for tz in ("BST", "UTC+6", "Asia/Kolkata", "utc", ""):
            with self.assertRaises(ValueError):
                runner.start_run(self.db, tz, False)
        runner.start_run(self.db, "Asia/Dhaka", False)

    def test_u_a_passing_report_is_invalidated_by_a_later_run_a_reset_or_a_rollback(self):
        rid = self.full_run()
        so = Path(self.tmp.name) / "signoff2.json"
        so.write_text(json.dumps(dict({f"q{n}": "waived" for n in range(21, 34)}, signed_by="owner")))
        self.assertTrue(self.recon(rid)["ok"])
        runner.start_run(self.db, "UTC", False)                      # a later run exists: the earlier report no longer describes the current state
        with self.assertRaises(DbError):
            runner.cleanup(self.db, so)
        rid2 = int(self.db.scalar("SELECT max(id) FROM apsis_ctl.run"))
        self.assertTrue(self.recon(rid2)["ok"])                      # a fresh reconciliation of the current state is accepted
        runner.reset_transform(self.db, self.mapping)                # a reset changes the checkpoints: the report is stale again
        with self.assertRaises(DbError):
            runner.cleanup(self.db, so)
        self.owner.run("SELECT apsis_hist.reset_history()")          # a rollback removes the report altogether
        with self.assertRaises(DbError):
            runner.cleanup(self.db, so)

    def test_p_a_run_that_covered_only_some_tables_cannot_be_signed(self):
        rid = runner.start_run(self.db, "UTC", False, note="only=master_sku")
        runner.stage_all(self.db, self.dump, self.mapping, self.review, rid, only={"master_sku", "master_routes", "master_outlets"}, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid, "UTC", "base", only={"master_sku", "master_routes", "master_outlets"}, log=lambda m: None)
        rep = self.recon(rid)
        self.assertFalse(rep["ok"])
        self.assertTrue(any("expected but never loaded" in p for p in rep["problems"]))
        self.assertFalse(rep["tables"]["by_table"]["trans_sales_by_outlet"]["loaded"])

    def test_q_an_ignored_file_in_the_dump_folder_fails_the_sign_off(self):
        d = Path(self.tmp.name) / "withextra"
        d.mkdir()
        for f in self.dump.iterdir():
            (d / f.name).write_bytes(f.read_bytes())
        (d / "unexpected_table.sql").write_text("CREATE TABLE `unexpected_table` (`a` int);\n")
        rid = runner.start_run(self.db, "UTC", False)
        runner.stage_all(self.db, d, self.mapping, self.review, rid, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid, "UTC", "base", log=lambda m: None)
        rep = self.recon(rid)
        self.assertFalse(rep["ok"])
        self.assertTrue(any("not on the table allow-list" in p for p in rep["problems"]))

    def test_r_every_other_reviewed_column_of_a_sales_row_is_kept_and_money_quality_is_reported(self):
        rid = self.full_run()
        ex = json.loads(self.db.scalar("SELECT extra::text FROM apsis_hist.sales_line WHERE gross_mtk = 123455"))
        self.assertIn("id", ex); self.assertNotIn("remarks", ex)          # the personal column was dropped by the review; the rest stays
        self.assertNotIn("quantity", ex); self.assertNotIn("amount", ex)  # mapped columns are not duplicated
        rep = self.recon(rid)
        self.assertEqual(rep["money_quality"]["negative_money_lines"], 1)  # the -10.0004 line is reported, never hidden

    def test_s_rollback_resets_the_checkpoints_and_a_rerun_reloads_everything(self):
        rid = self.full_run()
        first = self.snapshot()
        self.assertTrue(self.recon(rid)["ok"])
        for sql in ("SELECT apsis_hist.reset_history()", "TRUNCATE apsis_hist.sales_line"):
            with self.assertRaises(DbError):
                self.db.run(sql)                         # the job role cannot roll back, only the migrate role
        self.owner.run("SELECT apsis_hist.reset_history()")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_ctl.chunk"), "0")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "0")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'apsis_stage'"), "0")
        rid2 = self.full_run()
        self.assertEqual(self.snapshot(), first)
        self.assertTrue(self.recon(rid2)["ok"])


class HistoryReader(Base):
    def test_hist_ro_reads_only_the_history_views_and_a_scope_filter_works(self):
        self.full_run()
        self.owner.run("INSERT INTO app.wing(code,name) VALUES ('SW2','w2')")
        self.owner.run("INSERT INTO app.division(code,name,wing_id) SELECT 'SD2','d2',id FROM app.wing WHERE code='SW2'")
        self.owner.run("INSERT INTO app.territory(code,name,division_id) SELECT 'ST2','t2',id FROM app.division WHERE code='SD2'")
        self.owner.run("INSERT INTO app.zone(code,name,territory_id) SELECT 'SZ2','z2',id FROM app.territory WHERE code='ST2'")
        # two synthetic rows of another territory and one row with unresolved geography
        self.owner.run("INSERT INTO apsis_hist.sales_line (row_uuid, business_date, chunk_key, outlet_code, outlet_id, zone_id, territory_id, division_id, wing_id, qty_base, gross_mtk, is_zero_sale) "
                       "SELECT gen_random_uuid(), DATE '2026-03-10', '2026-03', 'X' || g, NULL, z.id, t.id, d.id, d.wing_id, 1, 1000, false FROM generate_series(1, 2) g, app.zone z "
                       "JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE z.code = 'SZ2'")
        self.owner.run("INSERT INTO apsis_hist.sales_line (row_uuid, business_date, chunk_key, outlet_code, qty_base, gross_mtk, is_zero_sale) VALUES (gen_random_uuid(), DATE '2026-03-11', '2026-03', 'NOGEO', 1, 500, false)")
        hist = Db(conninfo(self.name), role="hist_ro")
        own = self.owner.scalar("SELECT id FROM app.territory WHERE code = 'ST1'")
        q = "SELECT count(*) FROM dw.v_hist_sales_line WHERE territory_id = ANY(ARRAY[%s]::bigint[]) OR (%s AND territory_id IS NULL)"
        self.assertEqual(hist.scalar(q % (own, "false")), "4")      # a TSO of ST1 sees its own territory only, never the two ST2 rows, never the NULL-scope row
        self.assertEqual(hist.scalar(q % (own, "true")), "6")       # a national reader also sees the unresolved-geography row
        self.assertEqual(hist.scalar("SELECT count(*) FROM dw.v_sales_with_history WHERE source = 'apsis'"), "8")
        for sql in ("SELECT 1 FROM apsis_hist.sales_line", "SELECT 1 FROM app.outlet", "SELECT 1 FROM dw.v_memo", "SELECT 1 FROM apsis_ctl.chunk",
                    "INSERT INTO app.wing(code,name) VALUES ('Z','Z')", "SELECT 1 FROM dw.v_outlet_sku_daily"):
            with self.assertRaises(DbError, msg=sql) as cm:
                hist.run(sql)
            self.assertIn("42501", str(cm.exception), sql)
        self.owner.run("SELECT app.apply_db_role_grants()")
        self.assertEqual(hist.scalar("SELECT count(*) FROM dw.v_hist_sales_day") != "", True)      # the refresh does not touch hist_ro


class Normalisation(Base):
    dump_kwargs = {"outlet_rows": [
        (1, "syn-o-0001 ", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None),     # lower case and a trailing space
        (2, "0042", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None),             # a numeric code that lost nothing here ...
        (3, "SYN-O-0099", "x", "x", "x", "SZ1", "SR-9", 0, "2026-01-02 03:04:05", None)],
        "rows_sales": [(1, "syn-o-0001 ", "SYN-SKU-1", "sr-1", "SY01", "2026-03-05 05:00:00", 1, "10.000", 0, "M1", None),
                       (2, "0042", "SYN-SKU-1", "SR-1", "SY01", "2026-03-05 06:00:00", 1, "5.000", 0, "M2", None)]}

    def test_codes_match_by_the_written_rule_and_collisions_are_excluded(self):
        self.owner.run("INSERT INTO app.outlet(code,name,owner_name,zone_id,route_id,cluster_id,channel) SELECT '42','Syn 42','Syn Owner',zone_id,route_id,cluster_id,'GT' FROM app.outlet WHERE code='SYN-O-0001'")
        self.full_run()
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_id IS NOT NULL"), "2")       # '0042' = '42', 'syn-o-0001 ' = 'SYN-O-0001'
        self.assertEqual(self.db.scalar("SELECT outlet_code FROM apsis_hist.sales_line WHERE gross_mtk = 10000"), "syn-o-0001")   # the archive keeps the vendor's own text (trimmed)

    def test_live_codes_that_collide_after_normalisation_are_not_matched_and_are_counted(self):
        self.owner.run("INSERT INTO app.outlet(code,name,owner_name,zone_id,route_id,cluster_id,channel) SELECT c,'dup','Syn Owner',zone_id,route_id,cluster_id,'GT' FROM app.outlet, (VALUES ('syn-o-0001'),('42')) v(c) WHERE code='SYN-O-0001'")
        self.full_run()
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_map.code_ambiguous WHERE kind = 'outlet'"), "1")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_code = 'syn-o-0001' AND outlet_id IS NOT NULL"), "0")


class TotalMismatch(Base):
    dump_kwargs = {"outlet_rows": [(1, "NOPE-1", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None), (2, "NOPE-2", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None)]}

    def test_a_master_table_that_matches_nothing_stops_the_run(self):
        with self.assertRaises(DbError) as cm:
            self.full_run()
        self.assertIn("none of the", str(cm.exception))
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "0")


class ById(Base):
    dump_kwargs = {"sales_by_id": True}

    def test_facts_that_reference_masters_by_vendor_id_are_resolved_to_codes(self):
        rid = self.full_run()
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_id IS NOT NULL AND sku_id IS NOT NULL"), "3")
        self.assertEqual(self.db.scalar("SELECT outlet_code FROM apsis_hist.sales_line WHERE gross_mtk = 123455"), "SYN-O-0001")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE user_id IS NOT NULL"), "5")
        rep = self.recon(rid)
        self.assertTrue(rep["ok"], "; ".join(rep["problems"]))


class Resume(Base):
    def test_kill_mid_load_then_resume_gives_the_same_rows_and_exact_parse_count(self):
        real, calls = self.db.copy_in, {"n": 0}

        def flaky(table_sql, columns_sql, data):
            if "trans_sales_by_outlet" in table_sql:
                calls["n"] += 1
                if calls["n"] == 3:
                    raise DbError("simulated kill")
            return real(table_sql, columns_sql, data)

        self.db.copy_in = flaky
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises(DbError):
            runner.stage_all(self.db, self.dump, self.mapping, self.review, rid, log=lambda m: None)
        self.db.copy_in = real
        st = runner.ctl_get(self.db, "trans_sales_by_outlet", "_stage")
        self.assertEqual(st["state"], "pending")
        self.assertEqual(st["stmt_ordinal"], 2)            # two complete statements of two rows were staged; the third was in flight
        self.assertEqual(st["rows_parsed"], 4)
        rid2 = runner.start_run(self.db, "UTC", False)
        runner.stage_all(self.db, self.dump, self.mapping, self.review, rid2, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid2, "UTC", "base", log=lambda m: None)
        self.assertEqual(self.db.scalar("SELECT count(*) || '|' || count(DISTINCT row_uuid) || '|' || sum(gross_mtk) FROM apsis_hist.sales_line"), f"5|5|{123455 + 1 - 10000 + 7000}")
        self.assertEqual(runner.ctl_get(self.db, "trans_sales_by_outlet", "_stage")["rows_parsed"], 7)      # exactly the rows of the file, no double count
        rep = self.recon(rid2)
        self.assertTrue(rep["ok"], "; ".join(rep["problems"]))


class MissingColumn(Base):
    def test_a_required_column_with_no_source_stops_the_step_and_invents_nothing(self):
        m = copy.deepcopy(self.mapping)
        m["tables"]["trans_sales_by_outlet"]["columns"]["sale_date"] = ["no_such_column"]       # chunk_date still resolves; the canonical date does not
        rid = runner.start_run(self.db, "UTC", False)
        runner.stage_all(self.db, self.dump, m, self.review, rid, only={"trans_sales_by_outlet", "master_outlets", "master_routes", "master_sku"}, log=lambda x: None)
        st = runner.transform_all(self.db, m, rid, "UTC", "base", only={"trans_sales_by_outlet"}, log=lambda x: None)
        self.assertTrue(st["trans_sales_by_outlet"].startswith("missing:sale_date"))
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "0")


class MemoNumbers(Base):
    dump_kwargs = {"memo_dup": True}

    def test_a_memo_number_on_two_outlets_fails_the_report(self):
        rep = self.recon(self.full_run())
        self.assertEqual(rep["memo_numbers"]["numbers_on_more_than_one_outlet"], 1)
        self.assertFalse(rep["ok"])


class OutOfWindow(Base):
    dump_kwargs = {"extra_sales": [
        (8, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", "1970-01-01 05:00:00", 1, "1.000", 0, "SYN-M-8", None),
        (9, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", "2031-01-01 05:00:00", 1, "1.000", 0, "SYN-M-9", None)]}
    thresholds = dict(LENIENT, max_reject_share=0.0001)

    def test_dates_outside_the_window_are_rejected_counted_and_never_get_a_partition(self):
        rid = self.full_run()
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_hist.reject WHERE vendor_table = 'trans_sales_by_outlet'"), "2")
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM pg_class WHERE relkind = 'r' AND relname ~ 'y(1970|2031)m'"), "1")      # only the raw-archive sentinel month 1970-01 exists (masters)
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM pg_class WHERE relkind = 'r' AND relname ~ 'sales_line_y(1970|2031)'"), "0")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "5")
        rep = self.recon(rid)
        self.assertFalse(rep["ok"])
        self.assertTrue(any("outside the plausible window" in p for p in rep["problems"]))
        rep2 = runner.reconcile(self.db, rid, dict(self.mapping, thresholds=dict(LENIENT, max_reject_share=0.5)), self.expected)
        self.assertTrue(rep2["ok"], "; ".join(rep2["problems"]))
        self.assertEqual(rep2["tables"]["by_table"]["trans_sales_by_outlet"]["rejected_out_of_window"], 2)


class ZeroMoney(Base):
    dump_kwargs = {"rows_sales": [(i, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", f"2026-03-0{i} 05:00:00", 2, "0.000", 0, f"M{i}", None) for i in range(1, 6)]}

    def test_sales_lines_with_no_money_fail_the_gate(self):
        rep = self.recon(self.full_run())
        self.assertFalse(rep["ok"])
        self.assertTrue(any("money/quantity quality" in p for p in rep["problems"]))


class VendorCollision(Base):
    dump_kwargs = {"outlet_rows": [(1, "0042", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None), (2, "42", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None),
                                   (3, "SYN-O-0001", "x", "x", "x", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", None)],
                   "rows_sales": [(1, "0042", "SYN-SKU-1", "SR-1", "SY01", "2026-03-05 05:00:00", 1, "10.000", 0, "M1", None),
                                  (2, "42", "SYN-SKU-1", "SR-1", "SY01", "2026-03-05 06:00:00", 1, "20.000", 0, "M2", None),
                                  (3, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", "2026-03-05 07:00:00", 1, "30.000", 0, "M3", None)]}

    def test_two_vendor_codes_that_normalise_alike_are_never_merged_onto_one_outlet(self):
        self.owner.run("INSERT INTO app.outlet(code,name,owner_name,zone_id,route_id,cluster_id,channel) SELECT '42','Syn 42','Syn Owner',zone_id,route_id,cluster_id,'GT' FROM app.outlet WHERE code='SYN-O-0001'")
        self.full_run()
        self.assertEqual(self.owner.scalar("SELECT count(*) FROM apsis_map.code_ambiguous WHERE side = 'vendor' AND kind = 'outlet'"), "1")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_code IN ('0042', '42') AND outlet_id IS NOT NULL"), "0")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_code = 'SYN-O-0001' AND outlet_id IS NOT NULL"), "1")


class UnmappedColumn(Base):
    def test_a_target_column_with_no_vendor_column_stops_the_table_and_shows_in_the_coverage(self):
        m = copy.deepcopy(self.mapping)
        m["tables"]["trans_sales_by_outlet"]["columns"]["amount"] = ["no_such_amount_column"]
        rid = runner.start_run(self.db, "UTC", False)
        runner.stage_all(self.db, self.dump, m, self.review, rid, log=lambda x: None)
        st = runner.transform_all(self.db, m, rid, "UTC", "base", log=lambda x: None)
        self.assertEqual(st["trans_sales_by_outlet"], "missing:amount")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "0")
        cov = json.loads(self.db.scalar("SELECT value::text FROM apsis_ctl.report WHERE name = 'mapping_coverage'"))
        status = {r["target"]: r["status"] for r in cov["trans_sales_by_outlet"]}
        self.assertEqual((status["amount"], status["deleted"], status["qty"]), ("UNMAPPED", "intentionally_absent", "mapped"))
        rep = self.recon(rid)
        self.assertFalse(rep["ok"])
        self.assertTrue(any("unmapped target column" in p for p in rep["problems"]))


class Charset(Base):
    def test_text_that_is_not_utf8_stops_the_run_and_is_never_guessed(self):
        d = Path(self.tmp.name) / "latin"
        d.mkdir()
        (d / "master_sku.sql").write_bytes(b"CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES ('caf\xe9');\n")
        rv = {"tables": {"master_sku": {"keep": ["sku_code"], "drop": []}}}
        rid = runner.start_run(self.db, "UTC", False)
        with self.assertRaises(Exception) as cm:
            runner.stage_all(self.db, d, self.mapping, rv, rid, only={"master_sku"}, log=lambda m: None)
        self.assertIn("character set", str(cm.exception))
        self.assertNotIn("caf", str(cm.exception))
        rid2 = runner.start_run(self.db, "UTC", False)          # only an explicit owner decision loads it as latin1
        runner.stage_all(self.db, d, self.mapping, rv, rid2, only={"master_sku"}, log=lambda m: None, charset="latin-1")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_stage.master_sku"), "1")

    def test_the_profile_reports_undecodable_rows_and_mojibake_hints(self):
        d = Path(self.tmp.name) / "moji"
        d.mkdir()
        (d / "master_sku.sql").write_bytes("CREATE TABLE `master_sku` (\n  `sku_code` varchar(20)\n) ENGINE=InnoDB;\nINSERT INTO `master_sku` VALUES ('à¦¬à¦¾'),('ok');\n".encode("utf-8") + b"INSERT INTO `master_sku` VALUES ('bad\xe9');\n")
        rep = runner.profile(d, self.mapping)
        info = rep["tables"]["master_sku"]
        self.assertEqual((info["mojibake_rows"], info["undecodable_rows"]), (1, 1))

    def test_an_absurdly_long_statement_is_refused_with_a_clear_message(self):
        from apsis_migration import mysqldump
        old, mysqldump.MAX_LINE = mysqldump.MAX_LINE, 100
        try:
            d = Path(self.tmp.name) / "long"
            d.mkdir()
            (d / "master_sku.sql").write_text("CREATE TABLE `master_sku` (`sku_code` varchar(20));\nINSERT INTO `master_sku` VALUES " + ",".join("('%d')" % i for i in range(200)) + ";\n")
            with self.assertRaises(ValueError) as cm:
                list(mysqldump.events(d / "master_sku.sql"))
            self.assertIn("net-buffer-length", str(cm.exception))
        finally:
            mysqldump.MAX_LINE = old


class Evidence(unittest.TestCase):
    def test_the_dry_run_compares_the_vendors_date_column_with_the_timestamp_in_each_zone_and_lists_the_mapping_coverage(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "d"
            d.mkdir()
            # dates are the Dhaka date of a UTC timestamp: evening UTC rows (18:00-24:00) belong to the NEXT day
            rows = [("O1", "2026-03-06", "2026-03-05 19:00:00"), ("O1", "2026-03-06", "2026-03-05 23:30:00"), ("O2", "2026-03-05", "2026-03-05 10:00:00")]
            (d / "trans_by_outlet_promotion_data.sql").write_text(synth.table_file("trans_by_outlet_promotion_data",
                ["`outlet_code` varchar(32)", "`promotion_date` date", "`created_at` datetime"], None, rows))
            (d / "master_sku.sql").write_text(synth.table_file("master_sku", ["`sku_id` int NOT NULL", "`sku_code` varchar(20)"], "sku_id", [(1, "A")]))
            mapping = staging.load_mapping(ROOT / "mapping" / "columns.json")
            rep = runner.profile(d, mapping)
        ev = rep["tables"]["trans_by_outlet_promotion_data"]["date_column_agreement"][0]
        self.assertEqual((ev["rows_where_zones_disagree"], ev["share_if_stored_utc"], ev["share_if_stored_dhaka"]), (2, 1.0, 0.0))
        cov = {r["target"]: r["status"] for r in rep["tables"]["master_sku"]["mapping_coverage"]}
        self.assertEqual(cov, {"code": "mapped"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
