import { Button, Result, Typography } from 'antd'
import { Component, Fragment, type ErrorInfo, type PropsWithChildren } from 'react'
import { cacheBustedUrl } from './clientCacheReset'
import { isChunkLoadError, isReloadPending } from './routeErrors'

interface State {
  error: Error | null
  /** 每次"重试"加一，作为子树的 key，逼它整棵重新挂载而不是沿用崩掉前的状态。 */
  attempt: number
}

/**
 * 工作台内容区的错误边界。
 *
 * 没有它的话，任何一页渲染时抛出的异常都会一路冒到 `main.tsx` 的 `AppErrorBoundary`，
 * 整个应用——侧栏、顶栏、消息铃铛——被换成"页面加载失败 / 清除本站缓存"的全屏页。
 * 那一页是给"整个站打不开"准备的，而且它一旦出现就不会自己消失：换到别的菜单、
 * 再点回来，看到的还是它，用户只剩清缓存这一条路。
 *
 * 所以外壳把路由包在这里：一页崩了只换掉这一页的内容区，侧栏照常能点。它挂在
 * `route-stage` 里面，而那一层按 `pathname` 做 key，换一页就是一个全新的边界，
 * 上一页的错误不会跟着带过去。
 */
export default class RouteErrorBoundary extends Component<PropsWithChildren, State> {
  state: State = { error: null, attempt: 0 }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('页面渲染失败', error, info)
  }

  private retry = () => {
    this.setState(({ attempt }) => ({ error: null, attempt: attempt + 1 }))
  }

  private reload = () => {
    window.location.replace(cacheBustedUrl(window.location.href))
  }

  render() {
    const { error, attempt } = this.state
    if (!error) return <Fragment key={attempt}>{this.props.children}</Fragment>
    // 整页重载已经在路上了，报错只是被拦下的那次 import 留下的尾巴。
    if (isReloadPending()) return <div className="route-loading">正在加载最新版本…</div>

    const chunk = isChunkLoadError(error)
    return <Result className="route-error" status="warning"
      title={chunk ? '这一页的资源没有加载完成' : '这一页没能正常显示'}
      subTitle={chunk
        ? '可能是网站刚刚更新，或网络暂时不稳定。重新加载即可拿到最新版本。'
        : '其他页面不受影响，可以先重试一次；如果反复出现，请把下面这行报错发给管理员。'}
      extra={chunk
        ? <Button type="primary" onClick={this.reload}>重新加载</Button>
        : [
            <Button key="retry" type="primary" onClick={this.retry}>重试</Button>,
            <Button key="reload" onClick={this.reload}>重新加载页面</Button>,
          ]}>
      <Typography.Text type="secondary" className="route-error-detail" copyable>
        {error.message || String(error)}
      </Typography.Text>
    </Result>
  }
}
