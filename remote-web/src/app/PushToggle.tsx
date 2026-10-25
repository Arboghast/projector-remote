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

// Set when you turn notifications off here, so allowing them in the phone's settings doesn't
// switch them back on at the next load.
const OPTED_OUT_KEY = 'remote.push-off'

function setOptedOut(off: boolean) {
  try {
    if (off) localStorage.setItem(OPTED_OUT_KEY, '1')
    else localStorage.removeItem(OPTED_OUT_KEY)
  } catch {
    // Private mode: the choice isn't remembered.
  }
}

function optedOut() {
  try {
    return localStorage.getItem(OPTED_OUT_KEY) === '1'
  } catch {
    return false
  }
}

async function register() {
  const { registration, current } = await subscription()
  const key = await (await fetch('/push/key')).text()
  const sub = current ?? (await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) }))
  const res = await fetch('/push/subscribe', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(sub) })
  if (!res.ok) throw new Error(await res.text())
}

async function turnOn() {
  if ((await Notification.requestPermission()) !== 'granted') return false
  await register()
  setOptedOut(false)
  return true
}

async function turnOff() {
  setOptedOut(true)
  const { current } = await subscription()
  if (!current) return
  await fetch('/push/unsubscribe', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ endpoint: current.endpoint }) })
  await current.unsubscribe()
}

/**
 * Notifications allowed in the phone's settings but never switched on here: subscribe on load, since
 * allowing them is the whole intent and the toggle is easy to miss.
 */
export function useAutoSubscribe() {
  useEffect(() => {
    if (!supported() || Notification.permission !== 'granted' || optedOut()) return
    subscription()
      .then(({ current }) => (current ? undefined : register()))
      .catch(() => {})
  }, [])
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
