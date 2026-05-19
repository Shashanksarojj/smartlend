import React from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';

import Landing from './pages/Landing';
import Login from './pages/auth/Login';
import Register from './pages/auth/Register';
import Dashboard from './pages/applicant/Dashboard';
import ApplyLoan from './pages/applicant/ApplyLoan';
import EmiSchedule from './pages/applicant/EmiSchedule';
import AdminDashboard from './pages/admin/AdminDashboard';

function Spinner() {
  return (
    <div className="flex h-screen items-center justify-center bg-slate-100">
      <svg className="h-8 w-8 animate-spin text-blue-500" fill="none" viewBox="0 0 24 24">
        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
      </svg>
    </div>
  );
}

function RootRedirect() {
  const { user, isAdmin, isRestoring } = useAuth();
  if (isRestoring) return <Spinner />;
  if (!user) return <Landing />;
  return <Navigate to={isAdmin ? '/admin' : '/dashboard'} replace />;
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, isRestoring } = useAuth();
  if (isRestoring) return <Spinner />;
  return user ? <>{children}</> : <Navigate to="/login" replace />;
}

function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { user, isAdmin, isRestoring } = useAuth();
  if (isRestoring) return <Spinner />;
  if (!user) return <Navigate to="/login" replace />;
  return isAdmin ? <>{children}</> : <Navigate to="/dashboard" replace />;
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<RootRedirect />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />

          <Route
            path="/dashboard"
            element={<RequireAuth><Dashboard /></RequireAuth>}
          />
          <Route
            path="/apply"
            element={<RequireAuth><ApplyLoan /></RequireAuth>}
          />
          <Route
            path="/loans/:loanId/schedule"
            element={<RequireAuth><EmiSchedule /></RequireAuth>}
          />
          <Route
            path="/admin"
            element={<RequireAdmin><AdminDashboard /></RequireAdmin>}
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}