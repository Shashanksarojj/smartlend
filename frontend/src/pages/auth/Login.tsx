import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi, getErrorMessage } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Alert } from '../../components/ui/Alert';

const bullets = [
  'AI-powered credit scoring',
  'Decisions in under 2 minutes',
  'Transparent interest rates',
  'Secure JWT-based sessions',
];

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setIsLoading(true);
    try {
      const user = await authApi.login({ email, password });
      login(user);
      navigate(user.role === 'ADMIN' ? '/admin' : '/dashboard', { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div className="grid h-screen grid-cols-5">
      {/* Left panel — 2/5 */}
      <div className="col-span-2 hidden flex-col justify-between bg-gradient-to-br from-blue-900 via-blue-800 to-slate-900 p-10 lg:flex">
        <div>
          <div className="flex items-center gap-2">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-500 font-bold text-white">
              SL
            </div>
            <span className="text-xl font-bold text-white">SmartLend</span>
          </div>
        </div>

        <div>
          <h2 className="mb-4 text-3xl font-extrabold leading-tight text-white">
            AI-powered loans,<br />built for speed.
          </h2>
          <ul className="space-y-3">
            {bullets.map((b) => (
              <li key={b} className="flex items-center gap-2.5 text-blue-100">
                <svg className="h-4 w-4 shrink-0 text-blue-400" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                <span className="text-sm">{b}</span>
              </li>
            ))}
          </ul>
        </div>

        <p className="text-xs text-blue-300/60">
          © {new Date().getFullYear()} SmartLend
        </p>
      </div>

      {/* Right panel — 3/5 */}
      <div className="col-span-5 flex items-center justify-center bg-slate-50 px-6 lg:col-span-3">
        <div className="w-full max-w-md">
          <div className="mb-8">
            <h1 className="text-2xl font-bold text-slate-900">Welcome back</h1>
            <p className="mt-1 text-sm text-slate-500">
              Sign in to manage your loans.
            </p>
          </div>

          {error && <Alert type="error" className="mb-5">{error}</Alert>}

          <form onSubmit={handleSubmit} className="space-y-4">
            <Input
              label="Email address"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              autoComplete="email"
              required
            />
            <Input
              label="Password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
              required
            />
            <Button type="submit" fullWidth isLoading={isLoading} className="mt-2">
              Sign in
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-slate-500">
            Don't have an account?{' '}
            <Link to="/register" className="font-medium text-blue-600 hover:underline">
              Apply for a loan
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
