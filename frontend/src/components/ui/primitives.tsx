import React from 'react';
import { cn, type BadgeVariant } from '@/lib/utils';
import { Loader2, X } from 'lucide-react';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger' | 'success' | 'warning';
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'icon';
  loading?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'primary', size = 'md', loading, disabled, children, type = 'button', ...props }, ref) => {
    const variants: Record<NonNullable<ButtonProps['variant']>, string> = {
      primary:
        'bg-indigo-600 text-white hover:bg-indigo-700 dark:bg-indigo-500 dark:hover:bg-indigo-400 dark:shadow-[0_1px_2px_rgba(0,0,0,0.4),inset_0_1px_0_rgba(255,255,255,0.18)] shadow-xs',
      secondary:
        'bg-slate-100 text-slate-800 hover:bg-slate-200 dark:bg-[#25314a] dark:text-slate-100 dark:border dark:border-[#3b4b6e] dark:hover:bg-[#303f5e]',
      outline:
        'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 hover:text-slate-900 dark:border-[#3a4868] dark:bg-[#1e273b] dark:text-slate-100 dark:hover:bg-[#293550] dark:hover:border-indigo-400/60 dark:hover:text-white',
      ghost:
        'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-[#222d44] dark:hover:text-white',
      danger: 'bg-rose-600 text-white hover:bg-rose-700 dark:bg-rose-600 dark:hover:bg-rose-500 shadow-xs',
      success: 'bg-emerald-600 text-white hover:bg-emerald-700 dark:bg-emerald-600 dark:hover:bg-emerald-500 shadow-xs',
      warning: 'bg-amber-500 text-white hover:bg-amber-600 shadow-xs',
    };
    const sizes: Record<NonNullable<ButtonProps['size']>, string> = {
      xs: 'h-7 px-2.5 text-xs rounded-md gap-1',
      sm: 'h-8 px-3 text-xs font-medium rounded-lg gap-1.5',
      md: 'h-9 px-3.5 text-sm font-medium rounded-lg gap-1.5',
      lg: 'h-10 px-4 text-sm font-semibold rounded-lg gap-2',
      icon: 'h-8 w-8 rounded-lg p-0 justify-center',
    };
    return (
      <button
        ref={ref}
        type={type}
        disabled={disabled || loading}
        className={cn(
          'inline-flex items-center justify-center transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/40 disabled:pointer-events-none disabled:opacity-50 cursor-pointer',
          variants[variant],
          sizes[size],
          className
        )}
        {...props}
      >
        {loading && <Loader2 className="h-3.5 w-3.5 animate-spin shrink-0" />}
        {children}
      </button>
    );
  }
);
Button.displayName = 'Button';

export function Card({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-xl border border-slate-200/90 bg-white shadow-2xs dark:border-[#2c3854] dark:bg-[#141a27] dark:shadow-[0_3px_12px_rgba(0,0,0,0.42),inset_0_1px_0_rgba(255,255,255,0.04)]',
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}

export function CardHeader({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'flex items-center justify-between gap-3 rounded-t-xl border-b border-slate-100 bg-slate-50/40 px-4 py-3 dark:border-[#2b3752] dark:bg-[#1b2335]',
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}

export function CardBody({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn('p-4', className)} {...props}>
      {children}
    </div>
  );
}

export function Badge({
  variant = 'default',
  className,
  children,
  ...props
}: React.HTMLAttributes<HTMLSpanElement> & {
  variant?: BadgeVariant;
}) {
  const styles: Record<BadgeVariant, string> = {
    default: 'bg-slate-100 text-slate-700 ring-slate-200 dark:bg-[#242f47] dark:text-slate-200 dark:ring-[#3b4a6b]',
    neutral: 'bg-slate-100 text-slate-700 ring-slate-200 dark:bg-[#242f47] dark:text-slate-200 dark:ring-[#3b4a6b]',
    success: 'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-500/20 dark:text-emerald-300 dark:ring-emerald-400/45',
    warning: 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-500/20 dark:text-amber-200 dark:ring-amber-400/45',
    danger: 'bg-rose-50 text-rose-700 ring-rose-200 dark:bg-rose-500/20 dark:text-rose-200 dark:ring-rose-400/45',
    info: 'bg-indigo-50 text-indigo-700 ring-indigo-200 dark:bg-indigo-500/22 dark:text-indigo-200 dark:ring-indigo-400/45',
    purple: 'bg-purple-50 text-purple-700 ring-purple-200 dark:bg-purple-500/22 dark:text-purple-200 dark:ring-purple-400/45',
  };
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        styles[variant],
        className
      )}
      {...props}
    >
      {children}
    </span>
  );
}

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'flex h-9 w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:opacity-60 dark:border-[#354261] dark:bg-[#0a0e16] dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-indigo-400 dark:disabled:bg-[#111623]',
        className
      )}
      {...props}
    />
  )
);
Input.displayName = 'Input';

