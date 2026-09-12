import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (file: string) => readFile(new URL(`../src/${file}`, import.meta.url), 'utf8')

/** 勾选框方框的边长：antd 6 用 `controlInteractiveSize = controlHeight / 2`，见 main.tsx。 */
const CHECKBOX_SIZE = 20
/** 触摸目标的下限，44px 取自 WCAG 2.5.5。 */
const MIN_HIT_AREA = 44

/** 在图片卡片上放勾选框的页面，它们共用 `.photo-select-checkbox`。 */
const PAGES_WITH_PHOTO_CHECKBOX = [
  'pages/PhotosPage.tsx',
  'pages/ProjectDetailPage.tsx',
  'pages/SharedProjectPage.tsx',
]

test('勾选框的可点区域不小于 44×44，且不挪动勾选框本身', async () => {
  const styles = await read('styles.css')
  const rule = /\.photo-overlay > \.photo-select-checkbox \{([^}]*)\}/.exec(styles)
  // 选择器要压过 antd `.ant-checkbox-wrapper` 自带的 margin/padding 归零，只写一个类名赢不了。
  assert.ok(rule, '找不到 .photo-overlay > .photo-select-checkbox 的规则')
  const padding = Number(/padding:\s*(-?\d+)px/.exec(rule[1])?.[1])
  const margin = Number(/margin:\s*(-?\d+)px/.exec(rule[1])?.[1])
  assert.ok(
    CHECKBOX_SIZE + padding * 2 >= MIN_HIT_AREA,
    `可点区域只有 ${CHECKBOX_SIZE + padding * 2}px，点偏一点就落到封面上了`,
  )
  // 负外边距要等量抵消内边距，否则勾选框会往封面里挪，和右侧的圆形按钮错位。
  assert.equal(margin, -padding)
})

test('勾选框的高对比样式打在 antd 6 真实存在的节点上', async () => {
  const styles = await read('styles.css')
  // antd 6 的 Checkbox 只渲染 label > span.ant-checkbox > input，没有 `-inner`：
  // 打在 `.ant-checkbox-inner` 上的规则是死代码，压在照片上的勾选框会失去对比度。
  assert.doesNotMatch(styles, /\.photo-select-checkbox[^{]*\.ant-checkbox-inner/)
  assert.match(styles, /\.photo-select-checkbox \.ant-checkbox:not\(\.ant-checkbox-checked\) \{/)
})

test('图库 / 选题 / 分享页的勾选框都走同一套放大后的样式', async () => {
  for (const page of PAGES_WITH_PHOTO_CHECKBOX) {
    const source = await read(page)
    assert.match(source, /className="photo-select-checkbox"/, `${page} 的勾选框没有用放大后的样式`)
  }
})
