import React from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../components/ui/Button';

const stats = [
  { label: 'Loans Processed', value: '12,400+' },
  { label: 'Avg. Decision Time', value: '< 2 min' },
  { label: 'Approval Rate', value: '78%' },
  { label: 'Satisfied Customers', value: '9,800+' },
];

const steps = [
  { n: '01', title: 'Register',          desc: 'Create your free account in under a minute.' },
  { n: '02', title: 'Submit Application', desc: 'Fill in loan details — amount, purpose, tenure.' },
  { n: '03', title: 'AI Assessment',      desc: 'Our model scores your profile instantly.' },
  { n: '04', title: 'Get Funds',          desc: 'Approved amounts are disbursed to your account.' },
];

const features = [
  { icon: '⚡', title: 'Instant AI Scoring',      desc: 'ML model evaluates risk in milliseconds.' },
  { icon: '🔒', title: 'Bank-grade Security',     desc: 'JWT auth, encrypted storage, HTTPS-only.' },
  { icon: '📊', title: 'EMI Schedule',             desc: 'Amortization table generated on approval.' },
  { icon: '🔔', title: 'Real-time Notifications', desc: 'RabbitMQ-powered alerts on every status change.' },
  { icon: '📱', title: 'Responsive Portal',        desc: 'Works seamlessly across all devices.' },
  { icon: '🛡️', title: 'Role-based Access',        desc: 'Separate flows for applicants and admins.' },
];

export default function Landing() {
  return (
    <div className="min-h-screen bg-slate-950 text-white">
      {/* Nav */}
      <nav className="flex items-center justify-between px-6 py-4 lg:px-16">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600 text-xs font-bold">
            SL
          </div>
          <span className="text-lg font-bold">SmartLend</span>
        </div>
        <div className="flex items-center gap-3">
          <Link to="/login">
            <Button variant="ghost" size="sm" className="text-slate-300">
              Sign in
            </Button>
          </Link>
          <Link to="/register">
            <Button size="sm">Apply Now</Button>
          </Link>
        </div>
      </nav>

      {/* Hero */}
      <section className="mx-auto max-w-5xl px-6 py-20 text-center lg:py-28">
        <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-blue-500/30 bg-blue-500/10 px-4 py-1.5 text-sm text-blue-300">
          <span className="h-2 w-2 rounded-full bg-blue-400 animate-pulse" />
          AI-Powered Loan Decisions
        </div>
        <h1 className="mb-6 text-4xl font-extrabold leading-tight tracking-tight lg:text-6xl">
          Smart lending,{' '}
          <span className="bg-gradient-to-r from-blue-400 to-violet-400 bg-clip-text text-transparent">
            instant decisions
          </span>
        </h1>
        <p className="mx-auto mb-10 max-w-2xl text-lg text-slate-400">
          Apply for a personal loan and get an AI-scored risk assessment in seconds.
          Transparent rates, fast disbursal, zero paperwork.
        </p>
        <div className="flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
          <Link to="/register">
            <Button size="lg" className="min-w-[160px]">
              Get Started Free
            </Button>
          </Link>
          <Link to="/login">
            <Button variant="secondary" size="lg" className="min-w-[160px] bg-white/10 text-white hover:bg-white/20">
              Sign In
            </Button>
          </Link>
        </div>
      </section>

      {/* Stats */}
      <section className="border-y border-white/5 bg-white/5 py-10">
        <div className="mx-auto grid max-w-4xl grid-cols-2 gap-6 px-6 lg:grid-cols-4">
          {stats.map((s) => (
            <div key={s.label} className="text-center">
              <p className="text-2xl font-bold text-white">{s.value}</p>
              <p className="mt-1 text-sm text-slate-400">{s.label}</p>
            </div>
          ))}
        </div>
      </section>

      {/* How it works */}
      <section className="mx-auto max-w-5xl px-6 py-20">
        <h2 className="mb-12 text-center text-2xl font-bold">How it works</h2>
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {steps.map((s) => (
            <div key={s.n} className="rounded-xl border border-white/10 bg-white/5 p-6">
              <p className="mb-3 text-3xl font-extrabold text-blue-500">{s.n}</p>
              <p className="mb-1 font-semibold">{s.title}</p>
              <p className="text-sm text-slate-400">{s.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Features */}
      <section className="bg-white/5 py-20">
        <div className="mx-auto max-w-5xl px-6">
          <h2 className="mb-12 text-center text-2xl font-bold">Platform Features</h2>
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {features.map((f) => (
              <div
                key={f.title}
                className="rounded-xl border border-white/10 bg-slate-900 p-5 transition-colors hover:border-blue-500/30"
              >
                <span className="text-2xl">{f.icon}</span>
                <p className="mt-3 font-semibold">{f.title}</p>
                <p className="mt-1 text-sm text-slate-400">{f.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="mx-auto max-w-3xl px-6 py-20 text-center">
        <div className="rounded-2xl bg-gradient-to-r from-blue-600 to-violet-600 p-10">
          <h2 className="mb-3 text-2xl font-bold">Ready to apply?</h2>
          <p className="mb-8 text-blue-100">
            Join thousands who got their loan approved with SmartLend.
          </p>
          <Link to="/register">
            <Button
              size="lg"
              className="bg-white text-blue-700 hover:bg-blue-50 focus-visible:ring-white"
            >
              Apply for a Loan
            </Button>
          </Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t border-white/5 py-8 text-center text-sm text-slate-500">
        © {new Date().getFullYear()} SmartLend. Built with Spring Boot, FastAPI &amp; React.
      </footer>
    </div>
  );
}
