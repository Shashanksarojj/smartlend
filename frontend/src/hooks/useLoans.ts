import { useCallback, useEffect, useState } from 'react';
import { loanApi, getErrorMessage } from '../services/api';
import type { Loan } from '../types';

interface UseLoansResult {
  loans: Loan[];
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useLoans(userId: string): UseLoansResult {
  const [loans, setLoans] = useState<Loan[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchLoans = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await loanApi.myLoans(userId);
      setLoans(data);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    fetchLoans();
  }, [fetchLoans]);

  return { loans, isLoading, error, refetch: fetchLoans };
}
