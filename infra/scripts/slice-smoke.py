#!/usr/bin/env python3
"""SR slice proof on a deployed environment (lead request 2026-10-07): the API path a phone takes for one sale,
through the public address (Front Door), as the seeded SR on the seeded dev phone (db/seed: sr1001, device
00000000-0000-4000-8000-000000000001, a bound phone, so login answers "ok" without OTP).

  1 login (phone)                     POST /v1/auth/login           status ok, a token (never printed)
  2 bundle                            GET  /v1/sync/bundle          a planned route, an outlet with a pin, a priced SKU
  3 baseline tile                     GET  /v1/app/home
  4 one sale (visit, memo, line, close) POST /v1/sync/batch          every record accepted
  5 the same records, new batch_uuid  POST /v1/sync/batch          every record duplicate
  6 the first batch again             POST /v1/sync/batch          replayed: true
  7 server count                      server_totals of the batch answers: memos, active memos and gross the same
                                                                    after the re-upload as after the sale (never doubled)
  7b server count, reconciliation     GET  /v1/sync/totals          the same three numbers as the batch answers
  8 memo read                         GET  /v1/memos?memo_no=       exactly our memo, active, gross and net ours
  9 dashboard tile                    GET  /v1/app/home             active memos + 1, gross + ours (polled; the
                                                                    aggregation worker is asynchronous)
 10 cleanup                           POST /v1/sync/batch memo_void after ANY step past 4: no money left on dev

Payload shapes follow backend/app/src/test/.../SyncConvergenceFuzzTest.kt (the server's own accepted records).
Exit 0 when every step passed, 1 otherwise; one line per step, and with GITHUB_STEP_SUMMARY a table there.
Environment: SLICE_API_HOST (host only), SLICE_PASSWORD (the seed password; never printed), optional SLICE_USER
(sr1001), SLICE_DEVICE, SLICE_DEVICE_KEY (PEM file; signs X-Device-Proof), SLICE_OUTLET_CODE (SMOKE-SR-001), SLICE_APP_VERSION (1.0.9+9), SLICE_TILE_WAIT_S (300).
"""
import datetime
import base64
import gzip
import hashlib
import json
import os
import random
import subprocess
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
DEVICE_KEY = os.environ.get("SLICE_DEVICE_KEY", "")  # PEM file of the smoke device's key (Key Vault aron-dev-smoke-device-key)
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


def b64u(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def sign(key_file, message):
    """ES256 over [message] with the PEM key in [key_file], as raw r||s base64url (what DeviceProof.verify expects)."""
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_file], input=message.encode(), capture_output=True,
                         check=True).stdout
    # DER: 30 len 02 lr r 02 ls s  ->  r and s as 32-byte big-endian integers
    i = 2 if der[1] < 0x80 else 3
    lr = der[i + 1]; r = der[i + 2:i + 2 + lr]; j = i + 2 + lr
    ls_ = der[j + 1]; s_ = der[j + 2:j + 2 + ls_]
    return b64u(int.from_bytes(r, "big").to_bytes(32, "big") + int.from_bytes(s_, "big").to_bytes(32, "big"))


