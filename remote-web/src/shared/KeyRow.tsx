import type { ProjectorState, RemoteCommand } from '../relay'
import { Icon } from './icons'

/**
 * Back, Home and play/pause: the same keys, behaving the same, in every layout that has them.
 * Play/pause steps aside while something is playing, since the playback row above has it then.
 */
export function KeyRow({ state, send }: { state: ProjectorState; send: (command: RemoteCommand) => void }) {
  return (
    <nav className="actions">
      <button className="action" aria-label="Back" onClick={() => send({ type: 'key', key: 'back' })}><Icon name="back" /></button>
      <button className="action" aria-label="Home" onClick={() => send({ type: 'key', key: 'home' })}><Icon name="home" /></button>
      {!state.nowPlaying && (
        <button className="action" aria-label="Play or pause" onClick={() => send({ type: 'media', action: 'play-pause' })}><Icon name="playpause" /></button>
      )}
    </nav>
  )
}
