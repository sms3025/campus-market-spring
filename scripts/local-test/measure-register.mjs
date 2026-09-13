// Isolated item-registration timing. One request at a time, no load tool, so the
// numbers describe a single request path instead of a saturated server.
import fs from 'node:fs';
import assert from 'node:assert/strict';

const base = process.env.LOCAL_API_URL || 'http://localhost:8080';
const label = process.argv[2] || 'register';
const rounds = Number(process.argv[3] || 20);
const warmup = Number(process.argv[4] || 5);
const title = process.argv[5] || 'FCM-BENCH 측정';

async function call(path, { method = 'GET', body, token } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body) headers['Content-Type'] = 'application/json';
  const started = performance.now();
  const r = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const elapsed = performance.now() - started;
  const text = await r.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  return { status: r.status, data, elapsed, sql: Number(r.headers.get('x-local-sql-count')), sqlMs: Number(r.headers.get('x-local-sql-ms')) };
}

const dataset = await call('/local-test/api/dataset');
assert.equal(dataset.data.state, 'complete', 'Seed must be complete');
const mode = (await call('/local-test/api/fcm')).data.mode;
assert.equal(mode, 'mock', 'This measurement must not send real notifications');
const token = (await call('/local-test/api/session/1', { method: 'POST' })).data.accessToken;

const item = { title, description: '등록 경로 측정용 상품', price: 10000, category: 'OTHER',
  thumbnail: 'https://example.invalid/item.png', images: [] };
const samples = [];
for (let i = 1; i <= rounds + warmup; i++) {
  const r = await call('/api/v1/items', { method: 'POST', token, body: { ...item, title: `${title} ${i}` } });
  assert.equal(r.status, 200, `register failed: ${JSON.stringify(r.data)}`);
  if (i > warmup) samples.push({ ms: r.elapsed, sql: r.sql, sqlMs: r.sqlMs });
}
const sorted = [...samples].map(s => s.ms).sort((a, b) => a - b);
const pick = q => sorted[Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1)];
const report = {
  label, at: new Date().toISOString(), rounds, warmup, title,
  manifest: JSON.parse(dataset.data.manifest),
  latencyMs: { min: +sorted[0].toFixed(1), p50: +pick(0.5).toFixed(1), p95: +pick(0.95).toFixed(1), max: +sorted.at(-1).toFixed(1),
    avg: +(sorted.reduce((a, b) => a + b, 0) / sorted.length).toFixed(1) },
  sqlCount: { min: Math.min(...samples.map(s => s.sql)), max: Math.max(...samples.map(s => s.sql)) },
  sqlMsAvg: +(samples.reduce((a, s) => a + s.sqlMs, 0) / samples.length).toFixed(1),
  createdItems: samples.length + warmup,
};
fs.mkdirSync('artifacts/performance', { recursive: true });
fs.writeFileSync(`artifacts/performance/register-${label}.json`, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
