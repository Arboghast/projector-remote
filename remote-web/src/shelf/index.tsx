import type { LayoutProps } from '../app'
import { appStyle, TopBar } from '../shared/chrome'
import { Composer } from '../shared/Composer'
import { DPad } from '../shared/DPad'
import { KeyRow } from '../shared/KeyRow'
import { focusApp, NowPlayingPill, PlaybackRow } from '../shared/NowPlaying'
import { RecentList, ShelfList } from '../shared/Shelf'
import { WallTyping } from '../shared/TextEntry'
import './shelf.css'

/** Collect now, project later: the queue is the hero; on a wide screen the remote sits beside it. */
export function Shelf({ relay, openLayouts }: LayoutProps) {
  const { link, state, shelf, recent, send } = relay
  const app = focusApp(state)

  return (
    <main className="layout shelf" style={appStyle(app.color)}>
      <TopBar link={link} openLayouts={openLayouts} />

      <div className="shelf-body">
        <section className="shelf-main">
          <NowPlayingPill state={state} />
          <PlaybackRow state={state} send={send} />
          <div className="shelf-heading">
            <h1>Shelf</h1>
            <span className="muted">{shelf.length ? `${shelf.length} waiting` : ''}</span>
          </div>
          <div className="shelf-lists">
            <ShelfList items={shelf} send={send} />
            {shelf.length + recent.length > 0 && <p className="muted shelf-tip">swipe right to project · left to dismiss</p>}
            <RecentList items={recent} send={send} />
          </div>
          <Composer send={send} newestUrl={shelf[0]?.url} suggestionsAbove />
        </section>
        <aside className="shelf-side">
          <div className="shelf-remote">
            <DPad send={send} />
            <KeyRow state={state} send={send} />
          </div>
        </aside>
      </div>

      <WallTyping state={state} send={send} />
    </main>
  )
}
