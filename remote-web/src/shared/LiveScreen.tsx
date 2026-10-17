import { useEffect, useRef, useState, type RefObject } from 'react'
import type { RemoteCommand } from '../relay'
import { unwarpTransform } from './keystone'
import { ScreenPreview } from './ScreenPreview'
import { useH264Stream } from './useH264Stream'
import { useWallTouch } from './useWallTouch'

type Props = {
  send: (command: RemoteCommand) => void
  active: boolean
  /** A larger element to take touches from (the picture still decides where a tap lands). */
  surface?: RefObject<HTMLElement | null>
  /** The picture is shown turned on its side, so touches are turned back. */
  rotated?: boolean
}

/**
 * The wall as live video (~0.1–0.2 s behind), straightened for the projector's keystone. Falls back
 * to the snapshot preview when the browser can't decode it.
 */
export function LiveScreen({ send, active, surface, rotated = false }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const frame = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })
  const { failed, quad } = useH264Stream(canvas, active)
  // The snapshot fallback takes over the touch surface when video can't play.
  const ripple = useWallTouch(send, surface ?? frame, frame, !failed, rotated)

  useEffect(() => {
    const el = frame.current
    if (!el) return
    const observer = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }))
    observer.observe(el)
    return () => observer.disconnect()
  }, [failed])

  if (failed) return <ScreenPreview send={send} active={active} surface={surface} rotated={rotated} />

  return (
    <div ref={frame} className="screen-preview" data-wall-picture role="application" aria-label="The wall: tap to press, drag to scroll">
      <canvas
        ref={canvas}
        className="screen-canvas"
        style={quad && size.w ? { transform: unwarpTransform(quad, size.w, size.h) } : undefined}
      />
      {ripple}
      <span className="screen-live" aria-hidden="true" />
    </div>
  )
}
