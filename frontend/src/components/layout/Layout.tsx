import React from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { cn } from '../../utils/cn';

interface NavItem {
  to: string;
  label: string;
  icon: React.ReactNode;
}

const HomeIcon = () => (
  <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
      d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
  </svg>
);

const PlusIcon = () => (
  <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
  </svg>
);

const ShieldIcon = () => (
  <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
      d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
  </svg>
);

const APPLICANT_NAV: NavItem[] = [
  { to: '/dashboard', label: 'My Loans',    icon: <HomeIcon /> },
  { to: '/apply',     label: 'Apply',        icon: <PlusIcon /> },
];

const ADMIN_NAV: NavItem[] = [
  { to: '/admin', label: 'All Loans', icon: <ShieldIcon /> },
];

export function Layout({ children }: { children: React.ReactNode }) {
  const { user, isAdmin, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  const nav = isAdmin ? ADMIN_NAV : APPLICANT_NAV;
  const accent = isAdmin ? 'violet' : 'blue';

  const activeClass = accent === 'violet'
    ? 'bg-violet-700 text-white'
    : 'bg-blue-700 text-white';

  const inactiveClass = 'text-slate-300 hover:bg-white/10 hover:text-white';

  function handleLogout() {
    logout();
    navigate('/login');
  }

  return (
    <div className="flex h-screen bg-slate-100">
      {/* Sidebar */}
      <aside
        className={cn(
          'flex w-60 shrink-0 flex-col',
          accent === 'violet' ? 'bg-violet-900' : 'bg-blue-900'
        )}
      >
        {/* Brand */}
        <div className="flex items-center gap-2.5 px-5 py-5 border-b border-white/10">
          <div className={cn(
            'flex h-8 w-8 items-center justify-center rounded-lg text-xs font-bold',
            accent === 'violet' ? 'bg-violet-500' : 'bg-blue-500'
          )}>
            SL
          </div>
          <div>
            <p className="text-sm font-semibold text-white">SmartLend</p>
            <p className="text-xs text-slate-400">
              {isAdmin ? 'Admin Portal' : 'Applicant Portal'}
            </p>
          </div>
        </div>

        {/* Nav */}
        <nav className="flex-1 space-y-1 px-3 py-4">
          {nav.map(({ to, label, icon }) => {
            const isActive = location.pathname === to;
            return (
              <Link
                key={to}
                to={to}
                className={cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                  isActive ? activeClass : inactiveClass
                )}
              >
                {icon}
                {label}
              </Link>
            );
          })}
        </nav>

        {/* User footer */}
        <div className="border-t border-white/10 px-3 py-4">
          <div className="mb-3 flex items-center gap-3 px-2">
            <div className={cn(
              'flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-sm font-semibold text-white',
              accent === 'violet' ? 'bg-violet-500' : 'bg-blue-500'
            )}>
              {user?.fullName?.[0]?.toUpperCase() ?? '?'}
            </div>
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-white">{user?.fullName}</p>
              <p className="truncate text-xs text-slate-400">{user?.role}</p>
            </div>
          </div>
          <button
            onClick={handleLogout}
            className="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-300 hover:bg-white/10 hover:text-white transition-colors"
          >
            Sign out
          </button>
        </div>
      </aside>

      {/* Main */}
      <main className="flex-1 overflow-y-auto">
        <div className="min-h-full p-6 lg:p-8">{children}</div>
      </main>
    </div>
  );
}
