export type Role = 'APPLICANT' | 'ADMIN';
export type KycStatus = 'PENDING' | 'VERIFIED' | 'REJECTED';
export type LoanStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'DISBURSED' | 'CLOSED';
export type RiskLabel = 'LOW' | 'MEDIUM' | 'HIGH' | 'VERY_HIGH';
export type PaymentStatus = 'PENDING' | 'PAID' | 'OVERDUE';
export type EmploymentType = 'SALARIED' | 'SELF_EMPLOYED' | 'BUSINESS' | 'FREELANCER';

export interface AuthUser {
  userId: string;
  email: string;
  name: string;
  role: Role;
  token: string;
  monthlyIncome?: number;
  employmentType?: EmploymentType;
}

export interface Loan {
  id: string;
  userId: string;
  principalAmount: number;
  purpose: string;
  tenureMonths: number;
  status: LoanStatus;
  interestRate: number;
  creditScore: number;
  riskLabel: RiskLabel;
  adminNote?: string;
  emiAmount?: number;
  totalPayable?: number;
  outstandingAmount?: number;
  appliedAt: string;
}

export interface EmiPayment {
  installmentNumber: number;
  dueDate: string;
  principalComponent: number;
  interestComponent: number;
  emiAmount: number;
  remainingBalance: number;
  status: PaymentStatus;
}

export interface LoanSummary {
  totalLoans: number;
  pendingLoans: number;
  activeLoans: number;
}

export interface ApplyLoanRequest {
  principalAmount: number;
  purpose: string;
  tenureMonths: number;
}

export interface AdminDecisionRequest {
  decision: 'APPROVED' | 'REJECTED';
  interestRate?: number;
  adminNote?: string;
}

export interface ApiError {
  status: number;
  error: string;
  message: string;
  path: string;
  timestamp: string;
  fields?: Record<string, string>;
}

export interface ScoreResponse {
  creditScore: number;
  riskLabel: RiskLabel;
  recommendation: string;
}
