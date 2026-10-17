import { useState, type ReactNode } from 'react'
import type { RemoteCommand } from '../relay'
import { appStyle } from '../shared/chrome'
import { Icon } from '../shared/icons'
import { useLongPress } from '../shared/useLongPress'
import { LiveScreen } from '../shared/LiveScreen'
import type { useDrawing } from './Drawing'
import { InkSurface } from './InkSurface'
import './drawing.css'

type Props = {
  draw: ReturnType<typeof useDrawing>
  send: (command: RemoteCommand) => void
  linked: boolean
  appColor: string
}

/**
 * Drawing on the wall, the same in every layout: the wall fills the screen (sideways on a portrait
 * phone) with its live picture faint behind the ink, so strokes line up with what you're marking.
 */
export function DrawingScreen({ draw, send, linked, appColor }: Props) {
  const [stroking, setStroking] = useState(false)
  return (
    <main className="layout drawing-screen" style={{ ...appStyle(appColor), ['--ink' as string]: draw.ink }}>
      <InkSurface
        send={send}
        color={draw.ink}
        size={draw.size}
        ref={draw.surface}
        backdrop={<LiveScreen send={send} active={linked} />}
        onStrokeStart={() => setStroking(true)}
        onStrokeEnd={() => setStroking(false)}
      />
      <nav className="ink-bar" data-faded={stroking || undefined} aria-label="Drawing">
        {draw.inks.map((c) => (
          <button key={c} className="ink-dot" aria-label={`Ink ${c}`} aria-pressed={draw.ink === c} style={{ background: c }} onClick={() => draw.setInk(c)} />
        ))}
        <HoldButton label="Undo (hold to clear)" onTap={draw.undo} onHold={draw.clear}><Icon name="back" /></HoldButton>
        <button className="ink-done" onClick={draw.stop}>Done</button>
      </nav>
    </main>
  )
}

function HoldButton({ label, onTap, onHold, children }: { label: string; onTap: () => void; onHold: () => void; children: ReactNode }) {
  return (
    <button className="icon-button" aria-label={label} {...useLongPress(onTap, onHold)}>
      {children}
    </button>
  )
}
