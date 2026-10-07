#!/usr/bin/env python3
"""SR slice proof on a deployed environment (lead request 2026-10-07): the API path a phone takes for one sale,
through the public address (Front Door), as the seeded SR on the seeded dev phone (db/seed: sr1001, device
00000000-0000-4000-8000-000000000001, a bound phone, so login answers "ok" without OTP).

  1 login (phone)                     POST /v1/auth/login           status ok, a token (never printed)
  2 bundle                            GET  /v1/sync/bundle          a planned route, an outlet with a pin, a priced SKU
  3 baseline                          GET  /v1/sync/totals, /v1/app/home
  4 one sale (visit, memo, line, close) POST /v1/sync/batch          every record accepted
  5 the same records, new batch_uuid  POST /v1/sync/batch          every record duplicate
  6 the first batch again             POST /v1/sync/batch          replayed: true
  7 server count                      GET  /v1/sync/totals          memo count = baseline + 1 (never + 2)
  8 memo read                         GET  /v1/memos?memo_no=       exactly one memo, our client UUID
  9 dashboard tile                    GET  /v1/app/home             active memos + 1, gross + ours (polled; the
                                                                    aggregation worker is asynchronous)
 10 cleanup                           POST /v1/sync/batch memo_void the smoke sale leaves no money on dev

Payload shapes follow backend/app/src/test/.../SyncConvergenceFuzzTest.kt (the server's own accepted records).
Exit 0 when every step passed, 1 otherwise; one line per step, and with GITHUB_STEP_SUMMARY a table there.
Environment: SLICE_API_HOST (host only), SLICE_PASSWORD (the seed password; never printed), optional SLICE_USER
(sr1001), SLICE_DEVICE, SLICE_OUTLET_CODE (SMOKE-SR-001), SLICE_APP_VERSION (1.0.9+9), SLICE_TILE_WAIT_S (300).
"""
import datetime
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

HOST = os.environ.get("SLICE_API_HOST", "")
USER = os.environ.get("SLICE_USER", "sr1001")
PASSWORD = os.environ.get("SLICE_PASSWORD", "")
DEVICE = os.environ.get("SLICE_DEVICE", "00000000-0000-4000-8000-000000000001")
APP_VERSION = os.environ.get("SLICE_APP_VERSION", "1.0.9+9")
TILE_WAIT_S = int(os.environ.get("SLICE_TILE_WAIT_S", "300"))
OUTLET_CODE = os.environ.get("SLICE_OUTLET_CODE", "SMOKE-SR-001")  # the smoke's own outlet (infra/sql/devseed-smoke-outlet.sql)
TILE_POLL_S = float(os.environ.get("SLICE_TILE_POLL_S", "15"))
SCHEME = os.environ.get("SLICE_SCHEME", "https")  # http only for the offline test against a local stub
DHAKA = datetime.timezone(datetime.timedelta(hours=6))

results = []  # (step, ok, detail)


class StepFailed(Exception):
    pass


def step(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"slice: {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}", flush=True)
    if not ok:
        raise StepFailed(name)


