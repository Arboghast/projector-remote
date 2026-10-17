import { forwardRef, useEffect, useImperativeHandle, useRef, type PointerEvent, type ReactNode } from 'react'
import type { RemoteCommand } from '../relay'
import { toWall, useWallFit, wallStageStyle } from '../shared/wallFit'
import { drawShape, ERASE_SCALE, SHAPES, type InkTool, type Point } from './inkShapes'

type Props = {
  send: (command: RemoteCommand) => void
  color: string
  tool: InkTool
  /** Line width as a fraction of the wall's height. */
  size: number
  /** Drawn under the ink, lined up with the wall (e.g. its live picture). */
  backdrop?: ReactNode
  onStrokeStart?: () => void
  onStrokeEnd?: () => void
}

/** What the drawing controls can do to the surface. */
export type InkHandle = { clear: () => void; undo: () => void }

type Stroke = { id: string; color: string; size: number; tool: InkTool; points: Point[] }

const FLUSH_MS = 16
// The wall is ~5x the phone, so touchscreen jitter shows as jagged ink. Each sample is eased toward
// the finger, and samples closer together than MIN_STEP (fractions of the wall) are skipped.
const SMOOTHING = 0.4
const MIN_STEP = 0.0015

const round = (v: number) => Math.round(v * 10000) / 10000
const distance = (a: Point, b: Point) => Math.hypot(a[0] - b[0], a[1] - b[1])

/**
 * The wall as large as the screen allows. On a portrait phone the wall is turned on its side
 * (its top along the phone's right edge), so it fills the screen; turn the phone to draw upright.
 * Strokes are kept, so undo and clear match the wall exactly. Freehand ink is drawn a piece at a
 * time; a shape (arrow, rectangle, ellipse) follows the finger from where it went down.
 */
