import { useEffect, useRef, useState, type RefObject } from 'react'
import type { RemoteCommand } from '../relay'
import { useWallTouch } from './useWallTouch'

type Props = {
  send: (command: RemoteCommand) => void
  active: boolean
  surface?: RefObject<HTMLElement | null>
}

// A frame costs the projector ~2 s of capture, so the preview refreshes as fast as frames arrive
// and never queues more than one request.
const PAUSE_BETWEEN_MS = 300
const RETRY_MS = 3000

/** A live-ish picture of the wall (the fallback when video can't play): tap presses, drag scrolls. */
export function ScreenPreview({ send, active, surface }: Props) {
  const frame = useRef<HTMLDivElement>(null)
  const [src, setSrc] = useState<string | null>(null)
  const [stale, setStale] = useState(false)
  const current = useRef<string | null>(null)

  useEffect(() => {
    if (!active) return
    let stopped = false
    let timer: number | undefined

    const next = async () => {
      if (stopped) return
      if (document.hidden) {
        timer = window.setTimeout(next, RETRY_MS)
        return
      }
      try {
        const res = await fetch(`/screen.jpg?t=${Date.now()}`)
        if (!res.ok) throw new Error(String(res.status))
        const url = URL.createObjectURL(await res.blob())
        if (stopped) return URL.revokeObjectURL(url)
        if (current.current) URL.revokeObjectURL(current.current)
        current.current = url
        setSrc(url)
        setStale(false)
        timer = window.setTimeout(next, PAUSE_BETWEEN_MS)
      } catch {
        setStale(true)
        timer = window.setTimeout(next, RETRY_MS)
      }
    }
    next()

    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [active])

  useEffect(() => () => {
    if (current.current) URL.revokeObjectURL(current.current)
  }, [])

  const ripple = useWallTouch(send, surface ?? frame, frame)

  return (
    <div ref={frame} className="screen-preview" data-wall-picture data-stale={stale} role="application" aria-label="The wall: tap to press, drag to scroll">
      {src ? <img src={src} alt="" draggable={false} /> : <span className="screen-preview-wait">Loading the screen…</span>}
      {ripple}
      <span className="screen-live" aria-hidden="true" />
    </div>
  )
}
