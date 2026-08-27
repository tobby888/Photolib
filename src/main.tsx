import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { HashRouter } from 'react-router-dom'
import { AuthProvider } from './auth'
import { BrandingProvider } from './branding'
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
        algorithm: theme.defaultAlgorithm,
        token: {
          colorPrimary: '#4682B4',
          colorInfo: '#4682B4',
          colorSuccess: '#4682B4',
          colorWarning: '#b26a13',
          colorError: '#c43843',
          colorLink: '#4682B4',
          colorText: '#20384b',
          colorTextSecondary: '#60798a',
          colorBorder: '#B0E0E6',
          colorBgBase: '#ffffff',
          colorBgLayout: '#E0FFFF',
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          borderRadius: 12,
          controlHeight: 40,
          boxShadowSecondary: '0 18px 48px rgba(70, 130, 180, .16)',
          fontFamily: '"PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", system-ui, sans-serif',
        },
        components: {
          Layout: { bodyBg: '#E0FFFF', siderBg: '#4682B4', headerBg: '#ffffff' },
          Menu: {
            itemBg: 'transparent',
            itemColor: '#456174',
            itemHoverBg: '#E0FFFF',
            itemHoverColor: '#4682B4',
            itemSelectedBg: '#E0FFFF',
            itemSelectedColor: '#4682B4',
          },
          Card: { headerBg: 'transparent' },
          Button: { primaryShadow: 'none' },
          Table: { headerBg: '#E0FFFF', headerColor: '#315f86', rowHoverBg: '#f2ffff' },
        },
      }}>
        <AntApp>
          <HashRouter>
            <BrandingProvider><AuthProvider><App /></AuthProvider></BrandingProvider>
          </HashRouter>
        </AntApp>
      </ConfigProvider>
    </AppErrorBoundary>
  </React.StrictMode>,
)
