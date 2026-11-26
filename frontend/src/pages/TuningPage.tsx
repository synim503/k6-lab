import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Activity,
  ArrowLeft,
  BookmarkPlus,
  CheckCircle2,
  Eye,
  Play,
  RotateCcw,
  Share2,
  Sliders,
  Trash2,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { fmtDateTime, genStatusMeta } from '@/lib/utils';
import { useConfirm } from '@/components/ui/ConfirmContext';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Input,
  Modal,
  Select,
  Spinner,
} from '@/components/ui/primitives';

interface TuningFinding {
  severity: 'CRIT' | 'WARN' | 'INFO';
  area: string;
  title: string;
  detail: string;
  key?: string | null;
  current?: string | null;
  recommended?: string | null;
}

interface HostProfileDto {
  id: number;
  generatorId: number;
  generatorName: string;
  startedAt: string;
  finishedAt?: string | null;
  durationSec: number;
  status: string;
  error?: string | null;
  findings?: TuningFinding[] | null;
}

interface HostTuningDto {
  id: number;
  generatorId: number;
  generatorName: string;
  appliedAt: string;
  appliedBy: string;
  rolledBackAt?: string | null;
  status: string;
  changes?: Record<string, any> | null;
  error?: string | null;
}

interface TuningRow {
  id: number;
  name: string;
  host: string;
  status: string;
  profile?: HostProfileDto | null;
  tuning?: HostTuningDto | null;
  tuned: boolean;
}

