import { Component, type ErrorInfo, type PropsWithChildren } from 'react'

interface State {
  error: Error | null
}

export default class AppErrorBoundary extends Component<PropsWithChildren, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('页面渲染失败', error, info)
  }

  private reload = () => {
    const url = new URL(window.location.href)
    url.searchParams.set('_reload', Date.now().toString())
    window.location.replace(url)
  }

  render() {
    if (!this.state.error) return this.props.children

    return <main className="fatal-error">
      <section>
        <h1>页面加载失败</h1>
        <p>可能是网站刚刚更新，或部分资源暂时没有加载完成。</p>
        <button type="button" onClick={this.reload}>重新加载最新版本</button>
      </section>
    </main>
  }
}
