import { useCallback, useEffect, useRef, useState } from 'react'

// The Web Speech API isn't in TypeScript's DOM lib yet; this is the slice we use.
type Recognition = {
  lang: string
  interimResults: boolean
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null
  onend: (() => void) | null
  onerror: (() => void) | null
  start: () => void
  stop: () => void
}
type RecognitionCtor = new () => Recognition

function recognitionCtor(): RecognitionCtor | undefined {
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition
}

/** Dictation on the phone itself; the projector only ever receives the finished text. */
export function useVoice(onText: (text: string) => void) {
  const supported = recognitionCtor() !== undefined
  const active = useRef<Recognition | null>(null)
  const [listening, setListening] = useState(false)
  const onTextRef = useRef(onText)
  onTextRef.current = onText

  useEffect(() => () => active.current?.stop(), [])

  const toggle = useCallback(() => {
    if (active.current) {
      active.current.stop()
      return
    }
    const Ctor = recognitionCtor()
    if (!Ctor) return
    const rec = new Ctor()
    rec.lang = navigator.language
    rec.interimResults = false
    rec.onresult = (e) => {
      const transcript = Array.from(e.results).map((r) => r[0]?.transcript ?? '').join(' ').trim()
      if (transcript) onTextRef.current(transcript)
    }
    rec.onend = rec.onerror = () => {
      active.current = null
      setListening(false)
    }
    active.current = rec
    setListening(true)
    rec.start()
  }, [])

  return { supported, listening, toggle }
}
