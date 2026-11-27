import React, { useEffect, useMemo, useState } from 'react';
import { Button, Input, Select } from '@/components/ui/primitives';
import { FormulaInput, calcExpr } from './FormulaInput';
import { fmtDurationSec, parseDurationToSec } from '@/lib/utils';
import {
  ArrowDown,
  ArrowUp,
  CheckCircle2,
  Flame,
  Info,
  Plus,
  Sparkles,
  TrendingUp,
  Wand2,
  X,
} from 'lucide-react';

export interface K6Stage {
  duration: string;
  target: number;
}

export interface LoadStep {
  target: number;
  hold: string;
  ramp: string;
}

export const STAGE_PRESETS: { label: string; short: string; fn: (base: number) => K6Stage[] }[] = [
  {
    label: 'Поиск максимума: 5 ступеней скачком (0s)',
    short: '5 ступ. скачком (0s)',
    fn: (base) => {
      const b = Math.max(1, base || 100);
      const out: K6Stage[] = [];
      [1, 1.2, 1.4, 1.6, 1.8].forEach((k) => {
        const t = Math.round(b * k);
        out.push({ duration: '0s', target: t }, { duration: '5m', target: t });
      });
      return out;
    },
  },
  {
    label: 'Поиск максимума: 5 ступеней плавно (30s)',
    short: '5 ступ. плавно (30s)',
    fn: (base) => {
      const b = Math.max(1, base || 40);
      const out: K6Stage[] = [];
      [1, 1.2, 1.4, 1.6, 1.8].forEach((k, idx) => {
        const t = Math.round(b * k);
        out.push({ duration: idx === 0 ? '1m' : '30s', target: t }, { duration: '3m', target: t });
      });
      return out;
    },
  },
  {
    label: '3 ступени (100 / 120 / 140%)',
    short: '3 ступ. (100/120/140%)',
    fn: (base) => {
      const b = Math.max(1, base || 50);
      return [
        { duration: '1m', target: b },
        { duration: '3m', target: b },
        { duration: '30s', target: Math.round(b * 1.2) },
        { duration: '3m', target: Math.round(b * 1.2) },
        { duration: '30s', target: Math.round(b * 1.4) },
        { duration: '3m', target: Math.round(b * 1.4) },
      ];
    },
  },
  {
    label: 'Разгон 1m + полка 5m',
    short: 'Разгон 1m + полка 5m',
    fn: (base) => {
      const b = Math.max(1, base || 50);
      return [
        { duration: '1m', target: b },
        { duration: '5m', target: b },
      ];
    },
  },
  {
    label: 'Пиковый всплеск (Spike ×3)',
    short: 'Пик (Spike ×3)',
    fn: (base) => {
      const b = Math.max(1, base || 30);
      return [
        { duration: '1m', target: b },
        { duration: '2m', target: b },
        { duration: '30s', target: b * 3 },
        { duration: '2m', target: b * 3 },
        { duration: '30s', target: b },
        { duration: '2m', target: b },
      ];
    },
  },
];

export function stepUnitShort(executor: string, timeUnit?: string): string {
  if (!executor.includes('arrival')) return 'VU';
  const tu = String(timeUnit || '1s').trim();
  return !tu || tu === '1s' ? 'оп/с' : `оп/${tu}`;
}

