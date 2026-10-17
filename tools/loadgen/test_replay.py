#!/usr/bin/env python3
"""Self-test of tools/loadgen/replay.py's day builder (N-054; owner qa), offline: python3 tools/loadgen/test_replay.py"""
import argparse
import datetime
import gzip
import http.server
import json
import os
import threading
import importlib.util
import random
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("replay", Path(__file__).resolve().parent / "replay.py")
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)


def bundle(day, planned=True, outlets=40):
    return {
        "meta": {"valid_for_business_date": day, "bundle_version": day + ":1", "config_version": 7},
        "prices": [{"sku_id": 1, "price_type": "outlet", "amount_mtk": 9200, "per_base_qty": 1},
                   {"sku_id": 2, "price_type": "outlet", "amount_mtk": 8800, "per_base_qty": 1},
                   {"sku_id": 3, "price_type": "cc", "amount_mtk": 1, "per_base_qty": 1}],
        "routes": [{"route_id": 5, "planned_today": planned, "outlets": [{"outlet_id": 100 + i, "lat": 23.8, "lng": 90.36} for i in range(outlets)]}],
    }


class DayBuilder(unittest.TestCase):
    def test_counts_money_and_families_are_consistent(self):
        day = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=6)).date().isoformat()
        d, records, counts, money = replay.capture_day("lsr7", bundle(day), random.Random(1), 0.65, 0.85)
        self.assertEqual(day, d)
        by_type = {}
        for r in records:
            by_type.setdefault(r["type"], []).append(r)
        self.assertEqual({t: len(v) for t, v in by_type.items()}, counts, "device counts are the records built")
        self.assertEqual(len(by_type["visit"]), len(by_type["visit_close"]), "every visit is closed")
        self.assertEqual(2 * len(by_type["memo"]), len(by_type["memo_line"]))
        self.assertEqual(len({r["client_uuid"] for r in records}), len(records), "client uuids are unique")
        memos = {m["client_uuid"]: m["payload"] for m in by_type["memo"]}
        self.assertEqual(len(memos), money["active_memo_count"])
        self.assertEqual(sum(m["net_mtk"] for m in memos.values()), money["net_mtk"])
        self.assertEqual(sum(m["due_mtk"] for m in memos.values()), money["due_mtk"])
        self.assertEqual(sum(c["payload"]["amount_mtk"] for c in by_type.get("due_collection", [])), money["due_collected_mtk"])
        for m in memos.values():
            self.assertEqual(m["net_mtk"], m["paid_mtk"] + m["due_mtk"])
            self.assertEqual(m["is_credit"], m["due_mtk"] > 0)
            self.assertRegex(m["memo_no"], r"^lsr7-\d{6}-1\d{3}$")
        for line in by_type["memo_line"]:
            p = line["payload"]
            self.assertEqual(p["gross_mtk"], p["qty_base"] * p["base_price_mtk"])
            self.assertIn(p["memo_client_uuid"], memos)
        for c in by_type.get("due_collection", []):
            self.assertLessEqual(c["payload"]["amount_mtk"], memos[c["payload"]["against_memo_client_uuid"]]["due_mtk"])
        visits = {v["client_uuid"] for v in by_type["visit"]}
        self.assertTrue(all(r["family_uuid"] in visits for r in by_type["memo"] + by_type["memo_line"] + by_type["visit_close"]))
        self.assertTrue(all(r["payload"]["outcome_code"] in ("sold", "owner_absent") for r in by_type["visit_close"]))

    def test_captures_stay_inside_the_dhaka_business_day_and_never_in_the_future(self):
        now = datetime.datetime.now(datetime.timezone.utc)
        day = (now + datetime.timedelta(hours=6)).date().isoformat()
        _, records, _, _ = replay.capture_day("lsr1", bundle(day, outlets=60), random.Random(2), 1.0, 1.0)
        for r in records:
            at = datetime.datetime.fromisoformat(r["captured_at"].replace("Z", "+00:00"))
            self.assertLessEqual(at, now + datetime.timedelta(seconds=1))
            self.assertEqual(day, (at + datetime.timedelta(hours=6)).date().isoformat(), r["captured_at"])

    def test_unplanned_routes_are_not_visited(self):
        day = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=6)).date().isoformat()
        _, records, counts, _ = replay.capture_day("lsr1", bundle(day, planned=False), random.Random(3), 1.0, 1.0)
        self.assertEqual([], records)
        self.assertEqual({}, counts)

    def test_just_after_dhaka_midnight_every_capture_is_on_the_new_day(self):
        day = "2027-01-03"
        now = datetime.datetime.fromisoformat(day + "T00:00:20+06:00")
        _, records, _, _ = replay.capture_day("lsr1", bundle(day, outlets=60), random.Random(4), 1.0, 1.0, now=now)
        self.assertTrue(records)
        for r in records:
            at = datetime.datetime.fromisoformat(r["captured_at"].replace("Z", "+00:00"))
            self.assertEqual(day, (at + datetime.timedelta(hours=6)).date().isoformat(), r["captured_at"])
            self.assertLessEqual(at, now)

    def test_a_bundle_for_another_day_is_refused(self):
        with self.assertRaises(RuntimeError):
            replay.capture_day("lsr1", bundle("2020-01-01"), random.Random(5), 1.0, 1.0)

    def test_a_seed_reproduces_the_day(self):
        day = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=6)).date().isoformat()
        now = datetime.datetime.now(datetime.timezone.utc)
        a = replay.capture_day("lsr1", bundle(day), random.Random(9), 0.6, 0.8, now=now)[1]
        b = replay.capture_day("lsr1", bundle(day), random.Random(9), 0.6, 0.8, now=now)[1]
        self.assertEqual(a, b)

    def test_device_uuid_matches_the_generator(self):
        # generate.sql: ('1d000000-0000-4000-8000-' || lpad(to_hex(s), 12, '0'))
        self.assertEqual("1d000000-0000-4000-8000-000000002134", replay.device_uuid(8500))


