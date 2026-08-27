/**
 * Rewrites `import { Button } from 'antd'` into `import { default as Button } from 'antd/es/button'`.
 *
 * Barrel modules are the reason the entry chunk used to carry the whole UI kit: the barrel is
 * reachable from the static entry, so every component any lazy route imports through it becomes a
 * static dependency of the entry chunk. Deep imports let Rollup place each component in the route
 * chunk (or shared chunk) that actually needs it.
 */

/** Maps an exported binding to the module that provides it as its default export. */
export type BarrelExports = Record<string, string>

/** `export { default as Alert } from './alert'` -> `Alert: '<packageName>/es/alert'`. */
export function parseBarrelExports(barrelCode: string, packageName: string): BarrelExports {
  const exports: BarrelExports = {}
  const pattern = /export\s*\{\s*default\s+as\s+(\w+)\s*\}\s*from\s*['"]\.\/([\w-]+)['"]/g
  for (const [, name, directory] of barrelCode.matchAll(pattern)) {
    exports[name] = `${packageName}/es/${directory}`
  }
  return exports
}

/** `BellOutlined` -> `@ant-design/icons/es/icons/BellOutlined`, for the icon files that exist. */
export function iconBarrelExports(iconFileNames: Iterable<string>, packageName: string): BarrelExports {
  const exports: BarrelExports = {}
  for (const fileName of iconFileNames) {
    if (!fileName.endsWith('.js')) continue
    const name = fileName.slice(0, -3)
    exports[name] = `${packageName}/es/icons/${name}`
  }
  return exports
}

const IMPORT_PATTERN = /^[ \t]*import\s+(type\s+)?\{([^}]*)\}\s*from\s*['"]([^'"]+)['"];?/gm
const SPECIFIER_PATTERN = /^(type\s+)?([A-Za-z_$][\w$]*)(?:\s+as\s+([A-Za-z_$][\w$]*))?$/

/**
 * Returns the rewritten module, or null when nothing in it imports from a known barrel.
 * Type-only imports and bindings the barrel map does not know (`version`, `unstableSetRender`)
 * stay on the original barrel, so behaviour never depends on the map being complete.
 * The replacement keeps the statement's line count so stack traces still line up with the source.
 */
export function rewriteBarrelImports(code: string, barrels: Record<string, BarrelExports>): string | null {
  let rewritten = false
  const result = code.replace(IMPORT_PATTERN, (statement, typeOnly: string | undefined, clause: string, source: string) => {
    const exports = barrels[source]
    if (!exports || typeOnly) return statement

    const deepImports: string[] = []
    const kept: string[] = []
    for (const raw of clause.split(',')) {
      const specifier = raw.trim()
      if (!specifier) continue
      const parsed = SPECIFIER_PATTERN.exec(specifier)
      const module = parsed && !parsed[1] ? exports[parsed[2]] : undefined
      if (!parsed || !module) {
        kept.push(specifier)
        continue
      }
      deepImports.push(`import { default as ${parsed[3] || parsed[2]} } from '${module}';`)
    }
    if (!deepImports.length) return statement

    rewritten = true
    if (kept.length) deepImports.push(`import { ${kept.join(', ')} } from '${source}';`)
    const lineBreaks = statement.split('\n').length - 1
    return deepImports.join(' ') + '\n'.repeat(lineBreaks)
  })
  return rewritten ? result : null
}
