#!/usr/bin/env python3
"""N-054: replay one synthetic selling day of the load fleet (tools/loadgen/generate.sql) through the API.

For each SR lsr1..lsrN, on its own bound phone (device_uuid 1d000000-0000-4000-8000-<n in hex>):
  1 login            POST /v1/auth/login (app_sr)          the seed password from the environment, never printed
  2 morning download GET  /v1/sync/bundle                  today's planned routes, outlets with pins, priced SKUs
  3 the day          POST /v1/sync/batch (gzip, batches)    for --visit-rate of the planned outlets a visit; for
                                                            --memo-rate of the visits a memo with two lines on
                                                            credit and a close; a third of the credits collected
  4 evening resend   POST /v1/sync/batch, new batch_uuids   the whole day again: every record must be duplicate
  5 reconciliation   server_totals of the last answer       accepted per type == the device's own counts
SRs run --concurrency at a time. The report (stdout and --out JSON) has calls, latency p50/p95/max per phase, acks by
status, and every SR whose server count differs from its device count. Exit 0 only when every SR reconciled and the
resend changed nothing. Use a scratch environment loaded by generate.sql; the replay WRITES a day of sales.

  ARON_LOADGEN_PASSWORD=<seed password> python3 tools/loadgen/replay.py --api http://127.0.0.1:8080 --srs 50
"""
import argparse
import concurrent.futures
import datetime
import gzip
import json
import os
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

APP_VERSION = "1.0.9+9"
TYPES = ("visit", "memo", "memo_line", "visit_close", "due_collection")


def device_uuid(n: int) -> str:
    return f"1d000000-0000-4000-8000-{n:012x}"


class Api:
    def __init__(self, base: str, timeout: float):
        self.base = base.rstrip("/")
        self.timeout = timeout
        self.lock = threading.Lock()
        self.latency = {}  # phase -> [ms]
        self.statuses = {}  # phase -> {status: n}

    def call(self, phase, method, path, body=None, token=None, device=None, gz=False):
        data = None
        headers = {"X-App-Version": APP_VERSION, "Accept": "application/json"}
        if body is not None:
            raw = json.dumps(body, separators=(",", ":")).encode()
            data = gzip.compress(raw) if gz else raw
            headers["Content-Type"] = "application/json"
            if gz:
                headers["Content-Encoding"] = "gzip"
        if token:
            headers["Authorization"] = "Bearer " + token
        if device:
            headers["X-Device-Id"] = device
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                status, text = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read()
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            status, text = 599, str(e).encode()
        ms = (time.perf_counter() - t0) * 1000
        with self.lock:
            self.latency.setdefault(phase, []).append(ms)
            st = self.statuses.setdefault(phase, {})
            st[status] = st.get(status, 0) + 1
        if r_is_gzip(text):
            text = gzip.decompress(text)
        try:
            return status, json.loads(text) if text else None
        except ValueError:
            return status, None


def r_is_gzip(b: bytes) -> bool:
    return len(b) > 2 and b[0] == 0x1F and b[1] == 0x8B


def envelope(day, rtype, cu, family, rank, payload, at, route_id, bundle_version, config_version):
    return {
        "type": rtype, "client_uuid": cu, "family_uuid": family, "rank": rank, "schema_version": 1, "business_date": day,
        "captured_at": at, "captured_elapsed_ms": 18_330_000, "boot_count": 412, "clock_offset_ms": 0, "captured_offline": True,
        "route_id": route_id, "bundle_version": bundle_version, "bundle_stale": False, "config_version": config_version, "payload": payload,
    }


def fix(purpose, lat, lng):
    return {"purpose": purpose, "fix_status": "ok", "lat": lat, "lng": lng, "accuracy_m": 12.0, "provider": "fused", "is_mock": False, "reused": False,
            "device": {"device_owner": True, "dev_options_enabled": False, "adb_enabled": False, "auto_time_enabled": True, "mock_app_present": False}}


