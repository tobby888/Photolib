import assert from 'node:assert/strict'
import test from 'node:test'
import { brotliDecompressSync, gunzipSync } from 'node:zlib'
import { MIN_PRECOMPRESS_BYTES, precompress, shouldPrecompress } from '../build/precompress.ts'

test('only text assets under assets/ above the size floor are precompressed', () => {
  assert.equal(shouldPrecompress('assets/index-AbC123.js', 50_000), true)
  assert.equal(shouldPrecompress('assets/index-AbC123.css', 50_000), true)
  assert.equal(shouldPrecompress('assets/logo-AbC123.svg', 5_000), true)
  // index.html is revalidated on every visit and tiny; it stays a plain file.
  assert.equal(shouldPrecompress('index.html', 50_000), false)
  assert.equal(shouldPrecompress('assets/photo-AbC123.png', 50_000), false)
  assert.equal(shouldPrecompress('assets/font-AbC123.woff2', 50_000), false)
  assert.equal(shouldPrecompress('assets/tiny-AbC123.js', MIN_PRECOMPRESS_BYTES - 1), false)
  assert.equal(shouldPrecompress('assets/edge-AbC123.js', MIN_PRECOMPRESS_BYTES), true)
})

test('both encodings round-trip to the original bytes', async () => {
  const source = new TextEncoder().encode('export const value = "摄影工作站";\n'.repeat(200))
  const { br, gz } = await precompress(source)
  assert.ok(br && gz)
  assert.ok(br.byteLength < source.byteLength)
  assert.ok(gz.byteLength < source.byteLength)
  assert.deepEqual(new Uint8Array(brotliDecompressSync(br)), source)
  assert.deepEqual(new Uint8Array(gunzipSync(gz)), source)
})

test('a variant that does not shrink the file is dropped so the server serves the original', async () => {
  const random = new Uint8Array(4096)
  crypto.getRandomValues(random)
  const result = await precompress(random)
  assert.equal(result.br, undefined)
  assert.equal(result.gz, undefined)
})
