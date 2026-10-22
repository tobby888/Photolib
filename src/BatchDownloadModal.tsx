import { Button, Modal, Progress, Space, Typography } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  BatchDownloadCancelled, describeExportProgress, type ExportJobProgress, type WaitOptions,
} from './batchDownloadProgress'

type Stage = 'running' | 'done' | 'failed'

interface ProgressState {
  stage: Stage
  count: number
  job?: ExportJobProgress
  url?: string
  error?: string
}

export interface RunOptions {
  /**
   * 失败时先问页面要不要自己处理（例如分享会话失效要退回输密码那一步）；
   * 返回 true 就直接关掉弹窗，不再在弹窗里显示失败。
   */
  handleError?: (error: unknown) => boolean
}

/** 打包成功后弹窗停留多久再自动关上，给"没开始下载"的人留出点链接的时间。 */
const CLOSE_AFTER_DONE_MS = 4000

/**
 * 打包下载的进度弹窗。页面调 `run(张数, start)`，`start` 拿到 `{ signal, onProgress }`
 * 去建任务、轮询，返回下载地址；弹窗负责显示进度、成功后触发下载、失败时说明原因，
 * 「停止等待」会中断轮询（后端那条任务照常跑完，只是不再自动下载）。
 *
 * 返回 `true` 表示已经开始下载，页面据此清空勾选。
 */
export function useBatchDownloadProgress() {
  const [state, setState] = useState<ProgressState | null>(null)
  const controller = useRef<AbortController | null>(null)
  const closeTimer = useRef<number | undefined>(undefined)

  const clearCloseTimer = () => {
    if (closeTimer.current !== undefined) window.clearTimeout(closeTimer.current)
    closeTimer.current = undefined
  }

  const close = useCallback(() => {
    controller.current?.abort()
    controller.current = null
    clearCloseTimer()
    setState(null)
  }, [])

  useEffect(() => () => {
    controller.current?.abort()
    clearCloseTimer()
  }, [])

  const run = useCallback(async (
    count: number,
    start: (options: Pick<WaitOptions, 'onProgress' | 'signal'>) => Promise<string>,
    options: RunOptions = {},
  ): Promise<boolean> => {
    controller.current?.abort()
    clearCloseTimer()
    const current = new AbortController()
    controller.current = current
    setState({ stage: 'running', count })
    try {
      const url = await start({
        signal: current.signal,
        onProgress: job => {
          if (!current.signal.aborted) setState(previous => previous && { ...previous, job })
        },
      })
      if (current.signal.aborted) return false
      setState(previous => ({ ...(previous ?? { count }), stage: 'done', url }))
      window.location.assign(url)
      closeTimer.current = window.setTimeout(close, CLOSE_AFTER_DONE_MS)
      return true
    } catch (error) {
      if (error instanceof BatchDownloadCancelled || current.signal.aborted) return false
      if (options.handleError?.(error)) {
        setState(null)
        return false
      }
      setState(previous => ({
        ...(previous ?? { count }), stage: 'failed',
        error: (error as Error).message || '打包失败，请稍后重试',
      }))
      return false
    } finally {
      if (controller.current === current) controller.current = null
    }
  }, [close])

  const progress = state && describeExportProgress(
    state.stage === 'done' ? { ...(state.job ?? {}), status: 'SUCCEEDED' } : state.job, state.count)

  const modal = <Modal
    open={Boolean(state)}
    title={`打包下载 ${state?.count ?? 0} 张图片`}
    closable
    maskClosable={state?.stage !== 'running'}
    keyboard={state?.stage !== 'running'}
    onCancel={close}
    destroyOnHidden
    footer={state?.stage === 'running'
      ? <Button onClick={close}>停止等待</Button>
      : <Button type="primary" onClick={close}>关闭</Button>}>
    {state && progress && <Space direction="vertical" size={10} style={{ width: '100%' }}>
      <Progress
        percent={progress.percent}
        status={state.stage === 'failed' ? 'exception' : state.stage === 'done' ? 'success' : 'active'} />
      <Typography.Text strong>{state.stage === 'failed' ? '打包失败' : progress.title}</Typography.Text>
      {state.stage === 'running' && progress.detail && <Typography.Text type="secondary">{progress.detail}</Typography.Text>}
      {state.stage === 'running' && <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        图片多时要等几分钟，可以先做别的事，但请不要关闭这个页面。停止等待后后台不再自动下载，需要重新发起。
      </Typography.Text>}
      {state.stage === 'failed' && <Typography.Text type="danger">{state.error}</Typography.Text>}
      {state.stage === 'done' && state.url && <Typography.Text type="secondary">
        浏览器没有开始下载？<Typography.Link href={state.url}>点这里下载 ZIP</Typography.Link>
      </Typography.Text>}
    </Space>}
  </Modal>

  return { run, modal, running: state?.stage === 'running' }
}
