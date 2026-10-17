import type { RemoteCommand, RemoteKey } from '../relay'
import { useHoldRepeat } from './useHoldRepeat'

type Props = { send: (command: RemoteCommand) => void }

const ARROWS: { key: RemoteKey; label: string; rotate: number }[] = [
  { key: 'up', label: 'Up', rotate: 0 },
  { key: 'right', label: 'Right', rotate: 90 },
  { key: 'down', label: 'Down', rotate: 180 },
  { key: 'left', label: 'Left', rotate: 270 },
]

export function DPad({ send }: Props) {
  const hold = useHoldRepeat()

  return (
    <div className="dpad">
      {ARROWS.map(({ key, label, rotate }) => (
        <button
          key={key}
          className={`dpad-arrow dpad-${key}`}
          aria-label={label}
          {...hold.bind(() => send({ type: 'key', key }))}
        >
          <svg viewBox="0 0 24 24" style={{ transform: `rotate(${rotate}deg)` }}>
            <path d="M6 15l6-6 6 6" />
          </svg>
        </button>
      ))}
      <button className="dpad-ok" aria-label="OK" onClick={() => send({ type: 'key', key: 'ok' })} />
    </div>
  )
}
