import { useState } from 'react'
import type { LayoutProps } from '../app'
import { TopBar } from '../shared/chrome'
import { KeyRow } from '../shared/KeyRow'
import { WallTyping } from '../shared/TextEntry'
import { CursorPad, ScrollPad } from './pads'
import './pads.css'
import { PlaybackRow } from '../shared/NowPlaying'

type Mode = 'scroll' | 'cursor'

const MODES: { id: Mode; name: string }[] = [
  { id: 'scroll', name: 'Scroll' },
  { id: 'cursor', name: 'Cursor' },
]

const STORAGE_KEY = 'remote.padMode'

function initialMode(): Mode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return MODES.find((m) => m.id === stored)?.id ?? 'cursor'
  } catch {
    return 'cursor'
  }
}

/** Trackpad prototypes side by side: one big pad, three ways to drive the wall. */
export function Pads({ relay, openLayouts }: LayoutProps) {
  const { link, state, send } = relay
  const [mode, setMode] = useState<Mode>(initialMode)

  const choose = (m: Mode) => {
    setMode(m)
    try {
      localStorage.setItem(STORAGE_KEY, m)
    } catch {
      // Not remembered; harmless.
    }
  }

  return (
    <main className="layout pads">
      <TopBar link={link} openLayouts={openLayouts}>
        <nav className="segmented" aria-label="Trackpad mode">
          {MODES.map((m) => (
            <button key={m.id} aria-pressed={mode === m.id} onClick={() => choose(m.id)}>{m.name}</button>
          ))}
        </nav>
      </TopBar>

      {mode === 'scroll' && <ScrollPad send={send} />}
      {mode === 'cursor' && <CursorPad send={send} />}

      <WallTyping state={state} send={send} />

      <PlaybackRow state={state} send={send} />
      <KeyRow state={state} send={send} />
    </main>
  )
}
