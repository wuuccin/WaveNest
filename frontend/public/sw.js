const CACHE = 'wavenest-basemap-v1'
const OFFLINE = '/basemap/nanchang-segment.svg'
self.addEventListener('install', event => event.waitUntil(caches.open(CACHE).then(cache => cache.add(OFFLINE))))
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()))
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url)
  if (event.request.method !== 'GET') return
  if (url.pathname === OFFLINE || url.hostname.endsWith('tianditu.gov.cn')) {
    event.respondWith(caches.match(event.request).then(cached => cached || fetch(event.request).then(response => {
      if (response.ok) event.waitUntil(caches.open(CACHE).then(cache => cache.put(event.request, response.clone())))
      return response
    })))
  }
})
