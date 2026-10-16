#!/usr/bin/env python3
"""Unit tests of the retailer-list cleaning rules (tools/data/retailers_to_sql.py) on synthetic values: no real row,
name or number of a person is used.  Run: python -P -m unittest tools/data/test_retailers_to_sql.py
"""
import argparse
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import retailers_to_sql as m  # noqa: E402


class OutletCode(unittest.TestCase):
    def test_strips_stray_prefix(self):
        self.assertEqual(m.clean_outlet_code(":1520237"), "1520237")
        self.assertEqual(m.clean_outlet_code("*1262802"), "1262802")
        self.assertEqual(m.clean_outlet_code("।185837"), "185837")
        self.assertEqual(m.clean_outlet_code(" DHK-344-011 "), "DHK-344-011")

    def test_numeric_cell_and_bengali_digits(self):
        self.assertEqual(m.clean_outlet_code(1520237.0), "1520237")
        self.assertEqual(m.clean_outlet_code("১২৩"), "123")

    def test_unusable(self):
        self.assertIsNone(m.clean_outlet_code(None))
        self.assertIsNone(m.clean_outlet_code("::"))
        self.assertIsNone(m.clean_outlet_code("a" * 33))
        self.assertIsNone(m.clean_outlet_code("ab cd"))


class Phone(unittest.TestCase):
    def test_leading_zero_restored(self):
        self.assertEqual(m.clean_phone("1712345678"), "01712345678")
        self.assertEqual(m.clean_phone(1712345678), "01712345678")

    def test_country_code_and_punctuation_dropped(self):
        self.assertEqual(m.clean_phone("+880 1712-345678"), "01712345678")
        self.assertEqual(m.clean_phone("01712345678"), "01712345678")

    def test_bengali_digits(self):
        self.assertEqual(m.clean_phone("১৭১২৩৪৫৬৭৮"), "01712345678")

    def test_invalid(self):
        self.assertIsNone(m.clean_phone("171"))
        self.assertIsNone(m.clean_phone("01212345678"))     # 012 is not a Bangladeshi mobile prefix
        self.assertIsNone(m.clean_phone(None))
        self.assertIsNone(m.clean_phone("0171234567"))


class GeoClassAndChannel(unittest.TestCase):
    def test_geo_class(self):
        self.assertEqual(m.clean_geo_class("Semi Urban"), "SemiUrban")
        self.assertEqual(m.clean_geo_class("SemiUrban"), "SemiUrban")
        self.assertEqual(m.clean_geo_class("rural "), "Rural")
        self.assertEqual(m.clean_geo_class("Hill"), "Hill")
        self.assertIsNone(m.clean_geo_class(None))
        self.assertIsNone(m.clean_geo_class("Coastal"))

    def test_sub_channel(self):
        self.assertEqual(m.clean_sub_channel("GT"), ("GT", "GT", True))
        self.assertEqual(m.clean_sub_channel("Gold"), ("Astha", "Gold", True))
        self.assertEqual(m.clean_sub_channel("HoReCa"), ("HoReCa", "HoReCa", True))
        self.assertEqual(m.clean_sub_channel("Titanium"), ("Astha", None, False))
        self.assertEqual(m.clean_sub_channel(""), ("GT", None, False))


class RouteName(unittest.TestCase):
    def test_daily(self):
        self.assertEqual(m.parse_route_name("VandaripulDaily"), ("Vandaripul", "daily", 127, "Daily", True))

    def test_three_days(self):
        self.assertEqual(m.parse_route_name("Bdr Market.(Sun, Tue, Thu)"), ("Bdr Market.", "3f", 42, "(Sun, Tue, Thu)", True))
        self.assertEqual(m.parse_route_name("Gorurhat(Sat, Mon, Wed)"), ("Gorurhat", "3f", 21, "(Sat, Mon, Wed)", True))

    def test_two_days(self):
        self.assertEqual(m.parse_route_name("Bejerdanga(Sat, Tue)"), ("Bejerdanga", "2f", 9, "(Sat, Tue)", True))

    def test_unparseable(self):
        name, kind, mask, label, ok = m.parse_route_name("Some Route")
        self.assertEqual((name, kind, mask, label, ok), ("Some Route", None, 127, None, False))


