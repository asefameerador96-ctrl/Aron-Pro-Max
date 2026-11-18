"""SYNTHETIC data shaped like the Apsis inventory. Every name, code and number here is invented (SYN-*, 0000000xxxx);
nothing is derived from the real dump, which no one in this repository has seen."""
import gzip
from pathlib import Path


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int,)):
        return str(v)
    s = str(v)
    if s.lstrip("-").replace(".", "", 1).isdigit() and not s.startswith("0") or s in ("0",):
        return s
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\0", "\\0") + "'"


def insert(table, rows, per_stmt=1000):
    out = []
    for i in range(0, len(rows), per_stmt):
        chunk = rows[i:i + per_stmt]
        out.append(f"INSERT INTO `{table}` VALUES " + ",".join("(" + ",".join(q(v) for v in r) + ")" for r in chunk) + ";")
    return "\n".join(out)


HEADER = "-- MySQL dump 10.13  Distrib 8.0.36\n/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;\nSET NAMES utf8mb4;\n"


def table_file(name, ddl_cols, pk, rows, per_stmt=1000):
    pk_line = f",\n  PRIMARY KEY (`{pk}`)" if pk else ""
    ddl = f"DROP TABLE IF EXISTS `{name}`;\nCREATE TABLE `{name}` (\n" + ",\n".join(f"  {c}" for c in ddl_cols) + pk_line + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
    return HEADER + ddl + "LOCK TABLES `%s` WRITE;\n" % name + insert(name, rows, per_stmt) + "\nUNLOCK TABLES;\n"


# --- the synthetic master (loaded into the test database by tests, standing in for the October master-data load)
MASTER_SQL = """
INSERT INTO app.wing(code,name) VALUES ('SW1','Syn Wing');
INSERT INTO app.division(code,name,wing_id) SELECT 'SD1','Syn Division',id FROM app.wing WHERE code='SW1';
INSERT INTO app.territory(code,name,division_id) SELECT 'ST1','Syn Territory',id FROM app.division WHERE code='SD1';
INSERT INTO app.zone(code,name,territory_id) SELECT 'SZ1','Syn Zone',id FROM app.territory WHERE code='ST1';
INSERT INTO app.cluster(zone_id,name) SELECT id,'syn cluster' FROM app.zone WHERE code='SZ1';
INSERT INTO app.route(code,name,zone_id,kind,visit_days_mask) SELECT 'SR-1','Syn Route 1',id,'sr',127 FROM app.zone WHERE code='SZ1';
INSERT INTO app.route(code,name,zone_id,kind,visit_days_mask) SELECT 'SR-2','Syn Route 2',id,'sr',63 FROM app.zone WHERE code='SZ1';
INSERT INTO app.outlet(code,name,owner_name,zone_id,route_id,cluster_id,channel)
  SELECT c, 'Syn Outlet '||c, 'Syn Owner', z.id, r.id, cl.id, 'GT'
    FROM (VALUES ('SYN-O-0001','SR-1'),('SYN-O-0002','SR-1'),('SYN-O-0004','SR-2')) v(c, rc)
    JOIN app.route r ON r.code = v.rc JOIN app.zone z ON z.id = r.zone_id JOIN app.cluster cl ON cl.zone_id = z.id;
INSERT INTO app.product_node(level,name,code) VALUES ('category','Syn Cat','syn-cat');
INSERT INTO app.product_node(level,name,code,parent_id) SELECT 'segment','Syn Seg','syn-seg',id FROM app.product_node WHERE code='syn-cat';
INSERT INTO app.product_node(level,name,code,parent_id) SELECT 'brand','Syn Brand','syn-brand',id FROM app.product_node WHERE code='syn-seg';
INSERT INTO app.product_node(level,name,code,parent_id) SELECT 'variant','Syn Variant','syn-var',id FROM app.product_node WHERE code='syn-brand';
INSERT INTO app.sku(code,variant_id,category_code,name,short_name,base_unit,base_per_pack,entry_unit_default)
  SELECT 'SYN-SKU-1', id, 'cigarette', 'Syn Sku 1', 'S1', 'stick', 20, 'pack' FROM app.product_node WHERE code='syn-var';
INSERT INTO app.sku(code,variant_id,category_code,name,short_name,base_unit,base_per_pack,entry_unit_default)
  SELECT 'SYN-SKU-2', id, 'match', 'Syn Sku 2', 'S2', 'dozen', 1, 'dozen' FROM app.product_node WHERE code='syn-var';
INSERT INTO app.app_user(username,full_name,role,employee_code) VALUES ('sy01','Syn Rep One','SR','SY01');
"""

PASSWORD_MARKER = "SYN-HASH-NOT-A-REAL-HASH-0000"
DEVICE_MARKER = "SYN-DEVICE-0000"


