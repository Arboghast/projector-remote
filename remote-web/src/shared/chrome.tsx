import { useEffect, type CSSProperties, type ReactNode } from 'react'
import type { Link } from '../relay'
import { Icon } from './icons'

const LINK_LABEL: Record<Link, string> = {
  connecting: 'Connecting',
  'relay-down': 'Relay offline',
  'projector-off': 'Projector asleep',
  ready: 'Connected',
}

/** Green / amber / red: the only thing worth knowing about the connection is whether presses will land. */
export function LinkDot({ link, label }: { link: Link; label?: boolean }) {
  return (
    <span className="link" data-link={link} title={LINK_LABEL[link]}>
      <span className="link-dot" />
      {label && <span>{LINK_LABEL[link]}</span>}
      {!label && <span className="visually-hidden">{LINK_LABEL[link]}</span>}
    </span>
  )
}

export function LayoutButton({ onClick }: { onClick: () => void }) {
  return (
    <button className="icon-button layout-button" aria-label="Change layout" onClick={onClick}>
      <Icon name="layouts" />
    </button>
  )
}

/** Bottom sheet over the current layout; tapping the backdrop closes it. */
export function Sheet({ onClose, children, label }: { onClose: () => void; children: ReactNode; label: string }) {
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-label={label} onClick={(e) => e.stopPropagation()}>
        <span className="sheet-grip" aria-hidden="true" />
        {children}
      </div>
    </div>
  )
}

/** Keeps the phone awake while a remote is open; it would otherwise lock mid-film. */
export function useWakeLock() {
  useEffect(() => {
    let lock: WakeLockSentinel | null = null
    const acquire = async () => {
      try {
        if ('wakeLock' in navigator && document.visibilityState === 'visible') lock = await navigator.wakeLock.request('screen')
      } catch {
        // Not allowed (low battery, unsupported): the phone just locks as normal.
      }
    }
    acquire()
    document.addEventListener('visibilitychange', acquire)
    return () => {
      document.removeEventListener('visibilitychange', acquire)
      lock?.release().catch(() => {})
    }
  }, [])
}

/** Relay errors, shown only while the projector is reachable (otherwise the link dot already says why). */
export function Toast({ error, link }: { error: string | null; link: Link }) {
  if (!error || link !== 'ready') return null
  return <div className="toast" role="status">{error}</div>
}

/** Tints a subtree with the app's colour (`--app`), the remote's way of saying which app is on. */
export function appStyle(color: string): CSSProperties {
  return { ['--app' as string]: color }
}

/** Layouts button on the left, connection dot on the right; the corner every immersive layout shares. */
export function CornerBar({ link, openLayouts }: { link: Link; openLayouts: () => void }) {
  return (
    <header className="corner-bar">
      <LayoutButton onClick={openLayouts} />
      <LinkDot link={link} />
    </header>
  )
}

/** The app in front on the left, connection and layouts on the right. */
export function TopBar({ link, openLayouts, children }: { link: Link; openLayouts: () => void; children?: ReactNode }) {
  return (
    <header className="topbar">
      <span className="topbar-title">{children}</span>
      <LinkDot link={link} />
      <LayoutButton onClick={openLayouts} />
    </header>
  )
}
