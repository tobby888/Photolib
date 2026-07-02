import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { HashRouter } from 'react-router-dom'
import { AuthProvider } from './auth'
import App from './App'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{
      token: {
        colorPrimary: '#28594f',
        colorInfo: '#28594f',
        colorSuccess: '#3f7b65',
        colorWarning: '#c57a3d',
        colorError: '#b34f4f',
        borderRadius: 10,
        fontFamily: '"PingFang SC", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", system-ui, sans-serif',
      },
      components: {
        Layout: { bodyBg: '#f6f4ef', siderBg: '#173b35', headerBg: '#f6f4ef' },
        Menu: { darkItemBg: '#173b35', darkItemSelectedBg: '#f0b66d', darkItemSelectedColor: '#173b35' },
        Card: { headerBg: 'transparent' },
      },
    }}>
      <AntApp>
        <HashRouter>
          <AuthProvider><App /></AuthProvider>
        </HashRouter>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)
