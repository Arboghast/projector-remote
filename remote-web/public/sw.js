// Installability needs a service worker with a fetch handler; the remote is live-only, so it just
// passes every request through to the network.
self.addEventListener('fetch', () => {})

// The relay announces each link saved to the Shelf; tapping the notification puts it on the wall
// without opening the remote.
self.addEventListener('push', (event) => {
  const { url } = event.data?.json() ?? {}
  if (!url) return
  event.waitUntil(self.registration.showNotification('Saved to the Shelf', {
    body: `Tap to show it now · ${url.replace(/^https?:\/\/(www\.)?/, '')}`,
    icon: '/icon-192.png',
    tag: url,
    data: { url },
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = event.notification.data?.url
  if (!url) return
  event.waitUntil(project(url))
})

async function project(url) {
  try {
    const res = await fetch('/shelf/project', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'url=' + encodeURIComponent(url),
    })
    if (res.ok) return
    await self.registration.showNotification("Couldn't show it", { body: await res.text(), icon: '/icon-192.png' })
  } catch {
    await self.registration.showNotification("Couldn't reach the projector", { body: 'Check the Wi-Fi or Tailscale, then try again from the Shelf.', icon: '/icon-192.png' })
  }
}
