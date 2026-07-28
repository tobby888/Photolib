import { Alert, Skeleton, Space, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { calculateExposureHistogram, type ExposureHistogramData } from './histogramMath'

type HistogramState =
  | { status: 'loading' }
  | { status: 'ready'; data: ExposureHistogramData }
  | { status: 'error'; message: string }

const GRAPH_HEIGHT = 100

function linePath(values: number[], maximum: number) {
  return values.map((value, index) => {
    const y = GRAPH_HEIGHT - value / maximum * GRAPH_HEIGHT
    return `${index === 0 ? 'M' : 'L'} ${index} ${y.toFixed(2)}`
  }).join(' ')
}

function areaPath(values: number[], maximum: number) {
  return `${linePath(values, maximum)} L 255 ${GRAPH_HEIGHT} L 0 ${GRAPH_HEIGHT} Z`
}

function percent(value: number) {
  return `${value.toFixed(value >= 10 ? 1 : 2)}%`
}

export default function PhotoHistogram({ imageUrl, alt }: { imageUrl?: string; alt: string }) {
  const [state, setState] = useState<HistogramState>({ status: 'loading' })

  useEffect(() => {
    let active = true
    if (!imageUrl) {
      setState({ status: 'error', message: '预览图生成后即可分析曝光分布。' })
      return () => { active = false }
    }

    setState({ status: 'loading' })
    const image = new window.Image()
    image.crossOrigin = 'anonymous'
    image.decoding = 'async'
    image.onload = () => {
      if (!active) return
      try {
        const maximumDimension = 640
        const scale = Math.min(1, maximumDimension / Math.max(image.naturalWidth, image.naturalHeight))
        const width = Math.max(1, Math.round(image.naturalWidth * scale))
        const height = Math.max(1, Math.round(image.naturalHeight * scale))
        const canvas = document.createElement('canvas')
        canvas.width = width
        canvas.height = height
        const context = canvas.getContext('2d', { willReadFrequently: true })
        if (!context) throw new Error('浏览器不支持图像像素分析')
        context.drawImage(image, 0, 0, width, height)
        const data = calculateExposureHistogram(context.getImageData(0, 0, width, height).data)
        setState({ status: 'ready', data })
      } catch (reason) {
        setState({ status: 'error', message: (reason as Error).message || '无法分析图片曝光信息' })
      }
    }
    image.onerror = () => {
      if (active) setState({ status: 'error', message: '预览图加载失败，无法生成曝光直方图。' })
    }
    image.src = imageUrl

    return () => {
      active = false
      image.onload = null
      image.onerror = null
      image.src = ''
    }
  }, [imageUrl])

  if (state.status === 'loading') {
    return <Skeleton active paragraph={{ rows: 3 }} title={false} />
  }
  if (state.status === 'error') {
    return <Alert type="info" showIcon message={state.message} />
  }

  const { data } = state
  const maximum = Math.max(1, ...data.red, ...data.green, ...data.blue, ...data.luminance)
  return <div className="exposure-histogram">
    <svg viewBox={`0 0 256 ${GRAPH_HEIGHT}`} role="img"
      aria-label={`${alt}的曝光直方图，阴影削切 ${percent(data.shadowPercent)}，高光削切 ${percent(data.highlightPercent)}`}>
      <title>{alt}的曝光直方图</title>
      <g className="histogram-grid" aria-hidden="true">
        <line x1="64" y1="0" x2="64" y2={GRAPH_HEIGHT} />
        <line x1="128" y1="0" x2="128" y2={GRAPH_HEIGHT} />
        <line x1="192" y1="0" x2="192" y2={GRAPH_HEIGHT} />
      </g>
      <path className="histogram-luminance" d={areaPath(data.luminance, maximum)} />
      <path className="histogram-red" d={linePath(data.red, maximum)} />
      <path className="histogram-green" d={linePath(data.green, maximum)} />
      <path className="histogram-blue" d={linePath(data.blue, maximum)} />
    </svg>
    <div className="histogram-axis" aria-hidden="true"><span>阴影</span><span>中间调</span><span>高光</span></div>
    <Space className="histogram-legend" size={[14, 6]} wrap>
      <span><i className="histogram-key luminance" />亮度</span>
      <span><i className="histogram-key red" />红</span>
      <span><i className="histogram-key green" />绿</span>
      <span><i className="histogram-key blue" />蓝</span>
    </Space>
    <div className="histogram-summary">
      <div><Typography.Text type="secondary">平均亮度</Typography.Text><strong>{Math.round(data.meanLuminance)} / 255</strong></div>
      <div><Typography.Text type="secondary">阴影削切</Typography.Text><strong>{percent(data.shadowPercent)}</strong></div>
      <div><Typography.Text type="secondary">高光削切</Typography.Text><strong>{percent(data.highlightPercent)}</strong></div>
    </div>
  </div>
}
