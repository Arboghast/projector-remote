// Installability needs a service worker with a fetch handler; the remote is live-only, so it just
// passes every request through to the network.
self.addEventListener('fetch', () => {})
