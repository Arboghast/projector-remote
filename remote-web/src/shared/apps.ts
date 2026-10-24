export type AppInfo = {
  name: string
  color: string
}

export const TV_BRO = 'com.phlox.tvwebbrowser'

// Colour is the app's identity on the wall, so the remote borrows it to say "you're in YouTube"
// without words.
const APPS: Record<string, AppInfo> = {
  'com.google.android.youtube.tv': { name: 'YouTube', color: '#ff4e45' },
  'com.spotify.tv.android': { name: 'Spotify', color: '#1ed760' },
  [TV_BRO]: { name: 'TV Bro', color: '#5b8cff' },
  'tv.twitch.android.app': { name: 'Twitch', color: '#9b6bff' },
  'com.amazon.amazonvideo.livingroom': { name: 'Prime Video', color: '#1fa8e0' },
  'com.stremio.one': { name: 'Stremio', color: '#8a5cf6' },
  'com.google.android.apps.mediashell': { name: 'Cast', color: '#f5a524' },
  'com.google.android.tvlauncher': { name: 'Home', color: '#8a909c' },
  browser: { name: 'TV Bro', color: '#5b8cff' },
}

export function appInfo(pkg: string | null | undefined): AppInfo {
  if (!pkg) return { name: 'Projector', color: '#8a909c' }
  const known = APPS[pkg]
  if (known) return known
  const last = pkg.split('.').filter((p) => p !== 'android' && p !== 'tv' && p !== 'app').pop() ?? pkg
  return { name: last.charAt(0).toUpperCase() + last.slice(1), color: '#8a909c' }
}

export function linkLabel(url: string): { host: string; path: string } {
  try {
    const u = new URL(url)
    // Saved searches read as the words searched for, not "google.com / search".
    const q = u.searchParams.get('q')
    if (q && /(^|\.)google\./.test(u.hostname) && u.pathname === '/search') return { host: 'Search', path: q }
    const path = decodeURIComponent(u.pathname.replace(/\/$/, '')).split('/').pop()?.replace(/[_-]+/g, ' ') ?? ''
    return { host: u.hostname.replace(/^www\./, ''), path }
  } catch {
    return { host: url, path: '' }
  }
}

const BARE_DOMAIN = /^([\w-]+\.)+[a-z]{2,}(:\d+)?(\/\S*)?$/i

/** "youtube.com/x" or "https://…" → a full URL; anything else (a search, a sentence) → null. */
export function asLink(text: string): string | null {
  const t = text.trim()
  if (/^https?:\/\/\S+$/i.test(t)) return t
  if (BARE_DOMAIN.test(t)) return `https://${t}`
  return null
}


export const searchUrl = (q: string) => `https://www.google.com/search?q=${encodeURIComponent(q)}`
