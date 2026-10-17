import { useRef, useState } from 'react'
import type { RemoteCommand } from '../relay'
import type { InkHandle } from './InkSurface'

const INKS = ['#f4f4f4', '#f5a524', '#ff5c5c', '#5b8cff', '#3ecf8e']
const SIZE = 0.005

/** Drawing live on top of whatever is on the wall: the ink, and the commands that reach the overlay. */
export function useDrawing(send: (command: RemoteCommand) => void) {
  const [drawing, setDrawing] = useState(false)
  const [ink, setInk] = useState(INKS[1])
  const surface = useRef<InkHandle>(null)

  const start = () => {
    setDrawing(true)
    send({ type: 'draw' })
  }
  const stop = () => {
    setDrawing(false)
    send({ type: 'draw-end' })
  }
  const clear = () => {
    surface.current?.clear()
    send({ type: 'ink-clear' })
  }
  const undo = () => {
    surface.current?.undo()
    send({ type: 'ink-undo' })
  }

  return { drawing, start, stop, clear, undo, ink, setInk, inks: INKS, size: SIZE, surface }
}
