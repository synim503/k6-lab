import React, { createContext, useCallback, useContext, useState } from 'react';
import { Button, Modal } from './primitives';
import { AlertTriangle } from 'lucide-react';

export interface ConfirmOptions {
  title: string;
  message: string;
  items?: string[];
  confirmText?: string;
  variant?: 'danger' | 'warning' | 'primary';
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<ConfirmFn>(() => Promise.resolve(true));

export function useConfirm() {
  return useContext(ConfirmContext);
}

export function ConfirmProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<(ConfirmOptions & { resolve: (v: boolean) => void }) | null>(null);

  const confirm = useCallback<ConfirmFn>((opts) => {
    return new Promise<boolean>((resolve) => {
      setState({ ...opts, resolve });
    });
  }, []);

  const close = (result: boolean) => {
    if (state) {
      state.resolve(result);
      setState(null);
    }
  };

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <Modal
        open={!!state}
        onClose={() => close(false)}
        maxWidth="max-w-md"
        title={
          <span className="flex items-center gap-2">
            <AlertTriangle className={state?.variant === 'warning' ? 'h-4 w-4 text-amber-500' : 'h-4 w-4 text-rose-600'} />
            {state?.title || 'Подтвердите действие'}
          </span>
        }
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => close(false)}>
              Отмена
            </Button>
            <Button
              variant={state?.variant === 'warning' ? 'warning' : state?.variant === 'primary' ? 'primary' : 'danger'}
              size="sm"
              onClick={() => close(true)}
            >
              {state?.confirmText || 'Удалить'}
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-600 leading-relaxed">{state?.message}</p>
        {state?.items && state.items.length > 0 && (
          <ul className="mt-3 max-h-40 overflow-y-auto rounded-lg bg-slate-50 border border-slate-200 px-3 py-2 text-xs text-slate-700 space-y-1">
            {state.items.map((item, i) => (
              <li key={i} className="truncate font-mono">
                • {item}
              </li>
            ))}
          </ul>
        )}
      </Modal>
    </ConfirmContext.Provider>
  );
}
