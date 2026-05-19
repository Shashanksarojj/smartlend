import axios, { AxiosError } from 'axios';
import type {
  AuthUser,
  Loan,
  EmiPayment,
  ApplyLoanRequest,
  AdminDecisionRequest,
  ApiError,
} from '../types';

const USER_BASE = process.env.REACT_APP_USER_URL ?? 'http://localhost:8081/api';
const LOAN_BASE = process.env.REACT_APP_LOAN_URL ?? 'http://localhost:8082/api';

// ── In-memory token ───────────────────────────────────────────
// Never written to localStorage — survives only for the current browser session.
// On page refresh, AuthContext restores it via GET /api/auth/me (uses the HttpOnly cookie).
let _token: string | null = null;
export function setInMemoryToken(token: string | null): void { _token = token; }

// ── Axios instances ───────────────────────────────────────────

// user-service: credentials (HttpOnly cookie) sent automatically; no Authorization header needed
const userHttp = axios.create({
  baseURL: USER_BASE,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

// loan-service: different domain — cookie won't reach it; use in-memory token in Authorization header
const loanHttp = axios.create({
  baseURL: LOAN_BASE,
  headers: { 'Content-Type': 'application/json' },
});

// ── Shared 401 handler ────────────────────────────────────────

function handleUnauthorized() {
  setInMemoryToken(null);
  localStorage.removeItem('smartlend_user');
  const isAuthPage = ['/login', '/register'].includes(window.location.pathname);
  if (!isAuthPage) window.location.href = '/login';
}

// userHttp: 401 → clear session and redirect
userHttp.interceptors.response.use(
  (res) => res,
  (err: AxiosError) => {
    if (err.response?.status === 401) handleUnauthorized();
    return Promise.reject(err);
  }
);

// loanHttp: inject Bearer token from memory; 401 → clear session and redirect
loanHttp.interceptors.request.use((config) => {
  if (_token) config.headers.Authorization = `Bearer ${_token}`;
  return config;
});
loanHttp.interceptors.response.use(
  (res) => res,
  (err: AxiosError) => {
    if (err.response?.status === 401) handleUnauthorized();
    return Promise.reject(err);
  }
);

// ── Error helper ──────────────────────────────────────────────

export function getErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as ApiError | undefined;
    if (data?.message) return data.message;
    if (data?.error) return data.error;
    return err.message;
  }
  return err instanceof Error ? err.message : 'Something went wrong';
}

// ── Auth API ──────────────────────────────────────────────────

export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
  monthlyIncome: number;
  employmentType: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export const authApi = {
  register: (data: RegisterRequest) =>
    userHttp.post<AuthUser>('/auth/register', data).then((r) => r.data),

  login: (data: LoginRequest) =>
    userHttp.post<AuthUser>('/auth/login', data).then((r) => r.data),

  /** Restore session using the HttpOnly cookie — returns fresh profile + token. */
  me: () => userHttp.get<AuthUser>('/auth/me').then((r) => r.data),

  /** Clear the HttpOnly cookie server-side. */
  logout: () => userHttp.post<void>('/auth/logout').then(() => {}),

  createAdmin: (data: RegisterRequest) =>
    userHttp.post<AuthUser>('/auth/admin/create-admin', data).then((r) => r.data),
};

// ── AI Scoring preview ────────────────────────────────────────

const AI_BASE = process.env.REACT_APP_AI_URL ?? 'http://localhost:8000';
const aiHttp = axios.create({ baseURL: AI_BASE, headers: { 'Content-Type': 'application/json' } });

export interface ScorePreviewRequest {
  monthly_income: number;
  requested_amount: number;
  tenure_months: number;
  employment_type: string;
  existing_loans?: number;
}

export interface ScorePreviewResult {
  credit_score: number;
  risk_label: string;
  suggested_rate: number;
  recommendation: 'APPROVE' | 'REJECT';
  reasoning: string;
}

export const scoringApi = {
  preview: (data: ScorePreviewRequest) =>
    aiHttp.post<ScorePreviewResult>('/score', data).then((r) => r.data),
};

// ── Loan API ──────────────────────────────────────────────────

export const loanApi = {
  apply: (data: ApplyLoanRequest, userId: string, monthlyIncome: number, employmentType: string) =>
    loanHttp
      .post<Loan>('/loans/apply', data, {
        headers: {
          'X-User-Id': userId,
          'X-Monthly-Income': String(monthlyIncome),
          'X-Employment-Type': employmentType,
        },
      })
      .then((r) => r.data),

  myLoans: (userId: string) =>
    loanHttp
      .get<Loan[]>('/loans/my', { headers: { 'X-User-Id': userId } })
      .then((r) => r.data),

  allLoans: () =>
    loanHttp.get<Loan[]>('/loans/admin/all').then((r) => r.data),

  adminDecision: (loanId: string, data: AdminDecisionRequest) =>
    loanHttp.put<Loan>(`/loans/admin/${loanId}/decision`, data).then((r) => r.data),

  emiSchedule: (loanId: string) =>
    loanHttp.get<EmiPayment[]>(`/loans/${loanId}/emi-schedule`).then((r) => r.data),
};