def call(method, path, token=None, body=None, gz=False, headers=None):
    """Returns (status, parsed JSON or text). Never logs headers (they carry the token)."""
    h = {"Accept": "application/json", "Accept-Encoding": "gzip", "X-App-Version": APP_VERSION, "X-Device-Id": DEVICE}
    if token:
        h["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
        h["Content-Type"] = "application/json"
        if gz:
            data = gzip.compress(data)
            h["Content-Encoding"] = "gzip"
    h.update(headers or {})
    req = urllib.request.Request(f"{SCHEME}://{HOST}{path}", data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, raw, enc = r.status, r.read(), r.headers.get("Content-Encoding", "")
    except urllib.error.HTTPError as e:
        status, raw, enc = e.code, e.read(), e.headers.get("Content-Encoding", "")
    if enc == "gzip":
        raw = gzip.decompress(raw)
    text = raw.decode("utf-8", "replace")
    try:
        return status, json.loads(text) if text else None
    except ValueError:
        return status, text


def brief(status, body):
    """An error answer for the log: status plus the problem code, never a token."""
    if isinstance(body, dict):
        return f"HTTP {status} {body.get('code') or body.get('type') or ''} {body.get('title') or body.get('detail') or ''}".strip()
    return f"HTTP {status}"


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def run():
    if not HOST or not PASSWORD:
        step("inputs", False, "SLICE_API_HOST and SLICE_PASSWORD are required")
    today = datetime.datetime.now(DHAKA).date()
    day = today.isoformat()

    # 1 login
    s, b = call("POST", "/v1/auth/login", body={"username": USER, "password": PASSWORD, "client": "app_sr", "device_uuid": DEVICE})
    ok = s == 200 and isinstance(b, dict) and b.get("status") == "ok" and bool(b.get("access_token"))
    step("1 login", ok, f"{USER} on the seeded phone" if ok else brief(s, b) + (f" status={b.get('status')}" if isinstance(b, dict) else ""))
    token = b["access_token"]

    # 2 bundle
    s, bundle = call("GET", f"/v1/sync/bundle?for={day}", token)
    step("2 bundle", s == 200 and isinstance(bundle, dict), brief(s, bundle) if s != 200 else f"bundle {bundle['meta']['bundle_version']}")
    meta = bundle["meta"]
    skus = {k["id"]: k for k in bundle["products"]["skus"] if k.get("status", "active") == "active"}
    prices = {}
    for p in bundle["prices"]:
        if p["price_type"] == "outlet" and p["valid_from"] <= day and (p.get("valid_to") in (None, "") or p["valid_to"] >= day):
            prices[p["sku_id"]] = p
    pick = None
    for r in bundle["routes"]:
        if not r.get("planned_today"):
            continue
        outlet = next((o for o in r["outlets"] if o.get("code") == OUTLET_CODE and o.get("lat") is not None), None)
        sku_id = next((i for i in r["sales_plan_sku_ids"] if i in skus and i in prices), None)
        if outlet and sku_id:
            pick = (r, outlet, skus[sku_id], prices[sku_id])
            break
    step("2b route, outlet, SKU", pick is not None,
         f"no planned route with the smoke outlet {OUTLET_CODE} and a priced plan SKU" if pick is None
         else f"route {pick[0]['route_id']}, outlet {pick[1]['outlet_id']}, sku {pick[2]['code']}")
    route, outlet, sku, price = pick

    # 3 baseline
    s, tot0 = call("GET", f"/v1/sync/totals?business_date={day}", token)
    step("3 baseline totals", s == 200, brief(s, tot0) if s != 200 else "")
    memos0 = int(tot0["totals"]["by_type"].get("memo", 0))
    s, home0 = call("GET", f"/v1/app/home?business_date={day}", token)
    step("3 baseline tile", s == 200, brief(s, home0) if s != 200 else "")
    k0 = home0["kpis"]

    # 4 one sale
    at = now_iso()
    unit = sku["base_unit"]
    qty = 10
    gross = qty * price["amount_mtk"] // max(1, price["per_base_qty"])
    sec = (datetime.datetime.now(DHAKA) - datetime.datetime.combine(today, datetime.time(), DHAKA)).seconds
    memo_no = f"{USER}-{today.strftime('%y%m%d')}-{9000 + sec // 87}"  # 9000-9993: clear of a phone's own blocks
    visit, memo, line, close = (str(uuid.uuid4()) for _ in range(4))
    lat, lng = outlet["lat"], outlet["lng"]
    fix = {"fix_status": "ok", "lat": lat, "lng": lng, "accuracy_m": 12.0, "provider": "fused", "is_mock": False, "reused": False,
           "device": {"device_owner": True, "dev_options_enabled": False, "adb_enabled": False, "auto_time_enabled": True,
                      "mock_app_present": False}}

    def envelope(rtype, cu, family, rank, payload):
        return {"type": rtype, "client_uuid": cu, "family_uuid": family, "rank": rank, "schema_version": 1,
                "business_date": day, "captured_at": at, "captured_elapsed_ms": 18330000, "boot_count": 1,
                "clock_offset_ms": 0, "captured_offline": False, "route_id": route["route_id"],
                "bundle_version": meta["bundle_version"], "bundle_stale": False, "config_version": meta["config_version"],
                "payload": payload}

    records = [
        envelope("visit", visit, visit, 0, {
            "visit_kind": "sr_call", "outlet_id": outlet["outlet_id"], "opened_at": at, "sequence_no": 1, "planned": True,
            "fix": {**fix, "purpose": "visit_open"},
            "geo": {"verdict": "in_range", "distance_m": 0.0, "radius_m_used": outlet["radius_m"],
                    "max_accuracy_m_used": outlet["max_accuracy_m"], "location_basis": "master", "action": "sale_allowed"}}),
        envelope("memo", memo, visit, 1, {
            "visit_client_uuid": visit, "outlet_id": outlet["outlet_id"], "memo_no": memo_no, "memo_kind": "sale",
            "committed_at": at, "price_list_date": day, "price_type": "outlet", "gross_mtk": gross, "offer_discount_mtk": 0,
            "drp_discount_mtk": 0, "qc_deduction_mtk": 0, "round_adj_mtk": 0, "net_mtk": gross, "paid_mtk": gross, "due_mtk": 0,
            "is_credit": False, "line_count": 1, "discount_line_count": 0, "qc_line_count": 0, "offer_version_ids": [],
            "rounding_mode": "half_up_paisa"}),
        envelope("memo_line", line, visit, 2, {
            "memo_client_uuid": memo, "line_no": 1, "sku_id": sku["id"], "line_kind": "sale", "qty_entered": qty,
            "unit_entered": unit, "pack_factor": 1, "qty_base": qty, "price_type": "outlet",
            "price_valid_from": price["valid_from"], "base_price_mtk": price["amount_mtk"],
            "price_per_qty": price["per_base_qty"], "gross_mtk": gross}),
        envelope("visit_close", close, visit, 1, {
            "visit_client_uuid": visit, "outcome_code": "sold", "call_declined": False, "ended_at": at, "is_zero_sale": False}),
    ]

    def batch(recs, batch_uuid):
        return {"batch_uuid": batch_uuid, "device_uuid": DEVICE, "schema_version": 1, "app_version": APP_VERSION,
                "trigger": "manual", "sent_at_device": now_iso(), "pending_rows": 0, "time_anchors": [],
                "device_counts": {day: {"visit": 1, "memo": 1, "memo_line": 1, "visit_close": 1}}, "records": recs}

    def acks(b):
        return [a["status"] for a in b.get("acks", [])] if isinstance(b, dict) else []

    first = batch(records, str(uuid.uuid4()))
    s, r1 = call("POST", "/v1/sync/batch", token, first, gz=True)
    st = acks(r1)
    step("4 sale uploaded", s == 200 and st == ["accepted"] * 4,
         f"memo {memo_no}, {gross} mtk" if st == ["accepted"] * 4 else brief(s, r1) + f" acks={st} "
         + json.dumps([{k: a.get(k) for k in ('type', 'status', 'code', 'message_key')} for a in (r1 or {}).get('acks', [])]))

    # 5 same records, new batch
    s, r2 = call("POST", "/v1/sync/batch", token, batch(records, str(uuid.uuid4())), gz=True)
    st = acks(r2)
    step("5 re-upload acked duplicate", s == 200 and st == ["duplicate"] * 4, f"acks={st}" if st != ["duplicate"] * 4 else "4 of 4 duplicate")

    # 6 replay of the first batch
    s, r3 = call("POST", "/v1/sync/batch", token, first, gz=True)
    step("6 batch replay", s == 200 and isinstance(r3, dict) and r3.get("replayed") is True, brief(s, r3) if s != 200 else "replayed: true")

    # 7 server count
    s, tot1 = call("GET", f"/v1/sync/totals?business_date={day}", token)
    memos1 = int(tot1["totals"]["by_type"].get("memo", 0)) if s == 200 else -1
    step("7 server memo count +1", memos1 == memos0 + 1, f"{memos0} -> {memos1}")

    # 8 memo read
    s, page = call("GET", f"/v1/memos?memo_no={memo_no}", token)
    items = page.get("items", []) if isinstance(page, dict) else []
    step("8 memo read", s == 200 and len(items) == 1 and items[0]["memo_client_uuid"] == memo,
         f"{len(items)} memo(s)" if s == 200 else brief(s, page))

    # 9 dashboard tile (asynchronous aggregation)
    deadline, k1 = time.time() + TILE_WAIT_S, None
    while True:
        s, home1 = call("GET", f"/v1/app/home?business_date={day}", token)
        if s == 200:
            k1 = home1["kpis"]
            if k1.get("active_memo_count", 0) >= k0.get("active_memo_count", 0) + 1 and k1.get("gross_mtk", 0) >= k0.get("gross_mtk", 0) + gross:
                break
        if time.time() > deadline:
            break
        time.sleep(TILE_POLL_S)
    shown = k1 is not None and k1.get("active_memo_count", 0) >= k0.get("active_memo_count", 0) + 1
    try:
        step("9 dashboard tile shows the sale", shown,
             f"active memos {k0.get('active_memo_count')} -> {k1.get('active_memo_count') if k1 else '?'}, "
             f"gross {k0.get('gross_mtk')} -> {k1.get('gross_mtk') if k1 else '?'} mtk")
    finally:
        # 10 cleanup, also after a failed tile check: the smoke sale leaves no money on dev
        vu = str(uuid.uuid4())
        void = envelope("memo_void", vu, vu, 0, {"memo_client_uuid": memo, "memo_no": memo_no, "reason_code": "retailer_cancelled",
                                                 "retailer_ack": True, "fix": {**fix, "purpose": "memo_void"}})
        s, r4 = call("POST", "/v1/sync/batch", token, batch([void], str(uuid.uuid4())), gz=True)
        st = acks(r4)
        step("10 cleanup: sale voided", s == 200 and st == ["accepted"], f"acks={st}" if s == 200 else brief(s, r4))


def main():
    try:
        run()
        passed = True
    except StepFailed:
        passed = False
    except Exception as e:  # an unexpected answer shape: report the step, never a token
        results.append(("unexpected", False, f"{type(e).__name__}: {e}"[:300]))
        print(f"slice: FAIL unexpected {type(e).__name__}: {str(e)[:300]}", flush=True)
        passed = False
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"### SR slice smoke: {'PASSED' if passed else 'FAILED'}\n| Step | Result | Detail |\n|---|---|---|\n")
            for n, ok, d in results:
                f.write(f"| {n} | {'pass' if ok else 'FAIL'} | {d.replace('|', '/')} |\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
