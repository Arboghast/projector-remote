import { useCallback, useEffect, useRef, useState } from 'react'
import { parseRelayEvent, type ClaudeEvent, type ProjectorState, type RemoteCommand, type ShelfItem } from './protocol'

export type { ClaudeEvent, InkTool, MediaAction, NowPlaying, ProjectorState, RemoteCommand, RemoteKey, ShelfItem } from './protocol'

/** One spoken request to Claude: what it's done so far, and how it ended once it has. */
export type ClaudeRun = {
  id: number
  phase: ClaudeEvent['phase']
  request: string | null
  steps: { kind: ClaudeEvent['kind']; text: string }[]
  answer: string | null
}

/** relay = phone ↔ PC socket; projector = PC ↔ projector adb link. */
export type Link = 'connecting' | 'relay-down' | 'projector-off' | 'ready'

export type Relay = {
  link: Link
  error: string | null
  state: ProjectorState
  shelf: ShelfItem[]
  claude: ClaudeRun | null
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
  const [claude, setClaude] = useState<ClaudeRun | null>(null)

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
          case 'claude': setClaude((run) => nextRun(run, event)); break
        }
      }
      ws.onclose = () => {
        if (closed) return
        // The relay replays the run in progress on reconnect; what's here may have ended meanwhile.
        setClaude(null)
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

  return { link, error, state, shelf, claude, send }
}

/** Folds one progress event into the run it belongs to; a working event after an ending starts a new run. */
export function nextRun(run: ClaudeRun | null, event: ClaudeEvent): ClaudeRun {
  const base: ClaudeRun = run?.id === event.run ? run : { id: event.run, phase: 'working', request: null, steps: [], answer: null }
  if (event.phase === 'working') {
    if (event.kind === 'request') return { ...base, request: event.text }
    if (event.kind === 'status') return base
    return { ...base, steps: [...base.steps, { kind: event.kind, text: event.text }] }
  }
  // Claude's last words usually are its answer; don't show them twice.
  const last = base.steps[base.steps.length - 1]
  const repeated = last?.kind === 'said' && last.text.trim() === event.text.trim()
  return { ...base, phase: event.phase, steps: repeated ? base.steps.slice(0, -1) : base.steps, answer: event.text }
}

/** Where the phone is, as shown on shelf items ("from iPhone"). */
export function deviceName(): string {
  const ua = navigator.userAgent
  if (/iPhone|iPad/.test(ua)) return 'iPhone'
  if (/SM-F9/.test(ua)) return 'Fold'
  if (/Android/.test(ua)) return 'Android'
  return 'PC'
}
