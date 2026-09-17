import assert from 'node:assert/strict'
import test from 'node:test'
import {
  DEFAULT_PHOTO_CARD_SHORTCUTS, formatShortcutKey, matchPhotoCardShortcut, readPhotoCardShortcuts,
  savePhotoCardShortcuts, validatePhotoCardShortcuts, validateShortcutKey,
} from '../src/photoCardShortcuts.ts'

const press = (key: string, modifiers: Partial<Record<'ctrlKey' | 'altKey' | 'metaKey', boolean>> = {}) =>
  ({ key, ctrlKey: false, altKey: false, metaKey: false, ...modifiers })

function withStorage(run: (store: Map<string, string>) => void) {
  const store = new Map<string, string>()
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => { store.set(key, value) },
      removeItem: (key: string) => { store.delete(key) },
    },
  })
  try {
    run(store)
  } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original)
    else delete (globalThis as { localStorage?: unknown }).localStorage
  }
}

test('默认 Enter 打开、空格选择', () => {
  assert.equal(matchPhotoCardShortcut(press('Enter'), DEFAULT_PHOTO_CARD_SHORTCUTS), 'open')
  assert.equal(matchPhotoCardShortcut(press(' '), DEFAULT_PHOTO_CARD_SHORTCUTS), 'select')
  assert.equal(matchPhotoCardShortcut(press('a'), DEFAULT_PHOTO_CARD_SHORTCUTS), null)
})

test('自定义字母键不分大小写，Shift 连选和大写锁定时也能命中', () => {
  const shortcuts = { open: 'o', select: 'x' }
  assert.equal(matchPhotoCardShortcut(press('O'), shortcuts), 'open')
  assert.equal(matchPhotoCardShortcut(press('X'), shortcuts), 'select')
  assert.equal(matchPhotoCardShortcut(press('Enter'), shortcuts), null)
})

test('按住 Ctrl / Alt / ⌘ 时不接管，免得吃掉浏览器快捷键', () => {
  const shortcuts = { open: 'o', select: 'a' }
  assert.equal(matchPhotoCardShortcut(press('a', { ctrlKey: true }), shortcuts), null)
  assert.equal(matchPhotoCardShortcut(press('o', { metaKey: true }), shortcuts), null)
  assert.equal(matchPhotoCardShortcut(press('o', { altKey: true }), shortcuts), null)
})

test('Tab、Esc 和单独的修饰键不能设为快捷键；两个动作不能同键', () => {
  assert.ok(validateShortcutKey('Tab'))
  assert.ok(validateShortcutKey('Escape'))
  assert.ok(validateShortcutKey('Shift'))
  assert.ok(validateShortcutKey(''))
  assert.equal(validateShortcutKey('ArrowRight'), null)
  assert.ok(validatePhotoCardShortcuts({ open: 'a', select: 'A' }))
  assert.equal(validatePhotoCardShortcuts({ open: 'o', select: 'x' }), null)
})

test('按键名：空格、方向键和字母给出可读的名字', () => {
  assert.equal(formatShortcutKey(' '), '空格')
  assert.equal(formatShortcutKey('ArrowLeft'), '←')
  assert.equal(formatShortcutKey('k'), 'K')
  assert.equal(formatShortcutKey('F2'), 'F2')
})

test('保存后读回；恢复默认时清掉存储项', () => {
  withStorage(store => {
    assert.deepEqual(readPhotoCardShortcuts(), DEFAULT_PHOTO_CARD_SHORTCUTS)
    savePhotoCardShortcuts({ open: 'O', select: 'x' })
    assert.deepEqual(readPhotoCardShortcuts(), { open: 'o', select: 'x' })
    savePhotoCardShortcuts(DEFAULT_PHOTO_CARD_SHORTCUTS)
    assert.equal(store.size, 0)
    assert.deepEqual(readPhotoCardShortcuts(), DEFAULT_PHOTO_CARD_SHORTCUTS)
  })
})

test('存储里的数据损坏或不合法时退回默认值，保存非法设置直接拒绝', () => {
  withStorage(store => {
    store.set('photolib_photo_card_shortcuts', '{oops')
    assert.deepEqual(readPhotoCardShortcuts(), DEFAULT_PHOTO_CARD_SHORTCUTS)
    store.set('photolib_photo_card_shortcuts', JSON.stringify({ open: 'Tab', select: ' ' }))
    assert.deepEqual(readPhotoCardShortcuts(), DEFAULT_PHOTO_CARD_SHORTCUTS)
    store.set('photolib_photo_card_shortcuts', JSON.stringify({ open: 'a', select: 'a' }))
    assert.deepEqual(readPhotoCardShortcuts(), DEFAULT_PHOTO_CARD_SHORTCUTS)
    assert.throws(() => savePhotoCardShortcuts({ open: 'a', select: 'a' }))
  })
})
