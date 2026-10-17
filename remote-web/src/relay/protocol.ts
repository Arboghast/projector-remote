// Mirrors Protocol.kt in the relay.

export type RemoteKey =
  | 'up' | 'down' | 'left' | 'right' | 'ok'
  | 'back' | 'home' | 'menu'
  | 'playpause' | 'volup' | 'voldown' | 'mute'
  | 'enter' | 'delete' | 'captions'

export type MediaAction = 'play-pause' | 'next' | 'previous' | 'rewind' | 'fast-forward'

export type RemoteCommand =
  | { type: 'key'; key: RemoteKey }
  | { type: 'text'; text: string }
  | { type: 'scroll'; dx: number; dy: number }
  | { type: 'open'; url: string }
  | { type: 'tap'; x: number; y: number }
  | { type: 'media'; action: MediaAction }
  | { type: 'shelf-add'; url: string; from: string }
  | { type: 'shelf-project'; id: string }
  | { type: 'shelf-remove'; id: string }
  | { type: 'pointer'; dx: number; dy: number }
  | { type: 'click' }
  | { type: 'draw' }
  | { type: 'draw-end' }
  | { type: 'player-settings' }
  | { type: 'ink'; id: string; points: [number, number][]; color: string; size: number }
  | { type: 'ink-clear' }
  | { type: 'ink-undo' }

export type NowPlaying = {
  app: string
  title: string | null
  subtitle: string | null
  playing: boolean
}

export type ProjectorState = {
  app: string | null
  nowPlaying: NowPlaying | null
  lastOpened: string | null
  /** The projector's on-screen keyboard is up: something on the wall wants typing. */
  keyboard: boolean
}

export type ShelfItem = {
  id: string
  url: string
  app: string
  from: string
  addedAt: number
}

export type RelayEvent =
  | { type: 'status'; connected: boolean; device: string }
  | { type: 'error'; message: string }
  | { type: 'state'; state: ProjectorState }
  | { type: 'shelf'; items: ShelfItem[] }

const EVENT_TYPES = new Set(['status', 'error', 'state', 'shelf'])

/** The relay is ours and its shapes mirror Protocol.kt, so only the discriminator is checked here. */
export function parseRelayEvent(raw: string): RelayEvent | null {
  try {
    const data: unknown = JSON.parse(raw)
    if (typeof data !== 'object' || data === null || !('type' in data)) return null
    return EVENT_TYPES.has(String(data.type)) ? (data as RelayEvent) : null
  } catch {
    return null
  }
}
