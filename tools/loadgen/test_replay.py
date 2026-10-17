#!/usr/bin/env python3
"""Self-test of tools/loadgen/replay.py's day builder (N-054; owner qa), offline: python3 tools/loadgen/test_replay.py"""
import datetime
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
        d, records, counts = replay.capture_day("lsr7", bundle(day), random.Random(1), 0.65, 0.85)
        self.assertEqual(day, d)
        by_type = {}
        for r in records:
            by_type.setdefault(r["type"], []).append(r)
        self.assertEqual({t: len(v) for t, v in by_type.items()}, counts, "device counts are the records built")
        self.assertEqual(len(by_type["visit"]), len(by_type["visit_close"]), "every visit is closed")
        self.assertEqual(2 * len(by_type["memo"]), len(by_type["memo_line"]))
        self.assertEqual(len({r["client_uuid"] for r in records}), len(records), "client uuids are unique")
        memos = {m["client_uuid"]: m["payload"] for m in by_type["memo"]}
        for m in memos.values():
            self.assertEqual(m["net_mtk"], m["paid_mtk"] + m["due_mtk"])
            self.assertEqual(m["is_credit"], m["due_mtk"] > 0)
            self.assertRegex(m["memo_no"], r"^lsr7-\d{6}-\d{3,4}$")
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
        _, records, _ = replay.capture_day("lsr1", bundle(day, outlets=60), random.Random(2), 1.0, 1.0)
        for r in records:
            at = datetime.datetime.fromisoformat(r["captured_at"].replace("Z", "+00:00"))
            self.assertLessEqual(at, now + datetime.timedelta(seconds=1))
            self.assertEqual(day, (at + datetime.timedelta(hours=6)).date().isoformat(), r["captured_at"])

    def test_unplanned_routes_are_not_visited(self):
        _, records, counts = replay.capture_day("lsr1", bundle("2027-01-03", planned=False), random.Random(3), 1.0, 1.0)
        self.assertEqual([], records)
        self.assertEqual({}, counts)

    def test_device_uuid_matches_the_generator(self):
        # generate.sql: ('1d000000-0000-4000-8000-' || lpad(to_hex(s), 12, '0'))
        self.assertEqual("1d000000-0000-4000-8000-000000002134", replay.device_uuid(8500))


if __name__ == "__main__":
    unittest.main()