class Coordinates(unittest.TestCase):
    def test_inside_bangladesh(self):
        self.assertEqual(m.parse_coord(23.8069, 90.3687), (23.8069, 90.3687))
        self.assertEqual(m.parse_coord("23.80694444", "90.36871111"), (23.806944, 90.368711))

    def test_outside_or_missing(self):
        self.assertIsNone(m.parse_coord(None, None))
        self.assertIsNone(m.parse_coord(1.0, 1.0))
        self.assertIsNone(m.parse_coord(0, 0))
        self.assertIsNone(m.parse_coord("abc", "90.1"))


class SqlLiteral(unittest.TestCase):
    def test_quoting(self):
        self.assertEqual(m.q("O'Neil"), "'O''Neil'")
        self.assertEqual(m.q(None), "NULL")
        self.assertEqual(m.q(True), "true")
        self.assertEqual(m.q(23.5), "23.5")


def synthetic_row(**over):
    """One sheet row of made-up values (a test shop, no real person)."""
    row = {c: "" for c in m.COLUMNS}
    row.update({"Wing": "Test Wing", "Division": "Test Division", "Territory Code": "9001", "Territory": "Test T",
                "Zone Code": "9101", "Zone Name": "Test Z", "Route Code": "9201", "Route": "Test R(Sat, Mon, Wed)",
                "Outlet Code": ":9900001", "Outlet Name": "Test shop", "Owner Name": "Test owner",
                "Contact Number": "01712345678", "Cluster Type": "Market", "Cluster Name": "Test cluster",
                "Address": "Test road", "Latitude": "23.8", "Longitude": "90.37", "Geo Classification": "Urban",
                "Sub Channel": "GT"})
    row.update(over)
    return row


class ImporterRules(unittest.TestCase):
    """The Importer on synthetic rows: parent conflicts are counted (majority kept) and the SQL keeps the placement
    history in step with the outlet, including a change on the day the open row started."""

    @staticmethod
    def importer(rows):
        a = argparse.Namespace(
            division=None, territory_code=None, zone_code=None, route_code=None, limit=None, hierarchy="all",
            codes_csv=None, placeholder_min_shared=3)
        imp = m.Importer(a)
        imp.run(rows)
        return imp

    def test_parent_conflicts_counted_majority_kept(self):
        rows = [synthetic_row(**{"Outlet Code": "9900001"}),
                synthetic_row(**{"Outlet Code": "9900002", "Territory Code": "9002", "Latitude": "23.81"}),
                synthetic_row(**{"Outlet Code": "9900003", "Territory Code": "9002", "Latitude": "23.82"}),
                synthetic_row(**{"Outlet Code": "9900004", "Zone Code": "9102", "Latitude": "23.83"})]
        imp = self.importer(rows)
        self.assertEqual(imp.counter["zones listed under more than one territory (majority kept)"], 1)
        self.assertEqual(imp.zones["9101"][1], "9002")
        self.assertEqual(imp.counter["routes listed under more than one zone (majority kept)"], 1)
        self.assertEqual(imp.routes["9201"]["zone"], "9101")
        self.assertEqual(imp.counter["territories listed under more than one division (majority kept)"], 0)
        self.assertEqual(len(imp.outlets), 4)

    def test_sql_placement_history_same_day(self):
        imp = self.importer([synthetic_row()])
        buf = io.StringIO()
        imp.write_sql(buf)
        sql = buf.getvalue()
        self.assertIn("SET valid_to = current_date", sql)
        self.assertIn("h.valid_from < current_date", sql)
        self.assertIn("SET route_id = o.route_id, cluster_id = o.cluster_id", sql)
        self.assertIn("h.valid_from = current_date", sql)
        self.assertIn("INSERT INTO app.outlet_placement_history", sql)
        self.assertNotIn("NID", sql)
        self.assertIn("'9900001'", sql)            # the stray prefix is gone in the emitted code
        self.assertNotIn("':9900001'", sql)


if __name__ == "__main__":
    unittest.main()
