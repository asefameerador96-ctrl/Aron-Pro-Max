"""Tests with SYNTHETIC data only. Needs PostgreSQL 16 + psql: ARON_TEST_PG_URL (postgresql://user:pw@host:port/db, role with CREATEDB)
or the libpq environment (PGHOST, PGPORT, PGUSER). Builds one template database (db/migrations + provisional migrations) per run."""
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
from apsis_migration import keys, runner, staging  # noqa: E402
from apsis_migration.db import Db, DbError  # noqa: E402
from apsis_migration.mysqldump import parse_values  # noqa: E402

REPO = ROOT.parent.parent
MIGRATIONS = REPO / "db" / "migrations"
PROVISIONAL = ROOT / "provisional-migrations"


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
    t.file(HERE / "master_fixture.sql") if (HERE / "master_fixture.sql").exists() else t.run(synth.MASTER_SQL)


def tearDownModule():
    admin().run(f"DROP DATABASE IF EXISTS {TEMPLATE}")


class Base(unittest.TestCase):
    memo_dup = False

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dump = Path(cls.tmp.name) / "dump"
        synth.build(cls.dump, memo_dup=cls.memo_dup)
        cls.mapping = staging.load_mapping(ROOT / "mapping" / "columns.json")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        # a fresh database per test (cloned from the template): tests never share checkpoints or rows
        self.name = "apsis_t_%d_%s" % (os.getpid(), uuid.uuid4().hex[:8])
        admin().run(f"CREATE DATABASE {self.name} TEMPLATE {TEMPLATE}")
        self.db = Db(conninfo(self.name))
        self.addCleanup(lambda: admin().run(f"DROP DATABASE IF EXISTS {self.name} WITH (FORCE)"))

    def full_run(self, **kw):
        rid = runner.start_run(self.db, "UTC", False, note="qty_unit=" + kw.get("qty_unit", "base"))
        runner.stage_all(self.db, self.dump, self.mapping, rid, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid, "UTC", kw.get("qty_unit", "base"), log=lambda m: None, **{k: v for k, v in kw.items() if k != "qty_unit"})
        return rid

    def snapshot(self):
        """Counts and checksums of every target table."""
        q = """SELECT jsonb_build_object(
          'sales', (SELECT jsonb_build_object('n', count(*), 'h', md5(string_agg(row_uuid::text || business_date || qty_base || gross_mtk, ',' ORDER BY row_uuid)))
                      FROM apsis_hist.sales_line),
          'geo', (SELECT count(*) FROM apsis_hist.visit_geo), 'att', (SELECT count(*) FROM apsis_hist.attendance),
          'summary', (SELECT count(*) FROM apsis_hist.route_day_vendor), 'stub', (SELECT count(*) FROM apsis_hist.outlet_stub),
          'raw', (SELECT count(*) FROM apsis_hist.raw_archive), 'outlet_route', (SELECT count(*) FROM apsis_hist.outlet_route),
          'idmap', (SELECT count(*) FROM apsis_map.id_map))"""
        return json.loads(self.db.scalar(q))


