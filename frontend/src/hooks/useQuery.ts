import { useCallback, useEffect, useRef, useState } from 'react';
import type { DependencyList } from 'react';
import { getErrorMessage } from '../services/api';

export interface QueryResult<T> {
  data: T | null;
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
}

export interface QueryOptions<T> {
  /** Skip the fetch when false — useful when a required param is not yet available. */
  enabled?: boolean;
  /** Pre-populate data before the first fetch completes. */
  initialData?: T;
}

/**
 * Generic data-fetching hook. Standardises loading/error/data state and
 * eliminates the useCallback+useEffect+try/catch boilerplate across every page.
 *
 * @param queryFn  Async function that returns the data. Re-runs when deps change.
 * @param deps     Dependency array — same semantics as useEffect deps.
 * @param options  { enabled, initialData }
 *
 * @example
 * const { data, isLoading, error, refetch } = useQuery(
 *   () => loanApi.myLoans(userId),
 *   [userId],
 * );
 * const loans = data ?? [];
 *
 * @example with enabled guard
 * const { data, isLoading, error } = useQuery(
 *   () => loanApi.emiSchedule(loanId!),
 *   [loanId],
 *   { enabled: !!loanId },
 * );
 * const schedule = data ?? [];
 */
export function useQuery<T>(
  queryFn: () => Promise<T>,
  deps: DependencyList,
  { enabled = true, initialData }: QueryOptions<T> = {},
): QueryResult<T> {
  const [data,      setData     ] = useState<T | null>(initialData ?? null);
  const [isLoading, setIsLoading] = useState(enabled);
  const [error,     setError    ] = useState<string | null>(null);

  // Always hold the latest queryFn without making it a dep of run().
  // Callers pass inline arrow functions that change identity every render —
  // putting queryFn in deps would cause infinite re-fetch loops.
  const queryFnRef = useRef(queryFn);
  useEffect(() => { queryFnRef.current = queryFn; });

  // Prevent stale async responses from overwriting newer state when deps change mid-flight.
  const abortRef = useRef<AbortController | null>(null);

  const run = useCallback(async () => {
    if (!enabled) return;

    abortRef.current?.abort();
    abortRef.current = new AbortController();
    const { signal } = abortRef.current;

    setIsLoading(true);
    setError(null);

    try {
      const result = await queryFnRef.current();
      if (!signal.aborted) setData(result);
    } catch (err) {
      if (!signal.aborted) setError(getErrorMessage(err));
    } finally {
      if (!signal.aborted) setIsLoading(false);
    }
  }, [enabled, ...deps]);

  useEffect(() => {
    void run();
    return () => abortRef.current?.abort();
  }, [run]);

  return { data, isLoading, error, refetch: run };
}