export function parseStepsFromStages(
  stages: K6Stage[],
  startVal: number,
  warmStagesCount: number,
  fallbackWarmDur = '2m'
): { exact: boolean; warm: K6Stage | null; steps: LoadStep[] } {
  const list = stages || [];
  const warmN = Math.min(warmStagesCount || 0, list.length);
  const warm =
    warmN > 0
      ? { duration: list[0].duration || fallbackWarmDur, target: Number(list[0].target) || 0 }
      : null;
  const main = list.slice(warmN);
  let prev = warm ? warm.target : Number(startVal) || 0;
  const steps: LoadStep[] = [];
  let exact = true;
  let i = 0;

  while (i < main.length) {
    const s = main[i];
    const sd = parseDurationToSec(s.duration);
    const st = Number(s.target) || 0;
    if (sd > 0 && st > 0 && Math.abs(st - prev) < 1e-9) {
      steps.push({ target: st, hold: s.duration, ramp: '0s' });
      prev = st;
      i += 1;
      continue;
    }
    if (i + 1 < main.length) {
      const n = main[i + 1];
      const nd = parseDurationToSec(n.duration);
      const nt = Number(n.target) || 0;
      if (nd > 0 && nt > 0 && Math.abs(nt - st) < 1e-9) {
        steps.push({ target: nt, hold: n.duration, ramp: s.duration || '0s' });
        prev = nt;
        i += 2;
        continue;
      }
    }
    exact = false;
    if (sd > 0 && st > 0) {
      steps.push({ target: st, hold: s.duration, ramp: '0s' });
    }
    prev = st;
    i += 1;
  }

  return { exact: exact && steps.length > 0, warm, steps };
}

export function stepsToStages(warm: K6Stage | null, steps: LoadStep[], fallbackWarmDur = '2m'): K6Stage[] {
  const out: K6Stage[] = [];
  if (warm) {
    out.push({ duration: warm.duration || fallbackWarmDur, target: Number(warm.target) || 0 });
  }
  (steps || []).forEach((st) => {
    const hold = String(st.hold || '').trim();
    if (!hold) return;
    const ramp = String(st.ramp || '').trim() || '0s';
    const tg = Number(st.target) || 0;
    out.push({ duration: ramp, target: tg }, { duration: hold, target: tg });
  });
  return out;
}

interface StageConstructorProps {
  executor: string;
  timeUnit?: string;
  startVal: number;
  stages: K6Stage[];
  warmupStagesCount: number;
  globalWarmupDur: string;
  onChangeStages: (stages: K6Stage[]) => void;
}

