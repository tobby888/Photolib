import { CaretRightOutlined, PauseOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import { useCallback, useEffect, useRef, useState, type PointerEvent, type ReactNode } from 'react'
import {
  closeAnimation, frameDelayMs, HOVER_PLAY_DELAY_MS, movedBeyondJitter, openAnimation,
  type AnimationSource, type Point,
} from './animatedImage'

/**
 * 动图在界面上的三种样子（逻辑见 `src/animatedImage.ts`）：
 * - 网格：平时显示静态首帧缩略图；鼠标在格子上停住 3 秒后盖一层播放，挪开就撤掉。
 * - 详情页：进来就播，带暂停按钮；暂停时回到首帧。
 * - 角标：网格里标一个「动图」，让人知道停一下能看动画。
 */

interface PlayerProps {
  src: string
  playing: boolean
  alt: string
  /**
   * overlay：盖在缩略图上的一层（网格），画出第一帧之前透明。
   * inline：自己占位（详情页），画出第一帧之前显示 `poster`。
   */
  layout: 'overlay' | 'inline'
  poster?: ReactNode
  onError?: (message: string) => void
}

export function AnimatedImagePlayer({ src, playing, alt, layout, poster, onError }: PlayerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [source, setSource] = useState<AnimationSource | null>(null)
  const [ready, setReady] = useState(false)
  const onErrorRef = useRef(onError)
  useEffect(() => { onErrorRef.current = onError })

  useEffect(() => {
    let cancelled = false
    let opened: AnimationSource | null = null
    setSource(null)
    setReady(false)
    openAnimation(src).then(result => {
      if (cancelled) {
        closeAnimation(result)
        return
      }
      opened = result
      setSource(result)
    }, (reason: unknown) => {
      if (cancelled) return
      console.error('[animation] 动图加载失败', reason)
      onErrorRef.current?.(reason instanceof Error ? reason.message : '动图没能加载出来')
    })
    return () => {
      cancelled = true
      if (opened) closeAnimation(opened)
    }
  }, [src])

  // 逐帧播放；暂停时只画第一帧。帧时长扣掉解码耗时，长动图不会越放越慢。
  useEffect(() => {
    if (source?.kind !== 'decoder') return
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const show = async (index: number) => {
      const { image } = await source.decoder.decode({ frameIndex: index })
      try {
        if (stopped) return 0
        if (canvas.width !== image.displayWidth || canvas.height !== image.displayHeight) {
          canvas.width = image.displayWidth
          canvas.height = image.displayHeight
        }
        context.clearRect(0, 0, canvas.width, canvas.height)
        context.drawImage(image, 0, 0)
        setReady(true)
        return frameDelayMs(image.duration)
      } finally {
        image.close()
      }
    }
    const fail = (reason: unknown) => {
      if (stopped) return
      console.error('[animation] 动图播放失败', reason)
      onErrorRef.current?.('动图播放失败')
    }
    const loop = (index: number) => {
      const started = performance.now()
      show(index).then(delay => {
        if (stopped) return
        const next = (index + 1) % source.frameCount
        timer = setTimeout(() => loop(next), Math.max(0, delay - (performance.now() - started)))
      }, fail)
    }
    if (playing) loop(0)
    else show(0).catch(fail)
    return () => {
      stopped = true
      if (timer !== undefined) clearTimeout(timer)
    }
  }, [source, playing])

  // 退回 <img> 时没法停在首帧：暂停就不显示它，让静态首帧缩略图露出来。
  const imageMode = source?.kind === 'image'
  const showMedia = ready && (!imageMode || playing)
  const media = source?.kind === 'decoder'
    ? <canvas ref={canvasRef} role="img" aria-label={alt} />
    : imageMode && playing
      ? <img src={source.objectUrl} alt={alt} onLoad={() => setReady(true)} />
      : null

  if (layout === 'overlay') {
    return <div className={`animated-player animated-player-overlay${showMedia ? ' is-ready' : ''}`}
      aria-hidden={!showMedia}>
      {media}
    </div>
  }
  return <>
    {!showMedia && poster}
    <div className="animated-player animated-player-inline" hidden={!showMedia}>{media}</div>
  </>
}

export interface HoverPlaybackHandlers {
  onPointerEnter?: (event: PointerEvent) => void
  onPointerMove?: (event: PointerEvent) => void
  onPointerLeave?: () => void
}

/**
 * 鼠标停住才播放：进入格子开始计时，挪动超过手抖幅度就重新计时，离开就停。
 * 只认鼠标——触屏没有悬停，按住会和长按选择打架。
 *
 * 一个网格只要一份：同一时刻鼠标只会停在一个格子上，`bind(key)` 给每个格子发一套事件，
 * `playingKey` 是正在播放的那一格。这样网格在 `.map()` 里直接渲染格子也能用。
 */
export function useHoverPlayback() {
  const [playingKey, setPlayingKey] = useState<string | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const anchor = useRef<Point | null>(null)
  const startedKey = useRef<string | null>(null)

  const clear = useCallback(() => {
    if (timer.current !== undefined) clearTimeout(timer.current)
    timer.current = undefined
  }, [])
  useEffect(() => clear, [clear])

  const bind = (key: string, enabled: boolean): HoverPlaybackHandlers => {
    if (!enabled) return {}
    const arm = (point: Point) => {
      clear()
      anchor.current = point
      timer.current = setTimeout(() => {
        timer.current = undefined
        startedKey.current = key
        setPlayingKey(key)
      }, HOVER_PLAY_DELAY_MS)
    }
    return {
      onPointerEnter: event => {
        if (event.pointerType === 'mouse') arm({ x: event.clientX, y: event.clientY })
      },
      onPointerMove: event => {
        if (event.pointerType !== 'mouse' || startedKey.current === key) return
        const point = { x: event.clientX, y: event.clientY }
        if (!anchor.current || movedBeyondJitter(anchor.current, point)) arm(point)
      },
      onPointerLeave: () => {
        clear()
        anchor.current = null
        startedKey.current = null
        setPlayingKey(current => (current === key ? null : current))
      },
    }
  }

  return { playingKey, bind }
}

/** 网格格子里的动图：角标，以及停住之后盖上来的播放层。放在缩略图后面、操作按钮前面。 */
export function AnimatedHoverLayer({ src, playing, alt }: { src?: string; playing: boolean; alt: string }) {
  return <>
    <span className="animated-badge" aria-label="动图，鼠标停住 3 秒播放" title="鼠标停住 3 秒播放">动图</span>
    {src && playing && <AnimatedImagePlayer src={src} playing layout="overlay" alt={alt} />}
  </>
}

function prefersReducedMotion() {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

/**
 * 详情页的动图：进来就循环播放（系统设置了「减少动态效果」时先停着），右下角暂停 / 播放。
 * 加载失败时只显示首帧缩略图并说明原因。
 */
export function AnimatedPhotoStage({ src, poster, alt }: { src: string; poster: ReactNode; alt: string }) {
  const [playing, setPlaying] = useState(() => !prefersReducedMotion())
  const [error, setError] = useState<string | null>(null)
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  // 换了地址（签名续期、翻到下一张）就再试一次。
  const failed = error !== null && failedSrc === src

  return <div className="animated-stage">
    {failed
      ? poster
      : <AnimatedImagePlayer src={src} playing={playing} layout="inline" alt={alt} poster={poster}
        onError={message => { setError(message); setFailedSrc(src) }} />}
    {failed
      ? <span className="animated-stage-note">{error}，只能显示首帧</span>
      : <Button className="animated-stage-toggle" shape="round"
        icon={playing ? <PauseOutlined /> : <CaretRightOutlined />}
        aria-label={playing ? '暂停动图（回到首帧）' : '播放动图'}
        onClick={() => setPlaying(value => !value)}>
        {playing ? '暂停' : '播放'}
      </Button>}
  </div>
}
