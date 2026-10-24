import type { MediaAction, ProjectorState, RemoteCommand } from '../relay'
import { appInfo, linkLabel, TV_BRO } from './apps'
import { appStyle } from './chrome'
import { Icon } from './icons'

type Props = {
  state: ProjectorState
  send: (command: RemoteCommand) => void
}

/** The app that matters right now: whatever is playing, else whatever is in front. */
export function focusApp(state: ProjectorState) {
  return appInfo(state.nowPlaying?.app ?? state.app)
}

/** A slim "what's on" bar for layouts where playback is secondary; the playback row under it has the controls. */
export function NowPlayingPill({ state }: { state: ProjectorState }) {
  const app = focusApp(state)
  const now = state.nowPlaying
  const inBrowser = (now?.app ?? state.app) === TV_BRO
  const title = now?.title ?? (state.lastOpened && inBrowser ? linkLabel(state.lastOpened).host : app.name)
  return (
    <div className="now-pill" style={appStyle(app.color)}>
      <span className="app-dot" />
      <span className="now-pill-title">{title}</span>
    </div>
  )
}

/**
 * The full set of playback controls, the same in every layout: previous, back 10 s, play/pause,
 * forward 10 s, next, subtitles and the player's own settings (quality, audio). Shown only while
 * something is playing.
 */
export function PlaybackRow({ state, send }: Props) {
  const now = state.nowPlaying
  if (!now) return null
  const media = (action: MediaAction) => () => send({ type: 'media', action })
  return (
    <div className="playback" role="group" aria-label="Playback" style={appStyle(focusApp(state).color)}>
      <button className="icon-button" aria-label="Previous" onClick={media('previous')}><Icon name="previous" /></button>
      <button className="icon-button" aria-label="Back 10 seconds" onClick={media('rewind')}><Icon name="rewind" /></button>
      <button className="icon-button playback-play" aria-label={now.playing ? 'Pause' : 'Play'} onClick={media('play-pause')}>
        <Icon name={now.playing ? 'pause' : 'play'} />
      </button>
      <button className="icon-button" aria-label="Forward 10 seconds" onClick={media('fast-forward')}><Icon name="forward" /></button>
      <button className="icon-button" aria-label="Next" onClick={media('next')}><Icon name="next" /></button>
      <span className="playback-extra">
        <button className="playback-chip" aria-label="Subtitles" onClick={() => send({ type: 'key', key: 'captions' })}>CC</button>
        <button className="playback-chip" aria-label="Player settings: quality, audio" onClick={() => send({ type: 'player-settings' })}>
          <Icon name="settings" size={16} />
        </button>
      </span>
    </div>
  )
}
