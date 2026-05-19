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
import { LOAN_PURPOSES, LOAN_TERMS, RISK_CONFIG, EMPLOYMENT_TYPES } from '../../constants';
import { formatCurrency, formatPercent, shortId } from '../../utils/formatters';
import type { Loan } from '../../types';

const PURPOSE_OPTIONS = LOAN_PURPOSES.map((p) => ({ value: p, label: p }));
const TERM_OPTIONS = LOAN_TERMS.map((t) => ({ value: String(t), label: `${t} months` }));
const EMPLOYMENT_OPTIONS = EMPLOYMENT_TYPES.map((e) => ({ value: e.value, label: e.label }));
const EXISTING_LOANS_OPTIONS = [
  { value: '0', label: 'None' },
  { value: '1', label: '1 loan' },
  { value: '2', label: '2 loans' },
  { value: '3', label: '3+ loans' },
];

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

      <div className="mb-4">
        <CreditScoreBar score={result.credit_score} />
      </div>

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

      <p className="text-xs text-slate-500 italic">{result.reasoning}</p>
      <p className="mt-2 text-[10px] text-slate-400">
        * Estimate only. Final score is determined at submission.
      </p>
    </div>
  );
}

// ── Section Divider ───────────────────────────────────────────

function SectionHeader({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="mb-4">
      <p className="text-sm font-semibold text-slate-700">{title}</p>
      {subtitle && <p className="text-xs text-slate-400 mt-0.5">{subtitle}</p>}
    </div>
  );
}

// ── Main Component ────────────────────────────────────────────

export default function ApplyLoan() {
  const { user } = useAuth();

  const [form, setForm] = useState({
    amount: '',
    purpose: '',
    termMonths: '',
    monthlyIncome: String(user?.monthlyIncome ?? ''),
    employmentType: user?.employmentType ?? 'SALARIED',
    existingLoans: '0',
  });

  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [submitted, setSubmitted] = useState<Loan | null>(null);

  const [scorePreview, setScorePreview] = useState<ScorePreviewResult | null>(null);
  const [scoreLoading, setScoreLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Re-init income/employment if user loads after mount
  useEffect(() => {
    if (user && !form.monthlyIncome) {
      setForm((prev) => ({
        ...prev,
        monthlyIncome: String(user.monthlyIncome ?? ''),
        employmentType: user.employmentType ?? 'SALARIED',
      }));
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  // Fetch live score preview whenever key inputs change
  useEffect(() => {
    const amount = parseFloat(form.amount);
    const tenure = parseInt(form.termMonths, 10);
    const income = parseFloat(form.monthlyIncome);

    if (!amount || amount < 10000 || !tenure || !income) {
      setScorePreview(null);
      return;
    }

    if (debounceRef.current) clearTimeout(debounceRef.current);

    debounceRef.current = setTimeout(async () => {
      setScoreLoading(true);
      try {
        const result = await scoringApi.preview({
          monthly_income: income,
          requested_amount: amount,
          tenure_months: tenure,
          employment_type: form.employmentType,
          existing_loans: parseInt(form.existingLoans, 10),
        });
        setScorePreview(result);
      } catch {
        setScorePreview(null);
      } finally {
        setScoreLoading(false);
      }
    }, 600);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [form.amount, form.termMonths, form.monthlyIncome, form.employmentType, form.existingLoans]);

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

    const income = parseFloat(form.monthlyIncome);
    if (isNaN(income) || income <= 0) {
      setError('Please enter a valid monthly income.');
      return;
    }

    setIsLoading(true);
    try {
      const loan = await loanApi.apply(
        { principalAmount: amount, purpose: form.purpose, tenureMonths: parseInt(form.termMonths, 10) },
        user!.userId,
        income,
        form.employmentType
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

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Loan Details */}
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <SectionHeader title="Loan Details" />
            <div className="space-y-5">
              <Input
                label="Loan amount (₹)"
                name="amount"
                type="number"
                value={form.amount}
                onChange={handleChange}
                placeholder="e.g. 500000"
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
            </div>
          </div>

          {/* Financial Profile */}
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <SectionHeader
              title="Financial Profile"
              subtitle="Pre-filled from your profile — update if your situation has changed."
            />
            <div className="space-y-5">
              <Input
                label="Current monthly income (₹)"
                name="monthlyIncome"
                type="number"
                value={form.monthlyIncome}
                onChange={handleChange}
                placeholder="e.g. 75000"
                min={1}
                hint="Your take-home salary or business income per month"
                required
              />
              <Select
                label="Employment type"
                name="employmentType"
                value={form.employmentType}
                onChange={handleChange}
                options={EMPLOYMENT_OPTIONS}
                required
              />
              <div>
                <Select
                  label="Existing active loans"
                  name="existingLoans"
                  value={form.existingLoans}
                  onChange={handleChange}
                  options={EXISTING_LOANS_OPTIONS}
                />
                <p className="mt-1 text-xs text-slate-400">Include home loans, car loans, personal loans, etc.</p>
              </div>
            </div>

            <div className="mt-4 flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2.5 text-xs text-blue-700">
              <svg className="mt-0.5 h-3.5 w-3.5 shrink-0 text-blue-500" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
              </svg>
              This information is used only for AI credit scoring on this application. It does not update your profile.
            </div>
          </div>

          {/* Live credit score preview */}
          <ScorePreviewCard result={scorePreview} isLoading={scoreLoading} />

          {/* Hint when fields not filled */}
          {!scoreLoading && !scorePreview && (
            <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 px-5 py-6 text-center">
              <p className="text-sm font-medium text-slate-500">Credit Score Preview</p>
              <p className="mt-1 text-xs text-slate-400">
                Enter a loan amount, tenure, and income above to see your estimated AI credit score instantly.
              </p>
            </div>
          )}

          <Button type="submit" fullWidth isLoading={isLoading} size="lg">
            Submit Application
          </Button>
        </form>
      </div>
    </Layout>
  );
}