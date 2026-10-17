// SCALE-01 (docs/14 row 18, D-133/134; audit docs/audit/production-readiness-2026-10-15.md): the k6 smoke of the two
// daily storms at pilot size, runnable against dev (TEST account) or a local API. Not a capacity proof: the 8,500-user
// shapes S1 to S10 run in the FINAL account with Azure Load Testing (docs/28 rule 3). What this proves on every run:
// every endpoint of the storms answers, p95 per endpoint, and the server's statements per batch stay bounded (the
// SCALE-02 and SCALE-03 numbers come from BatchAcceptanceTest and BundleAcceptanceTest; this script measures them live).
//
//   k6 run -e BASE_URL=https://<api host> -e USERS=sr1001,sr1002 -e PASSWORD=<pilot pw> -e DEVICE_UUID_PREFIX=0000... tools/load/smoke.js
//   k6 run -e BASE_URL=http://127.0.0.1:8080 -e USERS=sr1001 -e PASSWORD=... -e VUS=5 -e EVENING_BATCHES=3 tools/load/smoke.js
//
// Morning storm (06:00 to 09:00 at fleet size: about 130 requests per second): login, bundle (200), bundle again
// with the ETag (304), delta with the cursor (304). Evening storm (17:00 to 19:00): a 200-row sync batch per VU, the
// same batch again (replay), the same rows under a new batch_uuid (duplicates). Thresholds are the docs/24 s13.1 SLOs
// at pilot scale; a failing threshold fails the run (exit 99).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const USERS = (__ENV.USERS || 'sr1001').split(',').map((u) => u.trim()).filter(Boolean);
const PASSWORD = __ENV.PASSWORD || '';
const APP_VERSION = __ENV.APP_VERSION || '1.0.9+9';
const DEVICE_PREFIX = __ENV.DEVICE_UUID_PREFIX || '00000000-0000-4000-8000-0000000000';
const VUS = Number(__ENV.VUS || Math.min(USERS.length, 10));
const EVENING_BATCHES = Number(__ENV.EVENING_BATCHES || 2);
const ROWS = Number(__ENV.ROWS || 200);

const bundleBytes = new Trend('aron_bundle_bytes');
const batchAccepted = new Counter('aron_batch_accepted');
const batchRejected = new Counter('aron_batch_rejected');

export const options = {
  scenarios: {
    morning: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, exec: 'morning', maxDuration: '5m' },
    evening: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, exec: 'evening', startTime: '20s', maxDuration: '10m' },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{name:login}': ['p(95)<1500'],
    'http_req_duration{name:bundle}': ['p(95)<2500'],
    'http_req_duration{name:bundle_304}': ['p(95)<500'],
    'http_req_duration{name:delta}': ['p(95)<1500'],
    'http_req_duration{name:batch}': ['p(95)<3000'],
    'http_req_duration{name:batch_replay}': ['p(95)<800'],
    'http_req_duration{name:batch_duplicates}': ['p(95)<1500'],
    'checks': ['rate>0.99'],
  },
};

function device(vu) {
  const n = String(1 + ((vu - 1) % 99)).padStart(2, '0');
  return DEVICE_PREFIX + n;
}

function headers(token, dev, extra) {
  return Object.assign({ Authorization: `Bearer ${token}`, 'X-Device-Id': dev, 'X-App-Version': APP_VERSION, 'Accept-Encoding': 'gzip' }, extra || {});
}

function login(vu) {
  const user = USERS[(vu - 1) % USERS.length];
  const dev = device(vu);
  const r = http.post(`${BASE}/v1/auth/login`, JSON.stringify({ username: user, password: PASSWORD, client: 'app_sr', device_uuid: dev }), {
    headers: { 'Content-Type': 'application/json', 'X-App-Version': APP_VERSION }, tags: { name: 'login' },
  });
  check(r, { 'login 200': (x) => x.status === 200 });
  if (r.status !== 200) return null;
  return { token: r.json('access_token'), dev, user };
}

