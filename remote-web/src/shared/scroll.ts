import { useRef } from 'react'
import { useFrameBatch } from '../pads/usePad'
import type { RemoteCommand } from '../relay'

const SCROLL_GAIN = 2.2
// A finger lifting off a swipe rolls back a few pixels. Opposite movement smaller than this is held
// back and dropped, so the wall doesn't jerk the other way at the end; a real reversal passes.
const REVERSE_PX = 12
// A pause this long starts a new swipe, whose direction is free again.
const NEW_SWIPE_MS = 180

/**
 * Finger movement on the phone → a scroll on the wall, used by every pad so they all agree.
 * Traditional direction (by request): moving the finger up scrolls the page up, like a scroll
 * wheel or laptop trackpad, rather than dragging the content along like a phone screen.
 */
function scrollCommand(dx: number, dy: number): RemoteCommand {
  return { type: 'scroll', dx: Math.round(dx * SCROLL_GAIN), dy: Math.round(dy * SCROLL_GAIN) }
}

type Axis = { dir: number; held: number }

/** Passes movement along each axis unless it briefly reverses the swipe's direction. */
export function steady(axis: Axis, d: number): number {
  if (d === 0) return 0
  const sign = Math.sign(d)
  if (axis.dir === 0 || sign === axis.dir) {
    axis.dir = sign
    axis.held = 0
    return d
  }
  axis.held += d
  if (Math.abs(axis.held) < REVERSE_PX) return 0
  const out = axis.held
  axis.dir = sign
  axis.held = 0
  return out
}

/** The scroll every surface uses: batched per frame, with lift-off wobble removed. */
export function useScroll(send: (command: RemoteCommand) => void) {
  return useSteady(useFrameBatch((dx, dy) => send(scrollCommand(dx, dy))))
}

function useSteady(sink: (dx: number, dy: number) => void) {
  const s = useRef({ x: { dir: 0, held: 0 }, y: { dir: 0, held: 0 }, at: 0 })
  return (dx: number, dy: number) => {
    const st = s.current
    const now = performance.now()
    if (now - st.at > NEW_SWIPE_MS) {
      st.x = { dir: 0, held: 0 }
      st.y = { dir: 0, held: 0 }
    }
    st.at = now
    const x = steady(st.x, dx)
    const y = steady(st.y, dy)
    if (x || y) sink(x, y)
  }
}
