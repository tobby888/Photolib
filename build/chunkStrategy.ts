/** Minimal view of the Rollup module graph the chunk strategy needs. */
export interface ModuleGraph {
  getModuleInfo(id: string): { isEntry: boolean, importers: readonly string[] } | null
}

/** `.../node_modules/@rc-component/select/es/x.js` -> `@rc-component/select`. */
export function packageNameOf(id: string): string | undefined {
  const [, ...inPackages] = id.replace(/\\/g, '/').split('/node_modules/')
  const parts = inPackages.pop()?.split('/')
  if (!parts?.length) return undefined
  return parts[0].startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0]
}

const REACT_RUNTIME = new Set(['react', 'react-dom', 'react-is', 'scheduler'])
const ROUTER = new Set(['react-router', 'react-router-dom'])
const ICONS = new Set(['@ant-design/icons', '@ant-design/icons-svg'])
const UI_KIT = /^(antd$|rc-[\w-]+$|@rc-component\/|@ant-design\/)/

/**
 * True when the module is pulled in by the app shell itself (`main.tsx` -> `App.tsx` and whatever
 * they import statically), as opposed to being reached only through a lazily imported route.
 */
export function reachableFromEntry(id: string, graph: ModuleGraph, cache = new Map<string, boolean>()): boolean {
  const cached = cache.get(id)
  if (cached !== undefined) return cached

  const visited = new Set([id])
  let frontier = [id]
  while (frontier.length) {
    const next: string[] = []
    for (const current of frontier) {
      const info = graph.getModuleInfo(current)
      if (!info) continue
      if (info.isEntry) {
        cache.set(id, true)
        return true
      }
      for (const importer of info.importers) {
        if (visited.has(importer)) continue
        visited.add(importer)
        next.push(importer)
      }
    }
    frontier = next
  }
  cache.set(id, false)
  return false
}

/**
 * Groups the dependencies the app shell needs into a handful of long-lived vendor chunks, and
 * leaves everything else to Rollup's automatic per-route splitting.
 *
 * Only shell-reachable modules are grouped on purpose. Grouping route-only modules by package
 * backfires: the UI kit cross-imports between components (`tooltip` pulls one tiny module out of
 * `table`), so a package-shaped chunk makes the shell depend on the whole component and the
 * browser downloads a table implementation to render the login screen.
 */
export function createManualChunks(): (id: string, graph: ModuleGraph) => string | undefined {
  const reachable = new Map<string, boolean>()
  return (id, graph) => {
    if (!id.includes('node_modules')) return undefined
    const packageName = packageNameOf(id)
    if (!packageName) return undefined
    // React never changes between releases of this app and is needed by every route: its own chunk
    // stays in the browser cache across deploys.
    if (REACT_RUNTIME.has(packageName)) return 'react'
    if (!reachableFromEntry(id, graph, reachable)) return undefined
    if (ROUTER.has(packageName)) return 'react-router'
    if (ICONS.has(packageName)) return 'antd-icons'
    if (UI_KIT.test(packageName)) return packageName === 'antd' ? 'antd' : 'antd-base'
    return 'vendor'
  }
}
