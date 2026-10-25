import { useEffect, useState } from 'react'
import { Icon } from '../shared/icons'

type Status = 'unsupported' | 'blocked' | 'off' | 'on' | 'busy'

// iPhones only offer push to the remote once it's added to the Home Screen, and every phone needs https.
const supported = () => window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window

async function subscription() {
  const registration = await navigator.serviceWorker.ready
  return { registration, current: await registration.pushManager.getSubscription() }
}

function keyBytes(base64url: string) {
  const raw = atob(base64url.replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(raw, (c) => c.charCodeAt(0))
}

async function turnOn() {
  if ((await Notification.requestPermission()) !== 'granted') return false
  const { registration, current } = await subscription()
  const key = await (await fetch('/push/key')).text()
  const sub = current ?? (await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) }))
  const res = await fetch('/push/subscribe', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(sub) })
  if (!res.ok) throw new Error(await res.text())
  return true
}

async function turnOff() {
  const { current } = await subscription()
  if (!current) return
  await fetch('/push/unsubscribe', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ endpoint: current.endpoint }) })
  await current.unsubscribe()
}

/** "Saved to the Shelf" notifications on this phone; tapping one puts the link on the wall. */
export function PushToggle() {
  const [status, setStatus] = useState<Status>(() => (supported() ? 'busy' : 'unsupported'))
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    if (!supported()) return
    if (Notification.permission === 'denied') return setStatus('blocked')
    subscription().then(({ current }) => setStatus(current && Notification.permission === 'granted' ? 'on' : 'off'), () => setStatus('off'))
  }, [])

  if (status === 'unsupported') return null

  const toggle = async () => {
    const was = status
    setProblem(null)
    setStatus('busy')
    try {
      if (was === 'on') {
        await turnOff()
        setStatus('off')
      } else {
        setStatus((await turnOn()) ? 'on' : Notification.permission === 'denied' ? 'blocked' : 'off')
      }
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e))
      setStatus(was)
    }
  }

  return (
    <button className="push-toggle" aria-pressed={status === 'on'} disabled={status === 'busy' || status === 'blocked'} onClick={toggle}>
      <Icon name="bell" size={20} />
      <span>
        <span className="layout-name">Shelf notifications</span>
        <span className="muted">
          {problem ?? (status === 'blocked' ? 'Blocked in your phone’s settings' : 'Tap one to put the link on the wall')}
        </span>
      </span>
      <span className="push-state">{status === 'on' ? 'On' : 'Off'}</span>
    </button>
  )
}
