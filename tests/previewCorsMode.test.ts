import assert from 'node:assert/strict'
import test from 'node:test'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { PREVIEW_CROSS_ORIGIN } from '../src/previewImage.ts'

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url))

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(full)
    return entry.isFile() && full.endsWith('.tsx') ? [full] : []
  })
}

/**
 * Block comments quote JSX in prose (`PreviewPhoto.tsx` documents the very tags
 * this file bans), so drop them before scanning or the docs fail their own rule.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '')
}

/**
 * Every image element in a source file, as raw text. JSX attributes hold
 * expressions, so the tag ends at the first `>` outside any braces.
 */
function imageTags(input: string): string[] {
  const source = withoutComments(input)
  const tags: string[] = []
  const openers = /<(?:img|Image|PreviewPhoto|PreviewPhotoImg)[\s/>]/g
  let opener: RegExpExecArray | null
  while ((opener = openers.exec(source)) !== null) {
    let depth = 0
    for (let index = opener.index; index < source.length; index += 1) {
      const character = source[index]
      if (character === '{') depth += 1
      else if (character === '}') depth -= 1
      else if (character === '>' && depth === 0) {
        tags.push(source.slice(opener.index, index + 1))
        break
      }
    }
  }
  return tags
}

/**
 * A signed preview URL as it appears in page source. `previewUrl` alone is too
 * loose — the admin page uses that name for a locally previewed icon upload.
 */
const SIGNED_PREVIEW = /thumbnailUrl|entry\.previewUrl/

test('the tag scanner ends a tag at the first `>` outside an expression', () => {
  assert.deepEqual(
    imageTags('<Image src={a > b ? x : y} alt="1" />\n<div><img src={u} /></div>'),
    ['<Image src={a > b ? x : y} alt="1" />', '<img src={u} />'],
  )
})

test('the tag scanner ignores JSX quoted inside comments', () => {
  assert.deepEqual(imageTags('/** <img src={thumbnailUrl} /> */\n<img src={u} />'), ['<img src={u} />'])
})

// The HTTP cache is keyed by URL alone, so one preview URL may only ever be
// requested in ONE CORS mode — mix the two and whichever response lands in the cache
// first breaks the other. `PreviewPhoto` is where that mode (and the expired-signature
// retry, see src/previewRetry.ts) is applied once for everyone, so pages must render
// signed preview URLs through it rather than reaching for `<Image>`/`<img>` themselves.
test('every element rendering a signed preview URL goes through PreviewPhoto', () => {
  const rendered = sourceFiles(sourceRoot)
    .flatMap(file => imageTags(readFileSync(file, 'utf8'))
      .filter(tag => SIGNED_PREVIEW.test(tag))
      .map(tag => ({ file: path.relative(sourceRoot, file), tag })))

  assert.ok(rendered.length >= 8, `expected the gallery preview elements, found ${rendered.length}`)
  const unguarded = rendered.filter(({ tag }) => !tag.startsWith('<PreviewPhoto'))
  assert.deepEqual(unguarded.map(({ file }) => file), [])
})

test('PreviewPhoto routes both its forms through the one CORS-mode decision', () => {
  const source = readFileSync(path.join(sourceRoot, 'PreviewPhoto.tsx'), 'utf8')
  const tags = imageTags(source)

  assert.equal(tags.length, 2, 'expected the antd <Image> and the bare <img>')
  for (const tag of tags) {
    assert.match(tag, /crossOrigin=\{PREVIEW_CROSS_ORIGIN\}/)
  }
})

// Previews are requested in no-cors mode because that is what a plain <img> does and
// what third-party code does by default — antd's <Image> loads the same src a second
// time through rc-image's `isImageValid` probe, which has no crossOrigin and no way
// to be given one. Asking for CORS here would only mean racing that probe for the
// cache entry, and losing it on any grid using loading="lazy". See src/previewImage.ts.
test('previews are requested without CORS', () => {
  assert.equal(PREVIEW_CROSS_ORIGIN, undefined)
})

// The other half of that invariant: the histogram needs readable pixels, so it is the
// single consumer that must have Access-Control-Allow-Origin. It can only be sure of
// getting one by refusing to read the shared cache entry, which normally holds the
// gallery's header-less no-cors response.
test('the one consumer that needs CORS bypasses the HTTP cache', () => {
  const source = readFileSync(path.join(sourceRoot, 'useLocalImageUrl.ts'), 'utf8')
  const call = withoutComments(source).match(/fetch\([^)]*\)/)

  assert.ok(call, 'expected useLocalImageUrl to fetch the preview')
  assert.match(call[0], /mode: 'cors'/)
  assert.match(call[0], /cache: 'reload'/)
})
