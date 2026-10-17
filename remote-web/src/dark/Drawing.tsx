import { useRef, useState } from 'react'
import type { InkTool, RemoteCommand } from '../relay'
import type { InkHandle } from './InkSurface'

const INKS = ['#f4f4f4', '#f5a524', '#ff5c5c', '#5b8cff', '#3ecf8e']
const TOOLS: InkTool[] = ['pen', 'arrow', 'rect', 'ellipse', 'erase']
const SIZE = 0.005

/** Drawing live on top of whatever is on the wall: the ink, and the commands that reach the overlay. */
export function useDrawing(send: (command: RemoteCommand) => void) {
  const [drawing, setDrawing] = useState(false)
  const [ink, setInk] = useState(INKS[1])
  const [tool, setTool] = useState<InkTool>('pen')
  const surface = useRef<InkHandle>(null)

  const start = () => {
    setDrawing(true)
    setTool('pen')
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
  // Picking a colour means you want to draw with it, so it leaves the eraser.
  const pickInk = (color: string) => {
    setInk(color)
    if (tool === 'erase') setTool('pen')
  }

  return { drawing, start, stop, clear, undo, ink, setInk: pickInk, inks: INKS, tool, setTool, tools: TOOLS, size: SIZE, surface }
}
