import React from 'react';
import { cn } from '../../utils/cn';

const styles = {
  error:   'bg-red-50 border-red-200 text-red-800',
  success: 'bg-emerald-50 border-emerald-200 text-emerald-800',
  warning: 'bg-amber-50 border-amber-200 text-amber-800',
  info:    'bg-blue-50 border-blue-200 text-blue-800',
} as const;

type AlertType = keyof typeof styles;

interface AlertProps {
  type?: AlertType;
  title?: string;
  children: React.ReactNode;
  className?: string;
}

export function Alert({ type = 'info', title, children, className }: AlertProps) {
  return (
    <div className={cn('rounded-lg border px-4 py-3 text-sm', styles[type], className)}>
      {title && <p className="font-semibold mb-0.5">{title}</p>}
      <p>{children}</p>
    </div>
  );
}
