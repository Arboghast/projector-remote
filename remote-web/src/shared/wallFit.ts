import { useEffect, useState, type RefObject } from 'react'

const WALL_ASPECT = 16 / 9

export type WallFit = { long: number; short: number; rotated: boolean }

/**
 * The largest 16:9 wall that fits the box. In a portrait box the wall is turned on its side (its
 * top along the phone's right edge), which makes it far larger on a phone held upright.
 */
export function useWallFit(box: RefObject<HTMLElement | null>): WallFit {
  const [fit, setFit] = useState<WallFit>({ long: 0, short: 0, rotated: false })

  useEffect(() => {
    const el = box.current
    if (!el) return
    const measure = () => {
      const w = el.clientWidth
      const h = el.clientHeight
      const rotated = h > w
      const along = rotated ? h : w
      const across = rotated ? w : h
      const long = Math.min(along, across * WALL_ASPECT)
      setFit({ long, short: long / WALL_ASPECT, rotated })
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [box])

  return fit
}

/** Style for the element holding the wall: centred in its box, turned when the fit says so. */
export function wallStageStyle(fit: WallFit) {
  return {
    width: fit.long,
    height: fit.short,
    transform: `translate(-50%, -50%)${fit.rotated ? ' rotate(90deg)' : ''}`,
  }
}

/** A point on screen → fractions of the wall, undoing the sideways turn. */
export function toWall(box: DOMRect, x: number, y: number, rotated: boolean): [number, number] {
  return rotated
    ? [(y - box.top) / box.height, (box.right - x) / box.width]
    : [(x - box.left) / box.width, (y - box.top) / box.height]
}

/** Finger movement on screen → movement along the wall, undoing the sideways turn. */
export function alongWall(dx: number, dy: number, rotated: boolean): [number, number] {
  return rotated ? [dy, -dx] : [dx, dy]
}
