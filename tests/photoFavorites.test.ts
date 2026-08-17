import assert from 'node:assert/strict'
import test from 'node:test'
import { updateFavoritePage } from '../src/photoFavorites.ts'

const page = {
  items: [
    { id: 'photo-1', favorited: false, title: '开幕式' },
    { id: 'photo-2', favorited: true, title: '运动会' },
  ],
  page: 1,
  pageSize: 24,
  total: 2,
  totalPages: 1,
}

test('favorite state updates in place in the regular photo library', () => {
  const updated = updateFavoritePage(page, 'photo-1', true, false)

  assert.equal(updated.items.length, 2)
  assert.equal(updated.items[0].favorited, true)
  assert.equal(updated.total, 2)
  assert.equal(page.items[0].favorited, false)
})

test('unfavoriting removes the photo and adjusts total in the favorites gallery', () => {
  const updated = updateFavoritePage(page, 'photo-2', false, true)

  assert.deepEqual(updated.items.map(item => item.id), ['photo-1'])
  assert.equal(updated.total, 1)
  assert.equal(updated.totalPages, 1)
})

test('a stale favorite response cannot decrement the total twice', () => {
  const updated = updateFavoritePage(page, 'missing-photo', false, true)

  assert.strictEqual(updated, page)
})
