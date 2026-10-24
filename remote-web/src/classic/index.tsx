import type { LayoutProps } from '../app'
import { LayoutButton, LinkDot } from '../shared/chrome'
import { DPad } from '../shared/DPad'
import { KeyRow } from '../shared/KeyRow'
import { WallTyping } from '../shared/TextEntry'
import { Trackpad } from '../shared/Trackpad'
import './classic.css'
import { PlaybackRow } from '../shared/NowPlaying'

/** The original remote: trackpad, D-pad and a button row. */
export function Classic({ relay, openLayouts }: LayoutProps) {
  const { link, state, send } = relay

  return (
    <main className="classic">
      <header className="classic-status">
        <LinkDot link={link} label />
        <LayoutButton onClick={openLayouts} />
      </header>

      <div className="classic-deck">
        <Trackpad send={send} />
        <div className="classic-controls">
          <DPad send={send} />
          <PlaybackRow state={state} send={send} />
          <KeyRow state={state} send={send} />
        </div>
      </div>

      <WallTyping state={state} send={send} />
    </main>
  )
}
