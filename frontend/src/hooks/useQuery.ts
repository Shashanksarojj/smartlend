import { useCallback, useEffect, useRef, useState } from 'react';
import type { DependencyList } from 'react';
import { getErrorMessage } from '../services/api';

export interface QueryResult<T> {
  // undefined (not null) so callers can use destructuring defaults: { data: loans = [] }
  data: T | undefined;
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
 * const { data: loans = [], isLoading, error, refetch } = useQuery(
 *   () => loanApi.myLoans(userId),
 *   [userId],
 * );
 *
 * @example with enabled guard
 * const { data: schedule = [], isLoading, error } = useQuery(
 *   () => loanApi.emiSchedule(loanId!),
 *   [loanId],
 *   { enabled: !!loanId },
 * );
 */
export function useQuery<T>(
  queryFn: () => Promise<T>,
  deps: DependencyList,
  { enabled = true, initialData }: QueryOptions<T> = {},
): QueryResult<T> {
  const [data,      setData     ] = useState<T | undefined>(initialData);
  const [isLoading, setIsLoading] = useState(enabled);
  const [error,     setError    ] = useState<string | null>(null);

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
      const result = await queryFn();
      if (!signal.aborted) setData(result);
    } catch (err) {
      if (!signal.aborted) setError(getErrorMessage(err));
    } finally {
      if (!signal.aborted) setIsLoading(false);
    }
  // queryFn is intentionally excluded — callers should stabilise it via useCallback
  // or inline arrow functions whose identity doesn't change on re-render.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, ...deps]);

  useEffect(() => {
    run();
    return () => abortRef.current?.abort();
  }, [run]);

  return { data, isLoading, error, refetch: run };
}