def public_jwk(key_file):
    """The P-256 public JWK of the PEM key and its RFC 7638 thumbprint (the device row the backend verifies with)."""
    der = subprocess.run(["openssl", "ec", "-in", key_file, "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
    point = der[-65:]
    if point[0] != 4:
        raise ValueError("not an uncompressed P-256 point")
    x, y = b64u(point[1:33]), b64u(point[33:65])
    thumb = b64u(hashlib.sha256(f'{{"crv":"P-256","kty":"EC","x":"{x}","y":"{y}"}}'.encode()).digest())
    return {"kty": "EC", "crv": "P-256", "x": x, "y": y}, thumb


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
            if path == "/v1/sync/batch" and DEVICE_KEY:
                # X-Device-Proof (docs/24 s8.3) over the gzip bytes as sent, as an enrolled phone signs its batches.
                msg = "\n".join(["aron-proof-v1", "batch", DEVICE, hashlib.sha256(data).hexdigest(), body["batch_uuid"], "1"])
                h["X-Device-Proof"] = sign(DEVICE_KEY, msg)
                h["X-Batch-Attempt"] = "1"
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
    s, home0 = call("GET", f"/v1/app/home?business_date={day}", token)
    step("3 baseline tile", s == 200 and isinstance(home0, dict), brief(s, home0) if s != 200 else "")
    k0 = home0["kpis"]

    # 4 one sale
    at = now_iso()
    unit = sku["base_unit"]
    qty = 10
    gross = qty * price["amount_mtk"] // max(1, price["per_base_qty"])
    memo_no = f"{USER}-{today.strftime('%y%m%d')}-{9000 + random.randrange(1000)}"  # 9xxx: clear of a phone's own blocks
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
    memo_accepted = any(a.get("client_uuid") == memo and a.get("status") == "accepted" for a in (r1 or {}).get("acks", [])) \
        if isinstance(r1, dict) else False
    try:
        step("4 sale uploaded", s == 200 and st == ["accepted"] * 4,
             f"memo {memo_no}, {gross} mtk" if st == ["accepted"] * 4 else brief(s, r1) + f" acks={st} "
             + json.dumps([{k: a.get(k) for k in ('type', 'status', 'code', 'message_key')} for a in (r1 or {}).get('acks', [])]))
        after_sale(token, day, memo, gross, k0, records, first, batch, acks, r1)
    finally:
        # 10 cleanup whenever the server took the memo, whatever failed after: the smoke sale leaves no money on dev
        if memo_accepted:  # never `return` here: it would swallow the step failure
            vu = str(uuid.uuid4())
            void = envelope("memo_void", vu, vu, 0, {"memo_client_uuid": memo, "memo_no": memo_no, "reason_code": "retailer_cancelled",
                                                     "retailer_ack": True, "fix": {**fix, "purpose": "memo_void"}})
            s, r4 = call("POST", "/v1/sync/batch", token, batch([void], str(uuid.uuid4())), gz=True)
            st = acks(r4)
            step("10 cleanup: sale voided", s == 200 and st == ["accepted"], f"acks={st}" if s == 200 else brief(s, r4))


def day_totals(resp, day):
    """(memo accepted count, active memo count, gross) of [day] from a batch answer's server_totals."""
    for t in (resp or {}).get("server_totals", []) if isinstance(resp, dict) else []:
        if t.get("business_date") == day:
            return (int(t["by_type"].get("memo", {}).get("accepted", 0)), int(t["money"]["active_memo_count"]),
                    int(t["money"]["gross_mtk"]))
    return None


def after_sale(token, day, memo, gross, k0, records, first, batch, acks, r1):
    t1 = day_totals(r1, day)
    step("4b server totals after the sale", t1 is not None and t1[0] >= 1 and t1[1] >= 1,
         f"memos accepted {t1[0]}, active {t1[1]}" if t1 else "no server_totals for today in the batch answer")

    # 5 same records, new batch
    s, r2 = call("POST", "/v1/sync/batch", token, batch(records, str(uuid.uuid4())), gz=True)
    st = acks(r2)
    step("5 re-upload acked duplicate", s == 200 and st == ["duplicate"] * 4, f"acks={st}" if st != ["duplicate"] * 4 else "4 of 4 duplicate")

    # 6 replay of the first batch
    s, r3 = call("POST", "/v1/sync/batch", token, first, gz=True)
    step("6 batch replay", s == 200 and isinstance(r3, dict) and r3.get("replayed") is True, brief(s, r3) if s != 200 else "replayed: true")

    # 7 the server still counts ONE sale (memos accepted, active memos and gross unchanged by the re-upload)
    t2 = day_totals(r2, day)
    step("7 server count unchanged by the re-upload", t2 is not None and t2 == t1, f"{t1} -> {t2} (memos, active, gross mtk)")

    # 7b the same count from GET /v1/sync/totals (reconciliation, F-SYS-005): the Server column equals the batch answers
    s, tot = call("GET", f"/v1/sync/totals?business_date={day}", token)
    t3 = day_totals({"server_totals": [tot.get("totals")]}, day) if s == 200 and isinstance(tot, dict) and isinstance(tot.get("totals"), dict) else None
    step("7b GET /v1/sync/totals agrees", t3 is not None and t3 == t2, f"{t3} (memos, active, gross mtk)" if s == 200 else brief(s, tot))

    # 8 memo read: GET /v1/memos?memo_no= returns exactly the uploaded memo, active, with its totals
    memo_no = records[1]["payload"]["memo_no"]
    s, page = call("GET", f"/v1/memos?memo_no={memo_no}", token)
    items = page.get("items", []) if s == 200 and isinstance(page, dict) else []
    m = items[0] if len(items) == 1 else {}
    ok = m.get("memo_client_uuid") == memo and m.get("memo_no") == memo_no and m.get("status") == "active" \
        and (m.get("totals") or {}).get("gross_mtk") == gross and (m.get("totals") or {}).get("net_mtk") == gross
    step("8 memo read", ok, f"{memo_no}, {gross} mtk" if ok else (brief(s, page) if s != 200 else
         f"{len(items)} item(s): " + json.dumps({k: m.get(k) for k in ('memo_no', 'status', 'totals')})[:200]))

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
    step("9 dashboard tile shows the sale", shown,
         f"active memos {k0.get('active_memo_count')} -> {k1.get('active_memo_count') if k1 else '?'}, "
         f"gross {k0.get('gross_mtk')} -> {k1.get('gross_mtk') if k1 else '?'} mtk")

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
    if sys.argv[1:2] == ["--print-jwk"]:  # deploy.sh: the public half for the dev seed job (never the private key)
        jwk, thumb = public_jwk(sys.argv[2])
        print(json.dumps({"jwk": jwk, "thumbprint": thumb}, separators=(",", ":")))
        sys.exit(0)
    sys.exit(main())
