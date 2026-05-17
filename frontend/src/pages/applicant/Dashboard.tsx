import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Layout } from '../../components/layout/Layout';
import { Badge } from '../../components/ui/Badge';
import { Alert } from '../../components/ui/Alert';
import { useAuth } from '../../context/AuthContext';
import { useLoans } from '../../hooks/useLoans';
import { LOAN_STATUS_CONFIG, RISK_CONFIG } from '../../constants';
import { formatCurrency, formatDate, shortId } from '../../utils/formatters';
import type { LoanStatus } from '../../types';

const ALL_TABS: { label: string; value: LoanStatus | 'ALL' }[] = [
  { label: 'All',      value: 'ALL' },
  { label: 'Pending',  value: 'PENDING' },
  { label: 'Approved', value: 'APPROVED' },
  { label: 'Rejected', value: 'REJECTED' },
  { label: 'Disbursed',value: 'DISBURSED' },
];

export default function Dashboard() {
  const { user } = useAuth();
  const { loans, isLoading, error } = useLoans(user?.userId ?? '');
  const [activeTab, setActiveTab] = useState<LoanStatus | 'ALL'>('ALL');

  const summary = useMemo(() => ({
    total:    loans.length,
    active:   loans.filter((l) => l.status === 'DISBURSED').length,
    pending:  loans.filter((l) => l.status === 'PENDING').length,
    approved: loans.filter((l) => l.status === 'APPROVED').length,
  }), [loans]);

  const filtered = useMemo(
    () => activeTab === 'ALL' ? loans : loans.filter((l) => l.status === activeTab),
    [loans, activeTab]
  );

  function tabCount(val: LoanStatus | 'ALL') {
    if (val === 'ALL') return loans.length;
    return loans.filter((l) => l.status === val).length;
  }

  return (
    <Layout>
      <div className="mb-6">
        <h1 className="text-xl font-bold text-slate-900">My Loans</h1>
        <p className="text-sm text-slate-500">Track your loan applications</p>
      </div>

      {/* Summary cards */}
      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {[
          { label: 'Total',    value: summary.total,    color: 'text-slate-700' },
          { label: 'Active',   value: summary.active,   color: 'text-blue-600' },
          { label: 'Pending',  value: summary.pending,  color: 'text-amber-600' },
          { label: 'Approved', value: summary.approved, color: 'text-emerald-600' },
        ].map((c) => (
          <div key={c.label} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <p className="text-xs font-medium text-slate-500">{c.label}</p>
            <p className={`mt-1 text-2xl font-bold ${c.color}`}>{c.value}</p>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div className="mb-4 flex gap-1 overflow-x-auto border-b border-slate-200 pb-px">
        {ALL_TABS.map((t) => (
          <button
            key={t.value}
            onClick={() => setActiveTab(t.value)}
            className={`shrink-0 rounded-t px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === t.value
                ? 'border-b-2 border-blue-600 text-blue-600'
                : 'text-slate-500 hover:text-slate-700'
            }`}
          >
            {t.label}
            <span className="ml-1.5 rounded-full bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">
              {tabCount(t.value)}
            </span>
          </button>
        ))}
      </div>

      {/* Content */}
      {isLoading ? (
        <div className="flex h-40 items-center justify-center text-sm text-slate-400">
          Loading loans…
        </div>
      ) : error ? (
        <Alert type="error">{error}</Alert>
      ) : filtered.length === 0 ? (
        <div className="flex h-40 flex-col items-center justify-center gap-3 text-center">
          <p className="text-sm text-slate-500">No loans found.</p>
          <Link
            to="/apply"
            className="text-sm font-medium text-blue-600 hover:underline"
          >
            Apply for your first loan →
          </Link>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((loan) => {
            const statusCfg = LOAN_STATUS_CONFIG[loan.status];
            const riskCfg   = RISK_CONFIG[loan.riskLabel as keyof typeof RISK_CONFIG] ?? RISK_CONFIG.MEDIUM;
            return (
              <div
                key={loan.id}
                className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm transition-shadow hover:shadow"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <p className="font-semibold text-slate-900">
                      {formatCurrency(loan.principalAmount)}
                    </p>
                    <p className="mt-0.5 text-xs text-slate-500">
                      {loan.purpose} · {loan.tenureMonths}mo · #{shortId(loan.id)}
                    </p>
                  </div>
                  <div className="flex gap-2">
                    <Badge className={statusCfg.badge}>{statusCfg.label}</Badge>
                    <Badge className={riskCfg.badge}>{riskCfg.label}</Badge>
                  </div>
                </div>

                <div className="mt-3 flex flex-wrap items-center gap-4 text-xs text-slate-500">
                  <span>Applied {formatDate(loan.appliedAt)}</span>
                  {loan.interestRate > 0 && (
                    <span>{loan.interestRate}% p.a.</span>
                  )}
                  {loan.creditScore > 0 && (
                    <span>Score: {loan.creditScore}</span>
                  )}
                  {loan.adminNote && (
                    <span className="italic">"{loan.adminNote}"</span>
                  )}
                </div>

                {loan.status === 'APPROVED' || loan.status === 'DISBURSED' ? (
                  <div className="mt-3">
                    <Link
                      to={`/loans/${loan.id}/schedule`}
                      className="text-xs font-medium text-blue-600 hover:underline"
                    >
                      View EMI schedule →
                    </Link>
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
    </Layout>
  );
}