interface OptProfileDto {
  id: number;
  name: string;
  description: string;
  settings: Record<string, string>;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

interface TuningIndexResponse {
  rows: TuningRow[];
  optProfiles: OptProfileDto[];
  history: HostTuningDto[];
  allKeys: string[];
}

export function TuningPage() {
  const qc = useQueryClient();
  const confirm = useConfirm();

  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [seconds, setSeconds] = useState('30');
  const [refHostId, setRefHostId] = useState('');
  const [inspectRow, setInspectRow] = useState<TuningRow | null>(null);

  const [saveProfileOpen, setSaveProfileOpen] = useState(false);
  const [profName, setProfName] = useState('');
  const [profDesc, setProfDesc] = useState('');
  const [profFromHost, setProfFromHost] = useState('');

  const { data, isLoading } = useQuery<TuningIndexResponse>({
    queryKey: ['tuning'],
    queryFn: () => apiFetch('/api/v1/tuning'),
    refetchInterval: 5000,
  });

  const showMessages = (res: { messages?: string[] }, fallback: string) => {
    if (res.messages && res.messages.length > 0) {
      toast.success(res.messages.join(' · '));
    } else {
      toast.success(fallback);
    }
    qc.invalidateQueries({ queryKey: ['tuning'] });
  };

  const profileMut = useMutation({
    mutationFn: () =>
      apiFetch<{ messages: string[] }>('/api/v1/tuning/profile', {
        method: 'POST',
        body: { generatorIds: selectedIds, seconds: Math.max(5, Number(seconds) || 30) },
      }),
    onSuccess: (res) => showMessages(res, 'Профилирование запущено'),
    onError: (e: Error) => toast.error(e.message),
  });

  const applyMut = useMutation({
    mutationFn: () =>
      apiFetch<{ messages: string[] }>('/api/v1/tuning/apply', {
        method: 'POST',
        body: { generatorIds: selectedIds },
      }),
    onSuccess: (res) => showMessages(res, 'Рекомендации применены'),
    onError: (e: Error) => toast.error(e.message),
  });

  const rolloutMut = useMutation({
    mutationFn: () =>
      apiFetch<{ messages: string[] }>('/api/v1/tuning/rollout', {
        method: 'POST',
        body: { sourceId: Number(refHostId), generatorIds: selectedIds },
      }),
    onSuccess: (res) => showMessages(res, 'Настройки эталона раскатаны'),
    onError: (e: Error) => toast.error(e.message),
  });

  const rollbackMut = useMutation({
    mutationFn: () =>
      apiFetch<{ messages: string[] }>('/api/v1/tuning/rollback', {
        method: 'POST',
        body: { generatorIds: selectedIds },
      }),
    onSuccess: (res) => showMessages(res, 'Тюнинг откачен на выбранных хостах'),
    onError: (e: Error) => toast.error(e.message),
  });

  const rollbackAllMut = useMutation({
    mutationFn: () => apiFetch<{ messages: string[] }>('/api/v1/tuning/rollback-all', { method: 'POST' }),
    onSuccess: (res) => showMessages(res, 'Тюнинг откачен на всех хостах'),
    onError: (e: Error) => toast.error(e.message),
  });

  const createOptMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/tuning/profiles', {
        method: 'POST',
        body: {
          name: profName.trim(),
          description: profDesc.trim(),
          fromHost: profFromHost ? Number(profFromHost) : null,
        },
      }),
    onSuccess: () => {
      toast.success('Профиль оптимизации сохранён');
      setSaveProfileOpen(false);
      setProfName('');
      setProfDesc('');
      qc.invalidateQueries({ queryKey: ['tuning'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const applyOptMut = useMutation({
    mutationFn: (id: number) =>
      apiFetch<{ messages: string[] }>(`/api/v1/tuning/profiles/${id}/apply`, {
        method: 'POST',
        body: { generatorIds: selectedIds },
      }),
    onSuccess: (res) => showMessages(res, 'Профиль оптимизации применён'),
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteOptMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/tuning/profiles/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Профиль удалён');
      qc.invalidateQueries({ queryKey: ['tuning'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (isLoading && !data) return <Spinner label="Загрузка тюнинга хостов…" />;

  const rows = data?.rows || [];
  const allSelected = rows.length > 0 && selectedIds.length === rows.length;

  const toggleOne = (id: number) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  };

  const handleRollbackAll = async () => {
    const ok = await confirm({
      title: 'Откатить тюнинг на всех серверах',
      message: 'Исходные значения sysctl и параметров ядра будут возвращены на всех генераторах с активным тюнингом.',
      confirmText: 'Откатить везде',
      variant: 'warning',
    });
    if (ok) rollbackAllMut.mutate();
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Link to="/generators">
            <Button variant="outline" size="icon">
              <ArrowLeft className="h-4 w-4" />
            </Button>
          </Link>
          <div>
            <h1 className="text-xl font-bold text-slate-900">Тюнинг ОС генераторов</h1>
            <p className="text-xs text-slate-500 mt-0.5">
              Профилирование узких мест Linux (sysctl, порты, буферы TCP, governor, THP) с безопасным откатом
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              const firstDone = rows.find((r) => r.profile?.status === 'DONE');
              if (firstDone && !profFromHost) setProfFromHost(String(firstDone.id));
              setSaveProfileOpen(true);
            }}
          >
            <BookmarkPlus className="h-3.5 w-3.5" />
            Сохранить профиль
          </Button>
          {rows.some((r) => r.tuned) && (
            <Button
              variant="outline"
              size="sm"
              loading={rollbackAllMut.isPending}
              onClick={handleRollbackAll}
            >
              <RotateCcw className="h-3.5 w-3.5" />
              Откатить на всех
            </Button>
          )}
        </div>
      </div>

      {/* Панель действий над выбранными хостами */}
      <Card className="p-3.5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-slate-600">
              Выбрано хостов: <strong className="text-slate-900">{selectedIds.length}</strong>
            </span>
            <div className="flex items-center gap-1.5 ml-2">
              <Input
                type="number"
                min={5}
                max={300}
                value={seconds}
                onChange={(e) => setSeconds(e.target.value)}
                className="w-20 h-8 text-xs"
              />
              <span className="text-xs text-slate-500">сек</span>
              <Button
                size="sm"
                disabled={selectedIds.length === 0}
                loading={profileMut.isPending}
                onClick={() => profileMut.mutate()}
              >
                <Activity className="h-3.5 w-3.5" />
                Профилировать
              </Button>
            </div>
            <Button
              variant="success"
              size="sm"
              disabled={selectedIds.length === 0}
              loading={applyMut.isPending}
              onClick={() => applyMut.mutate()}
            >
              <CheckCircle2 className="h-3.5 w-3.5" />
              Применить рекомендации
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={selectedIds.length === 0}
              loading={rollbackMut.isPending}
              onClick={() => rollbackMut.mutate()}
            >
              <RotateCcw className="h-3.5 w-3.5" />
              Откатить выбранные
            </Button>
          </div>

          <div className="flex items-center gap-2">
            <Select
              value={refHostId}
              onChange={(e) => setRefHostId(e.target.value)}
              className="w-52 h-8 text-xs"
            >
              <option value="">— Эталонный хост —</option>
              {rows
                .filter((r) => r.tuned || r.profile?.status === 'DONE')
                .map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} ({r.host})
                  </option>
                ))}
            </Select>
            <Button
              variant="secondary"
              size="sm"
              disabled={!refHostId || selectedIds.length === 0}
              loading={rolloutMut.isPending}
              onClick={() => rolloutMut.mutate()}
            >
              <Share2 className="h-3.5 w-3.5" />
              Раскатать с эталона
            </Button>
          </div>
        </div>
      </Card>

      {/* Таблица хостов */}
      <Card className="overflow-hidden">
        {rows.length === 0 ? (
          <EmptyState
            icon={<Sliders className="h-6 w-6" />}
            title="Нет генераторов для тюнинга"
            description="Сначала подключите генераторы нагрузки."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 w-10 text-left">
                    <input
                      type="checkbox"
                      checked={allSelected}
                      onChange={() => setSelectedIds(allSelected ? [] : rows.map((r) => r.id))}
                      className="rounded border-slate-300"
                    />
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">Хост</th>
                  <th className="px-3 py-2.5 text-left font-medium">Статус</th>
                  <th className="px-3 py-2.5 text-left font-medium">Последнее профилирование</th>
                  <th className="px-3 py-2.5 text-left font-medium">Находки</th>
                  <th className="px-3 py-2.5 text-left font-medium">Тюнинг</th>
                  <th className="px-4 py-2.5 text-right font-medium">Детали</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((r) => {
                  const gst = genStatusMeta(r.status);
                  const findings = Array.isArray(r.profile?.findings) ? r.profile!.findings! : [];
                  const critCount = findings.filter((f) => f.severity === 'CRIT').length;
                  const warnCount = findings.filter((f) => f.severity === 'WARN').length;
                  return (
                    <tr key={r.id} className="hover:bg-slate-50/70">
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selectedIds.includes(r.id)}
                          onChange={() => toggleOne(r.id)}
                          className="rounded border-slate-300"
                        />
                      </td>
                      <td className="px-3 py-3">
                        <Link to={`/generators/${r.id}`} className="font-semibold text-slate-900 hover:text-indigo-600">
                          {r.name}
                        </Link>
                        <div className="text-xs font-mono text-slate-500">{r.host}</div>
                      </td>
                      <td className="px-3 py-3">
                        <Badge variant={gst.variant}>{gst.label}</Badge>
                      </td>
                      <td className="px-3 py-3 text-xs text-slate-600">
                        {r.profile ? (
                          <div>
                            <Badge
                              variant={
                                r.profile.status === 'DONE'
                                  ? 'success'
                                  : r.profile.status === 'RUNNING'
                                    ? 'info'
                                    : 'danger'
                              }
                            >
                              {r.profile.status}
                            </Badge>
                            <div className="text-[11px] text-slate-400 mt-0.5">{fmtDateTime(r.profile.startedAt)}</div>
                          </div>
                        ) : (
                          <span className="text-slate-400">Не проводилось</span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-xs">
                        {findings.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {critCount > 0 && <Badge variant="danger">CRIT: {critCount}</Badge>}
                            {warnCount > 0 && <Badge variant="warning">WARN: {warnCount}</Badge>}
                            {critCount === 0 && warnCount === 0 && (
                              <Badge variant="info">INFO: {findings.length}</Badge>
                            )}
                          </div>
                        ) : r.profile?.status === 'DONE' ? (
                          <span className="text-emerald-600 font-medium">Оптимально</span>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-xs">
                        {r.tuned ? (
                          <div>
                            <Badge variant="success">Применён</Badge>
                            <div className="text-[11px] text-slate-400 mt-0.5">{fmtDateTime(r.tuning?.appliedAt)}</div>
                          </div>
                        ) : r.tuning?.status === 'ROLLED_BACK' ? (
                          <Badge variant="default">Откачен</Badge>
                        ) : (
                          <span className="text-slate-400">Нет</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right">
                        {(findings.length > 0 || r.tuning) && (
                          <Button variant="ghost" size="xs" onClick={() => setInspectRow(r)}>
                            <Eye className="h-3.5 w-3.5" />
                            Открыть
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Сохранённые профили оптимизации */}
      {(data?.optProfiles?.length ?? 0) > 0 && (
        <Card>
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">Сохранённые профили оптимизации</div>
          </CardHeader>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2 text-left font-medium">Название</th>
                  <th className="px-3 py-2 text-left font-medium">Параметров</th>
                  <th className="px-3 py-2 text-left font-medium">Автор</th>
                  <th className="px-4 py-2 text-right font-medium">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data!.optProfiles.map((op) => (
                  <tr key={op.id} className="hover:bg-slate-50/70">
                    <td className="px-4 py-2.5">
                      <div className="font-medium text-slate-900">{op.name}</div>
                      {op.description && <div className="text-xs text-slate-500">{op.description}</div>}
                    </td>
                    <td className="px-3 py-2.5 text-xs font-mono text-slate-600">
                      {Object.keys(op.settings || {}).length} sysctl
                    </td>
                    <td className="px-3 py-2.5 text-xs text-slate-500">{op.createdBy}</td>
                    <td className="px-4 py-2.5 text-right space-x-2">
                      <Button
                        variant="outline"
                        size="xs"
                        disabled={selectedIds.length === 0}
                        loading={applyOptMut.isPending}
                        onClick={() => applyOptMut.mutate(op.id)}
                      >
                        <Play className="h-3 w-3" />
                        Применить к выбранным
                      </Button>
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={async () => {
                          const ok = await confirm({
                            title: 'Удалить профиль оптимизации',
                            message: `Удалить сохранённый профиль «${op.name}»?`,
                            confirmText: 'Удалить',
                          });
                          if (ok) deleteOptMut.mutate(op.id);
                        }}
                      >
                        <Trash2 className="h-3.5 w-3.5 text-rose-500" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* История применений */}
      {(data?.history?.length ?? 0) > 0 && (
        <Card>
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">История изменений тюнинга</div>
          </CardHeader>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2 text-left font-medium">Время</th>
                  <th className="px-3 py-2 text-left font-medium">Генератор</th>
                  <th className="px-3 py-2 text-left font-medium">Статус</th>
                  <th className="px-3 py-2 text-left font-medium">Кто применил</th>
                  <th className="px-4 py-2 text-left font-medium">Примечание</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data!.history.map((h) => (
                  <tr key={h.id} className="text-xs">
                    <td className="px-4 py-2 text-slate-500">{fmtDateTime(h.appliedAt)}</td>
                    <td className="px-3 py-2 font-medium text-slate-800">{h.generatorName}</td>
                    <td className="px-3 py-2">
                      <Badge
                        variant={
                          h.status === 'APPLIED' ? 'success' : h.status === 'FAILED' ? 'danger' : 'default'
                        }
                      >
                        {h.status}
                      </Badge>
                    </td>
                    <td className="px-3 py-2 text-slate-600">{h.appliedBy}</td>
                    <td className="px-4 py-2 text-rose-600 truncate max-w-xs">{h.error || ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Модальное окно деталей находок */}
      <Modal
        open={!!inspectRow}
        onClose={() => setInspectRow(null)}
        title={`Результаты анализа — ${inspectRow?.name || ''}`}
        maxWidth="max-w-3xl"
      >
        {inspectRow && (
          <div className="space-y-4">
            {Array.isArray(inspectRow.profile?.findings) && inspectRow.profile!.findings!.length > 0 && (
              <div className="space-y-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                  Рекомендации и узкие места
                </div>
                {inspectRow.profile!.findings!.map((f, i) => (
                  <div key={i} className="rounded-xl border border-slate-200 p-3 text-xs space-y-1">
                    <div className="flex items-center justify-between gap-2">
                      <div className="font-semibold text-slate-900">{f.title}</div>
                      <Badge
                        variant={
                          f.severity === 'CRIT' ? 'danger' : f.severity === 'WARN' ? 'warning' : 'info'
                        }
                      >
                        {f.severity} · {f.area}
                      </Badge>
                    </div>
                    <p className="text-slate-600">{f.detail}</p>
                    {f.key && (
                      <div className="font-mono text-[11px] bg-slate-50 rounded px-2 py-1 text-slate-800">
                        {f.key}: <span className="text-slate-500">{f.current ?? '?'}</span> →{' '}
                        <span className="text-emerald-700 font-semibold">{f.recommended}</span>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
            {inspectRow.tuning?.changes && (
              <div>
                <div className="text-xs font-semibold uppercase tracking-wide text-slate-500 mb-1.5">
                  Применённые параметры (с сохранёнными исходными значениями)
                </div>
                <pre className="rounded-xl bg-slate-950 text-slate-100 p-3 text-xs font-mono overflow-x-auto">
                  {JSON.stringify(inspectRow.tuning.changes, null, 2)}
                </pre>
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Модальное окно сохранения профиля оптимизации */}
      <Modal
        open={saveProfileOpen}
        onClose={() => setSaveProfileOpen(false)}
        title="Сохранить профиль оптимизации ОС"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setSaveProfileOpen(false)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={!profName.trim() || !profFromHost}
              loading={createOptMut.isPending}
              onClick={() => createOptMut.mutate()}
            >
              Сохранить
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Название профиля *</label>
            <Input
              value={profName}
              onChange={(e) => setProfName(e.target.value)}
              placeholder="High-RPS 10Gbps Linux"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Описание</label>
            <Input
              value={profDesc}
              onChange={(e) => setProfDesc(e.target.value)}
              placeholder="Набор sysctl после профилирования эталона"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">
              Взять рекомендации с запрофилированного хоста *
            </label>
            <Select value={profFromHost} onChange={(e) => setProfFromHost(e.target.value)}>
              <option value="">— Выберите хост —</option>
              {rows
                .filter((r) => r.profile?.status === 'DONE')
                .map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} ({r.host})
                  </option>
                ))}
            </Select>
          </div>
        </div>
      </Modal>
    </div>
  );
}