class Unit(unittest.TestCase):
    def test_parser_escapes_nulls_negatives_hex(self):
        rows = parse_values("(1,'a''b\\n\\\\',NULL,-2.50,0xAB),(2,'x,(y);',NULL,1e3,'');")
        self.assertEqual(rows[0], ("1", "a'b\n\\", None, "-2.50", "0xAB"))
        self.assertEqual(rows[1], ("2", "x,(y);", None, "1e3", ""))

    def test_uuid_v5_is_the_standard_one_and_pinned(self):
        ns = uuid.uuid5(uuid.NAMESPACE_URL, "urn:aktcl:aron:apsis-migration:v1")
        self.assertEqual(keys.NAMESPACE, ns)
        self.assertEqual(keys.row_uuid("trans_sales_by_outlet", [42]), str(uuid.uuid5(ns, "trans_sales_by_outlet:42")))
        self.assertEqual(keys.row_uuid("t", [1, "a"]), str(uuid.uuid5(ns, "t:1|a")))
        # pinned: a change of namespace or name rule would orphan every migrated row
        self.assertEqual(keys.row_uuid("master_outlets", [1]), "d88de838-cb1b-5ac0-980d-856b87da95ea")
        self.assertEqual(keys.row_uuid("trans_sales_by_outlet", [42]), "924697c4-3f6a-5768-86bd-3827d1b4a8dc")
        self.assertEqual(keys.row_uuid("x", [1]), keys.row_uuid("x", ["1"]))

    def test_personal_columns_are_recognised(self):
        for c in ("owner_name", "mobile_no", "contact_phone", "address", "outlet_name", "ff_name", "email"):
            self.assertTrue(staging.is_personal(c), c)
        for c in ("outlet_code", "route_code", "amount", "dep_code"):
            self.assertFalse(staging.is_dropped(c), c)

    def test_credential_columns_are_recognised(self):
        for c in ("password_hash", "api_token", "otp_code", "device_imei", "fcm_key", "session_id", "salt"):
            self.assertTrue(staging.is_credential(c), c)
        for c in ("outlet_code", "amount", "created_at"):
            self.assertFalse(staging.is_credential(c), c)

    def test_gz_is_read(self):
        with tempfile.TemporaryDirectory() as t:
            d = synth.build(Path(t) / "d")
            raw = (d / "master_sku.sql").read_bytes()
            with gzip.open(Path(t) / "master_sku.sql.gz", "wb") as g:
                g.write(raw)
            from apsis_migration.mysqldump import events
            evs = list(events(Path(t) / "master_sku.sql.gz"))
            self.assertEqual(evs[0][1].name, "master_sku")
            self.assertEqual(sum(len(e[2]) for e in evs if e[0] == "rows"), 3)


class DryRun(unittest.TestCase):
    def test_profile_counts_only_and_leaks_nothing(self):
        with tempfile.TemporaryDirectory() as t:
            synth.build(Path(t) / "d")
            mapping = staging.load_mapping(ROOT / "mapping" / "columns.json")
            rep = runner.profile(Path(t) / "d", mapping)
        text = json.dumps(rep)
        for secret in ("SYN-O-0001", "Syn Owner", "00000000001", synth.PASSWORD_MARKER, synth.DEVICE_MARKER, "SYN-TOKEN-1", "SYN-M-1"):
            self.assertNotIn(secret, text)
        self.assertEqual(rep["tables"]["trans_sales_by_outlet"]["rows"], 7)
        self.assertEqual(rep["tables"]["trans_sales_by_outlet"]["chunks"], {"2026-03": 4, "2026-04": 2, "unknown": 1})
        self.assertEqual(rep["tables"]["master_field_forces"]["dropped_columns"], ["ff_name", "password_hash", "device_id", "api_token"])
        self.assertEqual(rep["tables"]["trans_sales_by_outlet"]["zero_dates"], 1)

    def test_cli_dry_run_needs_no_database(self):
        with tempfile.TemporaryDirectory() as t:
            synth.build(Path(t) / "d")
            out = Path(t) / "profile.json"
            env = dict(os.environ, PGHOST="/nonexistent", PGPORT="1")
            p = subprocess.run([sys.executable, "-I", str(ROOT / "apsis-migration.py"), "run", "--dry-run", "--dump-dir", str(Path(t) / "d"), "--out", str(out)],
                               capture_output=True, env=env)
            self.assertEqual(p.returncode, 0, p.stderr.decode()[:300])
            self.assertIn("trans_sales_by_outlet", out.read_text())


