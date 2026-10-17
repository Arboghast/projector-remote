import type { ProjectorState, RemoteCommand, RemoteKey } from '../relay'
import { appInfo } from '../shared/apps'
import { Icon } from '../shared/icons'
import { PlaybackRow } from '../shared/NowPlaying'
import { useHoldRepeat } from '../shared/useHoldRepeat'

type Props = {
  state: ProjectorState
  send: (command: RemoteCommand) => void
  onInk: () => void
}

const WEDGES: { key: RemoteKey; label: string }[] = [
  { key: 'up', label: 'Up' },
  { key: 'right', label: 'Right' },
  { key: 'down', label: 'Down' },
  { key: 'left', label: 'Left' },
]

/** Glance row on top (what's playing); the rest of the remote is revealed as the sheet rises. */
export function ControlSheet({ state, send, onInk }: Props) {
  const playing = state.nowPlaying
  const app = appInfo(playing?.app ?? state.app)
  const key = (k: RemoteKey) => send({ type: 'key', key: k })
  const repeat = useHoldRepeat()
  const hold = (k: RemoteKey) => repeat.bind(() => key(k))

  return (
    <>
      <div className="sheet-glance">
        <span className="app-dot" />
        <span className="sheet-title">
          <strong>{playing?.title ?? app.name}</strong>
          {playing?.title && <span className="muted">{app.name}</span>}
        </span>
        {playing && (
          <span className="sheet-media">
            <button className="icon-button sheet-play" aria-label={playing.playing ? 'Pause' : 'Play'} onClick={() => send({ type: 'media', action: 'play-pause' })}>
              <Icon name={playing.playing ? 'pause' : 'play'} />
            </button>
          </span>
        )}
      </div>

      <div className="sheet-full">
        <PlaybackRow state={state} send={send} />
        <div className="wheel" role="group" aria-label="Direction pad">
          {WEDGES.map(({ key: k, label }) => (
            <button key={k} className={`wheel-wedge wheel-${k}`} aria-label={label} {...hold(k)}>
              <Icon name="up" size={18} />
            </button>
          ))}
          <button className="wheel-ok" aria-label="OK" onClick={() => key('ok')} />
        </div>

        <div className="sheet-keys">
          <button className="sheet-key" aria-label="Back" onClick={() => key('back')}><Icon name="back" /></button>
          <button className="sheet-key" aria-label="Home" onClick={() => key('home')}><Icon name="home" /></button>
          <span className="sheet-rocker">
            <button aria-label="Volume down" {...hold('voldown')}>−</button>
            <button aria-label="Volume up" {...hold('volup')}>+</button>
          </span>
          <button className="sheet-key sheet-draw" aria-label="Draw on the wall" onClick={onInk}><Icon name="pen" /></button>
        </div>
      </div>
    </>
  )
}
