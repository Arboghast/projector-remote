import { useRef } from 'react'
import type { LayoutProps } from '../app'
import { appStyle, TopBar } from '../shared/chrome'
import { KeyRow } from '../shared/KeyRow'
import { focusApp, PlaybackRow } from '../shared/NowPlaying'
import { LiveScreen } from '../shared/LiveScreen'
import { WallTyping } from '../shared/TextEntry'
import { useWallFit, wallStageStyle } from '../shared/wallFit'
import './lens.css'

/**
 * The wall itself is the trackpad: its live picture fills the screen, a tap presses that spot and
 * a drag scrolls, so you watch the result happen under your finger. On a phone held upright the
 * picture is turned on its side, which makes it far larger.
 */
export function Lens({ relay, openLayouts }: LayoutProps) {
  const { link, state, send } = relay
  const app = focusApp(state)
  const surface = useRef<HTMLDivElement>(null)
  const fit = useWallFit(surface)

  return (
    <main className="layout lens" style={appStyle(app.color)}>
      <TopBar link={link} openLayouts={openLayouts}><span className="app-dot" />{app.name}</TopBar>

      <div className="lens-surface" ref={surface}>
        {fit.long > 0 && (
          <div className="lens-stage" style={wallStageStyle(fit)}>
            <LiveScreen send={send} active={link === 'ready'} surface={surface} rotated={fit.rotated} />
          </div>
        )}
      </div>

      <WallTyping state={state} send={send} />

      <PlaybackRow state={state} send={send} />
      <KeyRow send={send} />
    </main>
  )
}