export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(
        'flex w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:opacity-60 dark:border-[#354261] dark:bg-[#0a0e16] dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-indigo-400 dark:disabled:bg-[#111623]',
        className
      )}
      {...props}
    />
  )
);
Textarea.displayName = 'Textarea';

export const Select = React.forwardRef<HTMLSelectElement, React.SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, children, ...props }, ref) => (
    <select
      ref={ref}
      className={cn(
        'flex h-9 w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-900 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:opacity-60 dark:border-[#354261] dark:bg-[#0a0e16] dark:text-slate-100 dark:focus:border-indigo-400 dark:disabled:bg-[#111623]',
        className
      )}
      {...props}
    >
      {children}
    </select>
  )
);
Select.displayName = 'Select';

export function Switch({
  checked,
  onChange,
  disabled,
  label,
  className,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  label?: React.ReactNode;
  className?: string;
}) {
  return (
    <label className={cn('inline-flex items-center gap-2 select-none', disabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer', className)}>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => !disabled && onChange(!checked)}
        className={cn(
          'relative inline-flex h-5 w-9 shrink-0 rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/40',
          checked ? 'bg-indigo-600 dark:bg-indigo-500' : 'bg-slate-300 dark:bg-slate-700'
        )}
      >
        <span
          className={cn(
            'switch-thumb pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow-xs transition-transform mt-0.5',
            checked ? 'translate-x-4.5' : 'translate-x-0.5'
          )}
        />
      </button>
      {label && <span className="text-sm text-slate-700 dark:text-slate-300">{label}</span>}
    </label>
  );
}

export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  maxWidth = 'max-w-lg',
}: {
  open: boolean;
  onClose: () => void;
  title: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  maxWidth?: string;
}) {
  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 dark:bg-black/75 backdrop-blur-[2px]" onMouseDown={onClose}>
      <div
        className={cn(
          'w-full rounded-2xl bg-white dark:bg-[#161d2c] shadow-2xl border border-slate-200 dark:border-[#32405f] flex flex-col max-h-[90vh]',
          maxWidth
        )}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-3 rounded-t-2xl border-b border-slate-100 dark:border-[#2c3955] bg-slate-50/50 dark:bg-[#1d2639] px-5 py-3.5 shrink-0">
          <div className="font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-2">{title}</div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-[#2a3650] dark:hover:text-slate-100 cursor-pointer"
            aria-label="Закрыть"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="p-5 overflow-y-auto flex-1 text-slate-800 dark:text-slate-200">{children}</div>
        {footer && (
          <div className="flex items-center justify-end gap-2 rounded-b-2xl border-t border-slate-100 dark:border-[#2c3955] bg-slate-50/40 dark:bg-[#192132] px-5 py-3 shrink-0">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-12 text-sm text-slate-500 dark:text-slate-400">
      <Loader2 className="h-5 w-5 animate-spin text-indigo-600 dark:text-indigo-400" />
      <span>{label || 'Загрузка…'}</span>
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
      {icon && <div className="mb-3 rounded-full bg-slate-100 dark:bg-slate-800 p-3 text-slate-400">{icon}</div>}
      <div className="text-sm font-medium text-slate-900 dark:text-slate-100">{title}</div>
      {description && <div className="mt-1 max-w-md text-xs text-slate-500 dark:text-slate-400">{description}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
