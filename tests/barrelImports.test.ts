import assert from 'node:assert/strict'
import test from 'node:test'
import { iconBarrelExports, parseBarrelExports, rewriteBarrelImports } from '../build/barrelImports.ts'

const antd = parseBarrelExports(`
"use client";
import warning from './_util/warning';
export { default as Alert } from './alert';
export { default as App } from './app';
export { default as ConfigProvider } from './config-provider';
export { default as Table } from './table';
export const unstableSetRender = () => {};
`, 'antd')
const icons = iconBarrelExports(['BellOutlined.js', 'BellOutlined.d.ts', 'UserOutlined.js'], '@ant-design/icons')
const barrels = { antd, '@ant-design/icons': icons }

test('barrel exports map an exported name to the module that owns it', () => {
  assert.deepEqual(antd, {
    Alert: 'antd/es/alert',
    App: 'antd/es/app',
    ConfigProvider: 'antd/es/config-provider',
    Table: 'antd/es/table',
  })
  assert.deepEqual(icons, {
    BellOutlined: '@ant-design/icons/es/icons/BellOutlined',
    UserOutlined: '@ant-design/icons/es/icons/UserOutlined',
  })
})

test('named imports become deep imports, aliases included', () => {
  assert.equal(
    rewriteBarrelImports("import { Alert, App as AntApp } from 'antd'\n", barrels),
    "import { default as Alert } from 'antd/es/alert'; import { default as AntApp } from 'antd/es/app';\n",
  )
  assert.equal(
    rewriteBarrelImports("import { BellOutlined } from '@ant-design/icons'", barrels),
    "import { default as BellOutlined } from '@ant-design/icons/es/icons/BellOutlined';",
  )
})

test('a multi-line import keeps its line count so line numbers still match the source', () => {
  const rewritten = rewriteBarrelImports("import {\n  Alert,\n  Table,\n} from 'antd'\nconst x = 1\n", barrels)
  assert.equal(rewritten?.split('\n')[4], 'const x = 1')
})

test('what the map does not cover stays on the barrel, so behaviour never depends on the map', () => {
  // `version` and type-only bindings have no deep module here; they must survive untouched.
  assert.equal(
    rewriteBarrelImports("import { Alert, version, type TableProps } from 'antd'", barrels),
    "import { default as Alert } from 'antd/es/alert'; import { version, type TableProps } from 'antd';",
  )
  assert.equal(rewriteBarrelImports("import type { TableProps } from 'antd'", barrels), null)
  assert.equal(rewriteBarrelImports("import { useState } from 'react'", barrels), null)
  assert.equal(rewriteBarrelImports("import zhCN from 'antd/locale/zh_CN'", barrels), null)
  assert.equal(rewriteBarrelImports("// import { Alert } from 'antd' in a comment", barrels), null)
})