export function StageConstructor({
  executor,
  timeUnit = '1s',
  startVal,
  stages,
  warmupStagesCount,
  globalWarmupDur,
  onChangeStages,
}: StageConstructorProps) {
  const unit = stepUnitShort(executor, timeUnit);
  const isRate = executor.includes('arrival');

  const parsed = useMemo(
    () => parseStepsFromStages(stages, startVal, warmupStagesCount, globalWarmupDur),
    [stages, startVal, warmupStagesCount, globalWarmupDur]
  );

  const [mode, setMode] = useState<'steps' | 'raw'>(() => (parsed.exact ? 'steps' : 'raw'));
  const [ladderOpen, setLadderOpen] = useState(false);
  const [ladStart, setLadStart] = useState<number>(
    parsed.steps[0]?.target || (isRate ? 100 : 20)
  );
  const [ladInc, setLadInc] = useState<number>(20);
  const [ladKind, setLadKind] = useState<'pct' | 'abs'>('pct');
  const [ladCnt, setLadCnt] = useState<number>(5);
  const [ladHold, setLadHold] = useState<string>('5m');
  const [ladRamp, setLadRamp] = useState<string>('0s');

  useEffect(() => {
    if (!parsed.exact && mode === 'steps' && stages.length > warmupStagesCount) {
      setMode('raw');
    }
  }, [parsed.exact, stages.length, warmupStagesCount]);

  const totalSec = useMemo(
    () => (stages || []).reduce((acc, s) => acc + parseDurationToSec(s.duration), 0),
    [stages]
  );

  const commitSteps = (warm: K6Stage | null, nextSteps: LoadStep[]) => {
    onChangeStages(stepsToStages(warm, nextSteps, globalWarmupDur));
  };

  // Live preview of ladder generator
  const ladderPreview = useMemo(() => {
    const cnt = Math.max(1, Math.min(20, Math.round(ladCnt) || 5));
    const st = Math.max(0.01, Number(ladStart) || (isRate ? 100 : 20));
    const inc = Number(ladInc) || 0;
    const vals: number[] = [];
    for (let i = 0; i < cnt; i++) {
      const v = ladKind === 'pct' ? st * (1 + (inc / 100) * i) : Math.max(0.01, st + inc * i);
      vals.push(v >= 10 ? Math.round(v) : Math.round(v * 100) / 100);
    }
    const perStepSec = parseDurationToSec(ladRamp || '0s') + parseDurationToSec(ladHold || '5m');
    const sumSec = perStepSec * cnt + (parsed.warm ? parseDurationToSec(parsed.warm.duration) : 0);
    return { vals, sumSec, cnt };
  }, [ladStart, ladInc, ladKind, ladCnt, ladHold, ladRamp, isRate, parsed.warm]);

  const applyPreset = (presetIdx: number) => {
    const preset = STAGE_PRESETS[presetIdx];
    if (!preset) return;
    const base =
      parsed.steps[0]?.target ||
      stages[warmupStagesCount]?.target ||
      stages[stages.length - 1]?.target ||
      (isRate ? 50 : 20);
    const built = preset.fn(base);
    if (warmupStagesCount > 0) {
      const wTarget = parsed.warm?.target || Math.max(1, Math.round(base * 0.3));
      const wDur = parsed.warm?.duration || globalWarmupDur || '2m';
      built.unshift({ duration: wDur, target: wTarget });
    }
    onChangeStages(built);
    setMode('steps');
  };

  const addSmartStep = (forcePlus20: boolean) => {
    const cur = parsed.steps;
    const last = cur[cur.length - 1];
    const prev = cur.length >= 2 ? cur[cur.length - 2] : null;
    const defVal = isRate ? 100 : 20;
    let nextTarget: number;
    if (!last) {
      nextTarget = defVal;
    } else if (!forcePlus20 && prev) {
      const delta = last.target - prev.target;
      nextTarget =
        delta > 0 ? Math.round((last.target + delta) * 100) / 100 : Math.max(1, Math.round(last.target * 1.2));
    } else {
      nextTarget = Math.max(1, Math.round(last.target * 1.2));
    }
    const hold = last?.hold || '5m';
    const ramp = last?.ramp || '0s';
    commitSteps(parsed.warm, [...cur, { target: nextTarget, hold, ramp }]);
  };

  const baseStepTarget = parsed.steps[0]?.target || 0;

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 space-y-2.5">
      {/* Шапка: переключатель Ступени / Стадии k6 + пресеты + длительность */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex rounded-lg border border-slate-200 bg-white p-0.5 shadow-2xs">
            <button
              type="button"
              onClick={() => {
                if (mode !== 'steps') {
                  if (!parsed.exact && parsed.steps.length > 0) {
                    commitSteps(parsed.warm, parsed.steps);
                  }
                  setMode('steps');
                }
              }}
              className={`rounded-md px-2.5 py-1 text-xs font-semibold transition-colors cursor-pointer ${
                mode === 'steps' ? 'bg-indigo-600 text-white' : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              Ступени ({parsed.steps.length})
            </button>
            <button
              type="button"
              onClick={() => setMode('raw')}
              className={`rounded-md px-2.5 py-1 text-xs font-semibold transition-colors cursor-pointer ${
                mode === 'raw' ? 'bg-indigo-600 text-white' : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              Стадии k6 ({stages.length})
            </button>
          </div>

          <span className="text-[11px] text-slate-500">
            Итого: <b className="text-slate-700">{fmtDurationSec(totalSec)}</b>
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          {mode === 'steps' && (
            <Button
              variant={ladderOpen ? 'primary' : 'outline'}
              size="xs"
              onClick={() => {
                if (!ladderOpen && parsed.steps[0]?.target) {
                  setLadStart(parsed.steps[0].target);
                }
                setLadderOpen((v) => !v);
              }}
            >
              <Wand2 className="h-3.5 w-3.5" />
              Генератор лесенки
            </Button>
          )}
          <Select
            value=""
            onChange={(e) => {
              if (e.target.value !== '') applyPreset(Number(e.target.value));
            }}
            className="h-7 text-xs w-52 bg-white"
          >
            <option value="">⚡ Готовый шаблон стадий…</option>
            {STAGE_PRESETS.map((pr, pIdx) => (
              <option key={pIdx} value={pIdx}>
                {pr.label}
              </option>
            ))}
          </Select>
        </div>
      </div>

      {/* Панель Генератора лесенки с живым предпросмотром */}
      {mode === 'steps' && ladderOpen && (
        <div className="rounded-xl border border-indigo-200 bg-indigo-50/60 p-3 space-y-2.5">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-indigo-950 flex items-center gap-1.5">
              <Sparkles className="h-3.5 w-3.5 text-indigo-600" />
              Автогенератор ступенчатого поиска максимума
            </span>
            <button
              type="button"
              onClick={() => setLadderOpen(false)}
              className="text-slate-400 hover:text-slate-700 cursor-pointer"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2 items-end">
            <div>
              <label className="block text-[11px] text-slate-600 mb-0.5">1-я ступень ({unit})</label>
              <FormulaInput value={ladStart} onChange={setLadStart} min={0.01} className="w-full" />
            </div>
            <div>
              <label className="block text-[11px] text-slate-600 mb-0.5">Шаг прироста</label>
              <FormulaInput value={ladInc} onChange={setLadInc} min={0} className="w-full" />
            </div>
            <div>
              <label className="block text-[11px] text-slate-600 mb-0.5">Тип шага</label>
              <Select
                value={ladKind}
                onChange={(e) => setLadKind(e.target.value as 'pct' | 'abs')}
                className="h-7 text-xs bg-white"
              >
                <option value="pct">+% от старта</option>
                <option value="abs">+{unit} (абс.)</option>
              </Select>
            </div>
            <div>
              <label className="block text-[11px] text-slate-600 mb-0.5">Ступеней (1–20)</label>
              <Input
                type="number"
                min={1}
                max={20}
                value={ladCnt}
                onChange={(e) => setLadCnt(Math.max(1, Math.min(20, Number(e.target.value) || 1)))}
                className="h-7 text-xs bg-white"
              />
            </div>
            <div>
              <label className="block text-[11px] text-slate-600 mb-0.5">Полка</label>
              <Input
                value={ladHold}
                onChange={(e) => setLadHold(e.target.value)}
                placeholder="5m"
                className="h-7 text-xs font-mono bg-white"
              />
            </div>
            <div>
              <label className="block text-[11px] text-slate-600 mb-0.5">Переход</label>
              <Input
                value={ladRamp}
                onChange={(e) => setLadRamp(e.target.value)}
                placeholder="0s"
                className="h-7 text-xs font-mono bg-white"
              />
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2 pt-1 border-t border-indigo-200/60 text-[11px]">
            <div className="text-indigo-900 font-mono truncate max-w-full">
              Предпросмотр: <b>{ladderPreview.vals.join(' → ')}</b> {unit} · длит.{' '}
              <b>{fmtDurationSec(ladderPreview.sumSec)}</b>
            </div>
            <Button
              size="xs"
              onClick={() => {
                const builtSteps: LoadStep[] = ladderPreview.vals.map((v) => ({
                  target: v,
                  hold: ladHold.trim() || '5m',
                  ramp: ladRamp.trim() || '0s',
                }));
                commitSteps(parsed.warm, builtSteps);
                setLadderOpen(false);
              }}
            >
              Собрать {ladderPreview.cnt} ступ.
            </Button>
          </div>
        </div>
      )}

      {/* Строка прогрева (когда включён общий прогрев) */}
      {warmupStagesCount > 0 && parsed.warm && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-amber-300/80 bg-amber-50/80 px-2.5 py-1.5 text-xs">
          <span className="inline-flex items-center gap-1 font-semibold text-amber-900">
            <Flame className="h-3.5 w-3.5 text-amber-600" />
            Прогрев (стадия #1)
          </span>
          <div className="flex items-center gap-1">
            <span className="text-[11px] text-amber-800">Уровень:</span>
            <FormulaInput
              value={parsed.warm.target}
              onChange={(v) => {
                const next = [...stages];
                next[0] = { duration: parsed.warm?.duration || globalWarmupDur || '2m', target: v };
                onChangeStages(next);
              }}
              suffix={unit}
              className="w-28"
            />
          </div>
          <div className="flex items-center gap-1">
            <span className="text-[11px] text-amber-800">Длит.:</span>
            <Input
              value={parsed.warm.duration}
              onChange={(e) => {
                const next = [...stages];
                next[0] = { ...next[0], duration: e.target.value };
                onChangeStages(next);
              }}
              className="h-7 w-20 text-xs font-mono bg-white"
            />
          </div>
          <span className="text-[11px] text-amber-700 ml-auto">
            Не портит SLA ступеней и итоги прогона
          </span>
        </div>
      )}

      {mode === 'steps' ? (
        <div className="space-y-2">
          {/* Массовые чипы перехода и полки */}
          {parsed.steps.length > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-slate-500">
              <div className="flex flex-wrap items-center gap-1">
                <span>Переход всем:</span>
                {['0s', '15s', '30s', '1m'].map((rv) => (
                  <button
                    key={rv}
                    type="button"
                    onClick={() =>
                      commitSteps(
                        parsed.warm,
                        parsed.steps.map((s) => ({ ...s, ramp: rv }))
                      )
                    }
                    className="rounded border border-slate-200 bg-white px-1.5 py-0.5 font-mono text-slate-700 hover:border-indigo-300 hover:bg-indigo-50 cursor-pointer"
                  >
                    {rv}
                  </button>
                ))}
              </div>
              <div className="flex flex-wrap items-center gap-1">
                <span>Полка всем:</span>
                {['3m', '5m', '10m'].map((hv) => (
                  <button
                    key={hv}
                    type="button"
                    onClick={() =>
                      commitSteps(
                        parsed.warm,
                        parsed.steps.map((s) => ({ ...s, hold: hv }))
                      )
                    }
                    className="rounded border border-slate-200 bg-white px-1.5 py-0.5 font-mono text-slate-700 hover:border-indigo-300 hover:bg-indigo-50 cursor-pointer"
                  >
                    {hv}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Список ступеней */}
          <div className="space-y-1.5">
            {parsed.steps.map((st, sIdx) => {
              const pct =
                baseStepTarget > 0 && st.target > 0
                  ? Math.round((st.target / baseStepTarget) * 1000) / 10
                  : null;
              return (
                <div
                  key={sIdx}
                  className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5"
                >
                  <span className="inline-flex h-5 min-w-[26px] items-center justify-center rounded bg-slate-100 px-1.5 text-[11px] font-mono font-semibold text-slate-600">
                    #{sIdx + 1}
                  </span>

                  <div className="flex items-center gap-1">
                    <span className="text-[11px] text-slate-500">Уровень</span>
                    <FormulaInput
                      value={st.target}
                      onChange={(v) => {
                        const next = parsed.steps.map((x, i) => (i === sIdx ? { ...x, target: v } : x));
                        commitSteps(parsed.warm, next);
                      }}
                      suffix={unit}
                      className="w-32"
                    />
                  </div>

                  <div className="flex items-center gap-1">
                    <span className="text-[11px] text-slate-500">Полка</span>
                    <Input
                      value={st.hold}
                      onChange={(e) => {
                        const next = parsed.steps.map((x, i) =>
                          i === sIdx ? { ...x, hold: e.target.value } : x
                        );
                        commitSteps(parsed.warm, next);
                      }}
                      placeholder="5m"
                      title="Длительность удержания ступени: 3m, 5m, 10m"
                      className="h-7 w-20 text-xs font-mono"
                    />
                  </div>

                  <div className="flex items-center gap-1">
                    <span className="text-[11px] text-slate-500">Переход</span>
                    <Input
                      value={st.ramp}
                      onChange={(e) => {
                        const next = parsed.steps.map((x, i) =>
                          i === sIdx ? { ...x, ramp: e.target.value } : x
                        );
                        commitSteps(parsed.warm, next);
                      }}
                      placeholder="0s"
                      title="Время выхода на ступень: 0s — скачком, 30s — плавный разгон"
                      className="h-7 w-20 text-xs font-mono"
                    />
                  </div>

                  <span
                    title="Доля от 1-й ступени"
                    className={`rounded-md px-1.5 py-0.5 text-[11px] font-mono font-semibold ${
                      sIdx === 0
                        ? 'bg-slate-100 text-slate-600'
                        : 'bg-indigo-50 text-indigo-700'
                    }`}
                  >
                    {pct !== null ? `${Number.isInteger(pct) ? pct : pct.toFixed(1)}%` : '—'}
                  </span>

                  <div className="ml-auto flex items-center gap-0.5">
                    <button
                      type="button"
                      disabled={sIdx === 0}
                      title="Выше"
                      onClick={() => {
                        if (sIdx === 0) return;
                        const next = [...parsed.steps];
                        [next[sIdx - 1], next[sIdx]] = [next[sIdx], next[sIdx - 1]];
                        commitSteps(parsed.warm, next);
                      }}
                      className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-30 cursor-pointer"
                    >
                      <ArrowUp className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      disabled={sIdx >= parsed.steps.length - 1}
                      title="Ниже"
                      onClick={() => {
                        if (sIdx >= parsed.steps.length - 1) return;
                        const next = [...parsed.steps];
                        [next[sIdx + 1], next[sIdx]] = [next[sIdx], next[sIdx + 1]];
                        commitSteps(parsed.warm, next);
                      }}
                      className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-30 cursor-pointer"
                    >
                      <ArrowDown className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      title="Удалить ступень"
                      onClick={() => {
                        const next = parsed.steps.filter((_, i) => i !== sIdx);
                        commitSteps(parsed.warm, next);
                      }}
                      className="rounded p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600 cursor-pointer"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
            <div className="flex flex-wrap items-center gap-1.5">
              <Button variant="outline" size="xs" onClick={() => addSmartStep(false)}>
                <Plus className="h-3.5 w-3.5" />
                Ступень
              </Button>
              <Button variant="outline" size="xs" onClick={() => addSmartStep(true)}>
                <TrendingUp className="h-3.5 w-3.5 text-indigo-600" />
                + Ступень +20%
              </Button>
            </div>

            <div className="flex items-center gap-1.5 text-[11px] text-slate-500">
              {parsed.steps.length >= 2 ? (
                <>
                  <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600 shrink-0" />
                  <span>
                    В отчёте автоматически появится блок <b>«Поиск максимума»</b> по {parsed.steps.length} ступеням
                  </span>
                </>
              ) : (
                <>
                  <Info className="h-3.5 w-3.5 text-indigo-500 shrink-0" />
                  <span>Пары стадий k6 (переход + полка) формируются автоматически</span>
                </>
              )}
            </div>
          </div>
        </div>
      ) : (
        /* Режим «Стадии k6» (сырой массив stages) */
        <div className="space-y-2">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5">
            {stages.map((st, sIdx) => {
              const isWarmRow = sIdx < warmupStagesCount;
              return (
                <div
                  key={sIdx}
                  className={`flex items-center gap-1.5 rounded-lg border px-2 py-1.5 ${
                    isWarmRow ? 'border-amber-300 bg-amber-50/70' : 'border-slate-200 bg-white'
                  }`}
                >
                  <span className="text-[11px] font-mono font-semibold text-slate-500 w-7 shrink-0">
                    {isWarmRow ? '🔥#1' : `#${sIdx + 1}`}
                  </span>
                  <Input
                    value={st.duration ?? '1m'}
                    onChange={(e) => {
                      const next = [...stages];
                      next[sIdx] = { ...next[sIdx], duration: e.target.value };
                      onChangeStages(next);
                    }}
                    placeholder="5m"
                    title="Длительность стадии (0s, 30s, 5m)"
                    className="h-7 w-20 text-xs font-mono bg-white"
                  />
                  <FormulaInput
                    value={st.target ?? 0}
                    onChange={(v) => {
                      const next = [...stages];
                      next[sIdx] = { ...next[sIdx], target: v };
                      onChangeStages(next);
                    }}
                    suffix={unit}
                    className="flex-1 min-w-[90px]"
                  />

                  {!isWarmRow && (
                    <div className="flex items-center gap-0.5">
                      <button
                        type="button"
                        title="Вставить копию ниже"
                        onClick={() => {
                          const next = [
                            ...stages.slice(0, sIdx + 1),
                            { ...st },
                            ...stages.slice(sIdx + 1),
                          ];
                          onChangeStages(next);
                        }}
                        className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-indigo-600 cursor-pointer"
                      >
                        <Plus className="h-3 w-3" />
                      </button>
                      <button
                        type="button"
                        disabled={sIdx <= warmupStagesCount}
                        title="Выше"
                        onClick={() => {
                          if (sIdx <= warmupStagesCount) return;
                          const next = [...stages];
                          [next[sIdx - 1], next[sIdx]] = [next[sIdx], next[sIdx - 1]];
                          onChangeStages(next);
                        }}
                        className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-30 cursor-pointer"
                      >
                        <ArrowUp className="h-3 w-3" />
                      </button>
                      <button
                        type="button"
                        disabled={sIdx >= stages.length - 1}
                        title="Ниже"
                        onClick={() => {
                          if (sIdx >= stages.length - 1) return;
                          const next = [...stages];
                          [next[sIdx + 1], next[sIdx]] = [next[sIdx], next[sIdx + 1]];
                          onChangeStages(next);
                        }}
                        className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-30 cursor-pointer"
                      >
                        <ArrowDown className="h-3 w-3" />
                      </button>
                      <button
                        type="button"
                        title="Удалить стадию"
                        onClick={() => onChangeStages(stages.filter((_, i) => i !== sIdx))}
                        className="rounded p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600 cursor-pointer"
                      >
                        <X className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          <div className="flex flex-wrap items-center gap-1.5 pt-1">
            <Button
              variant="outline"
              size="xs"
              onClick={() => {
                const last = stages[stages.length - 1];
                onChangeStages([
                  ...stages,
                  { duration: last?.duration || '2m', target: last?.target ?? (isRate ? 50 : 20) },
                ]);
              }}
            >
              <Plus className="h-3.5 w-3.5" />
              Стадия
            </Button>
            <Button
              variant="outline"
              size="xs"
              onClick={() => {
                const last = stages[stages.length - 1];
                onChangeStages([
                  ...stages,
                  { duration: '5m', target: last?.target ?? (isRate ? 50 : 20) },
                ]);
              }}
            >
              + Полка 5m
            </Button>
            <Button
              variant="outline"
              size="xs"
              onClick={() => {
                const last = stages[stages.length - 1];
                const base = calcExpr(last?.target) || (isRate ? 50 : 20);
                const nextT = Math.max(1, Math.round(base * 1.2));
                onChangeStages([
                  ...stages,
                  { duration: '30s', target: nextT },
                  { duration: '3m', target: nextT },
                ]);
              }}
            >
              + Ступень +20%
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
