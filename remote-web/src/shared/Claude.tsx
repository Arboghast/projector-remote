import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import type { ClaudeRun, Relay } from '../relay'
import { Icon } from './icons'
import { useVoice } from './useVoice'
import './claude.css'

type Ask = {
  run: ClaudeRun | null
  send: Relay['send']
  asking: boolean
  busy: boolean
  /** The spark: a new request, or while Claude is busy, a look at how it's going. */
  spark: () => void
  expanded: boolean
  setExpanded: (open: boolean) => void
  dismissed: boolean
  dismiss: () => void
}

const AskContext = createContext<Ask | null>(null)

function useAsk(): Ask {
  const ask = useContext(AskContext)
  if (!ask) throw new Error('needs ClaudeProvider')
  return ask
}

const ANSWER_SHOWN_MS = 9000

/**
 * Asking Claude to do something on the projector, from any layout: the spark opens a prompt and
 * starts listening in the same tap (phones only allow the mic from a tap), what's heard goes off
 * at once, and a card at the top follows Claude's progress with a Stop.
 */
export function ClaudeProvider({ relay, children }: { relay: Relay; children: ReactNode }) {
  const [asking, setAsking] = useState(false)
  const [expandedRun, setExpandedRun] = useState<number | null>(null)
  const [dismissedRun, setDismissedRun] = useState<number | null>(null)
  const run = relay.claude
  const busy = run?.phase === 'working'

  const submit = (request: string) => {
    if (!request.trim()) return
    relay.send({ type: 'ask', text: request.trim() })
    setAsking(false)
  }
  const voice = useVoice(submit)

  const spark = () => {
    if (busy && run) return setExpandedRun(run.id)
    setAsking(true)
    if (voice.supported && !voice.listening) voice.toggle()
  }
  // Putting the prompt away drops whatever was being heard: it mustn't go off as a request.
  const close = () => {
    voice.cancel()
    setAsking(false)
  }

  const ask: Ask = {
    run,
    send: relay.send,
    asking,
    busy,
    spark,
    expanded: run !== null && expandedRun === run.id,
    setExpanded: (open) => setExpandedRun(open && run ? run.id : null),
    dismissed: run !== null && dismissedRun === run.id,
    dismiss: () => run && setDismissedRun(run.id),
  }

  return (
    <AskContext.Provider value={ask}>
      {children}
      {asking && <AskPrompt voice={voice} onSubmit={submit} onClose={close} />}
    </AskContext.Provider>
  )
}

/** The spark in each layout's corner; it breathes while Claude is at work, as the wall glows. */
export function AskButton() {
  const { spark, busy } = useAsk()
  return (
    <button className="icon-button ask-button" data-busy={busy || undefined} aria-label={busy ? 'Claude is working' : 'Ask Claude'} onClick={spark}>
      <Icon name="spark" />
    </button>
  )
}

type Voice = ReturnType<typeof useVoice>

function AskPrompt({ voice, onSubmit, onClose }: { voice: Voice; onSubmit: (text: string) => void; onClose: () => void }) {
  const [text, setText] = useState('')

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const type = (value: string) => {
    // Typing takes over from listening, so a late result can't send something other than what's typed.
    if (value && voice.listening) voice.cancel()
    setText(value)
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit(text)
  }

  return (
    <div className="ask-backdrop" onClick={onClose}>
      <form className="ask-prompt" role="dialog" aria-label="Ask Claude" onSubmit={submit} onClick={(e) => e.stopPropagation()}>
        <div className="ask-field">
          <span className="ask-mark"><Icon name="spark" size={18} /></span>
          <input
            value={text}
            onChange={(e) => type(e.target.value)}
            aria-label="What Claude should do"
            placeholder={voice.problem ?? (voice.listening ? 'Listening…' : 'What should Claude do?')}
            enterKeyHint="go"
          />
          {voice.supported && (
            <button type="button" className="ask-mic" data-listening={voice.listening || undefined} aria-label={voice.listening ? 'Stop listening' : 'Speak'} onClick={voice.toggle}>
              <Icon name="mic" size={20} />
            </button>
          )}
          <button type="submit" className="ask-go" aria-label="Ask" disabled={!text.trim()}>
            <Icon name="send" size={20} />
          </button>
        </div>
      </form>
    </div>
  )
}

const MARK: Record<ClaudeRun['phase'], 'spark' | 'check' | 'close' | 'stop'> = { working: 'spark', done: 'check', failed: 'close', stopped: 'stop' }
const FALLBACK: Record<ClaudeRun['phase'], string> = { working: 'On it…', done: 'Done.', failed: 'Something went wrong.', stopped: 'Stopped.' }

/**
 * Claude's progress, with the other notices at the top: what it heard, its latest step, and Stop.
 * Tap it for every step so far. Once it's over it fades after a while; while asking it steps aside.
 */
export function ClaudeProgress() {
  const { run, send, asking, expanded, setExpanded, dismissed, dismiss } = useAsk()
  const working = run?.phase === 'working'

  useEffect(() => {
    if (!run || working) return
    const t = window.setTimeout(dismiss, ANSWER_SHOWN_MS)
    return () => window.clearTimeout(t)
    // Counts from when the run ends, not from each re-render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.id, working])

  if (!run || dismissed) return null
  const latest = run.steps.filter((s) => s.kind !== 'thought').at(-1)?.text
  const headline = (working ? latest : run.answer) || FALLBACK[run.phase]

  return (
    <section className="ask-card" data-phase={run.phase} hidden={asking}>
      <div className="ask-card-row">
        <button className="ask-summary" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
          <span className="ask-mark"><Icon name={MARK[run.phase]} size={18} /></span>
          <span className="ask-said">
            {run.request && <span className="ask-request">{run.request}</span>}
            <span className="ask-headline" role="status">{headline}</span>
          </span>
        </button>
        {working ? (
          <button className="icon-button ask-stop" aria-label="Stop Claude" onClick={() => send({ type: 'ask-stop' })}>
            <Icon name="stop" />
          </button>
        ) : (
          <button className="icon-button" aria-label="Dismiss" onClick={dismiss}>
            <Icon name="close" />
          </button>
        )}
      </div>
      {expanded && run.steps.length > 0 && (
        <ol className="ask-steps">
          {run.steps.map((s, i) => (
            <li key={i} data-kind={s.kind}>{s.text}</li>
          ))}
        </ol>
      )}
    </section>
  )
}
