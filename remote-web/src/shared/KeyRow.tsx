import type { RemoteCommand } from '../relay'
import { Icon } from './icons'

/** Back, Home and play/pause: the same three keys, behaving the same, in every layout that has them. */
export function KeyRow({ send }: { send: (command: RemoteCommand) => void }) {
  return (
    <nav className="actions">
      <button className="action" aria-label="Back" onClick={() => send({ type: 'key', key: 'back' })}><Icon name="back" /></button>
      <button className="action" aria-label="Home" onClick={() => send({ type: 'key', key: 'home' })}><Icon name="home" /></button>
      <button className="action" aria-label="Play or pause" onClick={() => send({ type: 'media', action: 'play-pause' })}><Icon name="playpause" /></button>
    </nav>
  )
}
