import { Component, type ErrorInfo, type PropsWithChildren } from 'react'
import { browserCacheHost, cacheBustedUrl, clearClientCaches } from './clientCacheReset'

interface State {
  error: Error | null
  /** 清缓存会顺带退出登录，所以按钮按一下先变成确认态，不直接动手。 */
  phase: 'idle' | 'confirming' | 'clearing'
}

export default class AppErrorBoundary extends Component<PropsWithChildren, State> {
  state: State = { error: null, phase: 'idle' }

  static getDerivedStateFromError(error: Error): State {
    return { error, phase: 'idle' }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('页面渲染失败', error, info)
  }

  private reload = () => {
    window.location.replace(cacheBustedUrl(window.location.href))
  }

  private clearAndReload = async () => {
    this.setState({ phase: 'clearing' })
    const result = await clearClientCaches(browserCacheHost())
    // 留一行在控制台：清不干净时用户也只会说"还是打不开"，没有这行就没法判断是
    // 缓存没清掉，还是清了也没用（那说明问题不在浏览器这一侧）。
    console.info('已清除本站缓存', result)
    this.reload()
  }

  render() {
    if (!this.state.error) return this.props.children

    const clearing = this.state.phase === 'clearing'
    return <main className="fatal-error">
      <section>
        <h1>页面加载失败</h1>
        <p>可能是网站刚刚更新，或部分资源暂时没有加载完成。</p>
        <div className="fatal-error-actions">
          <button type="button" onClick={this.reload} disabled={clearing}>重新加载最新版本</button>
          {this.state.phase === 'idle'
            ? <button type="button" className="ghost" onClick={() => this.setState({ phase: 'confirming' })}>
                清除本站缓存并重新加载
              </button>
            : <button type="button" className="ghost" onClick={this.clearAndReload} disabled={clearing}>
                {clearing ? '正在清除…' : '确认清除并重新加载'}
              </button>}
        </div>
        {this.state.phase === 'idle'
          ? <p className="fatal-error-hint">重新加载还是这一页，就试试清除缓存——它只清本站在这台浏览器上存的东西。</p>
          : <p className="fatal-error-hint">
              将清除本站的离线缓存和本地数据，<strong>清除后需要重新登录</strong>；已下载或已上传的图片不受影响。
              若清完仍打不开，请按 Ctrl+Shift+R（macOS 为 Shift+Command+R）强制刷新。
            </p>}
      </section>
    </main>
  }
}
