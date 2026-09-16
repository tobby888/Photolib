import assert from 'node:assert/strict'
import test from 'node:test'
import {
  clearTagHistory, readRecentTags, recordTagSearch, TAG_HISTORY_LIMIT,
} from '../src/photoTagHistory.ts'

function installLocalStorage() {
  const data = new Map<string, string>()
  const storage = {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value) },
    removeItem: (key: string) => { data.delete(key) },
  }
  Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true })
  return storage
}

test('recent tags are persisted, deduplicated, newest-first, and scoped', () => {
  installLocalStorage()

  assert.deepEqual(readRecentTags('library-tags'), [])
  assert.deepEqual(recordTagSearch('library-tags', ['合影', ' 颁奖 ']), ['合影', '颁奖'])
  assert.deepEqual(recordTagSearch('library-tags', ['开幕式', '合影']), ['开幕式', '合影', '颁奖'])
  assert.deepEqual(readRecentTags('project:1'), [])
  assert.deepEqual(readRecentTags('library-tags'), ['开幕式', '合影', '颁奖'])
})

test('history ignores blank/duplicate values and trims to limit', () => {
  installLocalStorage()
  const many = Array.from({ length: 20 }, (_, index) => `标签${index}`)

  recordTagSearch('x', many)
  assert.equal(readRecentTags('x').length, TAG_HISTORY_LIMIT)
  assert.deepEqual(
    recordTagSearch('x', ['', null, ' 标签0 ', undefined]),
    many.slice(0, TAG_HISTORY_LIMIT),
  )
})

test('clearTagHistory removes only that scope', () => {
  installLocalStorage()
  recordTagSearch('a', ['合影'])
  recordTagSearch('b', ['颁奖'])

  clearTagHistory('a')

  assert.deepEqual(readRecentTags('a'), [])
  assert.deepEqual(readRecentTags('b'), ['颁奖'])
})
