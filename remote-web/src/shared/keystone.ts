/**
 * The projector's live video includes its keystone correction, so the picture is a tilted quad
 * inside a black frame. The black outside the quad is never lit, so lighting accumulated over a
 * few frames outlines it; we fit its four edges and undo the warp with a CSS perspective transform.
 */

export type Point = { x: number; y: number }
/** Corners as fractions of the video frame: top-left, top-right, bottom-right, bottom-left. */
export type Quad = [Point, Point, Point, Point]

const GRID_W = 192
const GRID_H = 108
const LIT = 36
const STORE = 'remote.keystone'

function isQuad(x: unknown): x is Quad {
  return Array.isArray(x) && x.length === 4 &&
    x.every((p) => typeof p === 'object' && p !== null && typeof p.x === 'number' && typeof p.y === 'number')
}

export function savedQuad(): Quad | null {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(STORE) ?? 'null')
    return isQuad(parsed) ? parsed : null
  } catch {
    return null
  }
}

/** Ignores sub-pixel jitter so the picture doesn't shimmer as the fit refines. */
export function sameQuad(a: Quad | null, b: Quad) {
  return !!a && a.every((p, i) => Math.abs(p.x - b[i].x) < 0.002 && Math.abs(p.y - b[i].y) < 0.002)
}

function saveQuad(quad: Quad) {
  try {
    localStorage.setItem(STORE, JSON.stringify(quad))
  } catch {
    // Private mode: it's re-learned next time.
  }
}

/** Accumulates which cells of the frame have ever been lit, and reads the quad off that mask. */
export class QuadLearner {
  private mask = new Uint8Array(GRID_W * GRID_H)
  private scratch = document.createElement('canvas')
  private last: Quad | null = null

  constructor() {
    this.scratch.width = GRID_W
    this.scratch.height = GRID_H
  }

  add(source: CanvasImageSource): Quad | null {
    const g = this.scratch.getContext('2d', { willReadFrequently: true })
    if (!g) return null
    g.drawImage(source, 0, 0, GRID_W, GRID_H)
    const px = g.getImageData(0, 0, GRID_W, GRID_H).data
    for (let i = 0; i < this.mask.length; i++) {
      if (px[i * 4] + px[i * 4 + 1] + px[i * 4 + 2] > LIT) this.mask[i] = 1
    }
    const quad = this.quad()
    if (!quad || sameQuad(this.last, quad)) return this.last
    this.last = quad
    saveQuad(quad)
    return quad
  }

  private quad(): Quad | null {
    const left: Point[] = []
    const right: Point[] = []
    const top: Point[] = []
    const bottom: Point[] = []
    for (let y = 0; y < GRID_H; y++) {
      let l = -1
      let r = -1
      for (let x = 0; x < GRID_W; x++) if (this.mask[y * GRID_W + x]) { if (l < 0) l = x; r = x }
      if (l >= 0) { left.push({ x: l, y }); right.push({ x: r + 1, y }) }
    }
    for (let x = 0; x < GRID_W; x++) {
      let t = -1
      let b = -1
      for (let y = 0; y < GRID_H; y++) if (this.mask[y * GRID_W + x]) { if (t < 0) t = y; b = y }
      if (t >= 0) { top.push({ x, y: t }); bottom.push({ y: b + 1, x }) }
    }
    if (left.length < GRID_H / 3 || top.length < GRID_W / 3) return null

    // Vertical edges as x = a·y + b, horizontal ones as y = a·x + b; keep the outermost fit so
    // dark content near an edge (not yet lit) doesn't pull the edge inward.
    const l = outerFit(left.map((p) => ({ t: p.y, v: p.x })), -1)
    const r = outerFit(right.map((p) => ({ t: p.y, v: p.x })), 1)
    const t = outerFit(top.map((p) => ({ t: p.x, v: p.y })), -1)
    const b = outerFit(bottom.map((p) => ({ t: p.x, v: p.y })), 1)
    const corner = (v: Line, h: Line): Point => {
      // x = va·y + vb and y = ha·x + hb
      const y = (h.a * v.b + h.b) / (1 - h.a * v.a)
      return { x: (v.a * y + v.b) / GRID_W, y: y / GRID_H }
    }
    return [corner(l, t), corner(r, t), corner(r, b), corner(l, b)]
  }
}

type Line = { a: number; b: number }

function fit(points: { t: number; v: number }[]): Line {
  const n = points.length
  let st = 0, sv = 0, stt = 0, stv = 0
  for (const p of points) { st += p.t; sv += p.v; stt += p.t * p.t; stv += p.t * p.v }
  const d = n * stt - st * st
  const a = d === 0 ? 0 : (n * stv - st * sv) / d
  return { a, b: (sv - a * st) / n }
}

/** Least squares, then refit on the points on the outer side (`side` −1 = smaller values). */
function outerFit(points: { t: number; v: number }[], side: -1 | 1): Line {
  let line = fit(points)
  for (let i = 0; i < 3; i++) {
    const kept = points.filter((p) => (p.v - (line.a * p.t + line.b)) * side > -1.5)
    if (kept.length < 8) break
    line = fit(kept)
  }
  return line
}

/** CSS matrix3d that maps the quad (in an element of w×h px) onto the element's full rectangle. */
export function unwarpTransform(quad: Quad, w: number, h: number): string {
  const src = quad.map((p) => ({ x: p.x * w, y: p.y * h }))
  const dst = [{ x: 0, y: 0 }, { x: w, y: 0 }, { x: w, y: h }, { x: 0, y: h }]
  const m = homography(src, dst)
  if (!m) return 'none'
  const [a, b, c, d, e, f, g, k] = m
  return `matrix3d(${a},${d},0,${g},${b},${e},0,${k},0,0,1,0,${c},${f},0,1)`
}

/** Solves for the projective map src→dst: [a b c; d e f; g k 1]. */
function homography(src: Point[], dst: Point[]): number[] | null {
  const rows: number[][] = []
  for (let i = 0; i < 4; i++) {
    const { x, y } = src[i]
    const { x: u, y: v } = dst[i]
    rows.push([x, y, 1, 0, 0, 0, -u * x, -u * y, u])
    rows.push([0, 0, 0, x, y, 1, -v * x, -v * y, v])
  }
  for (let col = 0; col < 8; col++) {
    let pivot = col
    for (let r = col + 1; r < 8; r++) if (Math.abs(rows[r][col]) > Math.abs(rows[pivot][col])) pivot = r
    if (Math.abs(rows[pivot][col]) < 1e-9) return null
    ;[rows[col], rows[pivot]] = [rows[pivot], rows[col]]
    for (let r = 0; r < 8; r++) {
      if (r === col) continue
      const k = rows[r][col] / rows[col][col]
      for (let c = col; c < 9; c++) rows[r][c] -= k * rows[col][c]
    }
  }
  return rows.map((r, i) => r[8] / r[i])
}
