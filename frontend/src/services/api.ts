import axios, { AxiosInstance, AxiosError } from 'axios';
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

function createInstance(baseURL: string): AxiosInstance {
  return axios.create({ baseURL, headers: { 'Content-Type': 'application/json' } });
}

const userHttp = createInstance(USER_BASE);
const loanHttp = createInstance(LOAN_BASE);

function attachInterceptors(instance: AxiosInstance): void {
  instance.interceptors.request.use((config) => {
    const raw = localStorage.getItem('smartlend_user');
    if (raw) {
      const user: AuthUser = JSON.parse(raw);
      config.headers.Authorization = `Bearer ${user.token}`;
    }
    return config;
  });

  instance.interceptors.response.use(
    (res) => res,
    (err: AxiosError) => {
      const isAuthPage = ['/login', '/register'].includes(window.location.pathname);
      if (err.response?.status === 401 && !isAuthPage) {
        localStorage.removeItem('smartlend_user');
        window.location.href = '/login';
      }
      return Promise.reject(err);
    }
  );
}

attachInterceptors(userHttp);
attachInterceptors(loanHttp);

export function getErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as ApiError | undefined;
    if (data?.message) return data.message;
    if (data?.error) return data.error;
    return err.message;
  }
  return err instanceof Error ? err.message : 'Something went wrong';
}

// Auth
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

  createAdmin: (data: RegisterRequest) =>
    userHttp.post<AuthUser>('/auth/admin/create-admin', data).then((r) => r.data),
};

// AI Scoring preview (called directly — separate service on :8000)
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

// Loans
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
