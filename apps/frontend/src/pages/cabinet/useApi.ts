// Загрузка одного ресурса ядра для страниц кабинета: данные, ошибка, перечитать.
import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../../api/http';

export function useApi<T>(path: string | null): { data: T | null; error: string | null; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(path !== null);
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  useEffect(() => {
    if (path === null) { setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    api<T>('GET', path)
      .then((d) => { if (!cancelled) { setData(d); setError(null); } })
      .catch((e: unknown) => { if (!cancelled) setError(e instanceof ApiError ? e.message : 'Сервер CostGuard не отвечает'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [path, tick]);
  return { data, error, loading, reload };
}

export function errorText(e: unknown): string {
  return e instanceof ApiError ? e.message : 'Сервер CostGuard не отвечает';
}
