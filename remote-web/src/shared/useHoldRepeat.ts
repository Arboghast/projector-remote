import { useCallback, useEffect, useRef } from 'react'

const FIRST_REPEAT_MS = 400
const REPEAT_MS = 110

/** Fires once on press, then repeats while held — like a physical remote's arrow keys. */
export function useHoldRepeat() {
  const timers = useRef<{ delay?: number; repeat?: number }>({})

  const release = useCallback(() => {
    window.clearTimeout(timers.current.delay)
    window.clearInterval(timers.current.repeat)
    timers.current = {}
  }, [])

  const press = useCallback((action: () => void) => {
    release()
    action()
    timers.current.delay = window.setTimeout(() => {
      timers.current.repeat = window.setInterval(action, REPEAT_MS)
    }, FIRST_REPEAT_MS)
  }, [release])

  useEffect(() => release, [release])

  /** Props for a button that fires on press and repeats while held. */
  const bind = (action: () => void) => ({
    onPointerDown: () => press(action),
    onPointerUp: release,
    onPointerLeave: release,
    onPointerCancel: release,
  })

  return { press, release, bind }
}
