// One click (toolbar) or right-click → the link is saved to the Shelf; a notification says what was saved.
importScripts('config.js')
// Off the home network the Wi-Fi address never answers, so give up on it quickly and try the next.
const ATTEMPT_MS = 2500

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: 'send', title: 'Send to Projector', contexts: ['page', 'link', 'video', 'selection'] })
})

// Without the "tabs" permission Edge hands the click a tab with no address ("Nothing to send").
async function pageUrl(tab) {
  if (tab?.url) return tab.url
  if (tab?.id !== undefined) return (await chrome.tabs.get(tab.id)).url
  const [active] = await chrome.tabs.query({ active: true, currentWindow: true })
  return active?.url
}

chrome.action.onClicked.addListener(async (tab) => send(await pageUrl(tab)))

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  // A right-clicked link wins over the page it sits on; selected text is searched for if it isn't a link.
  send(info.linkUrl || info.srcUrl || info.selectionText || info.pageUrl || (await pageUrl(tab)))
})

async function send(raw) {
  if (!raw) return notify('Nothing to send', 'This page has no address to save.')
  const res = await post(raw)
  if (!res) return notify("Couldn't reach the Shelf", 'Check that you are on the home Wi-Fi (or Tailscale is on) and the server is running.')
  const text = await res.text()
  if (!res.ok) return notify("Couldn't save", text)
  const url = text.replace(/^Saved to the Shelf:\s*/, '')
  notify('Saved to the Shelf · click to show it now', url, PROJECT + url)
  chrome.action.setBadgeBackgroundColor({ color: '#3ecf8e' })
  chrome.action.setBadgeText({ text: 'OK' })
  setTimeout(() => chrome.action.setBadgeText({ text: '' }), 2000)
}

// A "Saved" notification's id carries its link, so clicking it can put that link on the wall.
const PROJECT = 'project:'

chrome.notifications.onClicked.addListener(async (id) => {
  chrome.notifications.clear(id)
  if (!id.startsWith(PROJECT)) return
  const res = await post(id.slice(PROJECT.length), '/shelf/project')
  if (!res) return notify("Couldn't reach the projector", 'Check that you are on the home Wi-Fi (or Tailscale is on) and the server is running.')
  if (res.status === 404) return notify("Couldn't show it", 'The server is older than this extension. Update it with ./deploy.sh.')
  if (!res.ok) notify("Couldn't show it", await res.text())
})

/** The first address in RELAYS that answers; only an unreachable address moves on, so a link is never sent twice. */
async function post(raw, path = '/shelf?from=Windows') {
  for (const relay of RELAYS) {
    try {
      return await fetch(`${relay}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'url=' + encodeURIComponent(raw),
        signal: AbortSignal.timeout(ATTEMPT_MS),
      })
    } catch {
      // Unreachable from here: try the next address.
    }
  }
  return null
}

function notify(title, message, id = '') {
  chrome.notifications.create(id, { type: 'basic', iconUrl: 'icon-128.png', title, message: message || '' })
}
