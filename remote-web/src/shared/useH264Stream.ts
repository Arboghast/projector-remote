import { useEffect, useState, type RefObject } from 'react'
import { wsUrl } from '../relay'
import { QuadLearner, savedQuad, type Quad } from './keystone'

const NO_FRAME_FALLBACK_MS = 8000
const LEARN_EVERY_MS = 400
const RECONNECT_MS = [500, 1000, 2000, 4000, 8000]
const MAX_DECODER_REBUILDS = 5
// More frames waiting than this and the phone is behind; skip ahead to the next key frame.
const MAX_QUEUED = 2

/** "avc1.PPCCLL" from the SPS NAL, so the decoder knows the exact H.264 profile and level. */
function codecFromConfig(config: Uint8Array): string | null {
  for (let i = 0; i + 4 < config.length; i++) {
    const startCode = config[i] === 0 && config[i + 1] === 0 && config[i + 2] === 1
    if (startCode && (config[i + 3] & 0x1f) === 7) {
      const hex = (b: number) => b.toString(16).padStart(2, '0')
      return `avc1.${hex(config[i + 4])}${hex(config[i + 5])}${hex(config[i + 6])}`
    }
  }
  return null
}

function joined(a: Uint8Array, b: Uint8Array) {
  const out = new Uint8Array(a.length + b.length)
  out.set(a)
  out.set(b, a.length)
  return out
}

/**
 * The wall's live H.264 picture, decoded with WebCodecs into [canvas]. Reconnects when the relay
 * restarts; reports [failed] (use the snapshot fallback) only when this browser can't decode it or
 * no frame arrives at all. Re-opening the picture tries video again. Also learns the keystone quad.
 */
export function useH264Stream(canvas: RefObject<HTMLCanvasElement | null>, active: boolean) {
  const supported = typeof window !== 'undefined' && 'VideoDecoder' in window
  const [failed, setFailed] = useState(false)
  const [quad, setQuad] = useState<Quad | null>(savedQuad)

  useEffect(() => {
    if (active) setFailed(false)
  }, [active])

  useEffect(() => {
    if (!supported || !active || failed) return
    let ws: WebSocket | null = null
    let config: Uint8Array | null = null
    let needKey = true
    let gotFrame = false
    let decoder: VideoDecoder | null = null
    let rebuilds = 0
    let attempt = 0
    let stopped = false
    let reconnect: number | undefined
    const learner = new QuadLearner()
    let learnedAt = 0

    const draw = (frame: VideoFrame) => {
      const c = canvas.current
      if (c) {
        if (c.width !== frame.displayWidth) c.width = frame.displayWidth
        if (c.height !== frame.displayHeight) c.height = frame.displayHeight
        c.getContext('2d')?.drawImage(frame, 0, 0)
        const now = performance.now()
        if (now - learnedAt > LEARN_EVERY_MS) {
          learnedAt = now
          const q = learner.add(c)
          if (q) setQuad(q)
        }
      }
      gotFrame = true
      attempt = 0
      frame.close()
    }

    const makeDecoder = (codec: string) => {
      decoder?.close()
      decoder = new VideoDecoder({
        output: draw,
        // A dropped packet corrupts the picture until the next key frame; start over from there.
        error: () => {
          if (++rebuilds > MAX_DECODER_REBUILDS) return setFailed(true)
          if (config) makeDecoder(codec)
        },
      })
      decoder.configure({ codec, optimizeForLatency: true })
      needKey = true
    }

    const onPacket = (bytes: Uint8Array) => {
      const flags = bytes[0]
      const payload = bytes.subarray(1)
      if (flags & 1) {
        config = payload.slice()
        const codec = codecFromConfig(config)
        if (!codec) return setFailed(true)
        makeDecoder(codec)
        return
      }
      if (!decoder || decoder.state !== 'configured' || !config) return
      const key = (flags & 2) !== 0
      if (!key && decoder.decodeQueueSize > MAX_QUEUED) needKey = true
      if (needKey && !key) return
      needKey = false
      // No avcC description is passed, so the stream is Annex-B and key frames carry SPS/PPS in-band.
      const data = key ? joined(config, payload) : payload
      decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: performance.now() * 1000, data }))
    }

    const connect = () => {
      ws = new WebSocket(wsUrl('/screen-stream'))
      ws.binaryType = 'arraybuffer'
      ws.onmessage = (e) => onPacket(new Uint8Array(e.data as ArrayBuffer))
      ws.onclose = () => {
        if (stopped) return
        reconnect = window.setTimeout(connect, RECONNECT_MS[Math.min(attempt++, RECONNECT_MS.length - 1)])
      }
    }
    connect()

    const watchdog = window.setTimeout(() => {
      if (!gotFrame) setFailed(true)
    }, NO_FRAME_FALLBACK_MS)

    return () => {
      stopped = true
      window.clearTimeout(watchdog)
      window.clearTimeout(reconnect)
      ws?.close()
      decoder?.close()
    }
  }, [supported, active, failed, canvas])

  return { failed: !supported || failed, quad }
}
