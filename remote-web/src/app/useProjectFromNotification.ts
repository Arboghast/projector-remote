import { useEffect, useState } from 'react'
import type { Link } from '../relay'

const RETRY_MS = 1500

/**
 * A tapped "Saved to the Shelf" notification on an iPhone opens the remote at `/?project=<link>`;
 * the link goes on the wall as soon as the projector is reachable (a woken phone's VPN can take a
 * moment), then the address is put back so a reload doesn't project it again.
 */
export function useProjectFromNotification(link: Link) {
  const [pending, setPending] = useState(() => new URLSearchParams(location.search).get('project'))

  // Already open when the notification was tapped: the service worker sends the link instead.
  useEffect(() => {
    const onMessage = (e: MessageEvent<unknown>) => {
      const data = e.data
      if (typeof data === 'object' && data !== null && 'type' in data && data.type === 'project' && 'url' in data && typeof data.url === 'string') {
        setPending(data.url)
      }
    }
    navigator.serviceWorker?.addEventListener('message', onMessage)
    return () => navigator.serviceWorker?.removeEventListener('message', onMessage)
  }, [])

  useEffect(() => {
    if (!pending || link !== 'ready') return
    let stopped = false
    let timer: number | undefined
    const attempt = async () => {
      try {
        const res = await fetch('/shelf/project', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: 'url=' + encodeURIComponent(pending),
        })
        // A refusal (say, the link won't open) is reported by the relay; retrying won't change it.
        if (res.ok || res.status < 500) return done()
      } catch {
        // Not reachable yet: try again.
      }
      if (!stopped) timer = window.setTimeout(attempt, RETRY_MS)
    }
    const done = () => {
      const params = new URLSearchParams(location.search)
      params.delete('project')
      history.replaceState(null, '', `${location.pathname}${params.size ? `?${params}` : ''}`)
      setPending(null)
    }
    attempt()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [pending, link])
}
