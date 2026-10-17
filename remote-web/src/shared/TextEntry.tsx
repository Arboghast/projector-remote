import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { ProjectorState, RemoteCommand } from '../relay'
import { Icon } from './icons'
import { useVoice } from './useVoice'

type Props = {
  state: ProjectorState
  send: (command: RemoteCommand) => void
}

/**
 * The phone's keyboard, only while a text field on the wall is waiting (the projector's on-screen
 * keyboard is up). Return types the text and presses Enter on the wall; Done hides it until the
 * next field.
 */
export function WallTyping({ state, send }: Props) {
  const [dismissed, setDismissed] = useState(false)
  useEffect(() => {
    if (!state.keyboard) setDismissed(false)
  }, [state.keyboard])
  if (!state.keyboard || dismissed) return null
  return <TextEntry send={send} onClose={() => setDismissed(true)} />
}

function TextEntry({ send, onClose }: { send: Props['send']; onClose: () => void }) {
  const [value, setValue] = useState('')
  const input = useRef<HTMLInputElement>(null)
  const voice = useVoice((heard) => setValue((v) => (v ? `${v} ${heard}` : heard)))
  const text = value.trim()

  // Browsers only raise the keyboard for a recent touch; if this doesn't, one tap on the field does.
  useEffect(() => input.current?.focus(), [])

  const type = (enter: boolean) => {
    if (text) send({ type: 'text', text })
    if (enter) send({ type: 'key', key: 'enter' })
    setValue('')
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    type(true)
  }

  return (
    <form className="text-entry" onSubmit={submit}>
      <div className="text-entry-row">
        <input
          ref={input}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="Type into the wall"
          autoCapitalize="off"
          autoCorrect="off"
          enterKeyHint="go"
        />
        {voice.supported && (
          <button type="button" className={voice.listening ? 'round-button listening' : 'round-button'} aria-label="Voice" onClick={voice.toggle}>
            <Icon name="mic" />
          </button>
        )}
      </div>
      <div className="text-entry-keys">
        <button type="button" className="chip" disabled={!text} onClick={() => type(false)}>Type</button>
        <button type="button" className="chip" onClick={() => send({ type: 'key', key: 'delete' })}>Delete</button>
        <button type="submit" className="chip chip-accent">Go</button>
        <button type="button" className="chip" onClick={onClose}>Done</button>
      </div>
    </form>
  )
}
