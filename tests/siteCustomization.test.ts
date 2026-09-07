import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { pickPlaceholderImage } from '../src/placeholderImages.ts'

const read = (path: string) => readFile(new URL(`../src/${path}`, import.meta.url), 'utf8')
const images = ['/a.png', '/b.png', '/c.png']

test('没有配置占位图时不给出地址，界面退回内置的灰底占位', () => {
  assert.equal(pickPlaceholderImage([], 'photo-1'), undefined)
  assert.equal(pickPlaceholderImage([]), undefined)
})

test('同一张图片每次都落到同一张占位图上，列表重渲染不会闪', () => {
  const first = pickPlaceholderImage(images, 'photo-1')

  assert.ok(first && images.includes(first))
  for (let attempt = 0; attempt < 20; attempt += 1) {
    assert.equal(pickPlaceholderImage(images, 'photo-1'), first)
  }
})

test('不同图片会散开到不同占位图，而不是全都用第一张', () => {
  const picked = new Set(Array.from({ length: 60 }, (_, index) =>
    pickPlaceholderImage(images, `photo-${index}`)))

  assert.equal(picked.size, images.length)
})

test('数字 ID 和字符串 ID 取到同一张，两种写法不会各挑各的', () => {
  assert.equal(pickPlaceholderImage(images, 42), pickPlaceholderImage(images, '42'))
})

test('没有 seed 时随机取一张，但始终落在已配置的图片里', () => {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const picked = pickPlaceholderImage(images)
    assert.ok(picked && images.includes(picked))
  }
})

test('图片加载失败也要顶上占位图，而不是留一个碎图图标', async () => {
  const [photos, project, delivery, featured] = await Promise.all([
    read('pages/PhotosPage.tsx'), read('pages/ProjectDetailPage.tsx'),
    read('pages/RequestDeliveryPage.tsx'), read('pages/FeaturedCollectionDetailPage.tsx'),
  ])

  // 这是回归点：只处理"没有预览地址"是不够的，对象存储故障或签名过期时
  // 地址是有的、取不回来，antd Image 的 fallback 和 <img> 的 onError 才是那一条路。
  for (const source of [photos, project, delivery]) {
    assert.match(source, /fallback=\{pickPlaceholderImage\(placeholderImages, photo\.id\)\}/)
  }
  assert.match(featured, /onError=\{event => \{/)
  assert.match(featured, /image\.dataset\.placeholder/)
})

test('已从图库删除的精选图片换成占位图之后，仍然写明它已被删除', async () => {
  const featured = await read('pages/FeaturedCollectionDetailPage.tsx')

  assert.match(featured, /note=\{entry\.photoAvailable \? '预览暂不可用' : '图片已从图库删除'\}/)
})

test('登录页文案全部来自后台配置，页面里不再写死任何一句', async () => {
  const login = await read('pages/LoginPage.tsx')

  assert.match(login, /\{headline && <Typography\.Title/)
  assert.match(login, /\{subheadline && <Typography\.Paragraph>/)
  assert.match(login, /highlights\.map/)
  assert.match(login, /\{notice && <Typography\.Text className="login-help">/)
  assert.doesNotMatch(login, /让每一次快门/)
  assert.doesNotMatch(login, /首次登录后，系统会引导你修改初始密码/)
})

test('页脚对每个角色都渲染：工作台、未分配权限组的账号和登录页各挂一处', async () => {
  const [app, login, footer] = await Promise.all([
    read('App.tsx'), read('pages/LoginPage.tsx'), read('SiteFooter.tsx'),
  ])

  assert.match(app, /<SiteFooter className="shell-footer" \/>/)
  // 没有权限组的账号进不了工作台，但它同样是"任何角色的用户"。
  assert.match(app, /<SiteFooter \/>/)
  assert.match(login, /<SiteFooter className="login-footer" \/>/)
  // 两项都空就整块不渲染，免得给没配过的部署留一条空白横条。
  assert.match(footer, /if \(!text && !links\.length\) return null/)
})