def capture_day(username, bundle, rnd, visit_rate, memo_rate):
    """The device's records for the day and its own counts (what the outbox committed)."""
    meta = bundle["meta"]
    day = meta["valid_for_business_date"]
    bv, cv = meta["bundle_version"], meta["config_version"]
    prices = {p["sku_id"]: p["amount_mtk"] for p in bundle.get("prices", []) if p.get("price_type") == "outlet" and p.get("amount_mtk", 0) > 0 and p.get("per_base_qty", 1) == 1}
    skus = sorted(prices)[:2]
    if len(skus) < 2:
        raise RuntimeError("the bundle has fewer than two priced SKUs")
    now = datetime.datetime.now(datetime.timezone.utc)
    records, seq = [], 0
    for route in bundle["routes"]:
        if not route.get("planned_today"):
            continue
        for o in route.get("outlets", []):
            if rnd.random() >= visit_rate or o.get("lat") is None:
                continue
            seq += 1
            # Capture times spread over the last hours, one second apart, never in the future.
            at = (now - datetime.timedelta(seconds=7200 - seq)).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            visit = str(uuid.uuid4())
            records.append(envelope(day, "visit", visit, visit, 0, {
                "visit_kind": "sr_call", "outlet_id": o["outlet_id"], "opened_at": at, "sequence_no": seq, "planned": True,
                "fix": fix("visit_open", o["lat"], o["lng"]),
                "geo": {"verdict": "in_range", "distance_m": 4.0, "radius_m_used": 100, "max_accuracy_m_used": 100, "location_basis": "master", "action": "sale_allowed"},
            }, at, route["route_id"], bv, cv))
            sold = rnd.random() < memo_rate
            if sold:
                memo = str(uuid.uuid4())
                q1 = 10 + seq % 50
                g1, g2 = q1 * prices[skus[0]], 10 * prices[skus[1]]
                gross = g1 + g2
                due = 1000 * rnd.randint(1, 30) if rnd.random() < 0.3 and gross > 30_000 else 0
                memo_no = f"{username}-{day[2:4]}{day[5:7]}{day[8:10]}-{1000 + seq}"
                records.append(envelope(day, "memo", memo, visit, 1, {
                    "visit_client_uuid": visit, "outlet_id": o["outlet_id"], "memo_no": memo_no, "memo_kind": "sale", "committed_at": at,
                    "price_list_date": day, "price_type": "outlet", "gross_mtk": gross, "offer_discount_mtk": 0, "drp_discount_mtk": 0,
                    "qc_deduction_mtk": 0, "round_adj_mtk": 0, "net_mtk": gross, "paid_mtk": gross - due, "due_mtk": due, "is_credit": due > 0,
                    "line_count": 2, "discount_line_count": 0, "qc_line_count": 0, "offer_version_ids": [], "rounding_mode": "half_up_paisa",
                }, at, route["route_id"], bv, cv))
                for no, (sku, q, g) in enumerate(((skus[0], q1, g1), (skus[1], 10, g2)), 1):
                    records.append(envelope(day, "memo_line", str(uuid.uuid4()), visit, 2, {
                        "memo_client_uuid": memo, "line_no": no, "sku_id": sku, "line_kind": "sale", "qty_entered": q, "unit_entered": "stick",
                        "pack_factor": 1, "qty_base": q, "price_type": "outlet", "price_valid_from": day, "base_price_mtk": prices[sku], "price_per_qty": 1, "gross_mtk": g,
                    }, at, route["route_id"], bv, cv))
                if due and rnd.random() < 0.33:
                    cu = str(uuid.uuid4())
                    records.append(envelope(day, "due_collection", cu, cu, 0, {
                        "outlet_id": o["outlet_id"], "against_memo_client_uuid": memo, "against_memo_no": memo_no, "against_memo_business_date": day,
                        "amount_mtk": due, "is_full_settlement": True, "outstanding_before_mtk": due, "payment_mode": "cash",
                    }, at, route["route_id"], bv, cv))
            records.append(envelope(day, "visit_close", str(uuid.uuid4()), visit, 1, {
                "visit_client_uuid": visit, "outcome_code": "sold" if sold else "no_order", "call_declined": False, "ended_at": at, "is_zero_sale": not sold,
            }, at, route["route_id"], bv, cv))
    counts = {}
    for r in records:
        counts[r["type"]] = counts.get(r["type"], 0) + 1
    return day, records, counts


def batch(device, day, counts, records):
    return {"batch_uuid": str(uuid.uuid4()), "device_uuid": device, "schema_version": 1, "app_version": APP_VERSION, "trigger": "manual",
            "sent_at_device": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "pending_rows": 0, "time_anchors": [], "device_counts": {day: counts}, "records": records}


