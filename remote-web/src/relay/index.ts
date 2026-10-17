import { useCallback, useEffect, useRef, useState } from 'react'
import { parseRelayEvent, type ProjectorState, type RemoteCommand, type ShelfItem } from './protocol'

export type { MediaAction, NowPlaying, ProjectorState, RemoteCommand, RemoteKey, ShelfItem } from './protocol'

/** relay = phone ↔ PC socket; projector = PC ↔ projector adb link. */
export type Link = 'connecting' | 'relay-down' | 'projector-off' | 'ready'

export type Relay = {
  link: Link
  error: string | null
  state: ProjectorState
  shelf: ShelfItem[]
  send: (command: RemoteCommand) => void
}

const RETRY_MS = 2000
const ERROR_SHOWN_MS = 3500
const EMPTY_STATE: ProjectorState = { app: null, nowPlaying: null, lastOpened: null, keyboard: false }

/** A socket on the relay that served this page. */
export function wsUrl(path: string) {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${path}`
}

export function useRelay(): Relay {
  const socket = useRef<WebSocket | null>(null)
  const [link, setLink] = useState<Link>('connecting')
  const [error, setError] = useState<string | null>(null)
  const [state, setState] = useState<ProjectorState>(EMPTY_STATE)
  const [shelf, setShelf] = useState<ShelfItem[]>([])

  useEffect(() => {
    let closed = false
    let retry: number | undefined

    const open = () => {
      const ws = new WebSocket(wsUrl('/ws'))
      socket.current = ws
      ws.onmessage = (e) => {
        const event = parseRelayEvent(String(e.data))
        switch (event?.type) {
          case 'status': setLink(event.connected ? 'ready' : 'projector-off'); break
          case 'error': setError(event.message); break
          case 'state': setState(event.state); break
          case 'shelf': setShelf(event.items); break
        }
      }
      ws.onclose = () => {
        if (closed) return
        setLink('relay-down')
        retry = window.setTimeout(open, RETRY_MS)
      }
    }
    open()

    return () => {
      closed = true
      window.clearTimeout(retry)
      socket.current?.close()
    }
  }, [])

  useEffect(() => {
    if (!error) return
    const timer = window.setTimeout(() => setError(null), ERROR_SHOWN_MS)
    return () => window.clearTimeout(timer)
  }, [error])

  const send = useCallback((command: RemoteCommand) => {
    const ws = socket.current
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(command))
  }, [])

  return { link, error, state, shelf, send }
}

/** Where the phone is, as shown on shelf items ("from iPhone"). */
export function deviceName(): string {
  const ua = navigator.userAgent
  if (/iPhone|iPad/.test(ua)) return 'iPhone'
  if (/SM-F9/.test(ua)) return 'Fold'
  if (/Android/.test(ua)) return 'Android'
  return 'PC'
}
