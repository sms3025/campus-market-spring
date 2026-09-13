/* Public Firebase web config only. Scope is /local-test/, never the site root. */
self.addEventListener('notificationclick',event=>{
  event.stopImmediatePropagation();event.notification.close();
  const data=event.notification.data?.FCM_MSG?.data ?? event.notification.data ?? {};
  let target=new URL('/local-test/',self.location.origin);
  try{const candidate=new URL(data.deeplink,self.location.origin);if(candidate.origin===self.location.origin&&candidate.pathname==='/local-test/')target=candidate;}catch{}
  event.waitUntil(self.clients.openWindow(target.href));
});
importScripts('https://www.gstatic.com/firebasejs/10.14.1/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.14.1/firebase-messaging-compat.js');
const config=JSON.parse(new URL(self.location.href).searchParams.get('config')||'{}');
if(config.projectId){
  firebase.initializeApp(config);
  firebase.messaging().onBackgroundMessage(async payload=>{
    // Firebase already displays notification payloads. Do not display a duplicate.
    const windows=await self.clients.matchAll({type:'window',includeUncontrolled:true});
    windows.forEach(client=>client.postMessage({source:'background',at:new Date().toISOString(),payload}));
  });
}
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
