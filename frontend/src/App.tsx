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

function RootRedirect() {
  const { user, isAdmin } = useAuth();
  if (!user) return <Landing />;
  return <Navigate to={isAdmin ? '/admin' : '/dashboard'} replace />;
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  return user ? <>{children}</> : <Navigate to="/login" replace />;
}

function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { isAdmin } = useAuth();
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