class FullRun(Base):
    def test_a_run_loads_exact_money_quantities_and_dhaka_dates(self):
        self.full_run()
        rows = self.db.run("SELECT business_date, outlet_code, sku_code, qty_base, gross_mtk, is_zero_sale, outlet_id IS NOT NULL, sku_id IS NOT NULL, "
                           "zone_id IS NOT NULL FROM apsis_hist.sales_line ORDER BY business_date, outlet_code, gross_mtk").strip().splitlines()
        got = [r.split("|") for r in rows]
        self.assertEqual(len(got), 5)        # 7 vendor rows: one exact duplicate collapses, one zero date skipped
        by = {(r[0], r[1], r[2]): r for r in got}
        # 17:59:59 UTC = 23:59:59 Dhaka: still 2026-03-05; 18:00:00 UTC = 00:00:00 Dhaka: 2026-03-06
        self.assertEqual(by[("2026-03-05", "SYN-O-0001", "SYN-SKU-1")][3:5], ["3", "123455"])
        self.assertEqual(by[("2026-03-06", "SYN-O-0001", "SYN-SKU-2")][3:5], ["2", "1"])         # 0.0005 taka = 0.5 mtk -> 1 (half up)
        # month end: 2026-03-31 18:30 UTC is 2026-04-01 Dhaka (vendor chunk 2026-03, business date April)
        self.assertEqual(by[("2026-04-01", "SYN-O-0002", "SYN-SKU-1")][3:5], ["1", "-10000"])    # -10.0004 -> -10000 mtk
        self.assertEqual(by[("2026-04-02", "SYN-O-0002", "")][5], "t")                            # zero sale
        self.assertEqual(by[("2026-04-03", "SYN-O-0003", "SYN-SKU-9")][6:], ["f", "f", "f"])      # not in our master: ids NULL, codes kept
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line_default"), "0")

    def test_b_second_run_changes_nothing_and_third_after_wipe_of_control_matches(self):
        self.full_run()
        first = self.snapshot()
        self.full_run()
        self.assertEqual(self.snapshot(), first)
        # forget the checkpoints entirely: a full re-stage and re-transform still gives identical rows (upsert by deterministic keys)
        self.db.run("TRUNCATE apsis_ctl.chunk; DROP SCHEMA apsis_stage CASCADE")
        self.full_run()
        self.assertEqual(self.snapshot(), first)

    def test_c_duplicates_collapse_and_nothing_doubles(self):
        self.full_run()
        s = self.snapshot()
        self.assertEqual(s["sales"]["n"], 5)
        self.assertEqual(s["raw"], 3)    # 1 master_promotion + 3 promotion rows of which one exact duplicate collapses (a table without a primary key)
        self.assertEqual(self.db.scalar("SELECT count(*) - count(DISTINCT row_uuid) FROM apsis_hist.sales_line"), "0")
        stage_dups = self.db.scalar("SELECT count(*) - count(DISTINCT _row_uuid) FROM apsis_stage.trans_sales_by_outlet")
        self.assertEqual(stage_dups, "1")         # the exact duplicate was staged as is (no transformation) and deduplicated in the transform

    def test_d_credentials_never_reach_staging_or_target(self):
        self.full_run()
        cols = self.db.run("SELECT column_name FROM information_schema.columns WHERE table_schema='apsis_stage' AND table_name='master_field_forces'").split()
        for c in ("ff_name", "password_hash", "device_id", "api_token"):
            self.assertNotIn(c, cols)
        ocols = self.db.run("SELECT column_name FROM information_schema.columns WHERE table_schema='apsis_stage' AND table_name='master_outlets'").split()
        for c in ("outlet_name", "owner_name", "mobile_no"):
            self.assertNotIn(c, ocols)
        dump = subprocess.run(["pg_dump", "-d", conninfo(self.name), "-n", "apsis_stage", "-n", "apsis_hist", "-n", "apsis_map", "-n", "apsis_ctl"], capture_output=True).stdout.decode("utf-8", "replace")
        for secret in (synth.PASSWORD_MARKER, synth.DEVICE_MARKER, "SYN-TOKEN-1", "Syn Owner", "00000000001"):
            self.assertFalse(secret in dump, "found a synthetic secret: " + secret[:8])    # no credential, device id, owner name or phone anywhere in the migration schemas

    def test_e_live_masters_are_untouched(self):
        before = self.db.scalar("SELECT md5(string_agg(o::text, ',' ORDER BY id)) FROM app.outlet o")
        self.full_run()
        self.assertEqual(self.db.scalar("SELECT md5(string_agg(o::text, ',' ORDER BY id)) FROM app.outlet o"), before)
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.outlet_stub"), "1")      # SYN-O-0003 only: codes, no names
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_map.id_map WHERE vendor_table='master_outlets'"), "2")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='apsis_hist' AND table_name='outlet_stub' AND column_name ~ 'name|phone|mobile'"), "0")

    def test_f_reconciliation_ok_and_numbers_only(self):
        rid = self.full_run()
        rep = runner.reconcile(self.db, rid)
        self.assertTrue(rep["ok"], "not ok: " + ",".join(k for k, v in rep.items() if isinstance(v, dict) and not v.get("ok", True)))
        self.assertEqual(rep["daily_money"]["days_differing"], 0)
        self.assertEqual(rep["daily_money"]["total_loaded_mtk"], 123455 + 1 - 10000 + 0 + 7000)
        self.assertEqual(rep["sku_qty"]["skus_differing"], 0)
        self.assertEqual(rep["control"]["default_partition_rows"], 0)
        self.assertEqual(rep["tables"]["by_table"]["trans_sales_by_outlet"]["skipped_no_date_or_key"], 1)    # the zero-date row
        text = json.dumps(rep)
        for secret in ("SYN-O-0001", "SYN-SKU", "SY01", "Syn Owner", "SYN-M-"):
            self.assertNotIn(secret, text)

    def test_g_sync_roles_cannot_read_history_but_analytics_views_work(self):
        self.full_run()
        for role in ("api_rw", "worker_rw", "jobs_rw"):
            self.assertEqual(self.db.scalar(f"SELECT has_schema_privilege('{role}', 'apsis_hist', 'USAGE')"), "f", role)
            self.assertEqual(self.db.scalar(f"SELECT has_table_privilege('{role}', 'apsis_hist.sales_line', 'SELECT')"), "f", role)
            self.assertEqual(self.db.scalar(f"SELECT has_table_privilege('{role}', 'dw.v_hist_sales_line', 'SELECT')"), "f", role)
        self.assertEqual(self.db.scalar("SELECT has_table_privilege('bi_reader', 'dw.v_hist_sales_line', 'SELECT')"), "t")
        self.assertEqual(self.db.scalar("SELECT has_table_privilege('bi_reader', 'apsis_hist.sales_line', 'SELECT')"), "f")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM dw.v_hist_sales_day"), "5")      # one row per outlet/SKU/day

    def test_h_scope_columns_resolved_from_route_then_outlet(self):
        self.full_run()
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_id IS NOT NULL AND (zone_id IS NULL OR territory_id IS NULL OR division_id IS NULL OR wing_id IS NULL)"), "0")
        # an outlet and route that are not in our master keep their vendor codes and get no scope ids (national roles only)
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_code='SYN-O-0003' AND zone_id IS NULL AND route_id IS NULL"), "1")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_code='SYN-O-0001' AND zone_id IS NOT NULL AND wing_id IS NOT NULL"), "2")

    def test_i_pack_unit_switch_multiplies_by_pack_factor(self):
        self.full_run(qty_unit="pack")
        self.assertEqual(self.db.scalar("SELECT qty_base FROM apsis_hist.sales_line WHERE sku_code='SYN-SKU-1' AND outlet_code='SYN-O-0001'"), "60")   # 3 packs x 20

    def test_j_fill_locations_only_where_null_and_respects_switch(self):
        self.full_run(fill_locations=True, confirm_locations=True)
        r = dict(l.split("|") for l in self.db.run("SELECT code, coalesce(lat::text,'null') FROM app.outlet").split())
        self.assertEqual(r["SYN-O-0001"], "23.78")
        self.assertEqual(r["SYN-O-0002"], "null")     # 0.0 / 0.0 is outside the Bangladesh box: not loaded
        self.assertEqual(self.db.scalar("SELECT location_confirmed FROM app.outlet WHERE code='SYN-O-0001'"), "t")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_map.fill_log"), "2")      # SYN-O-0001 and SYN-O-0004 (the rollback log)
        # rollback exactly as the runbook says
        self.db.run("UPDATE app.outlet o SET lat = NULL, lng = NULL, location_basis = f.prev_location_basis, "
                    "location_confirmed = COALESCE(f.prev_location_confirmed, false) FROM apsis_map.fill_log f WHERE f.outlet_id = o.id")
        self.assertEqual(self.db.scalar("SELECT count(*) FROM app.outlet WHERE lat IS NOT NULL"), "0")


