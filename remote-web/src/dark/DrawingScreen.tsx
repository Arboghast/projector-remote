import { useState, type ReactNode } from 'react'
import type { InkTool, RemoteCommand } from '../relay'
import { appStyle } from '../shared/chrome'
import { Icon, type IconName } from '../shared/icons'
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

const TOOL_ICON: Record<InkTool, IconName> = { pen: 'pen', arrow: 'arrow', rect: 'rect', ellipse: 'ellipse', erase: 'eraser' }
const TOOL_NAME: Record<InkTool, string> = { pen: 'Pen', arrow: 'Arrow', rect: 'Rectangle', ellipse: 'Ellipse', erase: 'Eraser' }
const INK_NAME: Record<string, string> = { '#f4f4f4': 'White', '#f5a524': 'Amber', '#ff5c5c': 'Red', '#5b8cff': 'Blue', '#3ecf8e': 'Green' }

/**
 * Drawing on the wall, the same in every layout: the wall fills the screen (sideways on a portrait
 * phone) with its live picture faint behind the ink, so strokes line up with what you're marking.
 * The tool button shows the current tool; tapping it reveals the others.
 */
export function DrawingScreen({ draw, send, linked, appColor }: Props) {
  const [stroking, setStroking] = useState(false)
  const [picking, setPicking] = useState(false)
  const choose = (t: InkTool) => {
    draw.setTool(t)
    setPicking(false)
  }

  return (
    <main className="layout drawing-screen" style={{ ...appStyle(appColor), ['--ink' as string]: draw.ink }}>
      <InkSurface
        send={send}
        color={draw.ink}
        tool={draw.tool}
        size={draw.size}
        ref={draw.surface}
        backdrop={<LiveScreen send={send} active={linked} />}
        onStrokeStart={() => {
          setStroking(true)
          setPicking(false)
        }}
        onStrokeEnd={() => setStroking(false)}
      />
      <div className="ink-controls" data-faded={stroking || undefined}>
        {picking && (
          <nav className="ink-tools" aria-label="Tools">
            {draw.tools.map((t) => (
              <button key={t} className="icon-button" aria-label={TOOL_NAME[t]} aria-pressed={draw.tool === t} onClick={() => choose(t)}>
                <Icon name={TOOL_ICON[t]} />
              </button>
            ))}
          </nav>
        )}
        <nav className="ink-bar" aria-label="Drawing">
          <button className="icon-button ink-tool" aria-label={`Tool: ${TOOL_NAME[draw.tool]}`} aria-expanded={picking} onClick={() => setPicking((p) => !p)}>
            <Icon name={TOOL_ICON[draw.tool]} />
          </button>
          <span className="ink-dots" data-idle={draw.tool === 'erase' || undefined}>
            {draw.inks.map((c) => (
              <button key={c} className="ink-dot" aria-label={`${INK_NAME[c] ?? c} ink`} aria-pressed={draw.ink === c} style={{ background: c }} onClick={() => draw.setInk(c)} />
            ))}
          </span>
          <HoldButton label="Undo (hold to clear)" onTap={draw.undo} onHold={draw.clear}><Icon name="undo" /></HoldButton>
          <button className="ink-done" onClick={draw.stop}>Done</button>
        </nav>
      </div>
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
