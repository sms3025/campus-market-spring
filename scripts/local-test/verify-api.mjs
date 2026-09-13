import assert from 'node:assert/strict';
import fs from 'node:fs';
const base=process.env.LOCAL_API_URL||'http://localhost:8080';
const results=[];
async function call(path,{method='GET',body,token,status=200}={}){
  const headers={};if(token)headers.Authorization=`Bearer ${token}`;if(body)headers['Content-Type']='application/json';
  const r=await fetch(base+path,{method,headers,body:body?JSON.stringify(body):undefined});
  const text=await r.text();let data;try{data=JSON.parse(text);}catch{data=text;}
  assert.equal(r.status,status,`${method} ${path}: ${text}`);return {data,headers:r.headers};
}
async function until(read,ready,message,timeoutMs=10000){
  const deadline=Date.now()+timeoutMs;
  for(;;){
    const value=await read();
    if(ready(value))return value;
    if(Date.now()>deadline)throw new Error(`${message} (waited ${timeoutMs}ms, last=${JSON.stringify(value)})`);
    await new Promise(r=>setTimeout(r,200));
  }
}
const one=(await call('/local-test/api/session/1',{method:'POST'})).data.accessToken;
const two=(await call('/local-test/api/session/2',{method:'POST'})).data.accessToken;
const admin=(await call('/local-test/api/session/3',{method:'POST'})).data.accessToken;
assert.equal((await call('/local-test/api/fcm')).data.mode,'mock','This verification must not send real notifications');
const keyword='verify-'+Date.now();let keywordId,itemId;
try{
  await call('/local-test/index.html');await call('/local-test/app.js');await call('/local-test/firebase-messaging-sw.js');
  results.push({check:'page/assets HTTP',passed:true});
  await call('/admin/api/v1/campuses',{token:one,status:403});await call('/admin/api/v1/campuses',{token:admin});
  await call('/api/v1/items?minPrice=100&maxPrice=10',{token:one,status:400});
  results.push({check:'role boundary and invalid price',passed:true});
  keywordId=(await call('/api/v1/keywords',{method:'POST',token:two,body:{keywordName:keyword}})).data.keywordId;
  await call('/local-test/api/fcm-token',{method:'POST',body:{account:2,token:'mock-browser-verification'}});
  const body={title:keyword,description:'API 검증 상품',price:100,category:'OTHER',thumbnail:'https://example.invalid/item.png',images:[]};
  const sent=async()=>(await call('/local-test/api/fcm')).data.events.filter(e=>e.body===keyword);
  itemId=(await call('/api/v1/items',{method:'POST',token:one,body})).data.itemId;
  // Sending now happens after the registration commits, so the check waits for the dispatch.
  const notifications=await until(sent,e=>e.length===1,'keyword notification was not dispatched');
  assert.equal(notifications[0].outcome,'success');
  results.push({check:'other-user keyword mock notification',passed:true});
  const ownItem=(await call('/api/v1/items',{method:'POST',token:two,body})).data.itemId;
  await new Promise(r=>setTimeout(r,1000));
  assert.equal((await sent()).length,1,'registering your own item must not notify you');
  await call(`/api/v1/items/${ownItem}`,{method:'DELETE',token:two,status:204});
  results.push({check:'own keyword notification excluded',passed:true});
  await call(`/api/v1/items/${itemId}/likes`,{method:'POST',token:two});
  const detail=(await call(`/api/v1/items/${itemId}`,{token:two})).data;
  assert.equal(detail.title,keyword);assert.equal(detail.likeCount,1);
  await call(`/api/v1/items/${itemId}`,{method:'PATCH',token:one,body:{...body,price:200},status:204});
  assert.equal((await call(`/api/v1/items/${itemId}`,{token:two})).data.price,200);
  await call(`/api/v1/items/${itemId}/likes`,{method:'DELETE',token:two});
  results.push({check:'item detail/update and likes',passed:true});
  const counts=[];
  for(const size of [10,50,100]){
    const r=await call(`/api/v1/items/likes?size=${size}`,{token:two});
    assert.equal(r.data.content.length,size);counts.push({size,sql:Number(r.headers.get('x-local-sql-count'))});
  }
  // Was 13/53/103 before the aggregate rewrite: one extra query per returned item.
  assert.equal(counts[0].sql,counts[1].sql);assert.equal(counts[1].sql,counts[2].sql);
  results.push({check:'likes query count independent of page size',passed:true,counts});
  const metrics=(await call('/actuator/prometheus')).data;
  assert.ok(metrics.includes('local_fcm_completed_total'));assert.ok(metrics.includes('http_server_requests_seconds_bucket'));
  results.push({check:'Prometheus metrics',passed:true});
} finally {
  if(keywordId)await call(`/api/v1/keywords/${keywordId}`,{method:'DELETE',token:two,status:204});
  if(itemId)await call(`/api/v1/items/${itemId}`,{method:'DELETE',token:one,status:204});
}
fs.mkdirSync('artifacts/performance',{recursive:true});
fs.writeFileSync('artifacts/performance/api-verification.json',JSON.stringify({at:new Date().toISOString(),results},null,2));
console.log(JSON.stringify(results,null,2));