def build(dump_dir, memo_dup=False, rows_sales=None, sales_by_id=False, outlet_rows=None):
    d = Path(dump_dir)
    d.mkdir(parents=True, exist_ok=True)
    f = {}
    f["master_outlets"] = table_file("master_outlets", [
        "`outlet_id` bigint NOT NULL", "`outlet_code` varchar(32) NOT NULL", "`outlet_name` varchar(120)", "`owner_name` varchar(120)",
        "`mobile_no` varchar(20)", "`dep_code` varchar(20)", "`route_code` varchar(20)", "`is_active` tinyint(1)",
        "`created_at` datetime", "`updated_at` datetime"], "outlet_id", [
        *(outlet_rows or [
        (1, "SYN-O-0001", "Syn Outlet 1", "Syn Owner A", "00000000001", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", "2026-02-02 03:04:05"),
        (2, "SYN-O-0002", "Syn Outlet 2 it's", "Syn Owner B", "00000000002", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", "0000-00-00 00:00:00"),
        (3, "SYN-O-0003", "Syn Outlet 3", "Syn Owner C", "00000000003", "SZ1", "SR-9", 0, "2026-01-02 03:04:05", None),
        (1, "SYN-O-0001", "Syn Outlet 1", "Syn Owner A", "00000000001", "SZ1", "SR-1", 1, "2026-01-02 03:04:05", "2026-02-02 03:04:05")])])  # duplicate key
    f["master_routes"] = table_file("master_routes", ["`route_id` int NOT NULL", "`route_code` varchar(20)", "`route_name` varchar(80)",
                                    "`dep_code` varchar(20)", "`is_active` tinyint(1)"], "route_id",
                                    [(1, "SR-1", "Syn Route 1", "SZ1", 1), (2, "SR-2", "Syn Route 2", "SZ1", 1), (3, "SR-9", "Syn Old Route", "SZ1", 0)])
    f["master_sku"] = table_file("master_sku", ["`sku_id` int NOT NULL", "`sku_code` varchar(20)", "`sku_name` varchar(80)", "`pack_size` int"],
                                 "sku_id", [(1, "SYN-SKU-1", "Syn Sku 1", 20), (2, "SYN-SKU-2", "Syn Sku 2", 1), (3, "SYN-SKU-9", "Syn Gone", 10)])
    f["master_field_forces"] = table_file("master_field_forces", ["`ff_id` int NOT NULL", "`ff_code` varchar(10)", "`ff_name` varchar(80)",
                                          "`password_hash` varchar(100)", "`device_id` varchar(40)", "`api_token` varchar(64)"], "ff_id",
                                          [(1, "SY01", "Syn Rep One", PASSWORD_MARKER, DEVICE_MARKER, "SYN-TOKEN-1")])
    sales = rows_sales or [
        # id, outlet, sku, route, ff, sales_date (UTC datetime), qty, amount (taka), zero flag, memo, remarks
        (1, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", "2026-03-05 17:59:59", 3, "123.455", 0, "SYN-M-1", "it's \"fine\"\nline2"),
        (2, "SYN-O-0001", "SYN-SKU-2", "SR-1", "SY01", "2026-03-05 18:00:00", 2, "0.0005", 0, "SYN-M-1" if not memo_dup else "SYN-M-1", None),
        (3, "SYN-O-0002", "SYN-SKU-1", "SR-1", "SY01", "2026-03-31 18:30:00", 1, "-10.0004", 0, "SYN-M-2", None),   # Dhaka: 2026-04-01
        (4, "SYN-O-0002", None, "SR-1", "SY01", "2026-04-02 05:00:00", 0, "0", 1, "SYN-M-3" if not memo_dup else "SYN-M-1", None),   # zero sale, no SKU
        (5, "SYN-O-0003", "SYN-SKU-9", "SR-9", "SY01", "2026-04-03 05:00:00", 7, "7.000", 0, "SYN-M-4", None),   # outlet and SKU not in our master
        (6, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", "0000-00-00 00:00:00", 1, "1.000", 0, "SYN-M-5", None),   # zero date: skipped and counted
        (1, "SYN-O-0001", "SYN-SKU-1", "SR-1", "SY01", "2026-03-05 17:59:59", 3, "123.455", 0, "SYN-M-1", "it's \"fine\"\nline2"),   # exact duplicate row
    ]
    if sales_by_id:       # facts that reference the masters by vendor id, not by code
        oid = {"SYN-O-0001": 1, "SYN-O-0002": 2, "SYN-O-0003": 3}
        sid = {"SYN-SKU-1": 1, "SYN-SKU-2": 2, "SYN-SKU-9": 3, None: None}
        rid = {"SR-1": 1, "SR-2": 2, "SR-9": 3}
        f["trans_sales_by_outlet"] = table_file("trans_sales_by_outlet", [
            "`id` bigint NOT NULL", "`outlet_id` bigint", "`sku_id` bigint", "`route_id` int", "`ff_id` int", "`sales_date` datetime", "`quantity` int",
            "`amount` decimal(14,4)", "`is_zero_sale` tinyint(1)"], "id",
            [(r[0], oid[r[1]], sid[r[2]], rid[r[3]], 1, r[5], r[6], r[7], r[8]) for r in sales], per_stmt=2)
    else:
        f["trans_sales_by_outlet"] = table_file("trans_sales_by_outlet", [
            "`id` bigint NOT NULL", "`outlet_code` varchar(32)", "`sku_code` varchar(20)", "`route_code` varchar(20)", "`ff_code` varchar(10)",
            "`sales_date` datetime", "`quantity` int", "`amount` decimal(14,4)", "`is_zero_sale` tinyint(1)", "`memo_no` varchar(30)", "`remarks` text"],
            "id", sales, per_stmt=2)
    f["live_geo_data_by_outlets"] = table_file("live_geo_data_by_outlets", [
        "`id` bigint NOT NULL", "`outlet_code` varchar(32)", "`ff_code` varchar(10)", "`route_code` varchar(20)", "`captured_at` datetime",
        "`latitude` decimal(10,7)", "`longitude` decimal(10,7)", "`accuracy` double", "`distance` double", "`is_valid` tinyint(1)", "`device_id` varchar(40)"], "id", [
        (1, "SYN-O-0001", "SY01", "SR-1", "2026-03-05 05:00:00", "23.7800000", "90.4100000", 12.5, 30.25, 1, DEVICE_MARKER),
        (2, "SYN-O-0002", "SY01", "SR-1", "2026-03-31 19:00:00", "23.7810000", "90.4110000", 40, 120, 0, DEVICE_MARKER),
        (3, "SYN-O-0003", "SY01", "SR-9", "2026-04-03 05:00:00", None, None, None, None, None, DEVICE_MARKER)])
    f["master_ff_attendance_records"] = table_file("master_ff_attendance_records", [
        "`id` bigint NOT NULL", "`ff_code` varchar(10)", "`check_in_time` datetime", "`check_out_time` datetime", "`latitude` decimal(10,7)", "`longitude` decimal(10,7)"], "id", [
        (1, "SY01", "2026-03-05 02:00:00", "2026-03-05 11:00:00", "23.78", "90.41"),
        (2, "SY01", "2026-03-31 18:10:00", None, "23.78", "90.41")])
    f["trans_section_summary"] = table_file("trans_section_summary", [
        "`id` bigint NOT NULL", "`route_code` varchar(20)", "`summary_date` date", "`target_outlets` int", "`visited_outlets` int", "`successful_calls` int"], "id", [
        (1, "SR-1", "2026-03-05", 2, 1, 1), (2, "SR-1", "2026-04-01", 2, 1, 1)])
    f["master_outlet_section_mapping"] = table_file("master_outlet_section_mapping", [
        "`id` bigint NOT NULL", "`outlet_code` varchar(32)", "`route_code` varchar(20)", "`effective_from` date", "`effective_to` date"], "id", [
        (1, "SYN-O-0001", "SR-1", "2026-01-01", None), (2, "SYN-O-0002", "SR-1", "2026-01-01", None), (3, "SYN-O-0004", "SR-2", "2026-01-01", None)])
    f["master_geo_config"] = table_file("master_geo_config", ["`id` bigint NOT NULL", "`outlet_code` varchar(32)", "`latitude` decimal(10,7)", "`longitude` decimal(10,7)"], "id", [
        (1, "SYN-O-0001", "23.7800000", "90.4100000"), (2, "SYN-O-0002", "0.0", "0.0"), (3, "SYN-O-0004", "23.7900000", "90.4200000")])
    f["master_promotion"] = table_file("master_promotion", ["`id` int NOT NULL", "`promotion_code` varchar(20)", "`created_at` datetime", "`token_limit` int"], "id",
                                       [(1, "SYN-PROMO-1", "2026-03-10 00:00:00", 5)])
    # a no-primary-key table: exact duplicates collapse via the full-row key
    f["trans_by_outlet_promotion_data"] = table_file("trans_by_outlet_promotion_data", ["`outlet_code` varchar(32)", "`promotion_date` date", "`units` int"], None, [
        ("SYN-O-0001", "2026-03-06", 4), ("SYN-O-0001", "2026-03-06", 4), ("SYN-O-0002", "2026-04-06", 1),
        ("SYN-O-0001", "0000-00-00", 2)])         # a MySQL zero date in a programme table
    for name, text in f.items():
        (d / f"{name}.sql").write_text(text, encoding="utf-8")
    return d
