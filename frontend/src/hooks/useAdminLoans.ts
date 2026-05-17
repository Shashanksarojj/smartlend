import { useCallback, useEffect, useState } from 'react';
import { loanApi, getErrorMessage } from '../services/api';
import type { AdminDecisionRequest, Loan } from '../types';

interface UseAdminLoansResult {
  loans: Loan[];
  isLoading: boolean;
  error: string | null;
  decide: (loanId: string, req: AdminDecisionRequest) => Promise<void>;
  refetch: () => void;
}

export function useAdminLoans(): UseAdminLoansResult {
  const [loans, setLoans] = useState<Loan[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchLoans = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await loanApi.allLoans();
      setLoans(data);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchLoans();
  }, [fetchLoans]);

  const decide = useCallback(async (loanId: string, req: AdminDecisionRequest) => {
    const updated = await loanApi.adminDecision(loanId, req);
    setLoans((prev) =>
      prev.map((l) => (l.id === updated.id ? updated : l))
    );
  }, []);

  return { loans, isLoading, error, decide, refetch: fetchLoans };
}