class FakeApi(http.server.BaseHTTPRequestHandler):
    """A tiny stand-in for the API: idempotent batches, cumulative per-day totals, and switchable faults."""
    state = {}

    def log_message(self, *a):
        pass

    def reply(self, status, body):
        raw = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

    def totals(self):
        st = self.state
        return {"business_date": st["day"], "by_type": {t: {"accepted": n, "duplicate": 0, "rejected": 0, "quarantined": 0} for t, n in st["counts"].items()},
                "money": dict(st["money"])}

    def do_GET(self):
        st = self.state
        if self.path.startswith("/v1/sync/bundle"):
            return self.reply(200, bundle(st["day"], planned=not st.get("friday")))
        if self.path.startswith("/v1/sync/totals"):
            return self.reply(200, {"totals": self.totals(), "day_states": []})
        self.reply(404, {})

    def do_POST(self):
        st = self.state
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path == "/v1/auth/login":
            return self.reply(200, {"status": "ok", "access_token": "t"})
        body = json.loads(gzip.decompress(raw))
        st["batches"] = st.get("batches", 0) + 1
        resend = all(r["client_uuid"] in st["seen"] for r in body["records"])
        if resend and st.get("fail_resend") and not st.get("failed_once"):
            st["failed_once"] = True
            return self.reply(503, {"code": "ERR_SERVICE_UNAVAILABLE"})
        acks = []
        for r in body["records"]:
            if r["client_uuid"] in st["seen"]:
                acks.append({"client_uuid": r["client_uuid"], "status": "duplicate"}); continue
            st["seen"].add(r["client_uuid"])
            st["counts"][r["type"]] = st["counts"].get(r["type"], 0) + 1
            p = r["payload"]
            if r["type"] == "memo":
                m = st["money"]; m["active_memo_count"] += 1; m["gross_mtk"] += p["gross_mtk"] + st.get("money_skew", 0); m["net_mtk"] += p["net_mtk"]
                m["paid_mtk"] += p["paid_mtk"]; m["due_mtk"] += p["due_mtk"]
            if r["type"] == "due_collection":
                st["money"]["due_collected_mtk"] += p["amount_mtk"]
            acks.append({"client_uuid": r["client_uuid"], "status": "accepted"})
        dup = sum(1 for a in acks if a["status"] == "duplicate")
        self.reply(200, {"acks": acks, "summary": {"accepted": len(acks) - dup, "duplicate": dup, "rejected": 0, "quarantined": 0}, "server_totals": [self.totals()]})


class Reconciliation(unittest.TestCase):
    """one_sr against FakeApi: it passes only when the server's change equals the device, and fails on every fault."""

    def setUp(self):
        FakeApi.state = {"day": (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=6)).date().isoformat(),
                         "seen": set(), "counts": {}, "money": {k: 0 for k in replay.MONEY_KEYS}}
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeApi)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.api = replay.Api(f"http://127.0.0.1:{self.server.server_address[1]}", 10)

    def tearDown(self):
        self.server.shutdown()

    def run_sr(self, **kw):
        args = argparse.Namespace(prefix="lsr", seed=54, visit_rate=0.7, memo_rate=0.9, run=kw.pop("run", 1), batch=25, allow_empty=kw.pop("allow_empty", False))
        return replay.one_sr(self.api, 3, args, "pw")

    def test_a_correct_server_reconciles(self):
        out = self.run_sr()
        self.assertTrue(out["ok"], out)
        self.assertEqual(out["device_counts"], out["server_counts"])

    def test_a_second_run_on_the_same_database_reconciles_its_own_change(self):
        self.assertTrue(self.run_sr()["ok"])
        out = self.run_sr(run=2)
        self.assertTrue(out["ok"], out)

    def test_an_empty_day_fails_unless_allowed(self):
        FakeApi.state["friday"] = True
        out = self.run_sr()
        self.assertFalse(out["ok"]); self.assertTrue(out["empty"])
        self.assertTrue(self.run_sr(allow_empty=True)["ok"])

    def test_a_failed_resend_batch_fails(self):
        FakeApi.state["fail_resend"] = True
        out = self.run_sr()
        self.assertFalse(out["ok"], out)
        self.assertGreater(out["resend_not_duplicate"], 0)

    def test_wrong_money_on_the_server_fails(self):
        FakeApi.state["money_skew"] = 10
        out = self.run_sr()
        self.assertFalse(out["ok"], out)
        self.assertNotEqual(out["device_money"], out["server_money"])

    def test_a_non_loopback_api_needs_the_scratch_flag(self):
        self.assertTrue(replay.scratch_target("http://127.0.0.1:8080"))
        self.assertFalse(replay.scratch_target("https://fde-aron-dev.example.net"))
        os.environ["ARON_LOADGEN_PASSWORD"] = "pw"
        self.assertEqual(2, replay.main(["--api", "https://fde-aron-dev.example.net", "--srs", "1"]))


if __name__ == "__main__":
    unittest.main()
