import assert from 'node:assert/strict'
import test from 'node:test'
import { acquireLocalImage } from '../src/useLocalImageUrl.ts'

test('shares one remote request and releases the local Blob after the final consumer leaves', async () => {
  const originalFetch = globalThis.fetch
  const originalCreateObjectUrl = URL.createObjectURL
  const originalRevokeObjectUrl = URL.revokeObjectURL
  let fetchCount = 0
  const revoked: string[] = []

  globalThis.fetch = async () => {
    fetchCount += 1
    return new Response(new Blob(['preview'], { type: 'image/jpeg' }), { status: 200 })
  }
  URL.createObjectURL = () => 'blob:photolib-preview'
  URL.revokeObjectURL = url => { revoked.push(url) }

  try {
    const first = acquireLocalImage('https://example.test/preview.jpg')
    first.release()
    const second = acquireLocalImage('https://example.test/preview.jpg')
    const third = acquireLocalImage('https://example.test/preview.jpg')

    const urls = await Promise.all([first.promise, second.promise, third.promise])
    assert.equal(fetchCount, 1)
    assert.deepEqual(urls, Array(3).fill('blob:photolib-preview'))

    second.release()
    await new Promise(resolve => setTimeout(resolve, 5))
    assert.deepEqual(revoked, [])

    third.release()
    await new Promise(resolve => setTimeout(resolve, 5))
    assert.deepEqual(revoked, ['blob:photolib-preview'])
  } finally {
    globalThis.fetch = originalFetch
    URL.createObjectURL = originalCreateObjectUrl
    URL.revokeObjectURL = originalRevokeObjectUrl
  }
})
