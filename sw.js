const CACHE = 'nafs-phase2-quran-hadith';
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.map(k => caches.delete(k)))));
});
self.addEventListener('fetch', e => {
  const u = e.request.url;
  if (u.includes('supabase.co') || u.includes('googleapis.com') || u.includes('quran.com') || u.includes('jsdelivr.net')) return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
});