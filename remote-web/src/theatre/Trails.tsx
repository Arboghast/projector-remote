import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react'
import type { Feedback } from './useCanvas'

type Mark = { x: number; y: number; at: number; kind: 'tap' | 'trail' }

const TAP_MS = 260
const TRAIL_MS = 320

/**
 * Light under the finger, so you can feel the pad working without looking at the phone.
 * Solid white while the remote is linked, a hollow grey ring when touches aren't going anywhere.
 */
export const Trails = forwardRef<Feedback, { linked: boolean }>(function Trails({ linked }, ref) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const marks = useRef<Mark[]>([])
  const raf = useRef<number | null>(null)
  const linkedRef = useRef(linked)
  linkedRef.current = linked

  useEffect(() => {
    const fit = () => {
      const c = canvas.current
      if (!c) return
      c.width = c.clientWidth * devicePixelRatio
      c.height = c.clientHeight * devicePixelRatio
    }
    fit()
    window.addEventListener('resize', fit)
    return () => {
      window.removeEventListener('resize', fit)
      if (raf.current !== null) cancelAnimationFrame(raf.current)
    }
  }, [])

  const paint = () => {
    raf.current = null
    const c = canvas.current
    const g = c?.getContext('2d')
    if (!c || !g) return
    const now = performance.now()
    const box = c.getBoundingClientRect()
    const k = devicePixelRatio
    marks.current = marks.current.filter((m) => now - m.at < (m.kind === 'tap' ? TAP_MS : TRAIL_MS))
    g.clearRect(0, 0, c.width, c.height)
    const ok = linkedRef.current
    for (const m of marks.current) {
      const t = (now - m.at) / (m.kind === 'tap' ? TAP_MS : TRAIL_MS)
      const x = (m.x - box.left) * k
      const y = (m.y - box.top) * k
      g.beginPath()
      if (m.kind === 'tap') {
        g.arc(x, y, (8 + 32 * t) * k, 0, Math.PI * 2)
        g.strokeStyle = ok ? `rgba(255,255,255,${0.55 * (1 - t)})` : `rgba(142,149,161,${0.6 * (1 - t)})`
        g.lineWidth = 2 * k
        g.stroke()
      } else {
        g.arc(x, y, 5 * (1 - t) * k, 0, Math.PI * 2)
        g.fillStyle = ok ? `rgba(255,255,255,${0.28 * (1 - t)})` : `rgba(142,149,161,${0.2 * (1 - t)})`
        g.fill()
      }
    }
    if (marks.current.length) raf.current = requestAnimationFrame(paint)
  }

  const add = (m: Mark) => {
    marks.current.push(m)
    if (marks.current.length > 80) marks.current.shift()
    if (raf.current === null) raf.current = requestAnimationFrame(paint)
  }

  useImperativeHandle(ref, () => ({
    tap: (x, y) => add({ x, y, at: performance.now(), kind: 'tap' }),
    trail: (x, y) => add({ x, y, at: performance.now(), kind: 'trail' }),
  }), [])

  return <canvas ref={canvas} className="trails" aria-hidden="true" />
})
