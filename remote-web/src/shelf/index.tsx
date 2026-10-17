import type { LayoutProps } from '../app'
import { appStyle, TopBar } from '../shared/chrome'
import { Composer } from '../shared/Composer'
import { DPad } from '../shared/DPad'
import { KeyRow } from '../shared/KeyRow'
import { focusApp, NowPlayingPill, PlaybackRow } from '../shared/NowPlaying'
import { ShelfList } from '../shared/Shelf'
import './shelf.css'

/** Collect now, project later: the queue is the hero; on a wide screen the remote sits beside it. */
export function Shelf({ relay, openLayouts }: LayoutProps) {
  const { link, state, shelf, send } = relay
  const app = focusApp(state)

  return (
    <main className="layout shelf" style={appStyle(app.color)}>
      <TopBar link={link} openLayouts={openLayouts} />

      <div className="shelf-body">
        <section className="shelf-main">
          <NowPlayingPill state={state} send={send} />
          <PlaybackRow state={state} send={send} />
          <div className="shelf-heading">
            <h1>Shelf</h1>
            <span className="muted">{shelf.length ? `${shelf.length} waiting` : ''}</span>
          </div>
          <ShelfList items={shelf} send={send} />
          {shelf.length > 0 && <p className="muted shelf-tip">swipe right to project · left to dismiss</p>}
          <Composer send={send} newestUrl={shelf[0]?.url} suggestionsAbove />
        </section>
        <aside className="shelf-side">
          <div className="shelf-remote">
            <DPad send={send} />
            <KeyRow send={send} />
          </div>
        </aside>
      </div>
    </main>
  )
}
