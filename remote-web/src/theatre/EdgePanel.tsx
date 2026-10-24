import { useRef, useState, type PointerEvent, type ReactNode } from 'react'
import type { Edge } from './useCanvas'

type Props = {
  edge: Edge
  /** Finger distance while being pulled out; null when not being dragged. */
  shown: number | null
  open: boolean
  glance?: boolean
  onClose: () => void
  children: ReactNode
}

const PUSH_START_PX = 12
const PUSH_CLOSE_PX = 60

/** Where a panel sits: following the pull, pushed back, open, at glance height, or tucked away. */
export function panelTransform(edge: Edge, shown: number | null, pushed: number, open: boolean, glance: boolean): string {
  const axis = edge === 'right' ? 'X' : 'Y'
  const sign = edge === 'top' ? -1 : 1
  if (shown !== null) {
    return edge === 'top'
      ? `translateY(min(0px, calc(-100% + ${shown}px)))`
      : `translate${axis}(max(0px, calc(100% - ${shown}px)))`
  }
  if (pushed) return `translate${axis}(${sign * pushed}px)`
  if (open) return 'none'
  if (glance) return 'translateY(calc(100% - var(--glance-h)))'
  return `translate${axis}(${sign * 100}%)`
}

/** A panel tucked behind an edge: it follows the finger while pulled, then settles open or away. */
export function EdgePanel({ edge, shown, open, glance = false, onClose, children }: Props) {
  const back = useRef<{ start: number; d: number } | null>(null)
  const [pushed, setPushed] = useState(0)
  const sign = edge === 'top' ? -1 : 1

  // Pushing a panel back toward its edge closes it.
  const pos = (e: PointerEvent) => (edge === 'right' ? e.clientX : e.clientY)
  const onPointerDown = (e: PointerEvent<HTMLElement>) => {
    // Touches on the wall's picture press and scroll the wall, and a Shelf card swipes on its own;
    // only the rest of the panel pushes it away.
    if ((e.target as Element).closest('[data-wall-picture], .shelf-face')) return
    back.current = { start: pos(e), d: 0 }
  }
  const onPointerMove = (e: PointerEvent<HTMLElement>) => {
    if (!back.current) return
    back.current.d = Math.max(0, (pos(e) - back.current.start) * sign)
    if (back.current.d > PUSH_START_PX) setPushed(back.current.d)
  }
  const onPointerUp = () => {
    if (back.current && back.current.d > PUSH_CLOSE_PX) onClose()
    back.current = null
    setPushed(0)
  }

  return (
    <section
      className={`edge-panel edge-${edge}`}
      data-settling={shown === null && !pushed ? true : undefined}
      data-hidden={shown === null && !open && !glance ? true : undefined}
      data-open={open || undefined}
      style={{ transform: panelTransform(edge, shown, pushed, open, glance) }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      {children}
    </section>
  )
}
