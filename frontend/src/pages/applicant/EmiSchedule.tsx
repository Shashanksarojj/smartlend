import React from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { Layout } from '../../components/layout/Layout';
import { Badge } from '../../components/ui/Badge';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { loanApi } from '../../services/api';
import { useQuery } from '../../hooks/useQuery';
import { PAYMENT_STATUS_CONFIG } from '../../constants';
import { formatCurrency, formatDate } from '../../utils/formatters';
import type { EmiPayment } from '../../types';

export default function EmiSchedule() {
  const { loanId } = useParams<{ loanId: string }>();
  const navigate = useNavigate();

  const { data, isLoading, error } = useQuery<EmiPayment[]>(
    () => loanApi.emiSchedule(loanId!),
    [loanId],
    { enabled: !!loanId },
  );
  const schedule = data ?? [];

  const paid = schedule.filter((p) => p.status === 'PAID').length;
  const progress = schedule.length > 0 ? (paid / schedule.length) * 100 : 0;

  const chartData = schedule.map((p) => ({
    n:         p.installmentNumber,
    Principal: Math.round(p.principalComponent),
    Interest:  Math.round(p.interestComponent),
    Balance:   Math.round(p.remainingBalance),
  }));

  return (
    <Layout>
      <div className="mb-4 flex items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
          ← Back
        </Button>
        <div>
          <h1 className="text-xl font-bold text-slate-900">EMI Schedule</h1>
          <p className="text-xs text-slate-500">Loan #{loanId?.slice(0, 8).toUpperCase()}</p>
        </div>
      </div>

      {isLoading ? (
        <div className="flex h-40 items-center justify-center text-sm text-slate-400">Loading…</div>
      ) : error ? (
        <Alert type="error">{error}</Alert>
      ) : (
        <div className="space-y-5">
          {/* Progress */}
          <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
            <div className="mb-2 flex justify-between text-sm">
              <span className="font-medium text-slate-700">Repayment Progress</span>
              <span className="text-slate-500">{paid} / {schedule.length} paid</span>
            </div>
            <div className="h-2.5 w-full rounded-full bg-slate-200">
              <div
                className="h-2.5 rounded-full bg-blue-600 transition-all"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>

          {/* Chart */}
          {schedule.length > 0 && (
            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <p className="mb-4 text-sm font-semibold text-slate-700">
                Principal vs Interest Over Time
              </p>
              <ResponsiveContainer width="100%" height={240}>
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="n" tick={{ fontSize: 11 }} label={{ value: 'Month', position: 'insideBottom', offset: -2, fontSize: 11 }} />
                  <YAxis tickFormatter={(v) => `₹${(v / 1000).toFixed(0)}k`} tick={{ fontSize: 11 }} />
                  <Tooltip formatter={(v: number) => formatCurrency(v)} />
                  <Legend />
                  <Line type="monotone" dataKey="Principal" stroke="#3b82f6" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="Interest"  stroke="#f59e0b" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}

          {/* Table */}
          <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
            <table className="w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50">
                <tr>
                  {['#', 'Due Date', 'Principal', 'Interest', 'EMI', 'Balance', 'Status'].map((h) => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-slate-500">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {schedule.map((p) => {
                  const cfg = PAYMENT_STATUS_CONFIG[p.status];
                  return (
                    <tr key={p.installmentNumber} className="hover:bg-slate-50">
                      <td className="px-4 py-3 text-slate-500">{p.installmentNumber}</td>
                      <td className="px-4 py-3 text-slate-700">{formatDate(p.dueDate)}</td>
                      <td className="px-4 py-3">{formatCurrency(p.principalComponent)}</td>
                      <td className="px-4 py-3 text-amber-600">{formatCurrency(p.interestComponent)}</td>
                      <td className="px-4 py-3 font-medium">{formatCurrency(p.emiAmount)}</td>
                      <td className="px-4 py-3 text-slate-500">{formatCurrency(p.remainingBalance)}</td>
                      <td className="px-4 py-3">
                        <Badge className={cfg.badge}>{cfg.label}</Badge>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </Layout>
  );
}
