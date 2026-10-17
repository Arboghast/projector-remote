import type { RemoteCommand } from '../relay'

const SCROLL_GAIN = 2.2

/**
 * Finger movement on the phone → a scroll on the wall, used by every pad so they all agree.
 * Traditional direction (by request): moving the finger up scrolls the page up, like a scroll
 * wheel or laptop trackpad, rather than dragging the content along like a phone screen.
 */
export function scrollCommand(dx: number, dy: number): RemoteCommand {
  return { type: 'scroll', dx: Math.round(dx * SCROLL_GAIN), dy: Math.round(dy * SCROLL_GAIN) }
}
