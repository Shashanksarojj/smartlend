import { loanApi } from '../services/api';
import type { Loan } from '../types';
import { useQuery } from './useQuery';
import type { QueryResult } from './useQuery';

type UseLoansResult = Omit<QueryResult<Loan[]>, 'data'> & { loans: Loan[] };

export function useLoans(userId: string): UseLoansResult {
  const { data, ...rest } = useQuery(
    () => loanApi.myLoans(userId),
    [userId],
  );
  return { loans: data ?? [], ...rest };
}