class Resume(Base):
    def test_kill_mid_load_then_resume_gives_the_same_rows(self):
        # clean reference in a sibling run is the other tests' snapshot; here: interrupt staging of the sales table after 2 batches
        real = self.db.copy_in
        calls = {"n": 0}

        def flaky(table, columns, data):
            if "trans_sales_by_outlet" in table:
                calls["n"] += 1
                if calls["n"] == 3:
                    raise DbError("simulated kill")
            return real(table, columns, data)

        self.db.copy_in = flaky
        rid = runner.start_run(self.db, "UTC", False, note="qty_unit=base")
        with self.assertRaises(DbError):
            runner.stage_all(self.db, self.dump, self.mapping, rid, log=lambda m: None)
        self.db.copy_in = real
        st = runner.ctl_get(self.db, "trans_sales_by_outlet", "_stage")
        self.assertEqual(st["state"], "pending")
        self.assertGreaterEqual(st["stmt_ordinal"], 1)
        # resume: a new run picks up at the checkpoint
        rid2 = runner.start_run(self.db, "UTC", False, note="qty_unit=base")
        runner.stage_all(self.db, self.dump, self.mapping, rid2, log=lambda m: None)
        runner.transform_all(self.db, self.mapping, rid2, "UTC", "base", log=lambda m: None)
        n, d = self.db.scalar("SELECT count(*) || '|' || count(DISTINCT row_uuid) FROM apsis_hist.sales_line").split("|")
        self.assertEqual((n, d), ("5", "5"))
        total = self.db.scalar("SELECT sum(gross_mtk) FROM apsis_hist.sales_line")
        self.assertEqual(total, str(123455 + 1 - 10000 + 7000))
        rep = runner.reconcile(self.db, rid2)
        self.assertTrue(rep["ok"], "not ok: " + ",".join(k for k, v in rep.items() if isinstance(v, dict) and not v.get("ok", True)))


class MissingColumn(Base):
    def test_a_required_column_that_no_candidate_matches_stops_the_step_and_invents_nothing(self):
        m = json.loads(json.dumps(self.mapping))
        m["tables"]["trans_sales_by_outlet"]["columns"]["sale_date"] = ["no_such_column"]
        rid = runner.start_run(self.db, "UTC", False, note="qty_unit=base")
        runner.stage_all(self.db, self.dump, m, rid, only={"trans_sales_by_outlet", "master_outlets", "master_routes", "master_sku"}, log=lambda x: None)
        st = runner.transform_all(self.db, m, rid, "UTC", "base", only={"trans_sales_by_outlet"}, log=lambda x: None)
        self.assertTrue(st["trans_sales_by_outlet"].startswith("missing:sale_date"))
        self.assertEqual(self.db.scalar("SELECT count(*) FROM apsis_hist.sales_line"), "0")


class MemoNumbers(Base):
    memo_dup = True

    def test_a_memo_number_on_two_outlets_fails_the_report(self):
        rid = self.full_run()
        rep = runner.reconcile(self.db, rid)
        self.assertEqual(rep["memo_numbers"]["numbers_on_more_than_one_outlet"], 1)
        self.assertFalse(rep["ok"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
