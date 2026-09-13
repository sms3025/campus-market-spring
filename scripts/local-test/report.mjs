import fs from 'node:fs';
import path from 'node:path';
const root='artifacts/performance';fs.mkdirSync(root,{recursive:true});
const rows=[];
for(const entry of fs.readdirSync(root,{withFileTypes:true})){
  if(!entry.isDirectory())continue;
  const folder=path.join(root,entry.name), summary=path.join(folder,'summary.json');
  if(!fs.existsSync(summary))continue;
  const read=file=>fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'')):{};
  const s=read(summary),c=read(path.join(folder,'conditions.json')),seed=read(path.join(folder,'seed-manifest.json'));
  const m=s.metrics||{};
  const latency=m.api_duration||m.http_req_duration, requests=m.api_requests||m.http_reqs, errors=m.api_failed||m.http_req_failed;
  rows.push({run:entry.name,scenario:c.scenario||'unknown',items:seed.counts?.item,page:c.pageSize,rate:c.rate,duration:c.duration,
    p95:latency?.values?.['p(95)'],p99:latency?.values?.['p(99)'],rps:requests?.values?.rate,
    errors:errors?.values?.rate,sql:m.request_sql_count?.values?.avg,dropped:m.dropped_iterations?.values?.count,
    html:fs.existsSync(path.join(folder,'report.html'))});
}
const esc=v=>String(v??'미측정').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const num=v=>v===undefined?'미측정':Number(v).toFixed(2);
const max=Math.max(1,...rows.map(r=>r.p95||0));
const table=rows.map(r=>`<tr><td><a href="${encodeURIComponent(r.run)}/${r.html?'report.html':'summary.json'}">${esc(r.run)}</a></td><td>${esc(r.items)}</td><td>${esc(r.page)}</td><td>${esc(r.rate)}</td><td>${esc(r.duration)}</td><td>${num(r.p95)}<div style="width:${(r.p95||0)/max*180}px;background:#15806a;height:6px"></div></td><td>${num(r.p99)}</td><td>${num(r.rps)}</td><td>${num((r.errors??0)*100)}%</td><td>${num(r.sql)}</td><td>${esc(r.dropped)}</td></tr>`).join('');
fs.writeFileSync(path.join(root,'index.html'),`<!doctype html><html lang="ko"><meta charset="utf-8"><title>Campus 성능 측정 결과</title><style>body{font:14px system-ui;margin:40px;color:#193342;background:#f6f8fa}table{background:white;border-collapse:collapse;width:100%}th,td{text-align:left;padding:12px;border-bottom:1px solid #d7e0e4}a{color:#14665b}h1{font-size:28px}</style><h1>실제 성능 측정 결과</h1><p>행마다 독립 실행 결과입니다. 동일 데이터·부하·계측 조건끼리 비교하세요. 짧은 실행은 HTML 대시보드가 없을 수 있습니다.</p><p>응답 지연은 k6 관측값(ms), SQL은 인증을 포함한 요청 스레드 실행 수입니다. 반복별 p95를 전체 p95로 합산하지 않습니다.</p><table><tr><th>실행</th><th>상품 수</th><th>페이지 크기</th><th>목표 RPS</th><th>기간</th><th>p95 ms</th><th>p99 ms</th><th>실제 RPS</th><th>오류율</th><th>SQL 평균</th><th>미시작 작업</th></tr>${table}</table></html>`);
console.log(`${root}/index.html: ${rows.length} runs`);
