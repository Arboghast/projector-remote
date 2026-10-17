import { useRef, type PointerEvent } from 'react'
import { useFrameBatch } from '../pads/usePad'
import type { RemoteCommand } from '../relay'
import { scrollCommand } from './scroll'

const TAP_SLOP_PX = 10
const TAP_MAX_MS = 300

type Props = { send: (command: RemoteCommand) => void }

/** Classic's pad: drag scrolls the page as one continuous drag on the wall; a tap is OK. */
export function Trackpad({ send }: Props) {
  const gesture = useRef({ id: -1, x: 0, y: 0, startX: 0, startY: 0, startedAt: 0, moved: false })
  const scroll = useFrameBatch((dx, dy) => send(scrollCommand(dx, dy)))

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // Capture only keeps moves flowing past the pad's edge.
    }
    gesture.current = { id: e.pointerId, x: e.clientX, y: e.clientY, startX: e.clientX, startY: e.clientY, startedAt: e.timeStamp, moved: false }
  }

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const g = gesture.current
    if (e.pointerId !== g.id) return
    scroll(e.clientX - g.x, e.clientY - g.y)
    g.x = e.clientX
    g.y = e.clientY
    if (Math.hypot(e.clientX - g.startX, e.clientY - g.startY) > TAP_SLOP_PX) g.moved = true
  }

  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    const g = gesture.current
    if (e.pointerId !== g.id) return
    g.id = -1
    if (!g.moved && e.timeStamp - g.startedAt < TAP_MAX_MS) send({ type: 'key', key: 'ok' })
  }

  const onPointerCancel = () => {
    gesture.current.id = -1
  }

  return (
    <div className="trackpad" onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerCancel}>
      <span className="trackpad-hint">swipe to scroll · tap to select</span>
    </div>
  )
}
