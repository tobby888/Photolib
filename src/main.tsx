import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { HashRouter } from 'react-router-dom'
import { AuthProvider } from './auth'
import App from './App'
import AppErrorBoundary from './AppErrorBoundary'
import './styles.css'

const PRELOAD_RELOAD_KEY = 'photolib_preload_reload'
window.addEventListener('vite:preloadError', (event) => {
  const lastReload = Number(sessionStorage.getItem(PRELOAD_RELOAD_KEY) || 0)
  if (Date.now() - lastReload < 30_000) return

  event.preventDefault()
  sessionStorage.setItem(PRELOAD_RELOAD_KEY, Date.now().toString())
  const url = new URL(window.location.href)
  url.searchParams.set('_reload', Date.now().toString())
  window.location.replace(url)
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppErrorBoundary>
      <ConfigProvider locale={zhCN} theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#00e7f2',
          colorInfo: '#00d9ff',
          colorSuccess: '#65f6bd',
          colorWarning: '#f4bf68',
          colorError: '#ff6b88',
          colorLink: '#55e9ff',
          colorText: '#e7f7ff',
          colorTextSecondary: '#7f94aa',
          colorBorder: 'rgba(92, 224, 255, .18)',
          colorBgBase: '#050914',
          colorBgLayout: '#050914',
          colorBgContainer: '#0b1625',
          colorBgElevated: '#101d2e',
          borderRadius: 14,
          controlHeight: 38,
          boxShadowSecondary: '0 24px 64px rgba(0, 0, 0, .38), 0 0 32px rgba(0, 231, 242, .06)',
          fontFamily: '"PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", system-ui, sans-serif',
        },
        components: {
          Layout: { bodyBg: '#050914', siderBg: '#070e1a', headerBg: 'rgba(7,14,26,.78)' },
          Menu: { darkItemBg: 'transparent', darkItemSelectedBg: 'rgba(0,231,242,.13)', darkItemSelectedColor: '#dffcff', darkItemHoverBg: 'rgba(139,92,246,.1)' },
          Card: { headerBg: 'transparent' },
          Button: { primaryShadow: '0 0 24px rgba(0,231,242,.2)' },
          Table: { headerBg: 'rgba(15,32,50,.9)', headerColor: '#91a9bd', rowHoverBg: 'rgba(0,231,242,.055)' },
        },
      }}>
        <AntApp>
          <HashRouter>
            <AuthProvider><App /></AuthProvider>
          </HashRouter>
        </AntApp>
      </ConfigProvider>
    </AppErrorBoundary>
  </React.StrictMode>,
)
