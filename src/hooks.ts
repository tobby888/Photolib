import { useCallback, useEffect, useState } from 'react'

export function useLoad<T>(loader: () => Promise<T>, initial: T, deps: unknown[] = []) {
  const [data, setData] = useState(initial)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try { setData(await loader()) } catch (reason) { setError((reason as Error).message) } finally { setLoading(false) }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  useEffect(() => { void load() }, [load])
  return { data, setData, loading, error, reload: load }
}
