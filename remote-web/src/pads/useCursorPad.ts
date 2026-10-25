import { useRef } from 'react'
import type { RemoteCommand } from '../relay'
import { useScroll } from '../shared/scroll'
import { useFrameBatch, usePad } from './usePad'

/**
 * The cursor trackpad, the same wherever it appears: the pointer traces your finger with
 * acceleration (slow moves are precise, fast ones cross the wall), tap clicks, two fingers scroll,
 * a two-finger tap is Back and a long press is Menu.
 */
export function useCursorPad(send: (command: RemoteCommand) => void, haptics = false, swipeBack = false) {
  const speed = useRef(0)
  const buzz = (ms: number) => {
    if (haptics) navigator.vibrate?.(ms)
  }
  const scroll = useScroll(send)
  const move = useFrameBatch((dx, dy) => {
    const gain = 1.4 + Math.min(speed.current * 2.2, 4)
    send({ type: 'pointer', dx: Math.round(dx * gain), dy: Math.round(dy * gain) })
  })

  return usePad({
    onMove: (dx, dy, v) => {
      speed.current = v
      move(dx, dy)
    },
    onTap: () => {
      send({ type: 'click' })
      buzz(8)
    },
    // A drag rather than wheel notches: notches jump in fixed steps, a drag glides.
    onTwoFingerMove: (dx, dy) => scroll(dx, dy),
    onTwoFingerTap: () => {
      send({ type: 'key', key: 'back' })
      buzz(15)
    },
    onLongPress: () => {
      send({ type: 'key', key: 'menu' })
      buzz(15)
    },
    // Right goes back a page in the browser's tab (plain Back in other apps); left goes forward.
    onThreeFingerSwipe: swipeBack
      ? (direction) => {
          send(direction === 1 ? { type: 'key', key: 'back' } : { type: 'browser-forward' })
          buzz(15)
        }
      : undefined,
  })
}
