// Mirrors InkView.java in the overlay app, so the phone and the wall draw the same thing.

import type { InkTool } from '../relay'

export type { InkTool }
export type Point = [number, number]

export const SHAPES: ReadonlySet<InkTool> = new Set(['arrow', 'rect', 'ellipse'])

/** Erasing needs a wider tip than drawing to be quick to use. */
export const ERASE_SCALE = 4

/** Arrowhead length relative to the line width, and never less than a fraction of the wall's height. */
const HEAD_PER_WIDTH = 4
const HEAD_MIN = 0.03
const HEAD_ANGLE = Math.PI / 7

/** Draws a two-point shape from a to b, in canvas pixels. */
export function drawShape(g: CanvasRenderingContext2D, tool: InkTool, a: Point, b: Point, wallHeight: number) {
  g.beginPath()
  if (tool === 'rect') {
    g.rect(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]))
  } else if (tool === 'ellipse') {
    g.ellipse((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, Math.abs(b[0] - a[0]) / 2, Math.abs(b[1] - a[1]) / 2, 0, 0, Math.PI * 2)
  } else {
    const angle = Math.atan2(b[1] - a[1], b[0] - a[0])
    const head = Math.max(g.lineWidth * HEAD_PER_WIDTH, HEAD_MIN * wallHeight)
    g.moveTo(a[0], a[1])
    g.lineTo(b[0], b[1])
    g.moveTo(b[0] - head * Math.cos(angle - HEAD_ANGLE), b[1] - head * Math.sin(angle - HEAD_ANGLE))
    g.lineTo(b[0], b[1])
    g.lineTo(b[0] - head * Math.cos(angle + HEAD_ANGLE), b[1] - head * Math.sin(angle + HEAD_ANGLE))
  }
  g.stroke()
}
