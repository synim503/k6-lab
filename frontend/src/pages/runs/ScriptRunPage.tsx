import React, { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Select, Spinner, Switch, Textarea } from '@/components/ui/primitives';
import { ScriptHead } from '../scripts/ScriptHead';
import { fmtDurationSec } from '@/lib/utils';
import { CalendarClock, Play } from 'lucide-react';
import { toast } from 'sonner';

export function ScriptRunPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const initialProf = searchParams.get('profile') || '';
  const [mode, setMode] = useState<'now' | 'schedule'>(location.hash === '#schedule' ? 'schedule' : 'now');
  const [profileId, setProfileId] = useState<string>(initialProf);
  const [count, setCount] = useState<number>(1);
  const [selectedGens, setSelectedGens] = useState<number[]>([]);
  const [httpDebug, setHttpDebug] = useState('');
  const [selectedFlags, setSelectedFlags] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  // Schedule fields
  const [preset, setPreset] = useState<'auto' | 'front' | 'back' | 'custom'>('auto');
  const [customRunAt, setCustomRunAt] = useState('');
  const [shift, setShift] = useState(true);
  const [pctByOp, setPctByOp] = useState<Record<string, number>>({});
  const [envText, setEnvText] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['script-run-init', id, initialProf],
    queryFn: () =>
      apiFetch(`/api/v1/scripts/${id}/run-init${initialProf ? `?profile=${encodeURIComponent(initialProf)}` : ''}`),
  });

  useEffect(() => {
    if (!data) return;
    const pid = data.selectedProfileId != null ? String(data.selectedProfileId) : '';
    setProfileId(pid);
    setCount(data.defaultCount || 1);
    const det = data.profileDetails?.[pid] || data.profileDetails?.[''] || {};
    setEnvText(det.envText || '');
    const nextPct: Record<string, number> = {};
    (det.opNames || []).forEach((n: string) => {
      nextPct[n] = 100;
    });
    setPctByOp(nextPct);
  }, [data]);

  const handleProfileChange = (nextPid: string) => {
    setProfileId(nextPid);
    const det = data?.profileDetails?.[nextPid] || {};
    setEnvText(det.envText || '');
    const nextPct: Record<string, number> = {};
    (det.opNames || []).forEach((n: string) => {
      nextPct[n] = 100;
    });
    setPctByOp(nextPct);
  };

  if (isLoading || !data) return <Spinner label="Подготовка параметров запуска…" />;

  const { script, profiles = [], profileDetails = {}, generators = [], k6Flags = [], freeCount = 0, blockReason, pendingScheduleCount = 0 } = data;
  const activeDetail = profileDetails[profileId] || profileDetails[''] || { ops: [], errors: [], durationSec: 0 };

  const parseEnvText = () => {
    const out: Record<string, string> = {};
    envText.split(/\r?\n/).forEach((l) => {
      const t = l.trim();
      if (!t || t.startsWith('#')) return;
      const eq = t.indexOf('=');
      if (eq > 0) out[t.slice(0, eq).trim()] = t.slice(eq + 1);
    });
    return out;
  };

  const handleRunNow = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/run`, {
        method: 'POST',
        body: {
          profileId: profileId ? Number(profileId) : null,
          count: selectedGens.length > 0 ? selectedGens.length : count,
          generatorIds: selectedGens,
          httpDebug,
          flags: selectedFlags,
        },
      });
      toast.success(`Запуск #${res.runId} стартовал`);
      navigate(`/runs/${res.runId}`);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const runAtIso =
        preset === 'custom' && customRunAt ? new Date(customRunAt).toISOString() : null;
      const queuePosition = preset === 'front' ? 'front' : preset === 'back' ? 'back' : null;
      const res = await apiFetch(`/api/v1/scripts/${id}/schedule`, {
        method: 'POST',
        body: {
          profileId: profileId ? Number(profileId) : null,
          runAt: runAtIso,
          queuePosition,
          count: selectedGens.length > 0 ? selectedGens.length : count,
          generatorIds: selectedGens,
          shift,
          intensityPct: pctByOp,
          env: parseEnvText(),
        },
      });
      qc.invalidateQueries({ queryKey: ['script-run-init', id] });
      toast.success(res.message || 'Запуск запланирован', {
        action: {
          label: 'Открыть расписание',
          onClick: () => navigate('/schedule'),
        },
      });
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-4">
      <ScriptHead script={script} />

      {blockReason && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800">{blockReason}</div>
      )}

      {activeDetail.errors?.length > 0 && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800 space-y-1">
          {activeDetail.errors.map((err: string, idx: number) => (
            <div key={idx}>
              • {err}{' '}
              <Link to={`/scripts/${script.id}/load`} className="underline font-semibold ml-1">
                Открыть нагрузку
              </Link>
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="inline-flex rounded-xl bg-slate-200/70 p-1 gap-1">
          <button
            type="button"
            onClick={() => setMode('now')}
            className={`flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium cursor-pointer ${
              mode === 'now' ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600'
            }`}
          >
            <Play className="h-4 w-4" />
            Запустить сейчас
          </button>
          <button
            type="button"
            onClick={() => setMode('schedule')}
            className={`flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium cursor-pointer ${
              mode === 'schedule' ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600'
            }`}
          >
            <CalendarClock className="h-4 w-4" />
            Запланировать запуск
            {pendingScheduleCount > 0 && <Badge variant="info">{pendingScheduleCount}</Badge>}
          </button>
        </div>

        <Link to="/schedule">
          <Button variant="outline" size="sm">
            Всё расписание →
          </Button>
        </Link>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        <div className="lg:col-span-8">
          <Card>
            <CardBody>
              <form onSubmit={mode === 'now' ? handleRunNow : handleSchedule} className="space-y-4">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  {profiles.length > 0 && (
                    <div>
                      <label className="block text-xs font-semibold text-slate-700 mb-1">Профиль нагрузки</label>
                      <Select value={profileId} onChange={(e) => handleProfileChange(e.target.value)}>
                        <option value="">
                          {data.baseOk ? '★ Базовая нагрузка скрипта' : 'Нагрузка скрипта (не описана)'}
                        </option>
                        {profiles.map((p: any) => (
                          <option key={p.id} value={p.id}>
                            {p.name}
                          </option>
                        ))}
                      </Select>
                    </div>
                  )}

                  <div>
                    <label className="block text-xs font-semibold text-slate-700 mb-1">Количество генераторов</label>
                    <div className="flex items-center gap-2">
                      <Input
                        type="number"
                        min={1}
                        max={Math.max(1, generators.length)}
                        value={selectedGens.length > 0 ? selectedGens.length : count}
                        disabled={selectedGens.length > 0}
                        onChange={(e) => setCount(Math.max(1, Number(e.target.value) || 1))}
                        className="w-28"
                      />
                      <Badge variant={freeCount > 0 ? 'success' : 'danger'}>
                        свободно {freeCount} из {generators.length}
                      </Badge>
                    </div>
                  </div>
                </div>

                {mode === 'schedule' && (
                  <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3.5 space-y-3">
                    <div className="text-xs font-semibold text-slate-800">Время постановки в очередь</div>
                    <div className="flex flex-wrap gap-1.5">
                      {[
                        { id: 'auto', label: 'Ближайшее свободное окно' },
                        { id: 'front', label: 'В начало (сразу после текущего)' },
                        { id: 'back', label: 'В конец очереди' },
                        { id: 'custom', label: 'Точное время' },
                      ].map((chip) => (
                        <button
                          key={chip.id}
                          type="button"
                          onClick={() => setPreset(chip.id as any)}
                          className={`rounded-lg px-2.5 py-1 text-xs font-medium cursor-pointer ${
                            preset === chip.id
                              ? 'bg-indigo-600 text-white'
                              : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-100'
                          }`}
                        >
                          {chip.label}
                        </button>
                      ))}
                    </div>

                    {preset === 'custom' && (
                      <Input
                        type="datetime-local"
                        required
                        value={customRunAt}
                        onChange={(e) => setCustomRunAt(e.target.value)}
                        className="max-w-xs text-xs"
                      />
                    )}

                    <Switch
                      checked={shift}
                      onChange={setShift}
                      label={<span className="text-xs">Сдвигать старт вперёд, если генераторы освободятся раньше</span>}
                    />
                  </div>
                )}

                {/* Выбор конкретных серверов */}
                {generators.length > 0 && (
                  <details className="rounded-xl border border-slate-200 p-3 text-xs">
                    <summary className="font-semibold text-slate-700 cursor-pointer">
                      Выбрать конкретные серверы {selectedGens.length > 0 && `(${selectedGens.length})`}
                    </summary>
                    <div className="mt-2.5 flex flex-wrap gap-2">
                      {generators.map((g: any) => {
                        const checked = selectedGens.includes(g.id);
                        return (
                          <label
                            key={g.id}
                            className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 cursor-pointer ${
                              checked ? 'border-indigo-500 bg-indigo-50 text-indigo-800' : 'border-slate-200 bg-white'
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={mode === 'now' && !g.availableForRuns}
                              onChange={(e) =>
                                setSelectedGens((prev) =>
                                  e.target.checked ? [...prev, g.id] : prev.filter((x) => x !== g.id)
                                )
                              }
                            />
                            <span>{g.name}</span>
                            {!g.availableForRuns && (
                              <span className="text-slate-400">({g.busy ? 'занят' : g.statusTitle})</span>
                            )}
                          </label>
                        );
                      })}
                    </div>
                  </details>
                )}

                {mode === 'now' ? (
                  <details className="rounded-xl border border-slate-200 p-3 text-xs space-y-2">
                    <summary className="font-semibold text-slate-700 cursor-pointer">
                      Параметры k6 и отладка (необязательно)
                    </summary>
                    <div className="mt-2.5 space-y-2.5">
                      <div>
                        <label className="block text-slate-600 mb-1">HTTP-отладка</label>
                        <Select value={httpDebug} onChange={(e) => setHttpDebug(e.target.value)} className="max-w-sm text-xs">
                          <option value="">Выключена</option>
                          <option value="headers">Заголовки (--http-debug)</option>
                          <option value="full">Заголовки и тела (--http-debug=full)</option>
                        </Select>
                      </div>
                      <div className="space-y-1.5">
                        {k6Flags.map((fl: any) => (
                          <label key={fl.name} className="flex items-start gap-2 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={selectedFlags.includes(fl.name)}
                              onChange={(e) =>
                                setSelectedFlags((prev) =>
                                  e.target.checked ? [...prev, fl.name] : prev.filter((x) => x !== fl.name)
                                )
                              }
                              className="mt-0.5"
                            />
                            <div>
                              <span className="font-medium text-slate-800">{fl.title}</span>{' '}
                              <code className="text-slate-500">{fl.arg}</code>
                              <div className="text-slate-400">{fl.description}</div>
                            </div>
                          </label>
                        ))}
                      </div>
                    </div>
                  </details>
                ) : (
                  <>
                    {Object.keys(pctByOp).length > 0 && (
                      <details className="rounded-xl border border-slate-200 p-3 text-xs">
                        <summary className="font-semibold text-slate-700 cursor-pointer">
                          Масштаб интенсивности операций (%)
                        </summary>
                        <div className="mt-2.5 space-y-2">
                          <div className="flex gap-1.5">
                            {[50, 80, 100, 120, 150, 200].map((v) => (
                              <button
                                key={v}
                                type="button"
                                onClick={() => {
                                  const next: Record<string, number> = {};
                                  Object.keys(pctByOp).forEach((k) => {
                                    next[k] = v;
                                  });
                                  setPctByOp(next);
                                }}
                                className="rounded border border-slate-200 px-2 py-0.5 hover:bg-slate-100 cursor-pointer"
                              >
                                {v}%
                              </button>
                            ))}
                          </div>
                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                            {Object.entries(pctByOp).map(([opName, val]) => (
                              <div key={opName} className="flex items-center gap-2">
                                <span className="truncate flex-1 font-mono">{opName}</span>
                                <Input
                                  type="number"
                                  min={1}
                                  max={10000}
                                  value={val}
                                  onChange={(e) => setPctByOp((p) => ({ ...p, [opName]: Number(e.target.value) }))}
                                  className="h-7 w-20 text-xs"
                                />
                                <span>%</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      </details>
                    )}

                    <details className="rounded-xl border border-slate-200 p-3 text-xs">
                      <summary className="font-semibold text-slate-700 cursor-pointer">
                        Переменные запуска (KEY=VALUE)
                      </summary>
                      <Textarea
                        rows={4}
                        value={envText}
                        onChange={(e) => setEnvText(e.target.value)}
                        className="mt-2 font-mono text-xs"
                      />
                    </details>
                  </>
                )}

                <div className="flex items-center gap-2 pt-2 border-t border-slate-100">
                  <Button
                    type="submit"
                    variant={mode === 'now' ? 'success' : 'primary'}
                    loading={submitting}
                    disabled={generators.length === 0 || activeDetail.errors?.length > 0}
                  >
                    {mode === 'now' ? (
                      <>
                        <Play className="h-4 w-4 fill-current" />
                        Запустить сейчас
                      </>
                    ) : (
                      <>
                        <CalendarClock className="h-4 w-4" />
                        Поставить в расписание
                      </>
                    )}
                  </Button>
                  <Link to={`/scripts/${script.id}`}>
                    <Button variant="outline">Отмена</Button>
                  </Link>
                </div>
              </form>
            </CardBody>
          </Card>
        </div>

        {/* Сводка выбранного профиля */}
        <div className="lg:col-span-4">
          <Card>
            <CardHeader>
              <span className="font-semibold text-sm">{activeDetail.title || 'Сводка нагрузки'}</span>
            </CardHeader>
            <CardBody className="space-y-3 text-xs">
              <div className="flex justify-between text-slate-600">
                <span>Оценка длительности:</span>
                <b className="text-slate-900">{fmtDurationSec(activeDetail.durationSec)}</b>
              </div>
              <div className="flex justify-between text-slate-600">
                <span>Активных операций:</span>
                <b className="text-slate-900">{activeDetail.ops?.length || 0}</b>
              </div>
              <div className="divide-y divide-slate-100 border-t border-slate-100 pt-2">
                {(activeDetail.ops || []).map((op: any) => (
                  <div key={op.name} className="py-1.5 flex items-center justify-between gap-2">
                    <span className="font-mono text-slate-800 truncate">{op.name}</span>
                    <span className="text-slate-500 shrink-0">
                      p{op.slaPercentile} ≤ {op.slaThresholdMs} мс
                    </span>
                  </div>
                ))}
              </div>
            </CardBody>
          </Card>
        </div>
      </div>
    </div>
  );
}
