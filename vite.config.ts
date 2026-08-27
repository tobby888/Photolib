import { readdirSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { iconBarrelExports, parseBarrelExports, rewriteBarrelImports, type BarrelExports } from './build/barrelImports'
import { createManualChunks } from './build/chunkStrategy'

/**
 * Rewrites `antd` / `@ant-design/icons` barrel imports to deep imports at build time. Importing the
 * barrel makes every component the whole app uses look like a dependency of the app shell, so the
 * bundler cannot tell shell code from route-only code and the first paint downloads all of it.
 * Build only: the dev server pre-bundles those packages anyway, and the source keeps the readable
 * barrel imports.
 */
function deepBarrelImports(): Plugin {
  const barrels: Record<string, BarrelExports> = {}
  return {
    name: 'photolib:deep-barrel-imports',
    apply: 'build',
    enforce: 'pre',
    buildStart() {
      // Plain Node resolution on purpose: `this.resolve` adds the barrels to the module graph as
      // orphan modules, and Rollup then crashes while rendering the chunk they land in.
      const require = createRequire(import.meta.url)
      const packageDirectory = (name: string) => dirname(require.resolve(`${name}/package.json`))
      barrels.antd =
        parseBarrelExports(readFileSync(join(packageDirectory('antd'), 'es/index.js'), 'utf8'), 'antd')
      barrels['@ant-design/icons'] =
        iconBarrelExports(readdirSync(join(packageDirectory('@ant-design/icons'), 'es/icons')), '@ant-design/icons')
    },
    transform(code, id) {
      if (id.includes('/node_modules/') || !/\.[jt]sx?$/.test(id)) return
      const rewritten = rewriteBarrelImports(code, barrels)
      return rewritten ? { code: rewritten, map: null } : undefined
    },
  }
}

const manualChunks = createManualChunks()

export default defineConfig({
  plugins: [react(), deepBarrelImports()],
  build: {
    rollupOptions: {
      output: { manualChunks },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
