import { useRef, useState, type PointerEvent } from 'react'
import type { LayoutProps } from '../app'
import { useDrawing } from '../dark/Drawing'
import { DrawingScreen } from '../dark/DrawingScreen'
import { appInfo } from '../shared/apps'
import { appStyle, CornerBar } from '../shared/chrome'
import { LiveScreen } from '../shared/LiveScreen'
import { Composer } from '../shared/Composer'
import { RecentList, ShelfList } from '../shared/Shelf'
import { WallTyping } from '../shared/TextEntry'
import { ControlSheet } from './ControlSheet'
import { EdgePanel } from './EdgePanel'
import { Trails } from './Trails'
import { useCanvas, type Edge, type Feedback } from './useCanvas'
import './theatre.css'

type Panel = 'sheet' | 'glance' | 'peek' | 'shelf'
type Drag = { edge: Edge; distance: number }

const PANEL_FOR: Record<Edge, Panel> = { bottom: 'sheet', top: 'peek', right: 'shelf' }
const FLING = 0.5 // px/ms
const OPEN_PX = 56
const FULL_SHEET_PX = 190

/**
 * A black canvas that is all trackpad. Each edge holds one thing, marked by a thin bar, and opens
 * by pulling it out: bottom = the remote (now playing first), top = a live picture of the wall,
 * right = the Shelf. Panels stay until you put them away.
 */
export function Theatre({ relay, openLayouts }: LayoutProps) {
  const { link, state, shelf, recent, send } = relay
  const [open, setOpen] = useState<Panel | null>(null)
  const [drag, setDrag] = useState<Drag | null>(null)
  const trails = useRef<Feedback>(null)
  const draw = useDrawing(send)
  const linked = link === 'ready'
  const app = appInfo(state.nowPlaying?.app ?? state.app)

  const feedback: Feedback = {
    tap: (x, y) => trails.current?.tap(x, y),
    trail: (x, y) => trails.current?.trail(x, y),
  }

  const canvas = useCanvas(send, {
    onPull: (edge, distance) => setDrag({ edge, distance }),
    onPullEnd: (edge, distance, speed) => {
      setDrag(null)
      const fling = speed > FLING
      if (distance < OPEN_PX && !fling) return setOpen(null)
      if (edge === 'bottom') setOpen(distance > FULL_SHEET_PX || (fling && distance > OPEN_PX) ? 'sheet' : 'glance')
      else setOpen(PANEL_FOR[edge])
    },
  }, feedback)

  // Touching the canvas while a panel is out only puts it away: that finger neither clicks nor moves the wall.
  const dismissing = useRef<number | null>(null)
  const onCanvasDown = (e: PointerEvent<HTMLElement>) => {
    if (open) {
      dismissing.current = e.pointerId
      return setOpen(null)
    }
    canvas.onPointerDown(e)
  }
  const unlessDismissing = (handler: (e: PointerEvent<HTMLElement>) => void) => (e: PointerEvent<HTMLElement>) => {
    if (e.pointerId !== dismissing.current) handler(e)
  }
  const onCanvasUp = (e: PointerEvent<HTMLElement>) => {
    if (e.pointerId === dismissing.current) dismissing.current = null
    else canvas.onPointerUp(e)
  }

  const shown = (panel: Panel) => drag && PANEL_FOR[drag.edge] === (panel === 'glance' ? 'sheet' : panel) ? drag.distance : null
  const sheetLevel = drag?.edge === 'bottom' ? 'drag' : open === 'sheet' ? 'full' : open === 'glance' ? 'glance' : 'closed'
  const close = () => setOpen(null)

  if (draw.drawing) return <DrawingScreen draw={draw} send={send} linked={linked} appColor={app.color} />

  return (
    <main className="layout theatre" style={appStyle(app.color)}>
      <div
        className="theatre-canvas"
        onPointerDown={onCanvasDown}
        onPointerMove={unlessDismissing(canvas.onPointerMove)}
        onPointerUp={onCanvasUp}
        onPointerCancel={() => {
          dismissing.current = null
          canvas.onPointerCancel()
        }}
      />
      <Trails ref={trails} linked={linked} />

      <div className="theatre-corner">
        <CornerBar link={link} openLayouts={openLayouts} />
      </div>

      <span className="edge-bar edge-bar-top" aria-hidden="true" />
      <span className="edge-bar edge-bar-bottom" data-live={state.nowPlaying?.playing || undefined} aria-hidden="true" />
      <span className="edge-bar edge-bar-right" data-waiting={shelf.length > 0 || undefined} aria-hidden="true" />

      <EdgePanel edge="top" shown={shown('peek')} open={open === 'peek'} onClose={close}>
        {(open === 'peek' || drag?.edge === 'top') && <LiveScreen send={send} active={linked} />}
      </EdgePanel>

      <EdgePanel edge="right" shown={shown('shelf')} open={open === 'shelf'} onClose={close}>
        <h2 className="drawer-title">Shelf</h2>
        <Composer send={send} newestUrl={shelf[0]?.url} />
        <ShelfList items={shelf} send={send} compact empty="Nothing waiting. Share a link to Remote from any app to save it here." />
        <RecentList items={recent} send={send} />
      </EdgePanel>

      <EdgePanel edge="bottom" shown={shown('sheet')} open={sheetLevel === 'full'} glance={sheetLevel === 'glance'} onClose={close}>
        <ControlSheet state={state} send={send} onInk={() => { close(); draw.start() }} />
      </EdgePanel>

      <WallTyping state={state} send={send} />
    </main>
  )
}
