import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Layout } from '../../components/layout/Layout';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Badge } from '../../components/ui/Badge';
import { Alert } from '../../components/ui/Alert';
import { useAuth } from '../../context/AuthContext';
import { loanApi, scoringApi, getErrorMessage } from '../../services/api';
import type { ScorePreviewResult } from '../../services/api';
import { LOAN_PURPOSES, LOAN_TERMS, RISK_CONFIG } from '../../constants';
import { formatCurrency, formatPercent, shortId } from '../../utils/formatters';
import type { Loan } from '../../types';

const PURPOSE_OPTIONS = LOAN_PURPOSES.map((p) => ({ value: p, label: p }));
const TERM_OPTIONS = LOAN_TERMS.map((t) => ({ value: String(t), label: `${t} months` }));

// ── Credit Score Bar ──────────────────────────────────────────

function CreditScoreBar({ score, animate = true }: { score: number; animate?: boolean }) {
  const pct = Math.min(Math.max(((score - 300) / 600) * 100, 0), 100);
  const color =
    score >= 750 ? 'bg-emerald-500'
    : score >= 650 ? 'bg-amber-400'
    : score >= 550 ? 'bg-orange-500'
    : 'bg-red-600';

  const label =
    score >= 750 ? 'Excellent'
    : score >= 650 ? 'Good'
    : score >= 550 ? 'Fair'
    : 'Poor';

  return (
    <div>
      <div className="mb-1.5 flex justify-between text-xs text-slate-500">
        <span>300 · Poor</span>
        <span className={`font-bold text-base ${color.replace('bg-', 'text-')}`}>
          {score} <span className="text-xs font-medium">({label})</span>
        </span>
        <span>900 · Excellent</span>
      </div>
      <div className="h-3 w-full rounded-full bg-slate-200">
        <div
          className={`h-3 rounded-full ${animate ? 'transition-all duration-700' : ''} ${color}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      {/* Range markers */}
      <div className="mt-1 flex justify-between text-[10px] text-slate-400">
        <span>300</span>
        <span>450</span>
        <span>600</span>
        <span>750</span>
        <span>900</span>
      </div>
    </div>
  );
}

// ── Live Score Preview Card ───────────────────────────────────

function ScorePreviewCard({
  result,
  isLoading,
}: {
  result: ScorePreviewResult | null;
  isLoading: boolean;
}) {
  if (isLoading) {
    return (
      <div className="rounded-xl border border-blue-100 bg-blue-50 p-5">
        <div className="flex items-center gap-3">
          <svg className="h-5 w-5 animate-spin text-blue-500" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          <p className="text-sm text-blue-700">Calculating your estimated credit score…</p>
        </div>
      </div>
    );
  }

  if (!result) return null;

  const riskCfg = RISK_CONFIG[result.risk_label as keyof typeof RISK_CONFIG] ?? RISK_CONFIG.MEDIUM;
  const isApproved = result.recommendation === 'APPROVE';

  return (
    <div className={`rounded-xl border p-5 ${isApproved ? 'border-emerald-200 bg-emerald-50' : 'border-red-100 bg-red-50'}`}>
      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className={`flex h-7 w-7 items-center justify-center rounded-full ${isApproved ? 'bg-emerald-100' : 'bg-red-100'}`}>
            {isApproved ? (
              <svg className="h-4 w-4 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            ) : (
              <svg className="h-4 w-4 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            )}
          </div>
          <p className={`text-sm font-semibold ${isApproved ? 'text-emerald-800' : 'text-red-700'}`}>
            AI Credit Score Preview
          </p>
        </div>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${isApproved ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>
          {isApproved ? 'Likely to Approve' : 'Likely to Reject'}
        </span>
      </div>

      {/* Score bar */}
      <div className="mb-4">
        <CreditScoreBar score={result.credit_score} />
      </div>

      {/* Stats row */}
      <div className="mb-4 grid grid-cols-3 gap-3">
        <div className="rounded-lg bg-white/70 px-3 py-2.5 text-center">
          <p className="text-xs text-slate-500">Credit Score</p>
          <p className="mt-0.5 text-lg font-bold text-slate-800">{result.credit_score}</p>
        </div>
        <div className="rounded-lg bg-white/70 px-3 py-2.5 text-center">
          <p className="text-xs text-slate-500">Risk Level</p>
          <div className="mt-1 flex justify-center">
            <Badge className={riskCfg.badge}>{riskCfg.label}</Badge>
          </div>
        </div>
        <div className="rounded-lg bg-white/70 px-3 py-2.5 text-center">
          <p className="text-xs text-slate-500">Est. Rate</p>
          <p className="mt-0.5 text-lg font-bold text-slate-800">{formatPercent(result.suggested_rate)}</p>
        </div>
      </div>

      {/* Reasoning */}
      <p className="text-xs text-slate-500 italic">{result.reasoning}</p>

      <p className="mt-2 text-[10px] text-slate-400">
        * Estimate only. Final score is determined at submission.
      </p>
    </div>
  );
}

// ── Main Component ────────────────────────────────────────────

export default function ApplyLoan() {
  const { user } = useAuth();

  const [form, setForm] = useState({ amount: '', purpose: '', termMonths: '' });
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [submitted, setSubmitted] = useState<Loan | null>(null);

  const [scorePreview, setScorePreview] = useState<ScorePreviewResult | null>(null);
  const [scoreLoading, setScoreLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Fetch live score preview whenever amount or tenure changes
  useEffect(() => {
    const amount = parseFloat(form.amount);
    const tenure = parseInt(form.termMonths, 10);

    if (!amount || amount < 10000 || !tenure) {
      setScorePreview(null);
      return;
    }

    if (debounceRef.current) clearTimeout(debounceRef.current);

    debounceRef.current = setTimeout(async () => {
      setScoreLoading(true);
      try {
        const result = await scoringApi.preview({
          monthly_income: user?.monthlyIncome ?? 50000,
          requested_amount: amount,
          tenure_months: tenure,
          employment_type: user?.employmentType ?? 'SALARIED',
          existing_loans: 0,
        });
        setScorePreview(result);
      } catch {
        // Silent — preview is best-effort
        setScorePreview(null);
      } finally {
        setScoreLoading(false);
      }
    }, 600);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [form.amount, form.termMonths, user]);

  function handleChange(e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const amount = parseFloat(form.amount);
    if (isNaN(amount) || amount < 10000) {
      setError('Minimum loan amount is ₹10,000.');
      return;
    }

    setIsLoading(true);
    try {
      const loan = await loanApi.apply(
        { principalAmount: amount, purpose: form.purpose, tenureMonths: parseInt(form.termMonths, 10) },
        user!.userId,
        user?.monthlyIncome ?? 50000,
        user?.employmentType ?? 'SALARIED'
      );
      setSubmitted(loan);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  }

  // ── Post-submission success screen ───────────────────────────
  if (submitted) {
    const riskCfg = RISK_CONFIG[submitted.riskLabel as keyof typeof RISK_CONFIG] ?? RISK_CONFIG.MEDIUM;
    return (
      <Layout>
        <div className="mx-auto max-w-2xl">
          <div className="mb-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6 text-center">
            <div className="mb-3 flex justify-center">
              <div className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100">
                <svg className="h-7 w-7 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
              </div>
            </div>
            <h2 className="text-xl font-bold text-emerald-800">Application Submitted!</h2>
            <p className="mt-1 text-sm text-emerald-600">Your loan is under review. We'll notify you once a decision is made.</p>
          </div>

          <div className="mb-6 flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 px-5 py-3">
            <span className="text-sm font-medium text-amber-800">Current Status</span>
            <Badge className="bg-amber-100 text-amber-800 px-3 py-1">PENDING REVIEW</Badge>
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <p className="mb-4 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Loan Reference #{shortId(submitted.id)}
            </p>
            <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3">
              {[
                { label: 'Amount',  value: formatCurrency(submitted.principalAmount) },
                { label: 'Purpose', value: submitted.purpose },
                { label: 'Tenure',  value: `${submitted.tenureMonths} months` },
                { label: 'Rate',    value: submitted.interestRate ? formatPercent(submitted.interestRate) : 'TBD' },
                { label: 'Applied', value: new Date(submitted.appliedAt).toLocaleDateString('en-IN') },
              ].map((d) => (
                <div key={d.label}>
                  <p className="text-xs text-slate-500">{d.label}</p>
                  <p className="mt-0.5 font-medium text-slate-800">{d.value}</p>
                </div>
              ))}
            </div>

            <div className="mb-5">
              <p className="mb-3 text-sm font-semibold text-slate-700">AI Credit Score</p>
              <CreditScoreBar score={submitted.creditScore} />
            </div>

            <div className="flex items-center gap-3">
              <p className="text-sm text-slate-600">Risk Assessment:</p>
              <Badge className={riskCfg.badge}>{riskCfg.label}</Badge>
            </div>
          </div>

          <div className="mt-6 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
            <p className="mb-3 font-semibold text-slate-800">What happens next?</p>
            <ol className="space-y-2 text-sm text-slate-600">
              {[
                'Our admin reviews your AI-scored application.',
                'You\'ll receive an email notification on approval or rejection.',
                'If approved, funds are disbursed within 24 hours.',
              ].map((step, i) => (
                <li key={i} className="flex gap-3">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-bold text-blue-600">
                    {i + 1}
                  </span>
                  {step}
                </li>
              ))}
            </ol>
          </div>

          <div className="mt-6 flex gap-3">
            <Link to="/dashboard" className="flex-1">
              <Button variant="secondary" fullWidth>View My Loans</Button>
            </Link>
            <Button variant="primary" className="flex-1" onClick={() => setSubmitted(null)}>
              Apply Again
            </Button>
          </div>
        </div>
      </Layout>
    );
  }

  // ── Application form ─────────────────────────────────────────
  return (
    <Layout>
      <div className="mx-auto max-w-xl">
        <div className="mb-6">
          <h1 className="text-xl font-bold text-slate-900">Apply for a Loan</h1>
          <p className="text-sm text-slate-500">Fill in the details to get an instant AI credit score preview.</p>
        </div>

        {error && <Alert type="error" className="mb-5">{error}</Alert>}

        <div className="space-y-4">
          {/* Form card */}
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <form onSubmit={handleSubmit} className="space-y-5">
              <Input
                label="Loan amount (₹)"
                name="amount"
                type="number"
                value={form.amount}
                onChange={handleChange}
                placeholder="100000"
                min={10000}
                max={5000000}
                hint="Min ₹10,000 · Max ₹50,00,000"
                required
              />
              <Select
                label="Purpose"
                name="purpose"
                value={form.purpose}
                onChange={handleChange}
                options={PURPOSE_OPTIONS}
                placeholder="Select a purpose"
                required
              />
              <Select
                label="Loan tenure"
                name="termMonths"
                value={form.termMonths}
                onChange={handleChange}
                options={TERM_OPTIONS}
                placeholder="Select tenure"
                required
              />

              {/* Profile summary */}
              <div className="rounded-lg bg-slate-50 border border-slate-200 px-4 py-3">
                <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">Your Profile</p>
                <div className="flex items-center gap-4 text-sm">
                  <div>
                    <p className="text-xs text-slate-400">Monthly Income</p>
                    <p className="font-semibold text-slate-700">{formatCurrency(user?.monthlyIncome ?? 0)}</p>
                  </div>
                  <div className="h-8 w-px bg-slate-200" />
                  <div>
                    <p className="text-xs text-slate-400">Employment</p>
                    <p className="font-semibold text-slate-700">{user?.employmentType ?? 'N/A'}</p>
                  </div>
                </div>
              </div>

              <Button type="submit" fullWidth isLoading={isLoading} size="lg">
                Submit Application
              </Button>
            </form>
          </div>

          {/* Live credit score preview */}
          <ScorePreviewCard result={scorePreview} isLoading={scoreLoading} />

          {/* Hint when fields not filled */}
          {!scoreLoading && !scorePreview && (
            <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 px-5 py-6 text-center">
              <p className="text-sm font-medium text-slate-500">Credit Score Preview</p>
              <p className="mt-1 text-xs text-slate-400">
                Enter a loan amount and tenure above to see your estimated AI credit score instantly.
              </p>
            </div>
          )}
        </div>
      </div>
    </Layout>
  );
}
