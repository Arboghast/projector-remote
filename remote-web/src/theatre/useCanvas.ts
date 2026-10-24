import { useEffect, useRef, type PointerEvent } from 'react'
import type { RemoteCommand } from '../relay'
import { useCursorPad } from '../pads/useCursorPad'

export type Edge = 'top' | 'bottom' | 'right'

export type EdgeHandlers = {
  /** A pull from an edge was recognised; `distance` is how far it has come inward (px). */
  onPull: (edge: Edge, distance: number) => void
  /** The finger let go; `speed` is inward px/ms at release. */
  onPullEnd: (edge: Edge, distance: number, speed: number) => void
}

export type Feedback = {
  tap: (x: number, y: number) => void
  trail: (x: number, y: number) => void
}

// The left edge is left to Safari's back swipe.
const BAND_PX = 28
const COMMIT_PX = 16
const DECIDE_MS = 200

type Pending = { edge: Edge; x: number; y: number; t: number }
type Pulling = { edge: Edge; x: number; y: number; last: { d: number; t: number }; speed: number }

type Insets = { top: number; bottom: number; right: number }

/** Safe-area insets in px, read from a probe since env() can't be read from script. */
function insets(): Insets {
  const probe = document.createElement('div')
  probe.style.cssText = 'position:fixed;visibility:hidden;top:0;left:0;padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) 0'
  document.body.appendChild(probe)
  const s = getComputedStyle(probe)
  const out = { top: parseFloat(s.paddingTop) || 0, bottom: parseFloat(s.paddingBottom) || 0, right: parseFloat(s.paddingRight) || 0 }
  probe.remove()
  return out
}

function edgeAt(el: HTMLElement, x: number, y: number, safe: Insets): Edge | null {
  const box = el.getBoundingClientRect()
  if (y - box.top < safe.top + BAND_PX) return 'top'
  if (box.bottom - y < safe.bottom + BAND_PX) return 'bottom'
  if (box.right - x < safe.right + BAND_PX) return 'right'
  return null
}

function inward(edge: Edge, from: { x: number; y: number }, to: { x: number; y: number }) {
  const dx = to.x - from.x
  const dy = to.y - from.y
  if (edge === 'top') return { d: dy, side: dx }
  if (edge === 'bottom') return { d: -dy, side: dx }
  return { d: -dx, side: dy }
}

/**
 * The whole canvas is a trackpad for the wall's cursor; a pull that starts at an edge opens that
 * edge's panel instead. A touch in an edge band is held for up to DECIDE_MS until it's clearly a
 * pull (inward, mostly straight); otherwise it's replayed to the trackpad with nothing lost.
 */
export function useCanvas(send: (command: RemoteCommand) => void, edges: EdgeHandlers, feedback: Feedback) {
  const safe = useRef<Insets>({ top: 0, bottom: 0, right: 0 })
  const pending = useRef<Pending | null>(null)
  const pulling = useRef<Pulling | null>(null)
  const e = useRef(edges)
  e.current = edges

  // Turning the phone moves the notch and home indicator.
  useEffect(() => {
    const measure = () => {
      safe.current = insets()
    }
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [])

  const pad = useCursorPad(send, true)

  // The pad already has the touch (so a long press is timed from when it began); it just hasn't seen moves.
  const toPad = () => {
    pending.current = null
  }

  const onPointerDown = (ev: PointerEvent<HTMLElement>) => {
    try {
      ev.currentTarget.setPointerCapture(ev.pointerId)
    } catch {
      // Capture only keeps moves flowing past the element's edge.
    }
    feedback.tap(ev.clientX, ev.clientY)
    const edge = ev.isPrimary && !pulling.current ? edgeAt(ev.currentTarget, ev.clientX, ev.clientY, safe.current) : null
    if (edge) pending.current = { edge, x: ev.clientX, y: ev.clientY, t: performance.now() }
    else if (pending.current) toPad() // a second finger joined: it's a trackpad gesture
    pad.handlers.onPointerDown(ev)
  }

  const onPointerMove = (ev: PointerEvent<HTMLElement>) => {
    const pull = pulling.current
    if (pull) {
      const { d } = inward(pull.edge, pull, { x: ev.clientX, y: ev.clientY })
      const now = performance.now()
      pull.speed = (d - pull.last.d) / Math.max(1, now - pull.last.t)
      pull.last = { d, t: now }
      e.current.onPull(pull.edge, Math.max(0, d))
      return
    }
    const p = pending.current
    if (p) {
      const { d, side } = inward(p.edge, p, { x: ev.clientX, y: ev.clientY })
      if (d >= COMMIT_PX && d > 2 * Math.abs(side)) {
        pending.current = null
        pad.abort()
        pulling.current = { edge: p.edge, x: p.x, y: p.y, last: { d, t: performance.now() }, speed: 0 }
        e.current.onPull(p.edge, d)
        return
      }
      if (performance.now() - p.t < DECIDE_MS && Math.abs(side) < COMMIT_PX && d > -COMMIT_PX) return
      toPad()
    }
    feedback.trail(ev.clientX, ev.clientY)
    pad.handlers.onPointerMove(ev)
  }

  const onPointerUp = (ev: PointerEvent<HTMLElement>) => {
    const pull = pulling.current
    if (pull) {
      pulling.current = null
      pad.abort()
      const { d } = inward(pull.edge, pull, { x: ev.clientX, y: ev.clientY })
      e.current.onPullEnd(pull.edge, Math.max(0, d), pull.speed)
      return
    }
    if (pending.current) toPad() // never moved enough to be a pull: a tap or a long press
    pad.handlers.onPointerUp(ev)
  }

  // The OS taking the touch (notification shade, back swipe) must not read as a tap or a pull.
  const onPointerCancel = () => {
    const pull = pulling.current
    pulling.current = null
    pending.current = null
    pad.abort()
    if (pull) e.current.onPullEnd(pull.edge, 0, 0)
  }

  return { onPointerDown, onPointerMove, onPointerUp, onPointerCancel }
}
