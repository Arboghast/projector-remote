import type { ReactNode } from 'react'
import type { RemoteCommand } from '../relay'
import { useScroll } from '../shared/scroll'
import { useCursorPad } from './useCursorPad'
import { usePad } from './usePad'
import './pads.css'

type PadProps = { send: (command: RemoteCommand) => void }

// ── A: drag to scroll (what Classic and Lens use) ──

/** The page follows the finger as one continuous drag; tap is OK. */
export function ScrollPad({ send }: PadProps) {
  const scroll = useScroll(send)
  const pad = usePad({
    onMove: (dx, dy) => scroll(dx, dy),
    onTap: () => send({ type: 'key', key: 'ok' }),
    onTwoFingerMove: (dx, dy) => scroll(dx, dy),
    onTwoFingerTap: () => send({ type: 'key', key: 'back' }),
    onLongPress: () => send({ type: 'key', key: 'menu' }),
  })
  return <div className="pad" {...pad.handlers}><span className="pad-hint">drag to scroll · tap to select</span></div>
}

// ── B: cursor ──

/**
 * A mouse pointer on the wall that traces the finger. Gain rises with speed (pointer
 * acceleration): slow moves are precise, fast moves cross the screen.
 */
export function CursorPad({ send, className, children }: PadProps & { className?: string; children?: ReactNode }) {
  const pad = useCursorPad(send)
  return (
    <div className={`pad ${className ?? ''}`} {...pad.handlers}>
      {children ?? <span className="pad-hint">move the cursor · tap to click · two fingers to scroll</span>}
    </div>
  )
}
