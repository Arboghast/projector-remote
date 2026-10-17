import { useCallback, useEffect, useRef, useState } from 'react'

// The Web Speech API isn't in TypeScript's DOM lib yet; this is the slice we use.
type Recognition = {
  lang: string
  interimResults: boolean
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null
  onend: (() => void) | null
  onerror: ((e: { error: string }) => void) | null
  start: () => void
  stop: () => void
}
type RecognitionCtor = new () => Recognition

const PROBLEM_MS = 5000

function recognitionCtor(): RecognitionCtor | undefined {
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition
}

/** Why dictation stopped, in words; null when there's nothing to say (you stopped it, or it worked). */
function voiceProblem(error: string): string | null {
  switch (error) {
    case 'aborted':
      return null
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Microphone blocked: allow it for this site'
    case 'no-speech':
      return "Didn't hear anything, tap the mic and speak"
    case 'audio-capture':
      return 'No microphone available'
    case 'network':
      return 'Voice needs an internet connection'
    default:
      return `Voice stopped (${error})`
  }
}

/** Dictation on the phone itself; the projector only ever receives the finished text. */
export function useVoice(onText: (text: string) => void) {
  // Phones only allow the microphone on https pages; on the plain-http Wi-Fi address the button would never
  // work, so it isn't offered (the keyboard's own mic still is).
  const supported = window.isSecureContext && recognitionCtor() !== undefined
  const active = useRef<Recognition | null>(null)
  const [listening, setListening] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const onTextRef = useRef(onText)
  onTextRef.current = onText

  useEffect(() => () => active.current?.stop(), [])

  useEffect(() => {
    if (!problem) return
    const t = window.setTimeout(() => setProblem(null), PROBLEM_MS)
    return () => window.clearTimeout(t)
  }, [problem])

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
    rec.onerror = (e) => setProblem(voiceProblem(e.error))
    rec.onend = () => {
      active.current = null
      setListening(false)
    }
    active.current = rec
    setProblem(null)
    setListening(true)
    try {
      rec.start()
    } catch {
      active.current = null
      setListening(false)
      setProblem(voiceProblem('not-allowed'))
    }
  }, [])

  return { supported, listening, problem, toggle }
}
