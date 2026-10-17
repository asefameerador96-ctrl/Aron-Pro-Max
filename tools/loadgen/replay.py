#!/usr/bin/env python3
"""N-054: replay one synthetic selling day of the load fleet (tools/loadgen/generate.sql) through the API.

For each SR lsr1..lsrN, on its own bound phone (device_uuid 1d000000-0000-4000-8000-<n in hex>):
  1 login            POST /v1/auth/login (app_sr)          the seed password from the environment, never printed
  2 morning download GET  /v1/sync/bundle                  today's planned routes, outlets with pins, priced SKUs
  3 the day          POST /v1/sync/batch (gzip, batches)    for --visit-rate of the planned outlets a visit; for
                                                            --memo-rate of the visits a memo with two lines on
                                                            credit and a close; a third of the credits collected
  4 evening resend   POST /v1/sync/batch, new batch_uuids   the whole day again: every record must be duplicate
  5 reconciliation   GET  /v1/sync/totals before and after  the change over this run, accepted per type and money
                                                            (count, gross, net, paid, due, collected), equals the
                                                            device's own; every batch answered 200, every resend
                                                            record `duplicate`; an SR with nothing to send fails
SRs run --concurrency at a time. The report (stdout and --out JSON) has calls, latency p50/p95/max per phase, acks by
status, and every SR whose server count differs from its device count. Exit 0 only when every SR reconciled and the
resend changed nothing. Use a scratch environment loaded by generate.sql; the replay WRITES a day of sales.

  read -rs ARON_LOADGEN_PASSWORD && export ARON_LOADGEN_PASSWORD   # the seed password, kept out of shell history
  python3 tools/loadgen/replay.py --api http://127.0.0.1:8080 --srs 50
A non-loopback --api needs --i-know-this-is-scratch. Up to nine runs of one day per database (--run 1..9).
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
import urllib.parse
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


def capture_day(username, bundle, rnd, visit_rate, memo_rate, run=1, now=None):
    """The device's records for the day, its own counts and its money (what the outbox committed). Client uuids come
    from [rnd], so a seed reproduces the day; [run] (1 to 9) leads the memo sequence, so up to nine replays of one
    day on one database never reuse a memo number."""
    meta = bundle["meta"]
    day = meta["valid_for_business_date"]
    bv, cv = meta["bundle_version"], meta["config_version"]
    prices = {p["sku_id"]: p["amount_mtk"] for p in bundle.get("prices", []) if p.get("price_type") == "outlet" and p.get("amount_mtk", 0) > 0 and p.get("per_base_qty", 1) == 1}
    skus = sorted(prices)[:2]
    if len(skus) < 2:
        raise RuntimeError("the bundle has fewer than two priced SKUs")
    now = now or datetime.datetime.now(datetime.timezone.utc)
    # Capture times stay inside the bundle's business day (Asia/Dhaka, UTC+6): never before its midnight, never in
    # the future. A bundle whose day has not started, or is over, cannot be replayed now.
    day_start = datetime.datetime.fromisoformat(day + "T00:00:00+06:00")
    if not day_start < now < day_start + datetime.timedelta(days=1):
        raise RuntimeError(f"the bundle's business day {day} is not today in Dhaka")
    span = max(1, min(7200, int((now - day_start).total_seconds()) - 1))
    uid = lambda: str(uuid.UUID(int=rnd.getrandbits(128), version=4))
    money = {"active_memo_count": 0, "gross_mtk": 0, "net_mtk": 0, "paid_mtk": 0, "due_mtk": 0, "due_collected_mtk": 0}
    records, seq = [], 0
    for route in bundle["routes"]:
        if not route.get("planned_today"):
            continue
        for o in route.get("outlets", []):
            if rnd.random() >= visit_rate or o.get("lat") is None:
                continue
            seq += 1
            at = (now - datetime.timedelta(seconds=max(1, span - seq % span) if span > 1 else 0)).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            visit = uid()
            records.append(envelope(day, "visit", visit, visit, 0, {
                "visit_kind": "sr_call", "outlet_id": o["outlet_id"], "opened_at": at, "sequence_no": seq, "planned": True,
                "fix": fix("visit_open", o["lat"], o["lng"]),
                "geo": {"verdict": "in_range", "distance_m": 4.0, "radius_m_used": 100, "max_accuracy_m_used": 100, "location_basis": "master", "action": "sale_allowed"},
            }, at, route["route_id"], bv, cv))
            sold = rnd.random() < memo_rate
            if sold:
                memo = uid()
                q1 = 10 + (seq + 17 * run) % 50  # each run sells other quantities: a repeat is a new sale, not a double tap
                q2 = 10 + run  # the server quarantines the same content to the same outlet twice a day (content_duplicate)
                g1, g2 = q1 * prices[skus[0]], q2 * prices[skus[1]]
                gross = g1 + g2
                due = 1000 * rnd.randint(1, 30) + 10 * run if rnd.random() < 0.3 and gross > 40_000 else 0
                memo_no = f"{username}-{day[2:4]}{day[5:7]}{day[8:10]}-{run}{seq:03d}"
                money["active_memo_count"] += 1; money["gross_mtk"] += gross; money["net_mtk"] += gross; money["paid_mtk"] += gross - due; money["due_mtk"] += due
                records.append(envelope(day, "memo", memo, visit, 1, {
                    "visit_client_uuid": visit, "outlet_id": o["outlet_id"], "memo_no": memo_no, "memo_kind": "sale", "committed_at": at,
                    "price_list_date": day, "price_type": "outlet", "gross_mtk": gross, "offer_discount_mtk": 0, "drp_discount_mtk": 0,
                    "qc_deduction_mtk": 0, "round_adj_mtk": 0, "net_mtk": gross, "paid_mtk": gross - due, "due_mtk": due, "is_credit": due > 0,
                    "line_count": 2, "discount_line_count": 0, "qc_line_count": 0, "offer_version_ids": [], "rounding_mode": "half_up_paisa",
                }, at, route["route_id"], bv, cv))
                for no, (sku, q, g) in enumerate(((skus[0], q1, g1), (skus[1], q2, g2)), 1):
                    records.append(envelope(day, "memo_line", uid(), visit, 2, {
                        "memo_client_uuid": memo, "line_no": no, "sku_id": sku, "line_kind": "sale", "qty_entered": q, "unit_entered": "stick",
                        "pack_factor": 1, "qty_base": q, "price_type": "outlet", "price_valid_from": day, "base_price_mtk": prices[sku], "price_per_qty": 1, "gross_mtk": g,
                    }, at, route["route_id"], bv, cv))
                if due and rnd.random() < 0.33:
                    money["due_collected_mtk"] += due
                    cu = uid()
                    records.append(envelope(day, "due_collection", cu, cu, 0, {
                        "outlet_id": o["outlet_id"], "against_memo_client_uuid": memo, "against_memo_no": memo_no, "against_memo_business_date": day,
                        "amount_mtk": due, "is_full_settlement": True, "outstanding_before_mtk": due, "payment_mode": "cash",
                    }, at, route["route_id"], bv, cv))
            records.append(envelope(day, "visit_close", uid(), visit, 1, {
                "visit_client_uuid": visit, "outcome_code": "sold" if sold else "owner_absent", "call_declined": False, "ended_at": at, "is_zero_sale": not sold,
            }, at, route["route_id"], bv, cv))
    counts = {}
    for r in records:
        counts[r["type"]] = counts.get(r["type"], 0) + 1
    if seq > 999:
        raise RuntimeError("more than 999 visits for one SR in a day: the memo sequence would overflow")
    return day, records, counts, money


def batch(device, day, counts, records):
    return {"batch_uuid": str(uuid.uuid4()), "device_uuid": device, "schema_version": 1, "app_version": APP_VERSION, "trigger": "manual",
            "sent_at_device": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "pending_rows": 0, "time_anchors": [], "device_counts": {day: counts}, "records": records}


MONEY_KEYS = ("active_memo_count", "gross_mtk", "net_mtk", "paid_mtk", "due_mtk", "due_collected_mtk")


def server_view(totals):
    """(accepted per type, money) of a ServerTotals object."""
    totals = totals or {}
    counts = {k: v.get("accepted", 0) for k, v in totals.get("by_type", {}).items()}
    money = {k: int(totals.get("money", {}).get(k, 0)) for k in MONEY_KEYS}
    return counts, money


def one_sr(api, n, args, password):
    """Replays one SR's day and reconciles it: the server's change over this run equals what the device sent."""
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
    try:
        day, records, counts, money = capture_day(username, bundle, random.Random(args.seed * 100_003 + n + args.run * 7_000_003), args.visit_rate, args.memo_rate, args.run)
    except RuntimeError as e:
        out["error"] = str(e)
        return out
    out.update(day=day, device_counts=counts, records=len(records))
    if not records:
        # A day with nothing sent proves nothing (a Friday, a holiday, an SR without a planned route today).
        out["empty"] = True
        out["ok"] = args.allow_empty
        return out
    # The server's totals before this run: the replay reconciles the change, so a database with earlier traffic works.
    st, before = api.call("totals", "GET", f"/v1/sync/totals?business_date={day}", token=token, device=device)
    if st != 200 or not before:
        out["error"] = f"totals before {st}"
        return out
    counts0, money0 = server_view(before.get("totals"))
    acked, acks, failures = set(), {}, 0
    pending = list(records)
    for _ in range(6):  # the outbox: send, then resend what is not final (children whose parent came later)
        if not pending:
            break
        for i in range(0, len(pending), args.batch):
            st, r = api.call("batch", "POST", "/v1/sync/batch", batch(device, day, counts, pending[i:i + args.batch]), token=token, device=device, gz=True)
            if st != 200:
                failures += 1
            for a in (r or {}).get("acks", []) if st == 200 else []:
                acks[a["status"]] = acks.get(a["status"], 0) + 1
                if a["status"] in ("accepted", "duplicate"):
                    acked.add(a["client_uuid"])
        pending = [x for x in pending if x["client_uuid"] not in acked]
    out["acks"] = acks
    out["batch_failures"] = failures
    out["unacked"] = len(pending)
    # The evening resend: everything again under new batch_uuids. Every batch must be answered 200 with one
    # `duplicate` ack per record; anything else (a 5xx, a short ack list) counts its whole batch as not duplicate.
    not_dup = 0
    for i in range(0, len(records), args.batch):
        part = records[i:i + args.batch]
        st, r = api.call("resend", "POST", "/v1/sync/batch", batch(device, day, counts, part), token=token, device=device, gz=True)
        ack_list = (r or {}).get("acks", []) if st == 200 else []
        dup = sum(1 for a in ack_list if a.get("status") == "duplicate")
        if st != 200 or len(ack_list) != len(part) or (r or {}).get("summary", {}).get("duplicate") != len(part):
            dup = 0
        not_dup += len(part) - dup
    out["resend_not_duplicate"] = not_dup
    # Reconciliation over this run: (server after) - (server before) == the device, for counts and money.
    st, after = api.call("totals", "GET", f"/v1/sync/totals?business_date={day}", token=token, device=device)
    if st != 200 or not after:
        out["error"] = f"totals after {st}"
        return out
    counts1, money1 = server_view(after.get("totals"))
    delta_counts = {k: counts1.get(k, 0) - counts0.get(k, 0) for k in set(counts1) | set(counts0) if counts1.get(k, 0) - counts0.get(k, 0)}
    delta_money = {k: money1[k] - money0[k] for k in MONEY_KEYS}
    out["server_counts"] = delta_counts
    out["server_money"] = delta_money
    out["device_money"] = money
    out["ok"] = not pending and not_dup == 0 and failures == 0 and delta_counts == counts and delta_money == money
    return out


def scratch_target(api: str) -> bool:
    """A loopback API is a local scratch stack; anything else needs --i-know-this-is-scratch."""
    host = urllib.parse.urlparse(api).hostname or ""
    return host in ("localhost", "127.0.0.1", "::1")


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
    ap.add_argument("--run", type=int, default=1, choices=range(1, 10), help="run number 1-9 of this day on this database (leads the memo sequence)")
    ap.add_argument("--allow-empty", action="store_true", help="an SR with nothing to send today passes (default: it fails)")
    ap.add_argument("--min-records", type=int, default=1, help="the whole run fails below this many records (default 1)")
    ap.add_argument("--i-know-this-is-scratch", action="store_true", help="allow a non-loopback API (it must be a scratch environment)")
    ap.add_argument("--out", help="write the JSON report here")
    args = ap.parse_args(argv)
    password = os.environ.get(args.password_env)
    if not password:
        print(f"set {args.password_env} (the seed password; it is never printed)", file=sys.stderr)
        return 2
    if not scratch_target(args.api) and not args.i_know_this_is_scratch:
        print("the replay writes a day of sales: point it at a local scratch stack, or pass --i-know-this-is-scratch", file=sys.stderr)
        return 2
    api = Api(args.api, args.timeout)
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(lambda n: one_sr(api, n, args, password), range(args.first, args.first + args.srs)))
    elapsed = time.time() - t0
    bad = [r for r in results if not r["ok"]]
    empty = [r["sr"] for r in results if r.get("empty")]
    report = {
        "srs": args.srs, "seconds": round(elapsed, 1), "records": sum(r.get("records", 0) for r in results),
        "device_counts": {t: sum(r.get("device_counts", {}).get(t, 0) for r in results) for t in TYPES},
        "server_counts": {t: sum((r.get("server_counts") or {}).get(t, 0) for r in results) for t in TYPES},
        "device_money": {k: sum((r.get("device_money") or {}).get(k, 0) for r in results) for k in MONEY_KEYS},
        "server_money": {k: sum((r.get("server_money") or {}).get(k, 0) for r in results) for k in MONEY_KEYS},
        "empty_srs": empty,
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
    total = sum(r.get("records", 0) for r in results)
    if total < args.min_records:
        print(f"only {total} records were sent (minimum {args.min_records}): nothing was proven", file=sys.stderr)
        return 1
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
