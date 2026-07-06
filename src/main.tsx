import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
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
        token: {
          colorPrimary: '#1f594c',
          colorInfo: '#1f594c',
          colorSuccess: '#34745f',
          colorWarning: '#c98837',
          colorError: '#b84f4f',
          colorText: '#202b28',
          colorTextSecondary: '#6f7975',
          colorBorder: '#e4e3dc',
          colorBgLayout: '#f7f7f3',
          colorBgContainer: '#fffefa',
          borderRadius: 12,
          controlHeight: 38,
          boxShadowSecondary: '0 18px 45px rgba(25, 49, 42, .08)',
          fontFamily: '"PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", system-ui, sans-serif',
        },
        components: {
          Layout: { bodyBg: '#f7f7f3', siderBg: '#123d34', headerBg: 'rgba(247,247,243,.88)' },
          Menu: { darkItemBg: '#123d34', darkItemSelectedBg: '#e9ba70', darkItemSelectedColor: '#153c34', darkItemHoverBg: 'rgba(255,255,255,.08)' },
          Card: { headerBg: 'transparent' },
          Button: { primaryShadow: 'none' },
          Table: { headerBg: '#f4f5f1', headerColor: '#56615d' },
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
