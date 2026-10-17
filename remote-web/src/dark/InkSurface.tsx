import { forwardRef, useEffect, useImperativeHandle, useRef, useState, type PointerEvent, type ReactNode } from 'react'
import type { RemoteCommand } from '../relay'

type Props = {
  send: (command: RemoteCommand) => void
  color: string
  /** Line width as a fraction of the wall's height. */
  size: number
  /** Drawn under the ink, lined up with the wall (e.g. its live picture). */
  backdrop?: ReactNode
  onStrokeStart?: () => void
  onStrokeEnd?: () => void
}

/** What the drawing controls can do to the surface. */
export type InkHandle = { clear: () => void; undo: () => void }

type Point = [number, number]
type Stroke = { id: string; color: string; size: number; points: Point[] }

const WALL_ASPECT = 16 / 9
const FLUSH_MS = 16

/**
 * The wall as large as the screen allows. On a portrait phone the wall is turned on its side
 * (its top along the phone's right edge), so it fills the screen; turn the phone to draw upright.
 * Strokes are kept, so undo and clear match the wall exactly; new ink is drawn a piece at a time.
 */
export const InkSurface = forwardRef<InkHandle, Props>(function InkSurface({ send, color, size, backdrop, onStrokeStart, onStrokeEnd }, ref) {
  const wrap = useRef<HTMLDivElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const strokes = useRef<Stroke[]>([])
  const active = useRef<{ pointer: number; stroke: Stroke } | null>(null)
  const pending = useRef<Point[]>([])
  const timer = useRef<number | undefined>(undefined)
  // The stage's box, measured once per stroke rather than for every sample (each read forces layout).
  const box = useRef<DOMRect | null>(null)
  const [layout, setLayout] = useState({ long: 0, short: 0, rotated: false })

  useEffect(() => {
    const fit = () => {
      const box = wrap.current?.getBoundingClientRect()
      if (!box) return
      const rotated = box.height > box.width
      const along = rotated ? box.height : box.width
      const across = rotated ? box.width : box.height
      const long = Math.min(along, across * WALL_ASPECT)
      setLayout({ long, short: long / WALL_ASPECT, rotated })
    }
    fit()
    const ro = new ResizeObserver(fit)
    if (wrap.current) ro.observe(wrap.current)
    return () => ro.disconnect()
  }, [])

  const pen = () => {
    const c = canvas.current
    const g = c?.getContext('2d')
    if (!c || !g) return null
    g.lineCap = 'round'
    g.lineJoin = 'round'
    return { c, g }
  }

  // Through the midpoints with the samples as control points: a smooth curve, no corners. Piece i
  // runs from the midpoint before sample i to the midpoint after it, so pieces join seamlessly and
  // a stroke can be drawn a piece at a time while the finger moves.
  const drawPiece = (g: CanvasRenderingContext2D, c: HTMLCanvasElement, s: Stroke, i: number) => {
    const at = (k: number): Point => [s.points[k][0] * c.width, s.points[k][1] * c.height]
    const mid = (a: Point, b: Point): Point => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
    g.strokeStyle = s.color
    g.fillStyle = s.color
    g.lineWidth = Math.max(2, s.size * c.height)
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
    const a: Point = [s.points[n - 2][0] * c.width, s.points[n - 2][1] * c.height]
    const b: Point = [s.points[n - 1][0] * c.width, s.points[n - 1][1] * c.height]
    g.beginPath()
    g.moveTo((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    g.lineTo(b[0], b[1])
    g.stroke()
  }

  /** Whole-canvas redraw, only for undo, clear and resizing. */
  const render = () => {
    const p = pen()
    if (!p) return
    p.g.clearRect(0, 0, p.c.width, p.c.height)
    for (const s of strokes.current) {
      for (let i = 0; i < s.points.length; i++) drawPiece(p.g, p.c, s, i)
      if (s !== active.current?.stroke) drawTail(p.g, p.c, s)
    }
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
  }, [])

  const flush = () => {
    timer.current = undefined
    const a = active.current
    if (!a || pending.current.length === 0) return
    send({ type: 'ink', id: a.stroke.id, points: pending.current, color: a.stroke.color, size: a.stroke.size })
    pending.current = []
  }

  /** Screen position → fraction of the wall, undoing the sideways turn in portrait. */
  const toWall = (x: number, y: number): Point => {
    const r = box.current
    if (!r) return [0, 0]
    const clamp = (v: number) => Math.round(Math.min(1, Math.max(0, v)) * 10000) / 10000
    return layout.rotated
      ? [clamp((y - r.top) / r.height), clamp((r.right - x) / r.width)]
      : [clamp((x - r.left) / r.width), clamp((y - r.top) / r.height)]
  }

  const add = (e: PointerEvent<HTMLDivElement>) => {
    const a = active.current
    if (!a) return
    const events = e.nativeEvent.getCoalescedEvents?.() ?? []
    const p = pen()
    for (const ev of events.length ? events : [e.nativeEvent]) {
      const pt = toWall(ev.clientX, ev.clientY)
      a.stroke.points.push(pt)
      pending.current.push(pt)
      // Draw just the new piece: redrawing every stroke on each move is what made long drawings lag.
      if (p) drawPiece(p.g, p.c, a.stroke, a.stroke.points.length - 1)
    }
    if (timer.current === undefined) timer.current = window.setTimeout(flush, FLUSH_MS)
  }

  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (active.current) return // one finger draws; extra fingers are ignored
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // still draws without capture
    }
    box.current = stage.current?.getBoundingClientRect() ?? null
    const stroke: Stroke = { id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, color, size, points: [] }
    strokes.current.push(stroke)
    active.current = { pointer: e.pointerId, stroke }
    onStrokeStart?.()
    add(e)
  }

  const move = (e: PointerEvent<HTMLDivElement>) => {
    if (active.current?.pointer === e.pointerId) add(e)
  }

  const up = (e: PointerEvent<HTMLDivElement>) => {
    if (active.current?.pointer !== e.pointerId) return
    window.clearTimeout(timer.current)
    flush()
    const p = pen()
    if (p && active.current) drawTail(p.g, p.c, active.current.stroke)
    active.current = null
    onStrokeEnd?.()
  }

  const stageStyle = {
    width: layout.long,
    height: layout.short,
    transform: `translate(-50%, -50%)${layout.rotated ? ' rotate(90deg)' : ''}`,
  }

  return (
    <div className="ink-wrap" ref={wrap} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
      <div className="ink-stage" ref={stage} style={stageStyle}>
        {backdrop && <div className="ink-backdrop">{backdrop}</div>}
        <canvas ref={canvas} className="ink-canvas" />
      </div>
    </div>
  )
})
