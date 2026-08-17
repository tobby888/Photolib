import { useCallback, useEffect, useRef, useState } from 'react'

export function useLoad<T>(loader: () => Promise<T>, initial: T, deps: unknown[] = []) {
  const [data, setData] = useState(initial)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const loaderRef = useRef(loader)
  const requestSequence = useRef(0)
  loaderRef.current = loader
  const load = useCallback(async () => {
    const requestId = ++requestSequence.current
    setLoading(true)
    setError('')
    try {
      const next = await loaderRef.current()
      if (requestId === requestSequence.current) setData(next)
    } catch (reason) {
      if (requestId === requestSequence.current) setError((reason as Error).message)
    } finally {
      if (requestId === requestSequence.current) setLoading(false)
    }
  }, [])
  useEffect(() => {
    void load()
    return () => { requestSequence.current += 1 }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return { data, setData, loading, error, reload: load }
}
