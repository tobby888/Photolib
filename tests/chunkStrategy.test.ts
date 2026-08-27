import assert from 'node:assert/strict'
import test from 'node:test'
import { createManualChunks, packageNameOf, reachableFromEntry, type ModuleGraph } from '../build/chunkStrategy.ts'

const modules = 'C:/app/node_modules'
const entry = 'C:/app/src/main.tsx'
const shell = 'C:/app/src/App.tsx'
const route = 'C:/app/src/pages/PhotosPage.tsx'

/** main.tsx -> App.tsx -> Menu; PhotosPage is only ever imported lazily. */
const graph: ModuleGraph = {
  getModuleInfo(id) {
    const importers: Record<string, string[]> = {
      [entry]: [],
      [shell]: [entry],
      [route]: [],
      [`${modules}/antd/es/menu/index.js`]: [shell],
      [`${modules}/antd/es/table/index.js`]: [route],
      [`${modules}/@rc-component/trigger/es/index.js`]: [`${modules}/antd/es/menu/index.js`],
      [`${modules}/@rc-component/table/es/index.js`]: [`${modules}/antd/es/table/index.js`],
      [`${modules}/@ant-design/icons/es/icons/BellOutlined.js`]: [shell],
      [`${modules}/react-dom/client.js`]: [entry],
      [`${modules}/axios/index.js`]: [shell],
      [`${modules}/exifr/dist/full.esm.mjs`]: [route],
    }
    return importers[id] ? { isEntry: id === entry, importers: importers[id] } : null
  },
}

test('package names survive scopes and Windows paths', () => {
  assert.equal(packageNameOf(`${modules}/@rc-component/select/es/index.js`), '@rc-component/select')
  assert.equal(packageNameOf(`${modules}/antd/es/menu/index.js`), 'antd')
  assert.equal(packageNameOf('C:\\app\\node_modules\\dayjs\\index.js'), 'dayjs')
  assert.equal(packageNameOf('C:/app/src/App.tsx'), undefined)
})

test('shell reachability follows static importers up to the entry only', () => {
  assert.equal(reachableFromEntry(`${modules}/antd/es/menu/index.js`, graph), true)
  assert.equal(reachableFromEntry(`${modules}/@rc-component/trigger/es/index.js`, graph), true)
  // Reached only through a lazily imported route: not part of the first paint.
  assert.equal(reachableFromEntry(`${modules}/antd/es/table/index.js`, graph), false)
  assert.equal(reachableFromEntry(`${modules}/@rc-component/table/es/index.js`, graph), false)
})

test('vendor chunks cover the shell, route-only dependencies stay with their route', () => {
  const chunkOf = createManualChunks()
  assert.equal(chunkOf(`${modules}/react-dom/client.js`, graph), 'react')
  assert.equal(chunkOf(`${modules}/antd/es/menu/index.js`, graph), 'antd')
  assert.equal(chunkOf(`${modules}/@rc-component/trigger/es/index.js`, graph), 'antd-base')
  assert.equal(chunkOf(`${modules}/@ant-design/icons/es/icons/BellOutlined.js`, graph), 'antd-icons')
  assert.equal(chunkOf(`${modules}/axios/index.js`, graph), 'vendor')
  // Route-only dependencies are left to Rollup, which splits them per route.
  assert.equal(chunkOf(`${modules}/antd/es/table/index.js`, graph), undefined)
  assert.equal(chunkOf(`${modules}/exifr/dist/full.esm.mjs`, graph), undefined)
  assert.equal(chunkOf(shell, graph), undefined)
})

test('React is bundled apart even when a route is the only thing that needs it', () => {
  const chunkOf = createManualChunks()
  const lazyOnly: ModuleGraph = { getModuleInfo: () => ({ isEntry: false, importers: [] }) }
  assert.equal(chunkOf(`${modules}/react-dom/client.js`, lazyOnly), 'react')
  assert.equal(chunkOf(`${modules}/antd/es/menu/index.js`, lazyOnly), undefined)
})
