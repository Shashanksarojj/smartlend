import type { LoanStatus, RiskLabel, PaymentStatus } from '../types';

interface StatusConfig {
  label: string;
  badge: string;
}

export const LOAN_STATUS_CONFIG: Record<LoanStatus, StatusConfig> = {
  PENDING:  { label: 'Pending Review', badge: 'bg-amber-100 text-amber-800' },
  APPROVED: { label: 'Approved',       badge: 'bg-emerald-100 text-emerald-800' },
  REJECTED: { label: 'Rejected',       badge: 'bg-red-100 text-red-800' },
  DISBURSED:{ label: 'Disbursed',      badge: 'bg-blue-100 text-blue-800' },
  CLOSED:   { label: 'Closed',         badge: 'bg-slate-100 text-slate-600' },
};

export const RISK_CONFIG: Record<RiskLabel, StatusConfig> = {
  LOW:      { label: 'Low Risk',       badge: 'bg-emerald-100 text-emerald-800' },
  MEDIUM:   { label: 'Medium Risk',    badge: 'bg-amber-100 text-amber-800' },
  HIGH:     { label: 'High Risk',      badge: 'bg-orange-100 text-orange-800' },
  VERY_HIGH:{ label: 'Very High Risk', badge: 'bg-red-100 text-red-800' },
};

export const PAYMENT_STATUS_CONFIG: Record<PaymentStatus, StatusConfig> = {
  PENDING: { label: 'Pending', badge: 'bg-amber-100 text-amber-800' },
  PAID:    { label: 'Paid',    badge: 'bg-emerald-100 text-emerald-800' },
  OVERDUE: { label: 'Overdue', badge: 'bg-red-100 text-red-800' },
};

export const LOAN_PURPOSES = [
  'Home Renovation',
  'Medical Emergency',
  'Education',
  'Business Expansion',
  'Debt Consolidation',
  'Vehicle Purchase',
  'Wedding',
  'Travel',
  'Other',
] as const;

export const EMPLOYMENT_TYPES = [
  { value: 'SALARIED',      label: 'Salaried' },
  { value: 'SELF_EMPLOYED', label: 'Self Employed' },
  { value: 'BUSINESS',      label: 'Business Owner' },
] as const;

export const LOAN_TERMS = [6, 12, 18, 24, 36, 48, 60] as const;
