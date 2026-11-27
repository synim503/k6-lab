import React, { useEffect, useState } from 'react';

export function calcExpr(text: unknown): number {
  const src = String(text ?? '')
    .replace(/,/g, '.')
    .replace(/[x×]/gi, '*')
    .replace(/÷/g, '/')
    .replace(/\s+/g, '');
  if (src === '' || !/^[0-9.+\-*/()]+$/.test(src)) return NaN;
  let i = 0;

  function num(): number {
    if (src[i] === '(') {
      i++;
      const v = add();
      if (src[i] !== ')') throw new Error('paren');
      i++;
      return v;
    }
    const m = /^[0-9]*\.?[0-9]+/.exec(src.slice(i));
    if (!m) throw new Error('num');
    i += m[0].length;
    return parseFloat(m[0]);
  }

  function unary(): number {
    if (src[i] === '-') {
      i++;
      return -unary();
    }
    if (src[i] === '+') {
      i++;
      return unary();
    }
    return num();
  }

  function mul(): number {
    let v = unary();
    while (src[i] === '*' || src[i] === '/') {
      const op = src[i++];
      const r = unary();
      v = op === '*' ? v * r : v / r;
    }
    return v;
  }

  function add(): number {
    let v = mul();
    while (src[i] === '+' || src[i] === '-') {
      const op = src[i++];
      const r = mul();
      v = op === '+' ? v + r : v - r;
    }
    return v;
  }

  try {
    const v = add();
    return i === src.length && Number.isFinite(v) ? v : NaN;
  } catch {
    return NaN;
  }
}

export function isFormulaString(text: string): boolean {
  const s = text.trim();
  if (!s) return false;
  return /[+\-*/×÷(),]/.test(s) && !/^-?\d+(\.\d+)?$/.test(s);
}

interface FormulaInputProps {
  value: number | string;
  onChange: (num: number) => void;
  integer?: boolean;
  min?: number;
  placeholder?: string;
  title?: string;
  className?: string;
  suffix?: string;
  disabled?: boolean;
}

export function FormulaInput({
  value,
  onChange,
  integer = false,
  min = 0,
  placeholder,
  title = 'Можно вводить формулу: 150*1.2, 95+95, (100+20)/2',
  className = '',
  suffix,
  disabled = false,
}: FormulaInputProps) {
  const [raw, setRaw] = useState<string>(String(value ?? ''));
  const [focused, setFocused] = useState(false);

  useEffect(() => {
    if (!focused) {
      setRaw(String(value ?? ''));
    }
  }, [value, focused]);

  const parsed = calcExpr(raw);
  const valid = Number.isFinite(parsed);
  const normalized = valid
    ? Math.max(min, integer ? Math.round(parsed) : Math.round(parsed * 10000) / 10000)
    : null;
  const hasFormula = isFormulaString(raw);

  const commit = () => {
    setFocused(false);
    if (normalized !== null) {
      setRaw(String(normalized));
      if (normalized !== Number(value)) {
        onChange(normalized);
      }
    } else {
      setRaw(String(value ?? 0));
    }
  };

  return (
    <div className={`relative inline-flex items-center ${className}`}>
      <input
        type="text"
        inputMode="decimal"
        autoComplete="off"
        disabled={disabled}
        value={raw}
        placeholder={placeholder}
        title={title}
        onFocus={() => setFocused(true)}
        onChange={(e) => {
          const next = e.target.value;
          setRaw(next);
          const v = calcExpr(next);
          if (Number.isFinite(v)) {
            const norm = Math.max(min, integer ? Math.round(v) : Math.round(v * 10000) / 10000);
            onChange(norm);
          }
        }}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            (e.target as HTMLInputElement).blur();
          }
        }}
        className={`w-full rounded-lg border px-2.5 py-1 text-xs font-mono text-slate-900 transition-colors focus:outline-none focus:ring-2 ${
          raw.trim() !== '' && !valid
            ? 'border-rose-300 bg-rose-50/40 focus:ring-rose-500/30'
            : hasFormula && valid
            ? 'border-indigo-300 bg-indigo-50/30 focus:ring-indigo-500/30'
            : 'border-slate-300 bg-white focus:border-indigo-500 focus:ring-indigo-500/20'
        } ${suffix ? 'pr-12' : ''} disabled:opacity-50`}
      />
      {hasFormula && valid && normalized !== null && (
        <span className="pointer-events-none absolute -top-2 right-1.5 rounded bg-indigo-600 px-1 py-0 text-[10px] font-mono font-semibold text-white shadow-xs">
          = {normalized}
        </span>
      )}
      {suffix && (
        <span className="pointer-events-none absolute right-2 text-[10px] font-medium text-slate-400">
          {suffix}
        </span>
      )}
    </div>
  );
}