export const InkSurface = forwardRef<InkHandle, Props>(function InkSurface({ send, color, tool, size, backdrop, onStrokeStart, onStrokeEnd }, ref) {
  const wrap = useRef<HTMLDivElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const strokes = useRef<Stroke[]>([])
  const active = useRef<{ pointer: number; stroke: Stroke; raw?: Point } | null>(null)
  const pending = useRef<Point[]>([])
  const timer = useRef<number | undefined>(undefined)
  const frame = useRef<number | undefined>(undefined)
  // The stage's box, measured once per stroke rather than for every sample (each read forces layout).
  const box = useRef<DOMRect | null>(null)
  const layout = useWallFit(wrap)

  const pen = () => {
    const c = canvas.current
    const g = c?.getContext('2d')
    if (!c || !g) return null
    g.lineCap = 'round'
    g.lineJoin = 'round'
    return { c, g }
  }

  const style = (g: CanvasRenderingContext2D, c: HTMLCanvasElement, s: Stroke) => {
    g.globalCompositeOperation = s.tool === 'erase' ? 'destination-out' : 'source-over'
    g.strokeStyle = s.color
    g.fillStyle = s.color
    g.lineWidth = Math.max(2, s.size * c.height)
  }

  const px = (c: HTMLCanvasElement, p: Point): Point => [p[0] * c.width, p[1] * c.height]

  // Through the midpoints with the samples as control points: a smooth curve, no corners. Piece i
  // runs from the midpoint before sample i to the midpoint after it, so pieces join seamlessly and
  // a stroke can be drawn a piece at a time while the finger moves.
  const drawPiece = (g: CanvasRenderingContext2D, c: HTMLCanvasElement, s: Stroke, i: number) => {
    const at = (k: number) => px(c, s.points[k])
    const mid = (a: Point, b: Point): Point => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
    style(g, c, s)
    g.beginPath()
    if (i === 0) {
      const p = at(0)
      g.arc(p[0], p[1], g.lineWidth / 2, 0, Math.PI * 2)
      g.fill()
      return
    }
    const from = i === 1 ? at(0) : mid(at(i - 2), at(i - 1))
    const to = mid(at(i - 1), at(i))
    g.moveTo(from[0], from[1])
    g.quadraticCurveTo(at(i - 1)[0], at(i - 1)[1], to[0], to[1])
    g.stroke()
  }

  /** The stroke's last half-piece, from its final midpoint to where the finger lifted. */
  const drawTail = (g: CanvasRenderingContext2D, c: HTMLCanvasElement, s: Stroke) => {
    const n = s.points.length
    if (n < 2) return
    const a = px(c, s.points[n - 2])
    const b = px(c, s.points[n - 1])
    style(g, c, s)
    g.beginPath()
    g.moveTo((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    g.lineTo(b[0], b[1])
    g.stroke()
  }

  const drawStroke = (g: CanvasRenderingContext2D, c: HTMLCanvasElement, s: Stroke) => {
    if (SHAPES.has(s.tool)) {
      if (s.points.length < 2) return
      style(g, c, s)
      drawShape(g, s.tool, px(c, s.points[0]), px(c, s.points[1]), c.height)
      return
    }
    for (let i = 0; i < s.points.length; i++) drawPiece(g, c, s, i)
    if (s !== active.current?.stroke) drawTail(g, c, s)
  }

  /** Whole-canvas redraw: undo, clear, resizing, and a shape following the finger. */
  const render = () => {
    const p = pen()
    if (!p) return
    p.g.globalCompositeOperation = 'source-over'
    p.g.clearRect(0, 0, p.c.width, p.c.height)
    for (const s of strokes.current) drawStroke(p.g, p.c, s)
  }

  const renderSoon = () => {
    if (frame.current === undefined) frame.current = requestAnimationFrame(() => {
      frame.current = undefined
      render()
    })
  }

  useEffect(() => {
    const c = canvas.current
    if (!c || !layout.long) return
    c.width = Math.round(layout.long * devicePixelRatio)
    c.height = Math.round(layout.short * devicePixelRatio)
    render()
  }, [layout])

  useImperativeHandle(ref, () => ({
    clear: () => {
      strokes.current = []
      render()
    },
    undo: () => {
      strokes.current.pop()
      render()
    },
  }), [])

  useEffect(() => () => {
    window.clearTimeout(timer.current)
    if (frame.current !== undefined) cancelAnimationFrame(frame.current)
  }, [])

  const flush = () => {
    timer.current = undefined
    const a = active.current
    if (!a || pending.current.length === 0) return
    const { id, color, size, tool } = a.stroke
    send({ type: 'ink', id, points: pending.current, color, size, tool })
    pending.current = []
  }

  const flushSoon = () => {
    if (timer.current === undefined) timer.current = window.setTimeout(flush, FLUSH_MS)
  }

  /** Screen position → fraction of the wall, undoing the sideways turn in portrait. */
  const wallPoint = (x: number, y: number): Point => {
    const r = box.current
    if (!r) return [0, 0]
    const clamp = (v: number) => round(Math.min(1, Math.max(0, v)))
    const [wx, wy] = toWall(r, x, y, layout.rotated)
    return [clamp(wx), clamp(wy)]
  }

  const push = (s: Stroke, pt: Point) => {
    s.points.push(pt)
    pending.current.push(pt)
    // Draw just the new piece: redrawing every stroke on each move is what made long drawings lag.
    const p = pen()
    if (p) drawPiece(p.g, p.c, s, s.points.length - 1)
    flushSoon()
  }

  const add = (e: PointerEvent<HTMLDivElement>) => {
    const a = active.current
    if (!a) return
    const s = a.stroke
    if (SHAPES.has(s.tool)) {
      // A shape is its two corners; the wall replaces its copy with each update.
      const end = wallPoint(e.clientX, e.clientY)
      s.points = [s.points[0] ?? end, end]
      pending.current = s.points
      renderSoon()
      flushSoon()
      return
    }
    const events = e.nativeEvent.getCoalescedEvents?.() ?? []
    for (const ev of events.length ? events : [e.nativeEvent]) {
      const raw = wallPoint(ev.clientX, ev.clientY)
      a.raw = raw
      const last = s.points.at(-1)
      if (!last) {
        push(s, raw)
        continue
      }
      const eased: Point = [round(last[0] + (raw[0] - last[0]) * SMOOTHING), round(last[1] + (raw[1] - last[1]) * SMOOTHING)]
      if (distance(eased, last) >= MIN_STEP) push(s, eased)
    }
  }

  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (active.current) return // one finger draws; extra fingers are ignored
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // still draws without capture
    }
    box.current = stage.current?.getBoundingClientRect() ?? null
    const width = tool === 'erase' ? size * ERASE_SCALE : size
    const stroke: Stroke = { id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, color, size: width, tool, points: [] }
    strokes.current.push(stroke)
    active.current = { pointer: e.pointerId, stroke }
    onStrokeStart?.()
    add(e)
  }

  const move = (e: PointerEvent<HTMLDivElement>) => {
    if (active.current?.pointer === e.pointerId) add(e)
  }

  const up = (e: PointerEvent<HTMLDivElement>) => {
    const a = active.current
    if (a?.pointer !== e.pointerId) return
    const s = a.stroke
    // Smoothing trails the finger slightly; finish exactly where it lifted.
    const last = s.points.at(-1)
    if (!SHAPES.has(s.tool) && a.raw && last && distance(a.raw, last) >= MIN_STEP / 2) push(s, a.raw)
    // A tap with a shape tool draws nothing; drop it on both sides.
    const empty = SHAPES.has(s.tool) && (s.points.length < 2 || distance(s.points[0], s.points[1]) < MIN_STEP * 4)
    window.clearTimeout(timer.current)
    flush()
    active.current = null
    if (empty) {
      strokes.current.pop()
      send({ type: 'ink-undo' })
      render()
    } else if (SHAPES.has(s.tool)) {
      render()
    } else {
      const p = pen()
      if (p) drawTail(p.g, p.c, s)
    }
    onStrokeEnd?.()
  }

  return (
    <div className="ink-wrap" ref={wrap} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
      <div className="ink-stage" ref={stage} style={wallStageStyle(layout)}>
        {backdrop && <div className="ink-backdrop">{backdrop}</div>}
        <canvas ref={canvas} className="ink-canvas" />
      </div>
    </div>
  )
})
