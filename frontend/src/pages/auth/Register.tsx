import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi, getErrorMessage } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Alert } from '../../components/ui/Alert';
import { EMPLOYMENT_TYPES } from '../../constants';

const perks = [
  'No hidden fees',
  'Real-time AI credit scoring',
  'Funds in your account in 24h',
  'Flexible tenure up to 60 months',
];

export default function Register() {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState({
    fullName: '',
    email: '',
    password: '',
    monthlyIncome: '',
    employmentType: '',
  });
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  function handleChange(e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');

    const income = parseFloat(form.monthlyIncome);
    if (!form.employmentType) {
      setError('Please select your employment type.');
      return;
    }
    if (isNaN(income) || income < 1000) {
      setError('Monthly income must be at least ₹1,000.');
      return;
    }

    setIsLoading(true);
    try {
      const user = await authApi.register({
        fullName: form.fullName,
        email: form.email,
        password: form.password,
        monthlyIncome: income,
        employmentType: form.employmentType,
      });
      login(user);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div className="grid min-h-screen grid-cols-5">
      {/* Left panel */}
      <div className="col-span-2 hidden flex-col justify-between bg-gradient-to-br from-emerald-800 via-teal-700 to-slate-900 p-10 lg:flex">
        <div className="flex items-center gap-2">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-emerald-500 font-bold text-white">
            SL
          </div>
          <span className="text-xl font-bold text-white">SmartLend</span>
        </div>

        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-emerald-300">
            Why SmartLend?
          </p>
          <h2 className="mb-5 text-3xl font-extrabold leading-tight text-white">
            Your loan,<br />your terms.
          </h2>
          <ul className="space-y-3">
            {perks.map((p) => (
              <li key={p} className="flex items-center gap-2.5 text-emerald-100">
                <svg className="h-4 w-4 shrink-0 text-emerald-400" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                <span className="text-sm">{p}</span>
              </li>
            ))}
          </ul>
        </div>

        <p className="text-xs text-emerald-300/60">© {new Date().getFullYear()} SmartLend</p>
      </div>

      {/* Right panel */}
      <div className="col-span-5 flex items-start justify-center overflow-y-auto bg-slate-50 px-6 py-10 lg:col-span-3 lg:items-center">
        <div className="w-full max-w-md">
          <div className="mb-7">
            <h1 className="text-2xl font-bold text-slate-900">Create your account</h1>
            <p className="mt-1 text-sm text-slate-500">Start your loan application today.</p>
          </div>

          {error && <Alert type="error" className="mb-5">{error}</Alert>}

          <form onSubmit={handleSubmit} className="space-y-4">
            <Input
              label="Full name"
              name="fullName"
              value={form.fullName}
              onChange={handleChange}
              placeholder="Shashank Dwivedi"
              autoComplete="name"
              required
            />
            <Input
              label="Email address"
              name="email"
              type="email"
              value={form.email}
              onChange={handleChange}
              placeholder="shashank@example.com"
              autoComplete="email"
              required
            />
            <Input
              label="Password"
              name="password"
              type="password"
              value={form.password}
              onChange={handleChange}
              placeholder="Min. 8 characters"
              autoComplete="new-password"
              minLength={8}
              required
            />
            <Input
              label="Monthly income (₹)"
              name="monthlyIncome"
              type="number"
              value={form.monthlyIncome}
              onChange={handleChange}
              placeholder="50000"
              min={1000}
              required
            />
            <Select
              label="Employment type"
              name="employmentType"
              value={form.employmentType}
              onChange={handleChange}
              options={[...EMPLOYMENT_TYPES]}
              placeholder="Select employment type"
              required
            />
            <Button
              type="submit"
              fullWidth
              isLoading={isLoading}
              variant="emerald"
              className="mt-2"
            >
              Create account
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-slate-500">
            Already have an account?{' '}
            <Link to="/login" className="font-medium text-emerald-600 hover:underline">
              Sign in
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
