/**
 * A real, behaving `Storage` for tests. The global vitest setup replaces `window.localStorage`
 * with `vi.fn()` stubs that store nothing, which is right for tests that only assert a call was
 * made and useless for anything that reads back what it wrote.
 */
export function memoryStorage(): Storage {
  const map = new Map<string, string>()
  return {
    get length() {
      return map.size
    },
    key: (i: number) => [...map.keys()][i] ?? null,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, String(v)),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
  }
}

/** Installs a fresh in-memory `localStorage` on `window` for the current test. */
export function useMemoryLocalStorage(): Storage {
  const storage = memoryStorage()
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true })
  return storage
}
