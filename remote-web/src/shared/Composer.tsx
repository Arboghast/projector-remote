import { useEffect, useState, type FormEvent } from 'react'
import { deviceName, type RemoteCommand } from '../relay'
import { asLink, linkLabel, searchUrl } from './apps'
import { Icon } from './icons'
import { useVoice } from './useVoice'

type Props = {
  send: (command: RemoteCommand) => void
  /** The shelf's newest link, so "Saved" is confirmed by the relay rather than assumed. */
  newestUrl: string | undefined
  /** Where suggestions open: above the field when it sits at the bottom of the screen. */
  suggestionsAbove?: boolean
}

type Option = { key: string; kind: 'link' | 'search'; label: string; url: string }

const CONFIRM_MS = 2500
const SUGGEST_DELAY_MS = 150

/**
 * The Shelf's input. A search (your words or a suggestion) goes straight onto the wall; an address
 * is saved to the queue for later. Nothing is ever typed on the projector.
 */
export function Composer({ send, newestUrl, suggestionsAbove }: Props) {
  const [value, setValue] = useState('')
  const [suggested, setSuggested] = useState<string[]>([])
  const [waitingFor, setWaitingFor] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [shown, setShown] = useState(false)
  const voice = useVoice((text) => setValue((v) => (v ? `${v} ${text}` : text)))
  const text = value.trim()
  const link = asLink(value)
  const canPaste = typeof navigator !== 'undefined' && !!navigator.clipboard?.readText

  // "Saved" only once the shelf the relay sends back has the item on top (re-saving moves it up).
  useEffect(() => {
    if (waitingFor === null || newestUrl !== waitingFor) return
    setWaitingFor(null)
    setSaved(true)
  }, [newestUrl, waitingFor])

  useEffect(() => {
    if (!saved && !shown) return
    const t = window.setTimeout(() => { setSaved(false); setShown(false) }, CONFIRM_MS)
    return () => window.clearTimeout(t)
  }, [saved, shown])

  useEffect(() => {
    if (!text || link) return setSuggested([])
    const ctrl = new AbortController()
    const t = window.setTimeout(() => {
      fetch(`/suggest?q=${encodeURIComponent(text)}`, { signal: ctrl.signal })
        .then((r) => (r.ok ? (r.json() as Promise<string[]>) : []))
        .then((list) => setSuggested(list.filter((s) => s.toLowerCase() !== text.toLowerCase())))
        .catch(() => {})
    }, SUGGEST_DELAY_MS)
    return () => {
      window.clearTimeout(t)
      ctrl.abort()
    }
  }, [text, link])

  const save = (url: string) => {
    send({ type: 'shelf-add', url, from: deviceName() })
    setWaitingFor(url)
    setValue('')
    setSuggested([])
  }

  const show = (url: string) => {
    send({ type: 'open', url })
    setShown(true)
    setValue('')
    setSuggested([])
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!text) return
    if (link) save(link)
    else show(searchUrl(text))
  }

  const paste = async () => {
    try {
      const clip = (await navigator.clipboard.readText()).trim()
      if (clip) setValue(clip)
    } catch {
      // Clipboard access declined; nothing to do.
    }
  }

  const options: Option[] = !text ? [] : [
    ...(link ? [{ key: 'link', kind: 'link' as const, label: linkText(link), url: link }] : []),
    { key: 'search', kind: 'search', label: text, url: searchUrl(text) },
    ...suggested.map((s) => ({ key: `s-${s}`, kind: 'search' as const, label: s, url: searchUrl(s) })),
  ]

  return (
    <div className={suggestionsAbove ? 'composer composer-above' : 'composer'}>
      {options.length > 0 && (
        <ul className="composer-options" aria-label="Show a search on the wall or save a link">
          {options.map((o) => (
            <li key={o.key}>
              <button type="button" data-kind={o.kind} onClick={() => (o.kind === 'search' ? show(o.url) : save(o.url))}>
                <Icon name={o.kind === 'link' ? 'link' : 'search'} size={16} />
                <span>{o.label}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <form className="composer-row" onSubmit={submit}>
        <label className="composer-field">
          <span className="visually-hidden">Add to the shelf</span>
          <input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={shown ? 'Showing on the wall' : saved ? 'Saved to the Shelf' : 'Search or paste a link'}
            autoCapitalize="off"
            autoCorrect="off"
            enterKeyHint="done"
          />
          {!value && canPaste && <button type="button" className="chip" onClick={paste}>Paste</button>}
          {value && <button type="button" className="icon-button" aria-label="Clear" onClick={() => setValue('')}><Icon name="close" size={16} /></button>}
        </label>
        {voice.supported && (
          <button
            type="button"
            className={voice.listening ? 'round-button listening' : 'round-button'}
            aria-label="Voice"
            onClick={voice.toggle}
          >
            <Icon name="mic" />
          </button>
        )}
      </form>
    </div>
  )
}

function linkText(url: string) {
  const l = linkLabel(url)
  return `${l.host}${l.path ? ` / ${l.path}` : ''}`
}
