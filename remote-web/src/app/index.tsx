import { useState, type ComponentType } from 'react'
import { useRelay, type Relay } from '../relay'
import { Sheet, Toast, useWakeLock } from '../shared/chrome'
import { ClaudeProgress, ClaudeProvider } from '../shared/Claude'
import { Icon, type IconName } from '../shared/icons'
import { Classic } from '../classic'
import { Lens } from '../lens'
import { Shelf } from '../shelf'
import { Dark } from '../dark'
import { Pads } from '../pads'
import { Theatre } from '../theatre'
import { PushToggle } from './PushToggle'
import { useProjectFromNotification } from './useProjectFromNotification'
import './app.css'

export type LayoutProps = {
  relay: Relay
  openLayouts: () => void
}

type LayoutId = 'theatre' | 'pads' | 'classic' | 'lens' | 'shelf' | 'dark'

const LAYOUTS: { id: LayoutId; name: string; blurb: string; icon: IconName; component: ComponentType<LayoutProps> }[] = [
  { id: 'theatre', name: 'Theatre', blurb: 'Everything, immersive', icon: 'play', component: Theatre },
  { id: 'pads', name: 'Pads', blurb: 'Trackpad prototypes', icon: 'pad', component: Pads },
  { id: 'classic', name: 'Classic', blurb: 'Trackpad and D-pad', icon: 'dpad', component: Classic },
  { id: 'lens', name: 'Lens', blurb: 'The wall is the trackpad', icon: 'grab', component: Lens },
  { id: 'shelf', name: 'Shelf', blurb: 'Collect links, project later', icon: 'shelf', component: Shelf },
  { id: 'dark', name: 'Dark', blurb: 'Gestures only', icon: 'moon', component: Dark },
]

const STORAGE_KEY = 'remote.layout'

function initialLayout(): LayoutId {
  const fromUrl = new URLSearchParams(location.search).get('layout')
  let stored: string | null = null
  try {
    stored = localStorage.getItem(STORAGE_KEY)
  } catch {
    // Private mode: fall back to the default.
  }
  const known = (id: string | null) => LAYOUTS.find((l) => l.id === id)?.id
  return known(fromUrl) ?? known(stored) ?? 'theatre'
}

export function App() {
  const relay = useRelay()
  const [layoutId, setLayoutId] = useState<LayoutId>(initialLayout)
  const [choosing, setChoosing] = useState(false)
  useWakeLock()
  useProjectFromNotification(relay.link)

  const choose = (id: LayoutId) => {
    setLayoutId(id)
    setChoosing(false)
    try {
      localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // Not remembered next time; harmless.
    }
  }

  const Layout = LAYOUTS.find((l) => l.id === layoutId)?.component ?? Theatre

  return (
    <ClaudeProvider relay={relay}>
      <Layout relay={relay} openLayouts={() => setChoosing(true)} />
      <div className="notices">
        <Toast error={relay.error} link={relay.link} />
        <ClaudeProgress />
      </div>
      {choosing && (
        <Sheet label="Choose a layout" onClose={() => setChoosing(false)}>
          <div className="layout-grid">
            {LAYOUTS.map((l) => (
              <button
                key={l.id}
                className="layout-option"
                aria-pressed={l.id === layoutId}
                onClick={() => choose(l.id)}
              >
                <Icon name={l.icon} size={22} />
                <span className="layout-name">{l.name}</span>
                <span className="muted">{l.blurb}</span>
              </button>
            ))}
          </div>
          <PushToggle />
        </Sheet>
      )}
    </ClaudeProvider>
  )
}
