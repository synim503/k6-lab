import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, apiUpload } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Input, Modal, Select, Spinner, Switch, Textarea } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { useAuth } from '@/context/AuthContext';
import { fmtDurationSec, fmtShortDate } from '@/lib/utils';
import {
  ArrowDown,
  ArrowUp,
  CalendarClock,
  ChevronsDown,
  ChevronsUp,
  Pause,
  Pencil,
  Play,
  Plus,
  RotateCcw,
  ShieldAlert,
  Terminal,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { toast } from 'sonner';

export function SchedulePage() {
  const { isAdmin } = useAuth();
  const confirm = useConfirm();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [filter, setFilter] = useState<'all' | 'pending' | 'history'>('all');
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

  // Quick add modal
  const [quickOpen, setQuickOpen] = useState(false);
  const [qmScriptId, setQmScriptId] = useState('');
  const [qmProfileId, setQmProfileId] = useState('');
  const [qmCount, setQmCount] = useState(1);
  const [qmPos, setQmPos] = useState<'auto' | 'front' | 'back'>('auto');

  // Import schedule modal
  const [impOpen, setImpOpen] = useState(false);
  const [impJson, setImpJson] = useState('');

  // Guard modal
  const [guardModal, setGuardModal] = useState<any | null>(null);
  const [promTestRes, setPromTestRes] = useState<any | null>(null);
  const [sshTestRes, setSshTestRes] = useState<any | null>(null);
  const [testingGuard, setTestingGuard] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['schedule'],
    queryFn: () => apiFetch('/api/v1/schedule'),
    refetchInterval: 5000,
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ['schedule'] });

  const actionMut = useMutation({
    mutationFn: ({ url, body }: { url: string; body?: any }) =>
      apiFetch(url, { method: 'POST', body }),
    onSuccess: (res: any) => {
      refresh();
      if (res?.message) toast.success(res.message);
      if (res?.startedRunId) {
        toast.success(`Запущен прогон #${res.startedRunId}`, {
          action: { label: 'Открыть', onClick: () => navigate(`/runs/${res.startedRunId}`) },
        });
      }
    },
    onError: (err: any) => toast.error(err.message),
  });

  if (isLoading || !data) return <Spinner label="Загрузка расписания…" />;

  const { paused, items = [], guards = [], scripts = [], sshCredentials = [], generators = [] } = data;
  const pendingItems = items.filter((i: any) => i.pending);
  const historyItems = items.filter((i: any) => !i.pending);
  const visibleItems =
    filter === 'pending' ? pendingItems : filter === 'history' ? historyItems : items;

  const selectedScriptObj = scripts.find((s: any) => String(s.id) === qmScriptId);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Расписание и очередь тестов</h1>
          <p className="text-xs text-slate-500">Управление очередью прогонов и автопроверками метрик Prometheus</p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            onClick={() => {
              const first = scripts[0];
              if (first) {
                setQmScriptId(String(first.id));
                setQmCount(first.generatorCount || 1);
              }
              setQuickOpen(true);
            }}
          >
            <Plus className="h-4 w-4" />
            Поставить в очередь
          </Button>
          <Button variant="outline" size="sm" onClick={() => setImpOpen(true)}>
            <Upload className="h-3.5 w-3.5" />
            Импорт JSON
          </Button>
          {paused ? (
            <Button
              variant="success"
              size="sm"
              onClick={() => actionMut.mutate({ url: '/api/v1/schedule/resume' })}
            >
              <Play className="h-3.5 w-3.5 fill-current" />
              Возобновить очередь
            </Button>
          ) : (
            <Button
              variant="warning"
              size="sm"
              onClick={() => actionMut.mutate({ url: '/api/v1/schedule/pause' })}
            >
              <Pause className="h-3.5 w-3.5" />
              На паузу
            </Button>
          )}
        </div>
      </div>

      {paused && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 p-3.5 flex items-center justify-between gap-3 text-sm text-amber-900">
          <div>
            <b>Планировщик на паузе.</b> Новые прогоны из очереди не стартуют, пока пауза не снята.
          </div>
          <Button variant="success" size="xs" onClick={() => actionMut.mutate({ url: '/api/v1/schedule/resume' })}>
            Возобновить
          </Button>
        </div>
      )}

      {/* Очередь прогонов */}
      <Card className="overflow-hidden">
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex rounded-lg bg-slate-100 p-0.5 text-xs">
              {[
                { id: 'all', label: `Все (${items.length})` },
                { id: 'pending', label: `Ожидают (${pendingItems.length})` },
                { id: 'history', label: `История (${historyItems.length})` },
              ].map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setFilter(t.id as any)}
                  className={`rounded-md px-2.5 py-1 font-medium cursor-pointer ${
                    filter === t.id ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            {selectedIds.size > 0 && (
              <div className="flex items-center gap-1.5 ml-2">
                <Badge variant="info">Выбрано: {selectedIds.size}</Badge>
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() =>
                    actionMut.mutate({
                      url: '/api/v1/schedule/bulk-requeue',
                      body: { ids: Array.from(selectedIds), position: 'front' },
                    })
                  }
                >
                  В начало
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() =>
                    actionMut.mutate({
                      url: '/api/v1/schedule/bulk-requeue',
                      body: { ids: Array.from(selectedIds), position: 'back' },
                    })
                  }
                >
                  В конец
                </Button>
                <Button
                  variant="danger"
                  size="xs"
                  onClick={() => {
                    actionMut.mutate({
                      url: '/api/v1/schedule/bulk-cancel',
                      body: { ids: Array.from(selectedIds) },
                    });
                    setSelectedIds(new Set());
                  }}
                >
                  Отменить выбранные
                </Button>
              </div>
            )}
          </div>

          <div className="flex items-center gap-2">
            {historyItems.length > 0 && (
              <Button
                variant="outline"
                size="xs"
                onClick={() => actionMut.mutate({ url: '/api/v1/schedule/clear-history' })}
              >
                Очистить историю
              </Button>
            )}
            {pendingItems.length > 0 && (
              <Button
                variant="danger"
                size="xs"
                onClick={async () => {
                  const ok = await confirm({
                    title: 'Отменить ВСЕ ожидающие запуски?',
                    message: `Все ожидающие записи в расписании (${pendingItems.length}) будут отменены.`,
                    confirmText: 'Отменить все',
                  });
                  if (ok) actionMut.mutate({ url: '/api/v1/schedule/cancel-all-pending' });
                }}
              >
                Отменить все ({pendingItems.length})
              </Button>
            )}
          </div>
        </CardHeader>

        {visibleItems.length === 0 ? (
          <EmptyState icon={<CalendarClock className="h-6 w-6" />} title="Записей в расписании нет" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-xs text-slate-500 border-b border-slate-200">
                <tr>
                  <th className="w-8 px-3 py-2.5">
                    <input
                      type="checkbox"
                      checked={visibleItems.length > 0 && visibleItems.every((i: any) => selectedIds.has(i.id))}
                      onChange={(e) => {
                        const next = new Set(selectedIds);
                        visibleItems.forEach((i: any) => {
                          if (e.target.checked) next.add(i.id);
                          else next.delete(i.id);
                        });
                        setSelectedIds(next);
                      }}
                    />
                  </th>
                  <th className="text-left font-medium px-3 py-2.5">Время старта</th>
                  <th className="text-left font-medium px-3 py-2.5">Скрипт / Профиль</th>
                  <th className="text-left font-medium px-3 py-2.5">Генераторы / Длит.</th>
                  <th className="text-left font-medium px-3 py-2.5">Статус</th>
                  <th className="text-right font-medium px-4 py-2.5">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {visibleItems.map((it: any) => (
                  <tr key={it.id} className="hover:bg-slate-50/70">
                    <td className="px-3 py-2.5">
                      <input
                        type="checkbox"
                        checked={selectedIds.has(it.id)}
                        onChange={(e) => {
                          const next = new Set(selectedIds);
                          if (e.target.checked) next.add(it.id);
                          else next.delete(it.id);
                          setSelectedIds(next);
                        }}
                      />
                    </td>
                    <td className="px-3 py-2.5 whitespace-nowrap text-xs font-mono text-slate-800">
                      {fmtShortDate(it.runAt)}
                    </td>
                    <td className="px-3 py-2.5">
                      <Link to={`/scripts/${it.scriptId}`} className="font-semibold text-slate-900 hover:text-indigo-600">
                        {it.scriptName}
                      </Link>
                      {it.profileName && (
                        <Badge variant="info" className="ml-2">
                          {it.profileName}
                        </Badge>
                      )}
                      {it.error && <div className="text-xs text-rose-600 mt-0.5">{it.error}</div>}
                    </td>
                    <td className="px-3 py-2.5 text-xs text-slate-600">
                      {it.generatorNames?.length > 0
                        ? it.generatorNames.join(', ')
                        : `пул: ${it.generatorCount || 1}`}{' '}
                      · {fmtDurationSec(it.durationSec)}
                    </td>
                    <td className="px-3 py-2.5">
                      {it.status === 'PENDING' && <Badge variant="info">Ожидает</Badge>}
                      {it.status === 'STARTED' && (
                        <Link to={`/runs/${it.runId}`}>
                          <Badge variant="success">Запущен #{it.runId}</Badge>
                        </Link>
                      )}
                      {it.status === 'FAILED' && <Badge variant="danger">Ошибка</Badge>}
                      {it.status === 'CANCELLED' && <Badge variant="default">Отменён</Badge>}
                    </td>
                    <td className="px-4 py-2.5 text-right whitespace-nowrap">
                      <div className="inline-flex items-center gap-1">
                        {it.pending && (
                          <>
                            <Button
                              variant="outline"
                              size="xs"
                              title="Запустить прямо сейчас"
                              onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/start-now` })}
                            >
                              <Play className="h-3.5 w-3.5 text-emerald-600 fill-emerald-600" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              title="Сдвинуть выше"
                              onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/move`, body: { position: 'up' } })}
                            >
                              <ArrowUp className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              title="Сдвинуть ниже"
                              onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/move`, body: { position: 'down' } })}
                            >
                              <ArrowDown className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              title="В начало очереди"
                              onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/move`, body: { position: 'front' } })}
                            >
                              <ChevronsUp className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              title="В конец очереди"
                              onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/move`, body: { position: 'back' } })}
                            >
                              <ChevronsDown className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              title="Отменить"
                              onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/cancel` })}
                            >
                              <X className="h-4 w-4 text-rose-600" />
                            </Button>
                          </>
                        )}
                        {!it.pending && (
                          <Button
                            variant="outline"
                            size="xs"
                            title="Повторить в конец очереди"
                            onClick={() => actionMut.mutate({ url: `/api/v1/schedule/${it.id}/requeue`, body: { position: 'back' } })}
                          >
                            <RotateCcw className="h-3.5 w-3.5" />
                            Повторить
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Автопроверки метрик Prometheus + SSH */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
            <ShieldAlert className="h-4 w-4 text-indigo-600" />
            Автопроверки метрик Prometheus и SSH-действия ({guards.length})
          </div>
          {isAdmin && (
            <Button
              variant="outline"
              size="xs"
              onClick={() => {
                setPromTestRes(null);
                setSshTestRes(null);
                setGuardModal({
                  id: null,
                  name: '',
                  enabled: true,
                  promql: '',
                  cmp: 'GT',
                  threshold: 80,
                  unit: '%',
                  runWhen: 'BEFORE_START',
                  sshHost: generators[0]?.host || '',
                  sshPort: 22,
                  sshCredentialId: sshCredentials[0]?.id || '',
                  useSudo: false,
                  script: '',
                  timeoutSec: 60,
                  cooldownSec: 60,
                  blockOnFail: true,
                });
              }}
            >
              <Plus className="h-3.5 w-3.5" />
              Добавить правило
            </Button>
          )}
        </CardHeader>
        <CardBody className="p-0">
          {guards.length === 0 ? (
            <div className="py-8 text-center text-xs text-slate-400">
              Правила автопроверки метрик перед/после запуска не настроены
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {guards.map((g: any) => (
                <div key={g.id} className="p-4 flex flex-wrap items-center justify-between gap-3">
                  <div className="space-y-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <Switch
                        checked={g.enabled}
                        disabled={!isAdmin}
                        onChange={(v) =>
                          actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/toggle`, body: { enabled: v } })
                        }
                      />
                      <span className="font-semibold text-sm text-slate-900">{g.name}</span>
                      <Badge variant="default">{g.runWhenTitle}</Badge>
                      <Badge variant="info">{g.thresholdText}</Badge>
                      {g.lastStatusTitle && (
                        <Badge variant={g.lastStatus === 'OK' ? 'success' : 'warning'}>{g.lastStatusTitle}</Badge>
                      )}
                    </div>
                    <div className="text-xs font-mono text-slate-500 truncate max-w-2xl">{g.promql}</div>
                    <div className="text-xs text-slate-400">SSH: {g.hostSummary}</div>
                  </div>

                  {isAdmin && (
                    <div className="flex items-center gap-1.5">
                      <Button
                        variant="outline"
                        size="xs"
                        onClick={() => actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/check` })}
                      >
                        Проверить
                      </Button>
                      <Button
                        variant="outline"
                        size="xs"
                        onClick={() => actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/run-ssh` })}
                      >
                        <Terminal className="h-3.5 w-3.5" />
                        SSH
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          setPromTestRes(null);
                          setSshTestRes(null);
                          setGuardModal({ ...g });
                        }}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={async () => {
                          const ok = await confirm({
                            title: 'Удалить автопроверку?',
                            message: `Удалить правило «${g.name}»?`,
                          });
                          if (ok) actionMut.mutate({ url: `/api/v1/schedule/guards/${g.id}/delete` });
                        }}
                      >
                        <Trash2 className="h-4 w-4 text-rose-500" />
                      </Button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </CardBody>
      </Card>

      {/* Быстрая постановка в очередь */}
      <Modal open={quickOpen} onClose={() => setQuickOpen(false)} title="Поставить тест в очередь">
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await apiFetch(`/api/v1/scripts/${qmScriptId}/schedule`, {
                method: 'POST',
                body: {
                  profileId: qmProfileId ? Number(qmProfileId) : null,
                  queuePosition: qmPos === 'auto' ? null : qmPos,
                  count: qmCount,
                  shift: true,
                },
              });
              setQuickOpen(false);
              refresh();
              toast.success('Добавлено в расписание');
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <div>
            <label className="block font-medium text-slate-700 mb-1">Скрипт *</label>
            <Select
              required
              value={qmScriptId}
              onChange={(e) => {
                setQmScriptId(e.target.value);
                setQmProfileId('');
              }}
            >
              {scripts.map((s: any) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </Select>
          </div>
          {selectedScriptObj?.profiles?.length > 0 && (
            <div>
              <label className="block font-medium text-slate-700 mb-1">Профиль нагрузки</label>
              <Select value={qmProfileId} onChange={(e) => setQmProfileId(e.target.value)}>
                <option value="">★ Базовая нагрузка скрипта</option>
                {selectedScriptObj.profiles.map((p: any) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </Select>
            </div>
          )}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block font-medium text-slate-700 mb-1">Генераторов</label>
              <Input type="number" min={1} value={qmCount} onChange={(e) => setQmCount(Number(e.target.value))} />
            </div>
            <div>
              <label className="block font-medium text-slate-700 mb-1">Позиция</label>
              <Select value={qmPos} onChange={(e) => setQmPos(e.target.value as any)}>
                <option value="auto">Ближайшее окно</option>
                <option value="front">В начало очереди</option>
                <option value="back">В конец очереди</option>
              </Select>
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setQuickOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Добавить
            </Button>
          </div>
        </form>
      </Modal>

      {/* Импорт расписания JSON */}
      <Modal open={impOpen} onClose={() => setImpOpen(false)} title="Импорт расписания (JSON)" maxWidth="max-w-xl">
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              const fd = new FormData();
              fd.append('json', impJson);
              const res = await apiUpload('/api/v1/schedule/import', fd);
              toast.success(res.message);
              setImpOpen(false);
              setImpJson('');
              refresh();
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <Textarea
            rows={8}
            required
            value={impJson}
            onChange={(e) => setImpJson(e.target.value)}
            placeholder='[{"script": "my-script", "profile": "Max", "count": 2}]'
            className="font-mono text-xs"
          />
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setImpOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>

      {/* Модалка правила Guard */}
      <Modal
        open={!!guardModal}
        onClose={() => setGuardModal(null)}
        title={guardModal?.id ? 'Редактировать автопроверку' : 'Новая автопроверка Prometheus + SSH'}
        maxWidth="max-w-2xl"
      >
        {guardModal && (
          <form
            className="space-y-3 text-xs"
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                await apiFetch('/api/v1/schedule/guards', {
                  method: 'POST',
                  body: {
                    ...guardModal,
                    sshCredentialId: Number(guardModal.sshCredentialId),
                  },
                });
                setGuardModal(null);
                refresh();
                toast.success('Правило сохранено');
              } catch (err: any) {
                toast.error(err.message);
              }
            }}
          >
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Название *</label>
                <Input
                  required
                  value={guardModal.name}
                  onChange={(e) => setGuardModal({ ...guardModal, name: e.target.value })}
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Когда проверять</label>
                <Select
                  value={guardModal.runWhen}
                  onChange={(e) => setGuardModal({ ...guardModal, runWhen: e.target.value })}
                >
                  <option value="BEFORE_START">Перед стартом теста</option>
                  <option value="AFTER_RUN">После завершения теста</option>
                  <option value="BOTH">До и после теста</option>
                </Select>
              </div>
            </div>

            <div>
              <div className="flex justify-between mb-1">
                <label className="font-medium text-slate-700">PromQL запрос *</label>
                <button
                  type="button"
                  onClick={async () => {
                    setTestingGuard(true);
                    try {
                      const sp = new URLSearchParams({
                        promql: guardModal.promql,
                        cmp: guardModal.cmp,
                        threshold: String(guardModal.threshold),
                        unit: guardModal.unit || '',
                      });
                      const r = await apiFetch(`/schedule/guards/test-promql?${sp.toString()}`, { method: 'POST' });
                      setPromTestRes(r);
                    } catch (err: any) {
                      toast.error(err.message);
                    } finally {
                      setTestingGuard(false);
                    }
                  }}
                  className="text-indigo-600 hover:underline cursor-pointer"
                >
                  Проверить PromQL
                </button>
              </div>
              <Input
                required
                value={guardModal.promql}
                onChange={(e) => setGuardModal({ ...guardModal, promql: e.target.value })}
                className="font-mono text-xs"
              />
              {promTestRes && (
                <div className="mt-1.5 rounded bg-slate-100 p-2 text-[11px]">
                  {promTestRes.ok ? promTestRes.message : <span className="text-rose-600">{promTestRes.error}</span>}
                </div>
              )}
            </div>

            <div className="grid grid-cols-3 gap-2">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Условие срабатывания</label>
                <Select value={guardModal.cmp} onChange={(e) => setGuardModal({ ...guardModal, cmp: e.target.value })}>
                  <option value="GT">&gt;</option>
                  <option value="GTE">≥</option>
                  <option value="LT">&lt;</option>
                  <option value="LTE">≤</option>
                </Select>
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Порог</label>
                <Input
                  type="number"
                  step="any"
                  value={guardModal.threshold}
                  onChange={(e) => setGuardModal({ ...guardModal, threshold: Number(e.target.value) })}
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Ед. изм.</label>
                <Input value={guardModal.unit} onChange={(e) => setGuardModal({ ...guardModal, unit: e.target.value })} />
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2">
              <div className="col-span-2">
                <label className="block font-medium text-slate-700 mb-1">SSH хосты (через запятую) *</label>
                <Input
                  required
                  value={guardModal.sshHost}
                  onChange={(e) => setGuardModal({ ...guardModal, sshHost: e.target.value })}
                  placeholder="10.0.0.10:22, 10.0.0.11:22"
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">SSH-учётка *</label>
                <Select
                  required
                  value={guardModal.sshCredentialId}
                  onChange={(e) => setGuardModal({ ...guardModal, sshCredentialId: e.target.value })}
                >
                  <option value="">— выберите —</option>
                  {sshCredentials.map((c: any) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </Select>
              </div>
            </div>

            <div>
              <div className="flex justify-between mb-1">
                <label className="font-medium text-slate-700">SSH-скрипт при превышении порога *</label>
                <button
                  type="button"
                  disabled={testingGuard}
                  onClick={async () => {
                    setTestingGuard(true);
                    try {
                      const sp = new URLSearchParams({
                        sshHost: guardModal.sshHost,
                        sshPort: String(guardModal.sshPort || 22),
                        sshCredentialId: String(guardModal.sshCredentialId),
                        useSudo: String(!!guardModal.useSudo),
                        script: guardModal.script,
                        timeoutSec: String(guardModal.timeoutSec || 60),
                      });
                      const r = await apiFetch(`/schedule/guards/test-ssh?${sp.toString()}`, { method: 'POST' });
                      setSshTestRes(r);
                    } catch (err: any) {
                      toast.error(err.message);
                    } finally {
                      setTestingGuard(false);
                    }
                  }}
                  className="text-indigo-600 hover:underline cursor-pointer"
                >
                  Тест SSH
                </button>
              </div>
              <Textarea
                rows={4}
                required
                value={guardModal.script}
                onChange={(e) => setGuardModal({ ...guardModal, script: e.target.value })}
                className="font-mono text-xs"
              />
              {sshTestRes && (
                <pre className="mt-1.5 rounded bg-slate-900 text-slate-100 p-2 text-[11px] max-h-32 overflow-auto">
                  {sshTestRes.stdout || sshTestRes.stderr || sshTestRes.message || sshTestRes.error}
                </pre>
              )}
            </div>

            <div className="flex items-center gap-4">
              <Switch
                checked={!!guardModal.useSudo}
                onChange={(v) => setGuardModal({ ...guardModal, useSudo: v })}
                label={<span className="text-xs">Выполнять через sudo</span>}
              />
              <Switch
                checked={!!guardModal.blockOnFail}
                onChange={(v) => setGuardModal({ ...guardModal, blockOnFail: v })}
                label={<span className="text-xs">Блокировать старт при ошибке</span>}
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" size="sm" onClick={() => setGuardModal(null)}>
                Отмена
              </Button>
              <Button type="submit" size="sm">
                Сохранить правило
              </Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
}
