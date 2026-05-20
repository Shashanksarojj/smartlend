import axios from 'axios';
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import type {
  AuthUser,
  Loan,
  EmiPayment,
  ApplyLoanRequest,
  AdminDecisionRequest,
  ApiError,
} from '../types';

// ── In-memory token ───────────────────────────────────────────
// Primary store: module-level variable (never in localStorage — invisible to XSS).
// Fallback store: sessionStorage — survives page refresh within the same tab,
// cleared when the tab closes. This prevents a 401 race where loan API calls
// fire before AuthContext's /me round-trip completes on a slow Railway cold start.
const SESSION_TOKEN_KEY = 'smartlend_session_token';

let _token: string | null = sessionStorage.getItem(SESSION_TOKEN_KEY);

export function setInMemoryToken(token: string | null): void {
  _token = token;
  if (token) sessionStorage.setItem(SESSION_TOKEN_KEY, token);
  else        sessionStorage.removeItem(SESSION_TOKEN_KEY);
}
export function getToken(): string | null { return _token; }

// ── HTTP client factory ───────────────────────────────────────

interface HttpClientOptions {
  baseURL: string;
  withCredentials?: boolean;
  timeout?: number;
  /** Max retries on network errors or 5xx. 4xx are never retried. */
  retries?: number;
  /** Return the Bearer token to inject, or null to skip the header. */
  getToken?: () => string | null;
  /** Called once when a 401 is received — should clear session and redirect. */
  onUnauthorized?: () => void;
}

// Extend config to track retry state across interceptor re-invocations.
type RetryableConfig = InternalAxiosRequestConfig & { _retryCount?: number };

function createHttpClient({
  baseURL,
  withCredentials = false,
  timeout = 10_000,
  retries = 2,
  getToken: tokenFn,
  onUnauthorized,
}: HttpClientOptions): AxiosInstance {
  const instance = axios.create({
    baseURL,
    withCredentials,
    timeout,
    headers: { 'Content-Type': 'application/json' },
  });

  // Request: inject Bearer token + add correlation ID for distributed tracing
  instance.interceptors.request.use((config) => {
    const token = tokenFn?.();
    if (token) config.headers.Authorization = `Bearer ${token}`;
    config.headers['X-Request-Id'] = crypto.randomUUID();
    return config;
  });

  // Dev logging — stripped in production builds by tree-shaking
  if (process.env.NODE_ENV === 'development') {
    instance.interceptors.request.use((config) => {
      console.debug(`→ ${config.method?.toUpperCase()} ${config.baseURL}${config.url}`);
      return config;
    });
    instance.interceptors.response.use((res) => {
      console.debug(`← ${res.status} ${res.config.url}`);
      return res;
    });
  }

  // Response: retry on transient failures; hard-stop on 4xx
  instance.interceptors.response.use(
    (res) => res,
    async (err: unknown) => {
      if (!axios.isAxiosError(err)) return Promise.reject(err);

      const config = err.config as RetryableConfig | undefined;
      const status = err.response?.status;

      // 401 → sign out immediately, never retry
      if (status === 401) {
        onUnauthorized?.();
        return Promise.reject(err);
      }

      // Any other 4xx (400, 403, 404, 422…) → client error, surface immediately
      const isClientError = status !== undefined && status >= 400 && status < 500;
      if (isClientError) return Promise.reject(err);

      // Network error or 5xx → retry with exponential backoff
      const retriesUsed = config?._retryCount ?? 0;
      if (config && retriesUsed < retries) {
        (config as RetryableConfig)._retryCount = retriesUsed + 1;
        await new Promise((r) => setTimeout(r, 2 ** retriesUsed * 300)); // 300ms, 600ms
        return instance(config);
      }

      return Promise.reject(err);
    }
  );

  return instance;
}

// ── Shared 401 handler ────────────────────────────────────────

function handleUnauthorized(): void {
  setInMemoryToken(null);                        // clears _token + sessionStorage
  localStorage.removeItem('smartlend_user');
  if (!['/login', '/register'].includes(window.location.pathname)) {
    window.location.href = '/login';
  }
}

// ── Axios instances ───────────────────────────────────────────

const USER_BASE = process.env.REACT_APP_USER_URL ?? 'http://localhost:8081/api';
const LOAN_BASE = process.env.REACT_APP_LOAN_URL ?? 'http://localhost:8082/api';
const AI_BASE   = process.env.REACT_APP_AI_URL   ?? 'http://localhost:8000';

// user-service: auth via HttpOnly cookie (withCredentials); no Bearer header needed
const userHttp = createHttpClient({
  baseURL: USER_BASE,
  withCredentials: true,
  onUnauthorized: handleUnauthorized,
});

// loan-service: different domain — cookie won't reach it; Bearer from in-memory token
const loanHttp = createHttpClient({
  baseURL: LOAN_BASE,
  getToken,
  onUnauthorized: handleUnauthorized,
});

// ai-scoring: public endpoint, no auth
const aiHttp = createHttpClient({ baseURL: AI_BASE });

// ── Error helper ──────────────────────────────────────────────

export function getErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as ApiError | undefined;
    if (data?.message) return data.message;
    if (data?.error)   return data.error;
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