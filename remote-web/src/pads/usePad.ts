import { useEffect, useRef, type PointerEvent } from 'react'

/**
 * Shared touch plumbing for the trackpad prototypes. It tracks fingers and reports moves,
 * taps and two-finger gestures; each pad decides what they mean on the projector.
 */
export type PadHandlers = {
  /** One finger moved by (dx, dy) phone pixels; `speed` is px/ms over the last few samples. */
  onMove: (dx: number, dy: number, speed: number) => void
  onTap: () => void
  /** One finger lifted after moving; velocity in px/ms (for momentum). */
  onRelease?: (vx: number, vy: number) => void
  onTwoFingerMove: (dx: number, dy: number) => void
  onTwoFingerTap: () => void
  onLongPress?: () => void
}

const TAP_SLOP_PX = 10
const TAP_MS = 250
const LONG_PRESS_MS = 550
const VELOCITY_WINDOW_MS = 80

type Sample = { x: number; y: number; t: number }

export function usePad(handlers: PadHandlers) {
  const h = useRef(handlers)
  h.current = handlers
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const s = useRef({ startX: 0, startY: 0, startT: 0, maxFingers: 0, moved: 0, last: { x: 0, y: 0 }, longFired: false })
  const samples = useRef<Sample[]>([])
  const longTimer = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearTimeout(longTimer.current), [])

  const centroid = () => {
    const ps = [...pointers.current.values()]
    return { x: ps.reduce((a, p) => a + p.x, 0) / ps.length, y: ps.reduce((a, p) => a + p.y, 0) / ps.length }
  }

  const velocity = () => {
    const now = performance.now()
    const recent = samples.current.filter((p) => now - p.t <= VELOCITY_WINDOW_MS)
    if (recent.length < 2) return { vx: 0, vy: 0 }
    const a = recent[0]
    const b = recent[recent.length - 1]
    const dt = Math.max(1, b.t - a.t)
    return { vx: (b.x - a.x) / dt, vy: (b.y - a.y) / dt }
  }

  const onPointerDown = (e: PointerEvent<HTMLElement>) => {
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // Capture only keeps moves flowing if the finger slides off the pad.
    }
    const st = s.current
    if (pointers.current.size === 1) {
      Object.assign(st, { startX: e.clientX, startY: e.clientY, startT: performance.now(), maxFingers: 1, moved: 0, last: { x: e.clientX, y: e.clientY }, longFired: false })
      samples.current = [{ x: e.clientX, y: e.clientY, t: performance.now() }]
      window.clearTimeout(longTimer.current)
      longTimer.current = window.setTimeout(() => {
        if (st.maxFingers === 1 && st.moved < TAP_SLOP_PX && h.current.onLongPress) {
          st.longFired = true
          h.current.onLongPress()
        }
      }, LONG_PRESS_MS)
    } else {
      window.clearTimeout(longTimer.current)
      st.maxFingers = Math.max(st.maxFingers, pointers.current.size)
      st.last = centroid()
    }
  }

  const onPointerMove = (e: PointerEvent<HTMLElement>) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const st = s.current
    const c = pointers.current.size > 1 ? centroid() : { x: e.clientX, y: e.clientY }
    const dx = c.x - st.last.x
    const dy = c.y - st.last.y
    st.last = c
    if (dx === 0 && dy === 0) return
    st.moved = Math.max(st.moved, Math.hypot(c.x - st.startX, c.y - st.startY))
    if (pointers.current.size > 1) {
      h.current.onTwoFingerMove(dx, dy)
      return
    }
    if (st.maxFingers > 1) return // lifting from a two-finger gesture; ignore the leftover finger
    samples.current.push({ x: c.x, y: c.y, t: performance.now() })
    if (samples.current.length > 12) samples.current.shift()
    const { vx, vy } = velocity()
    h.current.onMove(dx, dy, Math.hypot(vx, vy))
  }

  const onPointerUp = (e: PointerEvent<HTMLElement>) => {
    if (!pointers.current.delete(e.pointerId) || pointers.current.size > 0) return
    window.clearTimeout(longTimer.current)
    const st = s.current
    const quick = performance.now() - st.startT < TAP_MS + 100
    if (st.longFired) return
    if (st.maxFingers >= 2) {
      if (st.moved < TAP_SLOP_PX * 2 && quick) h.current.onTwoFingerTap()
      return
    }
    if (st.moved < TAP_SLOP_PX && performance.now() - st.startT < TAP_MS) {
      h.current.onTap()
      return
    }
    const { vx, vy } = velocity()
    h.current.onRelease?.(vx, vy)
  }

  /** Forgets the touch in progress without reporting a tap or release (another gesture took it). */
  const abort = () => {
    window.clearTimeout(longTimer.current)
    pointers.current.clear()
  }

  // The OS taking the touch (notification shade, back swipe) must not read as a tap.
  return { handlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: abort }, abort }
}

const BATCH_MS = 16

/**
 * Hands moves on at most once per ~frame (a timer, so it also runs when animation frames are
 * throttled). The first move after a pause goes out at once, so a drag starts without delay.
 */
export function useFrameBatch(flush: (dx: number, dy: number) => void) {
  const pending = useRef({ dx: 0, dy: 0 })
  const frame = useRef<number | null>(null)
  const flushRef = useRef(flush)
  flushRef.current = flush

  useEffect(() => () => {
    if (frame.current !== null) window.clearTimeout(frame.current)
  }, [])

  const tick = () => {
    const { dx, dy } = pending.current
    pending.current = { dx: 0, dy: 0 }
    if (dx === 0 && dy === 0) {
      frame.current = null
      return
    }
    flushRef.current(dx, dy)
    frame.current = window.setTimeout(tick, BATCH_MS)
  }

  return (dx: number, dy: number) => {
    pending.current.dx += dx
    pending.current.dy += dy
    if (frame.current === null) tick()
  }
}
