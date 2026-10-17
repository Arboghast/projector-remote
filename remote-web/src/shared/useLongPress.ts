import { useEffect, useRef } from 'react'

const HOLD_MS = 550

/** Props for a button where a tap does one thing and holding it does another. */
export function useLongPress(onTap: () => void, onHold: () => void) {
  const timer = useRef<number | undefined>(undefined)
  const held = useRef(false)
  const cancel = () => window.clearTimeout(timer.current)
  useEffect(() => cancel, [])

  return {
    onPointerDown: () => {
      held.current = false
      timer.current = window.setTimeout(() => {
        held.current = true
        onHold()
        navigator.vibrate?.(15)
      }, HOLD_MS)
    },
    onPointerUp: () => {
      cancel()
      if (!held.current) onTap()
    },
    onPointerLeave: cancel,
    onPointerCancel: cancel,
  }
}
