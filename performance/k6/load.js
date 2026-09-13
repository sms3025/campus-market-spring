import http from 'k6/http';
import {check, fail} from 'k6';
import {Trend,Counter,Rate} from 'k6/metrics';

const base=__ENV.BASE_URL||'http://localhost:8080';
const scenario=__ENV.SCENARIO||'items';
const rate=Number(__ENV.RATE||10), size=Number(__ENV.PAGE_SIZE||10), page=Number(__ENV.PAGE||0);
if(!['items','likes','admin','fcm'].includes(scenario))throw Error('Unknown scenario');
if(!Number.isInteger(rate)||rate<1||rate>1000)throw Error('RATE must be 1..1000');
if(!Number.isInteger(size)||size<1||size>100)throw Error('PAGE_SIZE must be 1..100');
const sqlCount=new Trend('request_sql_count');
const sqlMs=new Trend('request_sql_ms',true);
const apiDuration=new Trend('api_duration',true);
const apiRequests=new Counter('api_requests');
const apiFailed=new Rate('api_failed');
export const options={
  scenarios:{load:{executor:'constant-arrival-rate',rate,timeUnit:'1s',duration:__ENV.DURATION||'3m',preAllocatedVUs:Math.min(100,Math.max(10,rate)),maxVUs:200}},
  // Dropped iterations are recorded, not enforced: missing the target rate is a result to report,
  // not a reason to discard a run. Only real server errors abort.
  thresholds:{api_failed:[{threshold:'rate<0.05',abortOnFail:true,delayAbortEval:'15s'}],checks:['rate>0.99']},
  summaryTrendStats:['avg','min','med','max','p(95)','p(99)'],
  tags:{test_scenario:scenario},
};
function endpoint(){
  if(scenario==='fcm')return '/api/v1/items';
  const path=scenario==='likes'?'/api/v1/items/likes':scenario==='admin'?'/admin/api/v1/items':'/api/v1/items';
  return `${path}?size=${size}&page=${page}`+(scenario==='likes'?'':'&sort=createdDate,desc&name='+encodeURIComponent(__ENV.SEARCH||''));
}
export function setup(){
  const account=scenario==='likes'?2:scenario==='admin'?3:1;
  const r=http.post(`${base}/local-test/api/session/${account}`);
  if(r.status!==200)fail('Seed/session not ready: '+r.status);
  if(scenario==='fcm'){
    const mode=http.get(`${base}/local-test/api/fcm`).json('mode');
    if(mode!=='mock')fail('FCM load tests require mock mode');
  }
  return {token:r.json('accessToken')};
}
export default function(data){
  const params={headers:{Authorization:`Bearer ${data.token}`,'Content-Type':'application/json'},tags:{name:scenario},timeout:'15s'};
  const url=base+endpoint();
  const r=scenario==='fcm'?http.post(url,JSON.stringify({title:'FCM-BENCH load',description:'Synthetic local load item',price:10000,category:'OTHER',thumbnail:'https://example.invalid/item.png',images:[]}),params):http.get(url,params);
  apiDuration.add(r.timings.duration);apiRequests.add(1);apiFailed.add(r.status!==200);
  check(r,{'HTTP 200':r=>r.status===200,'valid response shape':r=>{try{return scenario==='fcm'?Number.isInteger(r.json('itemId')):Array.isArray(r.json('content'));}catch{return false;}}});
  if(r.headers['X-Local-Sql-Count']!==undefined)sqlCount.add(Number(r.headers['X-Local-Sql-Count']));
  if(r.headers['X-Local-Sql-Ms']!==undefined)sqlMs.add(Number(r.headers['X-Local-Sql-Ms']));
}
export function handleSummary(data){
  return {['/results/'+(__ENV.RUN_ID||'manual')+'/summary.json']:JSON.stringify(data,null,2),stdout:JSON.stringify({scenario,metrics:data.metrics},null,2)+'\n'};
}
