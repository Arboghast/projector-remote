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
 * keyboard is up). Go types the text and presses Enter on the wall; closing it also closes the
 * wall's keyboard, so the two never disagree.
 */
export function WallTyping({ state, send }: Props) {
  const [dismissed, setDismissed] = useState(false)
  useEffect(() => {
    if (!state.keyboard) setDismissed(false)
  }, [state.keyboard])
  if (!state.keyboard || dismissed) return null
  const close = () => {
    // Back is what puts away Android's keyboard and leaves the field.
    send({ type: 'key', key: 'back' })
    setDismissed(true)
  }
  return <TextEntry send={send} onClose={close} />
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
      <div className="text-entry-field">
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
          <button type="button" className="text-entry-icon" data-listening={voice.listening || undefined} aria-label="Voice" onClick={voice.toggle}>
            <Icon name="mic" size={20} />
          </button>
        )}
        <button type="submit" className="text-entry-go" aria-label="Go">
          <Icon name="send" size={20} />
        </button>
      </div>
      <div className="text-entry-actions">
        <button type="button" onClick={onClose}>
          <Icon name="close" size={16} />Close
        </button>
        <span className="text-entry-spacer" />
        <button type="button" onClick={() => send({ type: 'key', key: 'delete' })}>
          <Icon name="erase" size={16} />Delete
        </button>
        <button type="button" disabled={!text} onClick={() => type(false)}>Type only</button>
      </div>
    </form>
  )
}
