import { useRef, useState, type PointerEvent } from 'react'
import type { RemoteCommand, ShelfItem } from '../relay'
import { appInfo, linkLabel } from './apps'
import { appStyle } from './chrome'
import { Icon } from './icons'

type Props = {
  items: ShelfItem[]
  send: (command: RemoteCommand) => void
  compact?: boolean
  empty?: string
}

const COMMIT_PX = 90

/** Links waiting for the wall. Swipe right to project, left to dismiss; the button is the same as a right swipe. */
export function ShelfList({ items, send, compact, empty = 'Nothing waiting. Share a link here, or paste one below.' }: Props) {
  if (items.length === 0) {
    return <p className="shelf-empty">{empty}</p>
  }
  return (
    <ul className={compact ? 'shelf-list shelf-compact' : 'shelf-list'}>
      {items.map((item) => <ShelfCard key={item.id} item={item} send={send} />)}
    </ul>
  )
}

function ShelfCard({ item, send }: { item: ShelfItem; send: Props['send'] }) {
  const app = appInfo(item.app)
  const label = linkLabel(item.url)
  const [offset, setOffset] = useState(0)
  const drag = useRef<{ id: number; x: number } | null>(null)

  const project = () => send({ type: 'shelf-project', id: item.id })
  const dismiss = () => send({ type: 'shelf-remove', id: item.id })

  const down = (e: PointerEvent<HTMLDivElement>) => {
    drag.current = { id: e.pointerId, x: e.clientX }
  }
  const move = (e: PointerEvent<HTMLDivElement>) => {
    if (drag.current?.id !== e.pointerId) return
    const dx = e.clientX - drag.current.x
    if (Math.abs(dx) > 6) e.currentTarget.setPointerCapture(e.pointerId)
    setOffset(dx)
  }
  const up = () => {
    drag.current = null
    if (offset > COMMIT_PX) project()
    else if (offset < -COMMIT_PX) dismiss()
    setOffset(0)
  }

  const reveal = offset > 0 ? 'project' : offset < 0 ? 'dismiss' : null

  return (
    <li className="shelf-card" data-reveal={reveal} style={appStyle(app.color)}>
      <span className="shelf-under" aria-hidden="true">{reveal === 'project' ? 'Project' : reveal === 'dismiss' ? 'Dismiss' : ''}</span>
      <div
        className="shelf-face"
        style={{ transform: `translateX(${offset}px)`, transition: offset === 0 ? 'transform .2s' : 'none' }}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerCancel={up}
      >
        <span className="app-tile"><span /></span>
        <span className="shelf-text">
          <span className="shelf-title">{label.path || label.host}</span>
          <span className="muted">{label.host} · {app.name}{item.from ? ` · from ${item.from}` : ''}</span>
        </span>
        <button className="icon-button" aria-label={`Project ${label.host}`} onClick={project}>
          <Icon name="send" />
        </button>
      </div>
    </li>
  )
}