def one_sr(api, n, args, password):
    username, device = f"{args.prefix}{n}", device_uuid(n)
    out = {"sr": username, "ok": False}
    st, login = api.call("login", "POST", "/v1/auth/login", {"username": username, "password": password, "client": "app_sr", "device_uuid": device})
    token = (login or {}).get("access_token")
    if st != 200 or not token:
        out["error"] = f"login {st} {(login or {}).get('code')}"
        return out
    st, bundle = api.call("bundle", "GET", "/v1/sync/bundle", token=token, device=device)
    if st != 200 or not bundle:
        out["error"] = f"bundle {st} {(bundle or {}).get('code')}"
        return out
    day, records, counts = capture_day(username, bundle, random.Random(args.seed * 100_003 + n), args.visit_rate, args.memo_rate)
    out.update(day=day, device_counts=counts, records=len(records))
    acked, last, acks = set(), None, {}
    pending = list(records)
    for _ in range(6):  # the outbox: send, then resend what is not final (children whose parent came later)
        if not pending:
            break
        for i in range(0, len(pending), args.batch):
            st, r = api.call("batch", "POST", "/v1/sync/batch", batch(device, day, counts, pending[i:i + args.batch]), token=token, device=device, gz=True)
            for a in (r or {}).get("acks", []):
                acks[a["status"]] = acks.get(a["status"], 0) + 1
                if a["status"] in ("accepted", "duplicate"):
                    acked.add(a["client_uuid"])
            last = r or last
        pending = [x for x in pending if x["client_uuid"] not in acked]
    out["acks"] = acks
    out["unacked"] = len(pending)
    # The evening resend: everything again under new batch_uuids; nothing may be stored twice.
    not_dup = 0
    for i in range(0, len(records), args.batch):
        st, r = api.call("resend", "POST", "/v1/sync/batch", batch(device, day, counts, records[i:i + args.batch]), token=token, device=device, gz=True)
        not_dup += sum(1 for a in (r or {}).get("acks", []) if a["status"] != "duplicate") if r else len(records[i:i + args.batch])
        last = r or last
    out["resend_not_duplicate"] = not_dup
    totals = next((t for t in (last or {}).get("server_totals", []) if t.get("business_date") == day), None)
    server = {k: v.get("accepted", 0) for k, v in (totals or {}).get("by_type", {}).items() if v.get("accepted", 0)}
    out["server_counts"] = server
    out["ok"] = not pending and not_dup == 0 and server == counts
    return out


def pct(xs, p):
    xs = sorted(xs)
    return round(xs[min(len(xs) - 1, int(p / 100 * len(xs)))], 1) if xs else None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", required=True, help="API origin, e.g. http://127.0.0.1:8080")
    ap.add_argument("--srs", type=int, default=50, help="replay SRs lsr1..lsrN (default 50)")
    ap.add_argument("--first", type=int, default=1, help="first SR number (default 1)")
    ap.add_argument("--prefix", default="lsr")
    ap.add_argument("--concurrency", type=int, default=8)
    ap.add_argument("--batch", type=int, default=100, help="records per batch (default 100)")
    ap.add_argument("--visit-rate", type=float, default=0.65)
    ap.add_argument("--memo-rate", type=float, default=0.85)
    ap.add_argument("--seed", type=int, default=54)
    ap.add_argument("--timeout", type=float, default=60)
    ap.add_argument("--password-env", default="ARON_LOADGEN_PASSWORD")
    ap.add_argument("--out", help="write the JSON report here")
    args = ap.parse_args(argv)
    password = os.environ.get(args.password_env)
    if not password:
        print(f"set {args.password_env} (the seed password; it is never printed)", file=sys.stderr)
        return 2
    api = Api(args.api, args.timeout)
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(lambda n: one_sr(api, n, args, password), range(args.first, args.first + args.srs)))
    elapsed = time.time() - t0
    bad = [r for r in results if not r["ok"]]
    report = {
        "srs": args.srs, "seconds": round(elapsed, 1), "records": sum(r.get("records", 0) for r in results),
        "device_counts": {t: sum(r.get("device_counts", {}).get(t, 0) for r in results) for t in TYPES},
        "server_counts": {t: sum(r.get("server_counts", {}).get(t, 0) for r in results) for t in TYPES},
        "calls": {p: len(v) for p, v in api.latency.items()},
        "statuses": api.statuses,
        "latency_ms": {p: {"p50": pct(v, 50), "p95": pct(v, 95), "max": round(max(v), 1)} for p, v in api.latency.items()},
        "reconciled": len(results) - len(bad), "failed": bad[:20],
    }
    text = json.dumps(report, indent=2)
    print(text)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
