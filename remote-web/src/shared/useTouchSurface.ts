import { useEffect, useRef, type RefObject } from 'react'
import { useFrameBatch } from '../pads/usePad'

type Handlers = {
  /** A tap on the picture, as fractions of the picture's box. */
  onTap: (x: number, y: number) => void
  /** Finger movement while dragging, in phone pixels. */
  onDrag: (dx: number, dy: number) => void
}

const SLOP_PX = 10
const TAP_MS = 400

/**
 * Touch on a live picture of the wall: tap presses that spot, drag scrolls. The surface can be
 * larger than the picture (Lens uses the whole area around it), so taps are mapped through the
 * picture's own box and taps beside it do nothing. Native listeners, since the surface may belong
 * to a parent component.
 */
export function useTouchSurface(surface: RefObject<HTMLElement | null>, picture: RefObject<HTMLElement | null>, handlers: Handlers, enabled = true) {
  const h = useRef(handlers)
  h.current = handlers
  const drag = useFrameBatch((dx, dy) => h.current.onDrag(dx, dy))
  const dragRef = useRef(drag)
  dragRef.current = drag

  useEffect(() => {
    const el = surface.current
    if (!el || !enabled) return
    let start: { id: number; x: number; y: number; t: number } | null = null
    let last = { x: 0, y: 0 }
    let dragging = false

    const down = (e: PointerEvent) => {
      if (start) return // one finger at a time
      start = { id: e.pointerId, x: e.clientX, y: e.clientY, t: performance.now() }
      last = { x: e.clientX, y: e.clientY }
      dragging = false
      try {
        el.setPointerCapture(e.pointerId)
      } catch {
        // keeps working without capture
      }
    }
    const move = (e: PointerEvent) => {
      if (!start || e.pointerId !== start.id) return
      if (!dragging && Math.hypot(e.clientX - start.x, e.clientY - start.y) > SLOP_PX) dragging = true
      if (dragging) dragRef.current(e.clientX - last.x, e.clientY - last.y)
      last = { x: e.clientX, y: e.clientY }
    }
    const up = (e: PointerEvent) => {
      if (!start || e.pointerId !== start.id) return
      const quick = performance.now() - start.t < TAP_MS
      start = null
      if (dragging || !quick) return
      const box = picture.current?.getBoundingClientRect()
      if (!box) return
      const x = (e.clientX - box.left) / box.width
      const y = (e.clientY - box.top) / box.height
      if (x >= 0 && x <= 1 && y >= 0 && y <= 1) h.current.onTap(x, y)
    }
    const cancel = () => {
      start = null
    }

    el.addEventListener('pointerdown', down)
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', cancel)
    return () => {
      el.removeEventListener('pointerdown', down)
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
      el.removeEventListener('pointercancel', cancel)
    }
  }, [surface, picture, enabled])
}
