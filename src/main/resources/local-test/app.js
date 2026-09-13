const $ = id => document.getElementById(id);
let token = '', unsubscribe;
const sample = {title:'웹푸시 테스트 상품',description:'로컬 테스트 상품입니다.',price:10000,category:'OTHER',thumbnail:'https://example.invalid/item.png',images:[]};
const endpoints = [
  ['상품 검색','GET','/api/v1/items',null,'items'],['상품 등록','POST','/api/v1/items',sample],
  ['상품 상세','GET','/api/v1/items/{id}'],['상품 수정','PATCH','/api/v1/items/{id}',sample],
  ['상품 삭제','DELETE','/api/v1/items/{id}'],['카테고리 조회','GET','/api/v1/items/categories'],
  ['찜 목록','GET','/api/v1/items/likes',null,'page'],['찜 등록','POST','/api/v1/items/{id}/likes'],
  ['찜 해제','DELETE','/api/v1/items/{id}/likes'],['키워드 목록','GET','/api/v1/keywords'],
  ['키워드 등록','POST','/api/v1/keywords',{keywordName:'웹푸시'}],['키워드 삭제','DELETE','/api/v1/keywords/{id}'],
  ['알림 이력','GET','/api/v1/notification-history'],['알림 삭제','DELETE','/api/v1/notification-history/{id}'],
  ['관리자 상품 검색','GET','/admin/api/v1/items',null,'admin'],['관리자 캠퍼스 목록','GET','/admin/api/v1/campuses']
];
endpoints.forEach((entry,i) => $('endpoint').add(new Option(entry[0],i)));
['ELECTRONICS_IT','HOME_APPLIANCES','FASHION_ACCESSORIES','BOOKS_EDUCATIONAL_MATERIALS','STATIONERY_OFFICE_SUPPLIES','HOUSEHOLD_ITEMS','KITCHEN_SUPPLIES','FURNITURE_INTERIOR','SPORTS_LEISURE','ENTERTAINMENT_HOBBIES','OTHER'].forEach(c=>$('category').add(new Option(c,c)));
function selection(){const e=endpoints[Number($('endpoint').value)];$('body').value=e[3]?JSON.stringify(e[3],null,2):'';$('body').disabled=!e[3];}
$('endpoint').addEventListener('change',selection);selection();
async function request(path,method='GET',body,authenticated=true){
  const headers={};if(authenticated&&token)headers.Authorization=`Bearer ${token}`;
  if(body!==undefined)headers['Content-Type']='application/json';
  const r=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});
  const text=await r.text();let data;try{data=JSON.parse(text);}catch{data=text;}
  return {r,data};
}
function action(id,fn){$(id).addEventListener('click',async()=>{const b=$(id);b.disabled=true;try{await fn();}catch(e){$('response').textContent=e.message;$('pushStatus').textContent=e.message;}finally{b.disabled=false;}});}
action('session',async()=>{const {r,data}=await request(`/local-test/api/session/${$('account').value}`,'POST',undefined,false);if(!r.ok)throw Error(JSON.stringify(data));token=data.accessToken;$('sessionStatus').textContent=`사용자 ${data.userId} · ${data.role} · 메모리에만 토큰 보관`;$('jwt').value='';});
action('useJwt',async()=>{token=$('jwt').value.trim().replace(/^Bearer\s+/,'');$('jwt').value='';$('sessionStatus').textContent='직접 입력한 JWT 적용';});
action('send',async()=>{
  if(!token)throw Error('먼저 테스트 계정을 선택하세요.');
  const [label,method,template,,query]=endpoints[Number($('endpoint').value)];
  let path=template.replace('{id}',encodeURIComponent($('resourceId').value));
  if(query){const params=new URLSearchParams();let keys=['page','size'];if(query!=='page')keys.push('sort','name','minPrice','maxPrice','category','itemStatus');if(query==='admin')keys.push('campusId','isDeleted');keys.forEach(k=>{if($(k).value!=='')params.set(k,$(k).value);});path+='?'+params;}
  if(['DELETE','PATCH'].includes(method)&&!confirm(`${label}: ${path}\n테스트 데이터를 변경할까요?`))return;
  const body=$('body').disabled?undefined:JSON.parse($('body').value);
  $('requestPath').textContent=`${method} ${path}`;const start=performance.now();const {r,data}=await request(path,method,body);
  const elapsed=(performance.now()-start).toFixed(1);$('http').textContent=r.status;$('elapsed').textContent=`${elapsed} ms`;$('sql').textContent=r.headers.get('X-Local-SQL-Count')??'미측정';$('response').textContent=typeof data==='string'?data:JSON.stringify(data,null,2);
  if(method==='POST'&&template==='/api/v1/items'&&r.ok)$('resourceId').value=data.itemId;
  const li=document.createElement('li');li.textContent=`${new Date().toLocaleTimeString()} ${label}: ${r.status}, ${elapsed}ms, SQL ${$('sql').textContent}`;$('history').prepend(li);while($('history').children.length>30)$('history').lastChild.remove();
});
function pushLog(data){$('pushLog').textContent=(JSON.stringify(data,null,2)+'\n'+$('pushLog').textContent).slice(0,20000);}
action('fcmEvents',async()=>{const {r,data}=await request('/local-test/api/fcm','GET',undefined,false);if(!r.ok)throw Error('전송 내역 조회 실패');pushLog(data);});
action('registerPush',async()=>{
  const config=JSON.parse($('firebaseConfig').value);
  for(const k of ['apiKey','projectId','messagingSenderId','appId'])if(!config[k])throw Error(`웹 설정 ${k} 필요`);
  if(config.private_key||config.type==='service_account')throw Error('서비스 계정 비밀키는 브라우저에 입력할 수 없습니다.');
  if(!$('vapid').value.trim())throw Error('VAPID 공개 키를 입력하세요.');
  if(!('serviceWorker' in navigator)||!('Notification' in window))throw Error('서비스 워커와 알림을 지원하는 localhost 브라우저가 필요합니다.');
  if(await Notification.requestPermission()!=='granted')throw Error('브라우저 알림 권한이 허용되지 않았습니다.');
  const appSdk=await import('https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js');
  const msgSdk=await import('https://www.gstatic.com/firebasejs/10.14.1/firebase-messaging.js');
  if(!(await msgSdk.isSupported()))throw Error('이 브라우저는 FCM을 지원하지 않습니다.');
  const swUrl='/local-test/firebase-messaging-sw.js?config='+encodeURIComponent(JSON.stringify(config));
  const registration=await navigator.serviceWorker.register(swUrl,{scope:'/local-test/'});
  await navigator.serviceWorker.ready;
  const app=appSdk.getApps().length?appSdk.getApp():appSdk.initializeApp(config);
  const messaging=msgSdk.getMessaging(app);if(unsubscribe)unsubscribe();unsubscribe=msgSdk.onMessage(messaging,p=>pushLog({source:'foreground',at:new Date().toISOString(),payload:p}));
  const fcmToken=await msgSdk.getToken(messaging,{vapidKey:$('vapid').value.trim(),serviceWorkerRegistration:registration});
  const {r,data}=await request('/local-test/api/fcm-token','POST',{account:Number($('account').value),token:fcmToken},false);if(!r.ok)throw Error(JSON.stringify(data));
  $('pushStatus').textContent=`계정 ${$('account').value}에 토큰 등록 완료. 실제 수신은 서버 FCM_REAL=true 설정 후 확인하세요.`;
});
navigator.serviceWorker?.addEventListener('message',event=>{if(event.data?.source==='background')pushLog(event.data);});
const linkedItem=new URLSearchParams(location.search).get('itemId');if(linkedItem&&/^\d+$/.test(linkedItem)){$('resourceId').value=linkedItem;$('endpoint').value='2';selection();}
