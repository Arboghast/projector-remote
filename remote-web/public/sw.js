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
  // iOS always opens the app on a tap and cuts this worker's request short, so there the app
  // projects the link once it's connected; Android can do it in the background.
  if (/iPhone|iPad/.test(navigator.userAgent)) return event.waitUntil(projectInApp(url))
  event.waitUntil(project(url))
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

const ATTEMPTS = 3
const RETRY_MS = 1500

// A phone just woken by the tap may still be reconnecting Wi-Fi or Tailscale.
async function project(url) {
  for (let i = 1; i <= ATTEMPTS; i++) {
    try {
      const res = await fetch('/shelf/project', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'url=' + encodeURIComponent(url),
      })
      if (res.ok) return
      if (res.status < 500 || i === ATTEMPTS) {
        return self.registration.showNotification("Couldn't show it", { body: await res.text(), icon: '/icon-192.png' })
      }
    } catch {
      if (i === ATTEMPTS) {
        return self.registration.showNotification("Couldn't reach the projector", { body: 'Check the Wi-Fi or Tailscale, then try again from the Shelf.', icon: '/icon-192.png' })
      }
    }
    await new Promise((r) => setTimeout(r, RETRY_MS))
  }
}
