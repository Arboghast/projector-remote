// Installability needs a service worker with a fetch handler; the remote is live-only, so it just
// passes every request through to the network.
self.addEventListener('fetch', () => {})

// Take over at once: otherwise a new version waits until every remote window is closed, which an
// iPhone home-screen app almost never is, and taps keep reaching the old code.
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim().then(() => clientLog('sw activated'))))

// The relay announces each link saved to the Shelf; tapping the notification puts it on the wall
// without opening the remote.
self.addEventListener('push', (event) => {
  const { url, message } = event.data?.json() ?? {}
  // iOS drops the subscription if a push shows nothing, so every push shows something.
  if (!url) return event.waitUntil(self.registration.showNotification(message || 'Projector Remote', { icon: '/icon-192.png' }))
  event.waitUntil(self.registration.showNotification('Saved to the Shelf', {
    body: `Tap to show it now · ${url.replace(/^https?:\/\/(www\.)?/, '')}`,
    icon: '/icon-192.png',
    tag: url,
    data: { url },
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  // The tag is the link too: iOS may hand the tap over without the notification's data.
  const url = event.notification.data?.url ?? event.notification.tag
  event.waitUntil(clientLog(`sw notificationclick data=${JSON.stringify(event.notification.data ?? null)} tag=${event.notification.tag}`))
  if (!url) return
  // The tap takes you to the remote, which projects the link once it's connected (and iOS would
  // cut a request from this worker short anyway).
  event.waitUntil(projectInApp(url))
})

// An open remote only gets focused by openWindow, not reloaded, so it's told by message instead.
async function projectInApp(url) {
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  clientLog(`sw tap: ${windows.length} window(s) ${windows.map((w) => w.url).join(' ')}`)
  const [open] = windows
  if (!open) {
    const opened = await self.clients.openWindow('/?project=' + encodeURIComponent(url)).catch((e) => clientLog(`sw openWindow failed: ${e}`))
    return clientLog(`sw opened: ${opened ? opened.url : 'null'}`)
  }
  open.postMessage({ type: 'project', url })
  await open.focus().catch((e) => clientLog(`sw focus failed: ${e}`))
  clientLog('sw messaged the open window')
}

function clientLog(message) {
  return fetch('/client-log', { method: 'POST', body: message, keepalive: true }).catch(() => {})
}
