import { useCallback } from 'react';
import { loanApi } from '../services/api';
import type { AdminDecisionRequest, Loan } from '../types';
import { useQuery } from './useQuery';
import type { QueryResult } from './useQuery';

type UseAdminLoansResult = Omit<QueryResult<Loan[]>, 'data'> & {
  loans: Loan[];
  decide: (loanId: string, req: AdminDecisionRequest) => Promise<void>;
};

export function useAdminLoans(): UseAdminLoansResult {
  const { data, refetch, ...rest } = useQuery(
    () => loanApi.allLoans(),
    [],
  );

  // Optimistic update: replace the decided loan in local state immediately
  const decide = useCallback(async (loanId: string, req: AdminDecisionRequest) => {
    await loanApi.adminDecision(loanId, req);
    refetch();
  }, [refetch]);

  return { loans: data ?? [], refetch, decide, ...rest };
}
