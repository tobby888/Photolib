import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { brotliCompress, constants, gzip } from 'node:zlib'

/**
 * Writes `.br` and `.gz` siblings next to every compressible file under `assets/`, so the backend
 * can serve them as-is (Spring's `EncodedResourceResolver`, see `StaticResourceConfig`) instead of
 * sending the bundle uncompressed. Tomcat's own compression is off, and whether a reverse proxy in
 * front gzips is a deployment detail the app cannot count on.
 *
 * Both encodings are needed: browsers only advertise `br` over HTTPS, and the README documents
 * plain `http://host:8080/` access too.
 */

/** Only text formats shrink; images and fonts in `assets/` are already compressed. */
const COMPRESSIBLE = /\.(?:js|mjs|css|html|svg|json|txt|map)$/i

/** Below this the encoding overhead and the extra lookup outweigh the saved bytes. */
export const MIN_PRECOMPRESS_BYTES = 1024

export function shouldPrecompress(fileName: string, size: number): boolean {
  return fileName.startsWith('assets/') && COMPRESSIBLE.test(fileName) && size >= MIN_PRECOMPRESS_BYTES
}

export interface Precompressed {
  br?: Uint8Array
  gz?: Uint8Array
}

const brotli = promisify(brotliCompress)
const gzipAsync = promisify(gzip)

/**
 * Maximum levels on purpose: this runs once per build and every visitor pays for the bytes. A variant
 * that is not smaller than the original is dropped, so the server falls back to the plain file.
 */
export async function precompress(content: Uint8Array): Promise<Precompressed> {
  const [br, gz] = await Promise.all([
    brotli(content, {
      params: {
        [constants.BROTLI_PARAM_MODE]: constants.BROTLI_MODE_TEXT,
        [constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY,
        [constants.BROTLI_PARAM_SIZE_HINT]: content.byteLength,
      },
    }),
    gzipAsync(content, { level: constants.Z_BEST_COMPRESSION }),
  ])
  const result: Precompressed = {}
  if (br.byteLength < content.byteLength) result.br = br
  if (gz.byteLength < content.byteLength) result.gz = gz
  return result
}

/** Minimal slice of the Vite/Rollup plugin API this needs, so the module has no Vite import. */
export interface PrecompressPlugin {
  name: string
  apply: 'build'
  writeBundle(options: { dir?: string }, bundle: Record<string, unknown>): Promise<void>
}

export function precompressAssets(): PrecompressPlugin {
  return {
    name: 'photolib:precompress-assets',
    apply: 'build',
    // Reads the files back from disk rather than from the bundle: that is exactly what is shipped,
    // including anything another plugin rewrote after the chunks were rendered.
    async writeBundle(options, bundle) {
      const outDir = options.dir
      if (!outDir) return
      await Promise.all(Object.keys(bundle).map(async (fileName) => {
        if (!fileName.startsWith('assets/')) return
        const path = join(outDir, fileName)
        const content = await readFile(path)
        if (!shouldPrecompress(fileName, content.byteLength)) return
        const { br, gz } = await precompress(content)
        await Promise.all([
          br && writeFile(`${path}.br`, br),
          gz && writeFile(`${path}.gz`, gz),
        ])
      }))
    },
  }
}