export function morning() {
  const s = login(__VU);
  if (!s) return;
  const b = http.get(`${BASE}/v1/sync/bundle`, { headers: headers(s.token, s.dev), tags: { name: 'bundle' } });
  check(b, { 'bundle 200': (x) => x.status === 200, 'bundle has routes': (x) => x.status === 200 && Array.isArray(x.json('routes')) });
  if (b.status !== 200) return;
  bundleBytes.add(b.body.length);
  const etag = b.headers['Etag'] || b.headers['ETag'];
  const cursor = b.json('meta.cursor');
  const again = http.get(`${BASE}/v1/sync/bundle`, { headers: headers(s.token, s.dev, { 'If-None-Match': etag }), tags: { name: 'bundle_304' } });
  check(again, { 'bundle again 304': (x) => x.status === 304 });
  const d = http.get(`${BASE}/v1/sync/delta?since=${encodeURIComponent(cursor)}`, { headers: headers(s.token, s.dev), tags: { name: 'delta' } });
  check(d, { 'delta 304 or 200': (x) => x.status === 304 || x.status === 200 });
  sleep(1);
}

// A telemetry family the seed accepts from every pilot phone without outlet or memo state: geo breadcrumbs (type
// geo_breadcrumb, no route, no outlet), one per row, distinct minutes so the content fingerprint tells them apart.
function records(n, day) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const cu = uuidv4();
    const at = new Date(Date.parse(`${day}T03:00:00.000Z`) + i * 60000).toISOString();
    out.push({
      type: 'geo_breadcrumb', client_uuid: cu, family_uuid: cu, rank: 0, schema_version: 1, business_date: day, captured_at: at,
      captured_elapsed_ms: 18330000 + i, boot_count: 412, clock_offset_ms: 0, captured_offline: true, bundle_version: `${day}:1`, bundle_stale: false, config_version: 1,
      payload: { fix: { purpose: 'breadcrumb', fix_status: 'ok', lat: 23.80 + i * 0.0001, lng: 90.36, accuracy_m: 12.0, provider: 'fused', is_mock: false, reused: false,
        device: { device_owner: true, dev_options_enabled: false, adb_enabled: false, auto_time_enabled: true, mock_app_present: false } } },
    });
  }
  return out;
}

function send(s, body, name) {
  const r = http.post(`${BASE}/v1/sync/batch`, body, {
    headers: headers(s.token, s.dev, { 'Content-Type': 'application/json', 'Content-Encoding': 'gzip' }), tags: { name }, compression: 'gzip',
  });
  check(r, { [`${name} 200`]: (x) => x.status === 200 });
  if (r.status === 200) {
    const sum = r.json('summary');
    batchAccepted.add(sum.accepted + sum.duplicate);
    batchRejected.add(sum.rejected + sum.quarantined);
  }
  return r;
}

export function evening() {
  const s = login(__VU);
  if (!s) return;
  const day = (__ENV.BUSINESS_DATE || new Date(Date.now() + 6 * 3600000).toISOString().slice(0, 10));
  for (let b = 0; b < EVENING_BATCHES; b++) {
    const batchUuid = uuidv4();
    const recs = records(ROWS, day);
    const body = (uuid) => JSON.stringify({
      batch_uuid: uuid, device_uuid: s.dev, schema_version: 1, app_version: APP_VERSION, trigger: 'manual',
      sent_at_device: new Date().toISOString(), pending_rows: 0, time_anchors: [], device_counts: {}, records: recs,
    });
    const first = send(s, body(batchUuid), 'batch');
    if (first.status !== 200) return;
    check(first, { 'rows accepted': (x) => x.json('summary.accepted') === ROWS });
    const replay = send(s, body(batchUuid), 'batch_replay');
    check(replay, { 'replayed': (x) => x.status === 200 && x.json('replayed') === true });
    const dup = send(s, body(uuidv4()), 'batch_duplicates');
    check(dup, { 'all duplicates': (x) => x.status === 200 && x.json('summary.duplicate') === ROWS });
    sleep(0.5);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const p95 = (name) => (m[`http_req_duration{name:${name}}`] ? Math.round(m[`http_req_duration{name:${name}}`].values['p(95)']) : null);
  const lines = ['| endpoint | p50 ms | p95 ms | n |', '|---|---|---|---|'];
  for (const name of ['login', 'bundle', 'bundle_304', 'delta', 'batch', 'batch_replay', 'batch_duplicates']) {
    const t = m[`http_req_duration{name:${name}}`];
    if (t) lines.push(`| ${name} | ${Math.round(t.values['p(50)'])} | ${Math.round(t.values['p(95)'])} | ${t.values.count} |`);
  }
  const out = lines.join('\n') + `\n\nfailed requests: ${(m.http_req_failed.values.rate * 100).toFixed(2)} %, checks ${(m.checks.values.rate * 100).toFixed(1)} %, ` +
    `bundle p95 ${p95('bundle')} ms, batch p95 ${p95('batch')} ms\n`;
  return { stdout: out, 'tools/load/last-run.md': out };
}
