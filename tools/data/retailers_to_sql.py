#!/usr/bin/env python3
"""AKTCL retailer list (xlsx) to idempotent SQL for the Aron schema (dev test-data load, pilot size).

Generic and parameterised: no data lives in this file. It reads the sheet "retailer list" (row 1 empty, header on
row 2), cleans the vendor codes, maps the rows onto app.wing / division / territory / zone / route / cluster / outlet
(db/migrations V0002, V0004) and writes one SQL file of INSERT ... ON CONFLICT DO UPDATE statements keyed by the vendor
codes, so a second run changes nothing. NID, TIN and trade licence are never emitted (V0049: only envelope-encrypted
columns exist; the sheet's values are the placeholder 123 anyway, docs/16 D-256).

    python -P tools/data/retailers_to_sql.py IN.xlsx --out OUT.sql --division Mirpur \\
        --codes-csv docs/evidence/geofence-radius-2026-09.csv --stats-out stats.md

Filters (--division, --territory-code, --zone-code, --route-code, --limit) choose the outlets; the hierarchy (wings,
divisions, territories, zones, routes) is emitted country-wide by default (--hierarchy subset restricts it to the
filtered rows). Clusters are emitted only for the loaded outlets (app.outlet.cluster_id is NOT NULL).

Rules (docs/16 s4.1, docs/22 P-07, P-09, P-14):
  * outlet code: leading non-alphanumerics (":", "*", Bengali danda) stripped; must match the app.outlet.code CHECK
  * contact number: Bengali digits transliterated, 880 prefix dropped, leading 0 restored; else NULL
  * geo classification "Semi Urban" -> SemiUrban; blank stays NULL
  * sub channel -> channel: Astha tiers (Gold, Platinum, Diamond, Silver, Titanium) -> Astha, else the value itself;
    sub_channel_id only for the nine V0027 codes (Titanium is counted as unmapped, no master row exists)
  * route name "Name(Sun, Tue, Thu)" / "NameDaily" -> visit_days_mask (bit0 Sat .. bit6 Fri), visit_kind, display_label
  * rows without coordinates or outside Bangladesh (lat 20.5..26.7, lon 88.0..92.7) are rejected and counted
  * a 6-decimal point shared by at least --placeholder-min-shared (3) outlets marks location_confirmed = false (DQ-48)
  * duplicate outlet codes: the first row wins, the rest are counted
Statistics (--stats-out) hold counts only, never a name or a number of a person.
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import math
import re
import sys

BD_LAT = (20.5, 26.7)
BD_LON = (88.0, 92.7)
DAY_BITS = {"sat": 1, "sun": 2, "mon": 4, "tue": 8, "wed": 16, "thu": 32, "fri": 64}
DAY_ORDER = ["Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri"]
GEO_CLASS = {"hill": "Hill", "urban": "Urban", "semiurban": "SemiUrban", "semi urban": "SemiUrban", "rural": "Rural"}
SUB_CHANNELS = {"GT": "GT", "RCC": "RCC", "DCC": "DCC", "MT": "MT", "HoReCa": "HoReCa",
                "Gold": "Astha", "Platinum": "Astha", "Diamond": "Astha", "Silver": "Astha"}
ASTHA_TIERS = {"gold", "platinum", "diamond", "silver", "titanium"}
CHANNELS = {"GT", "DCC", "Astha", "RCC", "MT", "HoReCa"}
OUTLET_CODE_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$")
NODE_CODE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")
PHONE_RE = re.compile(r"^01[3-9][0-9]{8}$")
BN_DIGITS = str.maketrans("০১২৩৪৫৬৭৮৯", "0123456789")

COLUMNS = ["Wing", "Division", "Territory Code", "Territory", "Zone Code", "Zone Name", "Outlet Name", "Owner Name",
           "Contact Number", "Route Code", "Route", "Outlet Code", "Cluster Type", "Cluster Name", "Address", "NID",
           "TIN", "Trade License", "Latitude", "Longitude", "Geo Classification", "Sub Channel", "Created At",
           "Updated At"]


# ---------------------------------------------------------------------------------------------------- cleaning
def cell(v) -> str:
    """A cell as trimmed text; numeric codes lose a spurious .0."""
    if v is None:
        return ""
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v).strip()


def clean_outlet_code(v) -> str | None:
    """Strip the stray leading symbol (':', '*', '।', ...) the export puts before some codes; None when the rest does
    not satisfy the app.outlet.code CHECK."""
    s = cell(v).translate(BN_DIGITS)
    s = re.sub(r"^[^0-9A-Za-z]+", "", s).strip()
    return s if OUTLET_CODE_RE.match(s) else None


def clean_node_code(v) -> str | None:
    s = cell(v).translate(BN_DIGITS)
    s = re.sub(r"^[^0-9A-Za-z]+", "", s).strip()
    return s if NODE_CODE_RE.match(s) else None


def clean_phone(v) -> str | None:
    """11-digit ASCII mobile with the leading 0 (01[3-9]xxxxxxxx), or None."""
    s = cell(v).translate(BN_DIGITS)
    digits = re.sub(r"\D", "", s)
    if digits.startswith("880") and len(digits) == 13:
        digits = digits[3:]
    if len(digits) == 10 and digits.startswith("1"):
        digits = "0" + digits
    return digits if PHONE_RE.match(digits) else None


def clean_name(v, limit=120) -> str:
    s = re.sub(r"\s+", " ", cell(v))
    return s[:limit]


def clean_geo_class(v) -> str | None:
    s = re.sub(r"[\s_-]+", " ", cell(v).lower()).strip()
    return GEO_CLASS.get(s) or GEO_CLASS.get(s.replace(" ", ""))


def clean_sub_channel(v) -> tuple[str, str | None, bool]:
    """(channel, sub_channel code or None, mapped). Astha tiers map to channel Astha; a tier without a master row
    (Titanium) keeps the channel and no sub_channel_id."""
    s = cell(v)
    for code, channel in SUB_CHANNELS.items():
        if s.lower() == code.lower():
            return channel, code, True
    if s.lower() in ASTHA_TIERS:
        return "Astha", None, False
    if s in CHANNELS:
        return s, None, False
    return "GT", None, False


def parse_route_name(v) -> tuple[str, str | None, int, str | None, bool]:
    """'Vandaripul(Sun, Tue, Thu)' -> ('Vandaripul', '3f', 42, '(Sun, Tue, Thu)', True); 'XDaily' -> daily 127.
    Unparseable names keep the whole string, mask 127 and no visit_kind (the CHECK allows null)."""
    s = re.sub(r"\s+", " ", cell(v))
    m = re.match(r"^(.*?)\s*\(([A-Za-z ,]+)\)\s*$", s)
    if m:
        days = [d.strip().lower()[:3] for d in m.group(2).split(",") if d.strip()]
        if days and all(d in DAY_BITS for d in days):
            mask = 0
            for d in days:
                mask |= DAY_BITS[d]
            n = bin(mask).count("1")
            kind = "daily" if mask == 127 else "3f" if n == 3 else "2f" if n == 2 else None
            label = "(" + ", ".join(d for d in DAY_ORDER if DAY_BITS[d.lower()] & mask) + ")"
            return (m.group(1).strip() or s)[:120], kind, mask, label, True
    m = re.match(r"^(.*?)\s*Daily\s*$", s, re.IGNORECASE)
    if m:
        return (m.group(1).strip() or s)[:120], "daily", 127, "Daily", True
    return s[:120], None, 127, None, False


def parse_coord(lat_v, lon_v) -> tuple[float, float] | None:
    try:
        lat = float(cell(lat_v).translate(BN_DIGITS))
        lon = float(cell(lon_v).translate(BN_DIGITS))
    except ValueError:
        return None
    if math.isnan(lat) or math.isnan(lon):
        return None
    if not (BD_LAT[0] <= lat <= BD_LAT[1] and BD_LON[0] <= lon <= BD_LON[1]):
        return None
    return round(lat, 6), round(lon, 6)


def q(s) -> str:
    """SQL literal (standard_conforming_strings); None -> NULL."""
    if s is None:
        return "NULL"
    if isinstance(s, bool):
        return "true" if s else "false"
    if isinstance(s, (int, float)):
        return repr(s)
    return "'" + str(s).replace("\x00", "").replace("'", "''") + "'"


def norm(s) -> str:
    return re.sub(r"[^a-z0-9]", "", str(s or "").lower())


# ---------------------------------------------------------------------------------------------------- reading
def read_rows(path, sheet="retailer list"):
    import openpyxl

    wb = openpyxl.load_workbook(path, read_only=True)
    ws = wb[sheet]
    header = None
    for i, row in enumerate(ws.iter_rows(values_only=True)):
        if header is None:
            texts = [cell(c) for c in row]
            if "Outlet Code" in texts and "Zone Code" in texts:
                header = texts
            continue
        yield dict(zip(header, row))


class Importer:
    def __init__(self, a):
        self.a = a
        self.stats = collections.OrderedDict()
        self.counter = collections.Counter()
        self.wings = {}          # name -> code
        self.divisions = {}      # (wing, division) -> code
        self.territories = {}    # code -> (name, division key)
        self.zones = {}          # code -> (name, territory code)
        self.routes = {}         # code -> dict
        self.clusters = {}       # (zone code, name) -> cluster_type
        self.outlets = []        # dicts of the loaded subset
        self.seen_codes = set()
        self.by_wing = collections.Counter()
        self.by_division = collections.Counter()
        self.by_wing_coords = collections.Counter()
        self.territory_division = collections.defaultdict(collections.Counter)   # territory code -> division key counts
        self.codes = self.load_codes(a.codes_csv) if a.codes_csv else {}

    @staticmethod
    def load_codes(path):
        """territory_code -> (wing_code, division_code) from the radius evidence CSV."""
        out = {}
        with open(path, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                out[r["territory_code"]] = (r.get("wing_code") or "", r.get("division_code") or "")
        return out

    # ------------------------------------------------------------------------------------------- selection
    def selected(self, r) -> bool:
        a = self.a
        if a.division and norm(r["Division"]) != norm(a.division):
            return False
        if a.territory_code and cell(r["Territory Code"]) not in a.territory_code:
            return False
        if a.zone_code and cell(r["Zone Code"]) not in a.zone_code:
            return False
        if a.route_code and cell(r["Route Code"]) not in a.route_code:
            return False
        return True

    def run(self, rows):
        total = 0
        for r in rows:
            total += 1
            wing = clean_name(r["Wing"])
            division = clean_name(r["Division"])
            self.by_wing[wing] += 1
            self.by_division[(wing, division)] += 1
            coord = parse_coord(r["Latitude"], r["Longitude"])
            if coord:
                self.by_wing_coords[wing] += 1
            if cell(r["Latitude"]) == "" and cell(r["Longitude"]) == "":
                self.counter["rows without coordinates (whole sheet)"] += 1
            elif coord is None:
                self.counter["rows with coordinates outside Bangladesh or unreadable (whole sheet)"] += 1
            t_code = clean_node_code(r["Territory Code"])
            z_code = clean_node_code(r["Zone Code"])
            r_code = clean_node_code(r["Route Code"])
            if not (wing and division and t_code and z_code and r_code):
                self.counter["rows with a missing wing, division, territory, zone or route code (whole sheet)"] += 1
                continue
            in_subset = self.selected(r)
            if self.a.hierarchy == "all" or in_subset:
                self.wings.setdefault(wing, None)
                self.divisions.setdefault((wing, division), None)
                self.territories.setdefault(t_code, (clean_name(r["Territory"]) or t_code, (wing, division)))
                self.territory_division[t_code][(wing, division)] += 1
                self.zones.setdefault(z_code, (clean_name(r["Zone Name"]) or z_code, t_code))
                if r_code not in self.routes:
                    name, kind, mask, label, ok = parse_route_name(r["Route"])
                    self.routes[r_code] = {"name": name or r_code, "kind": kind, "mask": mask, "label": label, "zone": z_code}
                    if not ok:
                        self.counter["routes whose name carries no visit pattern (mask 127, no visit_kind)"] += 1
            if not in_subset:
                continue
            if self.a.limit and len(self.outlets) >= self.a.limit:
                continue
            self.counter["subset rows"] += 1
            code = clean_outlet_code(r["Outlet Code"])
            if code is None:
                self.counter["subset rejected: outlet code unusable"] += 1
                continue
            if code in self.seen_codes:
                self.counter["subset rejected: duplicate outlet code (first row kept)"] += 1
                continue
            if coord is None:
                self.counter["subset rejected: no coordinates or outside Bangladesh"] += 1
                continue
            self.seen_codes.add(code)
            channel, sub_code, mapped = clean_sub_channel(r["Sub Channel"])
            if not mapped:
                self.counter["subset outlets with an unmapped sub channel (channel kept, sub_channel_id null)"] += 1
            phone = clean_phone(r["Contact Number"])
            if phone is None:
                self.counter["subset outlets without a valid 11-digit mobile"] += 1
            geo = clean_geo_class(r["Geo Classification"])
            if geo is None:
                self.counter["subset outlets without a geo class"] += 1
            cluster = clean_name(r["Cluster Name"]) or "Unclustered"
            self.clusters.setdefault((z_code, cluster), clean_name(r["Cluster Type"], 60) or None)
            self.outlets.append({
                "code": code, "name": clean_name(r["Outlet Name"]) or code, "owner": clean_name(r["Owner Name"]),
                "phone": phone, "address": clean_name(r["Address"], 300) or None, "zone": z_code, "route": r_code,
                "cluster": cluster, "channel": channel, "sub": sub_code, "geo": geo, "lat": coord[0], "lng": coord[1],
            })
        self.stats["rows read"] = total
        self.finish()

    def finish(self):
        # codes for wings and divisions: the radius evidence (territory code -> wing/division code), majority per node
        div_votes = collections.defaultdict(collections.Counter)
        wing_votes = collections.defaultdict(collections.Counter)
        for t_code, (_, dkey) in self.territories.items():
            if t_code in self.codes:
                w, d = self.codes[t_code]
                if d:
                    div_votes[dkey][d] += 1
                if w:
                    wing_votes[dkey[0]][w] += 1
            else:
                self.counter["territories in the sheet without a radius row"] += 1
        self.counter["territories in the radius CSV absent from the sheet"] = sum(1 for c in self.codes if c not in self.territories)
        for wing in self.wings:
            self.wings[wing] = wing_votes[wing].most_common(1)[0][0] if wing_votes[wing] else "W-" + slug(wing)
        for dkey in self.divisions:
            self.divisions[dkey] = div_votes[dkey].most_common(1)[0][0] if div_votes[dkey] else "D-" + slug(dkey[1])
        used = collections.Counter(self.divisions.values())
        for dkey, code in list(self.divisions.items()):
            if used[code] > 1:                                  # two sheet divisions voted the same PDF code
                self.counter["divisions sharing a radius division code (slug code used)"] += 1
                self.divisions[dkey] = "D-" + slug(dkey[1])
        # a territory printed under two divisions keeps the majority one
        for t_code, (name, _) in self.territories.items():
            votes = self.territory_division[t_code]
            if len(votes) > 1:
                self.counter["territories listed under more than one division (majority kept)"] += 1
            self.territories[t_code] = (name, votes.most_common(1)[0][0])
        # placeholder pins: a 6-decimal point shared by n >= min outlets of the subset
        points = collections.Counter((o["lat"], o["lng"]) for o in self.outlets)
        shared = {p for p, n in points.items() if n >= self.a.placeholder_min_shared}
        seq = collections.Counter()
        for o in self.outlets:
            o["confirmed"] = (o["lat"], o["lng"]) not in shared
            seq[o["route"]] += 1
            o["seq"] = seq[o["route"]]
        self.counter["subset outlets on a shared point (location_confirmed false)"] = sum(1 for o in self.outlets if not o["confirmed"])
        self.stats["subset outlets emitted"] = len(self.outlets)
        self.stats["hierarchy emitted"] = (f"{len(self.wings)} wings, {len(self.divisions)} divisions, {len(self.territories)} territories, "
                                           f"{len(self.zones)} zones, {len(self.routes)} routes, {len(self.clusters)} clusters (subset only)")

    # ------------------------------------------------------------------------------------------------ SQL
    def write_sql(self, out):
        a = self.a
        w = out.write
        w(f"-- generated by tools/data/retailers_to_sql.py on {dt.datetime.now(dt.timezone.utc):%Y-%m-%d %H:%M} UTC; "
          f"filters: division={a.division!r} territory={a.territory_code} zone={a.zone_code} route={a.route_code} limit={a.limit}\n")
        w("-- idempotent: every statement upserts by the vendor code and changes a row only when a value differs.\n")
        w("BEGIN;\nSET LOCAL lock_timeout = '10s';\n")
        self.upsert(w, "app.wing", "code, name", [(code, name) for name, code in self.wings.items()],
                    "v.code, v.name", "", "code", ["name"])
        self.upsert(w, "app.division", "code, name, wing_id",
                    [(code, name, self.wings[wing]) for (wing, name), code in self.divisions.items()],
                    "v.code, v.name, w.id", "JOIN app.wing w ON w.code = v.c3", "code", ["name", "wing_id"])
        self.upsert(w, "app.territory", "code, name, division_id",
                    [(code, name, self.divisions[dkey]) for code, (name, dkey) in self.territories.items()],
                    "v.code, v.name, d.id", "JOIN app.division d ON d.code = v.c3", "code", ["name", "division_id"])
        self.upsert(w, "app.zone", "code, name, territory_id", [(code, name, t) for code, (name, t) in self.zones.items()],
                    "v.code, v.name, t.id", "JOIN app.territory t ON t.code = v.c3", "code", ["name", "territory_id"])
        self.upsert(w, "app.route", "code, name, display_label, zone_id, kind, visit_kind, visit_days_mask",
                    [(code, r["name"], r["label"], r["zone"], r["kind"], r["mask"]) for code, r in self.routes.items()],
                    "v.code, v.name, v.c3, z.id, 'sr', v.c5, v.c6::smallint", "JOIN app.zone z ON z.code = v.c4", "code",
                    ["name", "display_label", "zone_id", "visit_kind", "visit_days_mask"])
        self.upsert(w, "app.cluster", "zone_id, name, cluster_type",
                    [(zone, name, ctype) for (zone, name), ctype in self.clusters.items()],
                    "z.id, v.name, v.c3", "JOIN app.zone z ON z.code = v.code", "zone_id, name", ["cluster_type"])
        cols = ["name", "owner_name", "contact_number", "address", "zone_id", "route_id", "cluster_id", "channel",
                "sub_channel_id", "geo_class", "lat", "lng", "location_basis", "location_confirmed", "visit_sequence"]
        rows = [(o["code"], o["name"], o["owner"], o["phone"], o["address"], o["zone"], o["route"], o["cluster"], o["channel"],
                 o["sub"], o["geo"], o["lat"], o["lng"], o["confirmed"], o["seq"]) for o in self.outlets]
        self.upsert(w, "app.outlet", "code, " + ", ".join(cols), rows,
                    "v.code, v.name, v.c3, v.c4, v.c5, z.id, r.id, cl.id, v.c9, sc.id, v.c11, v.c12::double precision, "
                    "v.c13::double precision, 'master', v.c14::boolean, v.c15::int",
                    "JOIN app.zone z ON z.code = v.c6 JOIN app.route r ON r.code = v.c7 "
                    "JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = v.c8 LEFT JOIN app.sub_channel sc ON sc.code = v.c10",
                    "code", cols)
        # placement and location history for the loaded outlets (like db/seed/03), once per outlet and pin
        w("CREATE TEMP TABLE imp_outlet_code (code text PRIMARY KEY) ON COMMIT DROP;\n")
        for chunk in chunks([o["code"] for o in self.outlets], 1000):
            w("INSERT INTO imp_outlet_code (code) VALUES " + ", ".join(f"({q(c)})" for c in chunk) + ";\n")
        w("UPDATE app.outlet_placement_history h SET valid_to = current_date\n"
          "  FROM app.outlet o JOIN imp_outlet_code c ON c.code = o.code\n"
          " WHERE h.outlet_id = o.id AND h.valid_to IS NULL AND h.valid_from < current_date\n"
          "   AND (h.route_id, h.cluster_id) IS DISTINCT FROM (o.route_id, o.cluster_id);\n")
        w("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)\n"
          "  SELECT o.id, o.route_id, o.cluster_id, current_date FROM app.outlet o JOIN imp_outlet_code c ON c.code = o.code\n"
          "   WHERE NOT EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id AND h.valid_to IS NULL);\n")
        w("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis)\n"
          "  SELECT o.id, o.lat, o.lng, 'migration', 'master' FROM app.outlet o JOIN imp_outlet_code c ON c.code = o.code\n"
          "   WHERE NOT EXISTS (SELECT 1 FROM app.outlet_location_history h WHERE h.outlet_id = o.id AND h.lat = o.lat AND h.lng = o.lng);\n")
        w("COMMIT;\n")

    @staticmethod
    def upsert(w, table, cols, rows, select, joins, conflict, update_cols, batch=500):
        """INSERT ... SELECT ... FROM (VALUES ...) AS v(code, name, c3, c4, ...) joined to the parent tables by code;\n        ON CONFLICT DO UPDATE only when a value differs (app.touch_master then leaves version and updated_at alone)."""
        if not rows:
            return
        width = len(rows[0])
        vcols = ", ".join(["code", "name"][:width] + [f"c{i}" for i in range(3, width + 1)])
        set_clause = ", ".join(f"{c} = EXCLUDED.{c}" for c in update_cols)
        distinct = ("(" + ", ".join(f"{table}.{c}" for c in update_cols) + ") IS DISTINCT FROM ("
                    + ", ".join(f"EXCLUDED.{c}" for c in update_cols) + ")")
        for chunk in chunks(rows, batch):
            values = ",\n    ".join("(" + ", ".join(q(x) for x in row) + ")" for row in chunk)
            w(f"INSERT INTO {table} ({cols})\n  SELECT {select}\n  FROM (VALUES\n    {values}) AS v({vcols})\n"
              + (f"  {joins}\n" if joins else "")
              + f"  ON CONFLICT ({conflict}) DO UPDATE SET {set_clause}\n  WHERE {distinct};\n")

    # ------------------------------------------------------------------------------------------- statistics
    def stats_markdown(self) -> str:
        lines = [f"Generated by `tools/data/retailers_to_sql.py` on {dt.date.today()} (counts only).", ""]
        for k, v in self.stats.items():
            lines.append(f"- {k}: {v}")
        lines += ["", "| Wing | Rows | With coordinates |", "| --- | ---: | ---: |"]
        for wing, n in self.by_wing.most_common():
            lines.append(f"| {wing} | {n:,} | {self.by_wing_coords[wing]:,} |")
        lines += ["", "| Wing | Division | Rows |", "| --- | --- | ---: |"]
        for (wing, division), n in sorted(self.by_division.items()):
            lines.append(f"| {wing} | {division} | {n:,} |")
        lines += ["", "Quality counters:", ""]
        for k, v in sorted(self.counter.items()):
            lines.append(f"- {k}: {v:,}")
        return "\n".join(lines) + "\n"

    def nearest_routes(self, lat, lng, n=8):
        """Routes of the subset by the distance of their outlet centroid to a point (to choose a test route)."""
        groups = collections.defaultdict(list)
        for o in self.outlets:
            groups[o["route"]].append(o)
        out = []
        for code, os_ in groups.items():
            clat = sum(o["lat"] for o in os_) / len(os_)
            clng = sum(o["lng"] for o in os_) / len(os_)
            d = haversine(lat, lng, clat, clng)
            r = self.routes[code]
            out.append((d, code, r["name"], r["label"], r["zone"], len(os_), sum(1 for o in os_ if o["confirmed"])))
        out.sort()
        return out[:n]


def haversine(lat1, lng1, lat2, lng2):
    p = math.pi / 180
    a = 0.5 - math.cos((lat2 - lat1) * p) / 2 + math.cos(lat1 * p) * math.cos(lat2 * p) * (1 - math.cos((lng2 - lng1) * p)) / 2
    return 12742000 * math.asin(math.sqrt(a))


def slug(s):
    return re.sub(r"[^A-Z0-9]+", "-", str(s).upper()).strip("-")[:36] or "X"


def chunks(seq, n):
    seq = list(seq)
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("xlsx")
    ap.add_argument("--sheet", default="retailer list")
    ap.add_argument("--out", help="SQL file to write (omit for statistics only)")
    ap.add_argument("--division", help="load only this division (name, case-insensitive)")
    ap.add_argument("--territory-code", action="append", help="load only these territory codes (repeatable)")
    ap.add_argument("--zone-code", action="append")
    ap.add_argument("--route-code", action="append")
    ap.add_argument("--limit", type=int, help="at most N outlets")
    ap.add_argument("--hierarchy", choices=["all", "subset"], default="all", help="emit the whole country's wings..routes (default) or only the subset's")
    ap.add_argument("--codes-csv", help="docs/evidence/geofence-radius-2026-09.csv: wing and division codes per territory code")
    ap.add_argument("--placeholder-min-shared", type=int, default=3, help="cfg.outlet.placeholder_pin_min_shared (DQ-48)")
    ap.add_argument("--stats", action="store_true", help="print the statistics (markdown, counts only)")
    ap.add_argument("--stats-out", help="write the statistics to this file")
    ap.add_argument("--nearest", help="LAT,LNG: list the subset routes nearest to this point")
    a = ap.parse_args(argv)
    imp = Importer(a)
    imp.run(read_rows(a.xlsx, a.sheet))
    if a.out:
        with open(a.out, "w", encoding="utf-8", newline="\n") as f:
            imp.write_sql(f)
        print(f"wrote {a.out}: {len(imp.outlets)} outlets", file=sys.stderr)
    md = imp.stats_markdown()
    if a.stats or not a.out:
        print(md)
    if a.stats_out:
        with open(a.stats_out, "w", encoding="utf-8", newline="\n") as f:
            f.write(md)
    if a.nearest:
        lat, lng = (float(x) for x in a.nearest.split(","))
        print("| Distance m | Route code | Route | Days | Zone code | Outlets | Confirmed pins |\n| ---: | --- | --- | --- | --- | ---: | ---: |")
        for d, code, name, label, zone, n, conf in imp.nearest_routes(lat, lng):
            print(f"| {d:,.0f} | {code} | {name} | {label} | {zone} | {n} | {conf} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
