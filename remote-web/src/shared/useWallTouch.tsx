import { useState, type RefObject } from 'react'
import type { RemoteCommand } from '../relay'
import { useScroll } from './scroll'
import { useTouchSurface } from './useTouchSurface'

type Ripple = { x: number; y: number; at: number }

const RIPPLE_MS = 600

/**
 * A picture of the wall as a touch surface: tap presses that spot (with a ripple where you
 * touched) and drag moves the page with your finger. Returns the ripple to render on the picture.
 */
export function useWallTouch(
  send: (command: RemoteCommand) => void,
  surface: RefObject<HTMLElement | null>,
  picture: RefObject<HTMLElement | null>,
  enabled = true,
  rotated = false,
) {
  const [ripple, setRipple] = useState<Ripple | null>(null)
  const scroll = useScroll(send)

  useTouchSurface(surface, picture, {
    onTap: (x, y) => {
      send({ type: 'tap', x, y })
      const r = { x: x * 100, y: y * 100, at: Date.now() }
      setRipple(r)
      window.setTimeout(() => setRipple((cur) => (cur?.at === r.at ? null : cur)), RIPPLE_MS)
    },
    // You're touching the picture itself, so the page follows your finger (unlike the trackpads).
    onDrag: (dx, dy) => scroll(-dx, -dy),
  }, enabled, rotated)

  return ripple && <span key={ripple.at} className="screen-ripple" style={{ left: `${ripple.x}%`, top: `${ripple.y}%` }} />
}
