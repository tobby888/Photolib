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
 * Every `<img>` / `<Image>` element in a source file, as raw text. JSX attributes
 * hold expressions, so the tag ends at the first `>` outside any braces.
 */
function imageTags(source: string): string[] {
  const tags: string[] = []
  const openers = /<(?:img|Image)[\s/>]/g
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

test('the tag scanner ends a tag at the first `>` outside an expression', () => {
  assert.deepEqual(
    imageTags(`<Image src={a > b ? x : y} alt="1" />\n<div><img src={u} /></div>`),
    ['<Image src={a > b ? x : y} alt="1" />', '<img src={u} />'],
  )
})

// A preview URL requested as a plain <img> is cached without
// Access-Control-Allow-Origin, and the photo detail page's CORS fetch for the same
// URL is then blocked by that cache entry. See src/previewImage.ts.
test('every element rendering a signed preview URL requests it in CORS mode', () => {
  assert.equal(PREVIEW_CROSS_ORIGIN, 'anonymous')

  const rendered = sourceFiles(sourceRoot)
    .flatMap(file => imageTags(readFileSync(file, 'utf8'))
      .filter(tag => tag.includes('thumbnailUrl'))
      .map(tag => ({ file: path.relative(sourceRoot, file), tag })))

  assert.ok(rendered.length >= 6, `expected the gallery preview elements, found ${rendered.length}`)
  const unguarded = rendered.filter(({ tag }) => !tag.includes('crossOrigin={PREVIEW_CROSS_ORIGIN}'))
  assert.deepEqual(unguarded.map(({ file }) => file), [])
})
