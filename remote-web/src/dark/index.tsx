import { useState } from 'react'
import type { LayoutProps } from '../app'
import { CursorPad } from '../pads/pads'
import { CornerBar } from '../shared/chrome'
import { Icon } from '../shared/icons'
import { WallTyping } from '../shared/TextEntry'
import { useDrawing } from './Drawing'
import { DrawingScreen } from './DrawingScreen'
import { appInfo } from '../shared/apps'
import './dark.css'

const LEGEND_KEY = 'remote.dark.legendSeen.v2'

const LEGEND = [
  { glyph: '•', kind: 'move', gesture: 'Slide', action: 'Moves the cursor on the wall' },
  { glyph: '•', kind: 'ok', gesture: 'Tap', action: 'Select / click' },
  { glyph: '‹‹', kind: 'back', gesture: 'Two-finger tap', action: 'Back' },
  { glyph: '•', kind: 'scroll', gesture: 'Two-finger slide', action: 'Scroll' },
  { glyph: '•', kind: 'menu', gesture: 'Long press', action: 'Menu' },
]

function legendSeen() {
  try {
    return localStorage.getItem(LEGEND_KEY) === '1'
  } catch {
    return false
  }
}

/** No buttons: a black screen you work by feel, so nothing pulls your eyes off the wall. */
export function Dark({ relay, openLayouts }: LayoutProps) {
  const { link, state, send } = relay
  const [showLegend, setShowLegend] = useState(() => !legendSeen())
  const draw = useDrawing(send)

  // The legend teaches once, then gets out of the way for good.
  const hideLegend = () => {
    if (!showLegend) return
    setShowLegend(false)
    try {
      localStorage.setItem(LEGEND_KEY, '1')
    } catch {
      // Shown again next time; harmless.
    }
  }

  if (draw.drawing) return <DrawingScreen draw={draw} send={send} linked={link === 'ready'} appColor={appInfo(state.app).color} />

  return (
    <main className="layout dark">
      <CornerBar link={link} openLayouts={openLayouts} />

      <div className="dark-surface" onPointerDown={hideLegend}>
          <CursorPad send={send} className="dark-pad">
            {showLegend && (
              <ul className="dark-legend" aria-label="Gestures">
                {LEGEND.map((row) => (
                  <li key={row.gesture}>
                    <span className="dark-glyph" data-kind={row.kind}>{row.glyph}</span>
                    <span>
                      <span className="dark-gesture">{row.gesture}</span>
                      <span className="muted">{row.action}</span>
                    </span>
                  </li>
                ))}
                <li className="dark-legend-foot muted">This guide hides after your first touch.</li>
              </ul>
            )}
          </CursorPad>
        </div>

      <WallTyping state={state} send={send} />

      <footer className="dark-tray">
        <button className="icon-button" aria-label="Show gesture guide" onClick={() => setShowLegend(true)}>?</button>
        <button className="icon-button" aria-label="Draw on the wall" onClick={() => draw.start()}><Icon name="pen" /></button>
      </footer>
    </main>
  )
}
