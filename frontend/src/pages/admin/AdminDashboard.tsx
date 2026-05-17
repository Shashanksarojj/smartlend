import React, { useMemo, useState } from 'react';
import { Layout } from '../../components/layout/Layout';
import { Badge } from '../../components/ui/Badge';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { useAdminLoans } from '../../hooks/useAdminLoans';
import { LOAN_STATUS_CONFIG, RISK_CONFIG } from '../../constants';
import { formatCurrency, formatDate, shortId } from '../../utils/formatters';
import { cn } from '../../utils/cn';
import type { Loan, LoanStatus } from '../../types';

type Tab = LoanStatus | 'ALL';

const TABS: { label: string; value: Tab }[] = [
  { label: 'All',      value: 'ALL' },
  { label: 'Pending',  value: 'PENDING' },
  { label: 'Approved', value: 'APPROVED' },
  { label: 'Rejected', value: 'REJECTED' },
];

interface DecisionRowProps {
  loan: Loan;
  onDecide: (loanId: string, status: 'APPROVED' | 'REJECTED', rate: number, note: string) => Promise<void>;
}

function DecisionRow({ loan, onDecide }: DecisionRowProps) {
  const [rate, setRate] = useState(loan.interestRate > 0 ? String(loan.interestRate) : '');
  const [note, setNote] = useState(loan.adminNote ?? '');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  async function handle(status: 'APPROVED' | 'REJECTED') {
    setErr('');
    setLoading(true);
    try {
      await onDecide(loan.id, status, parseFloat(rate) || 0, note);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Error');
    } finally {
      setLoading(false);
    }
  }

  const isPending = loan.status === 'PENDING';
  const riskCfg   = RISK_CONFIG[loan.riskLabel as keyof typeof RISK_CONFIG] ?? RISK_CONFIG.MEDIUM;
  const statusCfg = LOAN_STATUS_CONFIG[loan.status];

  return (
    <tr className="border-b border-slate-100 hover:bg-slate-50">
      <td className="px-4 py-3">
        <p className="font-medium text-slate-800">{formatCurrency(loan.principalAmount)}</p>
        <p className="text-xs text-slate-400">#{shortId(loan.id)}</p>
      </td>
      <td className="px-4 py-3 text-sm text-slate-600">{loan.purpose}</td>
      <td className="px-4 py-3 text-sm text-slate-600">{loan.tenureMonths}mo</td>
      <td className="px-4 py-3">
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium">{loan.creditScore}</span>
          <Badge className={riskCfg.badge}>{riskCfg.label}</Badge>
        </div>
      </td>
      <td className="px-4 py-3 text-xs text-slate-500">{formatDate(loan.appliedAt)}</td>
      <td className="px-4 py-3">
        <Badge className={statusCfg.badge}>{statusCfg.label}</Badge>
      </td>
      <td className="px-4 py-3">
        {isPending ? (
          <div className="flex flex-col gap-2">
            {err && <p className="text-xs text-red-600">{err}</p>}
            <div className="flex gap-2">
              <input
                type="number"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
                placeholder="Rate %"
                className="h-8 w-20 rounded border border-slate-300 px-2 text-xs focus:outline-none focus:ring-1 focus:ring-violet-500"
              />
              <input
                type="text"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Admin note"
                className="h-8 flex-1 min-w-[120px] rounded border border-slate-300 px-2 text-xs focus:outline-none focus:ring-1 focus:ring-violet-500"
              />
            </div>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="emerald"
                isLoading={loading}
                onClick={() => handle('APPROVED')}
                className="flex-1"
              >
                Approve
              </Button>
              <Button
                size="sm"
                variant="danger"
                isLoading={loading}
                onClick={() => handle('REJECTED')}
                className="flex-1"
              >
                Reject
              </Button>
            </div>
          </div>
        ) : (
          <span className="text-xs text-slate-400 italic">
            {loan.adminNote || '—'}
          </span>
        )}
      </td>
    </tr>
  );
}

export default function AdminDashboard() {
  const { loans, isLoading, error, decide } = useAdminLoans();
  const [activeTab, setActiveTab] = useState<Tab>('ALL');

  const summary = useMemo(() => ({
    total:   loans.length,
    pending: loans.filter((l) => l.status === 'PENDING').length,
    approved:loans.filter((l) => l.status === 'APPROVED').length,
    rejected:loans.filter((l) => l.status === 'REJECTED').length,
  }), [loans]);

  const filtered = useMemo(
    () => activeTab === 'ALL' ? loans : loans.filter((l) => l.status === activeTab),
    [loans, activeTab]
  );

  async function handleDecide(
    loanId: string,
    status: 'APPROVED' | 'REJECTED',
    interestRate: number,
    adminNote: string
  ) {
    await decide(loanId, { decision: status, interestRate, adminNote });
  }

  return (
    <Layout>
      <div className="mb-6">
        <h1 className="text-xl font-bold text-slate-900">Loan Management</h1>
        <p className="text-sm text-slate-500">Review and action loan applications</p>
      </div>

      {/* Summary */}
      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {[
          { label: 'Total',    value: summary.total,    color: 'text-slate-700' },
          { label: 'Pending',  value: summary.pending,  color: 'text-amber-600' },
          { label: 'Approved', value: summary.approved, color: 'text-emerald-600' },
          { label: 'Rejected', value: summary.rejected, color: 'text-red-600' },
        ].map((c) => (
          <div key={c.label} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <p className="text-xs font-medium text-slate-500">{c.label}</p>
            <p className={`mt-1 text-2xl font-bold ${c.color}`}>{c.value}</p>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div className="mb-4 flex gap-1 border-b border-slate-200">
        {TABS.map((t) => {
          const count = t.value === 'ALL' ? loans.length : loans.filter((l) => l.status === t.value).length;
          return (
            <button
              key={t.value}
              onClick={() => setActiveTab(t.value)}
              className={cn(
                'shrink-0 rounded-t px-4 py-2 text-sm font-medium transition-colors',
                activeTab === t.value
                  ? 'border-b-2 border-violet-600 text-violet-700'
                  : 'text-slate-500 hover:text-slate-700'
              )}
            >
              {t.label}
              <span className="ml-1.5 rounded-full bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {isLoading ? (
        <div className="flex h-40 items-center justify-center text-sm text-slate-400">Loading…</div>
      ) : error ? (
        <Alert type="error">{error}</Alert>
      ) : filtered.length === 0 ? (
        <div className="flex h-40 items-center justify-center text-sm text-slate-400">
          No loans in this category.
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50">
              <tr>
                {['Amount', 'Purpose', 'Tenure', 'AI Score', 'Applied', 'Status', 'Action'].map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map((loan) => (
                <DecisionRow key={loan.id} loan={loan} onDecide={handleDecide} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Layout>
  );
}
