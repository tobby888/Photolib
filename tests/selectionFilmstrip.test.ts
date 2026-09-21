import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const styles = async () =>
  (await readFile(new URL('../src/styles.css', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')

/**
 * 选片页左边那列缩略图（`.selection-filmstrip`）。它是一个**固定高度**的 flex 列，
 * 而 flex 项默认可以被压扁（`flex-shrink: 1`）；缩略图自己又是 `overflow: hidden`，
 * 这让它的自动最小尺寸变成 0，于是几十张图会被一起压成几像素高的细条，容器反而没有溢出、
 * 也就滚不动。浏览器里量过：40 张图时每张只有 4px 高、`scrollHeight === clientHeight`；
 * 加上 `flex: 0 0 auto` 之后每张 77px、这一列正常滚动。
 */
test('左列缩略图保持自己的尺寸，让这一列滚动而不是把图压扁', async () => {
  assert.match(await styles(), /\.selection-filmstrip > \* \{ flex: 0 0 auto; \}/)
})

test('左列仍然是可滚动的固定高度容器', async () => {
  const css = await styles()
  const filmstrip = css.slice(css.indexOf('.selection-filmstrip {'), css.indexOf('.selection-thumb {'))

  assert.match(filmstrip, /overflow-y: auto/)
  assert.match(filmstrip, /flex-direction: column/)
  // 工作区写成固定高度，否则左列会把页面拉得几十屏长、右边的大图被推出视野。
  assert.match(css, /\.selection-workspace \{[^}]*height: calc\(100vh - 400px\)/)
})

test('窄屏上横着摆的那条仍然限定在左列里，图墙的格子不受影响', async () => {
  const css = await styles()

  assert.match(css, /\.selection-filmstrip \{ flex-direction: row; overflow-x: auto/)
  // 这条规则比 `.selection-filmstrip > *` 更具体，所以横向时的 140px 宽不会被覆盖掉。
  assert.match(css, /\.selection-filmstrip \.selection-thumb \{ width: 140px; flex: 0 0 140px;/)
})
