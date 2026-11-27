import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Activity,
  ArrowLeft,
  BookmarkPlus,
  CheckCircle2,
  Cpu,
  Eye,
  Pencil,
  Play,
  Plus,
  RotateCcw,
  Search,
  Share2,
  Sliders,
  Sparkles,
  Trash2,
  X,
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

interface HostMetricsDto {
  cpus?: number;
  memTotalKb?: number;
  memAvailKb?: number;
  swapTotalKb?: number;
  swapFreeKb?: number;
  kernel?: string;
  iface?: string;
  speedMbit?: number | null;
  thp?: string;
  governor?: string;
  conntrackCount?: number | null;
  conntrackMax?: number | null;
  psiCpu?: number | null;
  psiMem?: number | null;
  sysctl?: Record<string, string>;
  k6?: { pid: number; soft: number; hard: number; fds: number }[];
  windowSec?: number;
  cpuBusyPct?: number | null;
  cpuUserPct?: number | null;
  cpuSystemPct?: number | null;
  cpuSoftirqPct?: number | null;
  cpuStealPct?: number | null;
  cpuIowaitPct?: number | null;
  softnetDropped?: number | null;
  softnetSqueezed?: number | null;
  tcpOutSegsPerSec?: number | null;
  tcpRetransPct?: number | null;
  tcpAttemptFails?: number | null;
  tcpEstabResets?: number | null;
  listenOverflows?: number | null;
  listenDrops?: number | null;
  tcpInUse?: number | null;
  tcpTimeWait?: number | null;
  tcpOrphan?: number | null;
  rxMbit?: number | null;
  txMbit?: number | null;
  nicErrors?: number | null;
  nicDrops?: number | null;
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
  metrics?: HostMetricsDto | null;
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
  keyDescriptions?: Record<string, string>;
}

function fmtNum(v?: number | null, digits = 1): string {
  if (v == null || !Number.isFinite(v)) return '—';
  return Number.isInteger(v) ? String(v) : v.toFixed(digits);
}

export function TuningPage() {
  const qc = useQueryClient();
  const confirm = useConfirm();

  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [hostSearch, setHostSearch] = useState('');
  const [seconds, setSeconds] = useState('30');
  const [refHostId, setRefHostId] = useState('');
  const [inspectRow, setInspectRow] = useState<TuningRow | null>(null);

  // Unified Create / Edit Optimization Profile modal
  const [optEditorOpen, setOptEditorOpen] = useState(false);
  const [editingOptId, setEditingOptId] = useState<number | null>(null);
  const [profName, setProfName] = useState('');
  const [profDesc, setProfDesc] = useState('');
  const [profSettingsRows, setProfSettingsRows] = useState<{ k: string; v: string }[]>([]);
  const [newParamKey, setNewParamKey] = useState('');
  const [newParamVal, setNewParamVal] = useState('');

  const { data, isLoading } = useQuery<TuningIndexResponse>({
    queryKey: ['tuning'],
    queryFn: () => apiFetch('/api/v1/tuning'),
    refetchInterval: 5000,
  });

  const rows = data?.rows || [];
  const allKeys = data?.allKeys || [];
  const keyDesc = data?.keyDescriptions || {};

  const profiledRows = useMemo(
    () => rows.filter((r) => r.profile?.status === 'DONE'),
    [rows]
  );

  useEffect(() => {
    if (!refHostId && profiledRows.length > 0) {
      const withRecs = profiledRows.find((r) =>
        (r.profile?.findings || []).some((f) => f.key && f.recommended)
      );
      setRefHostId(String((withRecs || profiledRows[0]).id));
    }
  }, [profiledRows, refHostId]);

  const showMessages = (res: { messages?: string[] }, fallback: string) => {
    if (res.messages && res.messages.length > 0) {
      toast.success(res.messages.join(' · '));
    } else {
      toast.success(fallback);
    }
    qc.invalidateQueries({ queryKey: ['tuning'] });
  };

  const profileMut = useMutation({
    mutationFn: (ids: number[] | null) =>
      apiFetch<{ messages: string[] }>('/api/v1/tuning/profile', {
        method: 'POST',
        body: { generatorIds: ids && ids.length > 0 ? ids : null, seconds: Math.max(5, Number(seconds) || 30) },
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
    mutationFn: ({ sourceId, ids }: { sourceId: number; ids: number[] | null }) =>
      apiFetch<{ messages: string[] }>('/api/v1/tuning/rollout', {
        method: 'POST',
        body: { sourceId, generatorIds: ids && ids.length > 0 ? ids : null },
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

  const saveOptMut = useMutation({
    mutationFn: () => {
      const settingsObj: Record<string, string> = {};
      profSettingsRows.forEach((r) => {
        if (r.k.trim() && r.v.trim()) settingsObj[r.k.trim()] = r.v.trim();
      });
      const url = editingOptId ? `/api/v1/tuning/profiles/${editingOptId}` : '/api/v1/tuning/profiles';
      return apiFetch(url, {
        method: 'POST',
        body: {
          name: profName.trim(),
          description: profDesc.trim(),
          settings: settingsObj,
        },
      });
    },
    onSuccess: () => {
      toast.success(editingOptId ? 'Профиль оптимизации обновлён' : 'Профиль оптимизации создан');
      setOptEditorOpen(false);
      qc.invalidateQueries({ queryKey: ['tuning'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const applyOptMut = useMutation({
    mutationFn: ({ id, ids }: { id: number; ids: number[] | null }) =>
      apiFetch<{ messages: string[] }>(`/api/v1/tuning/profiles/${id}/apply`, {
        method: 'POST',
        body: { generatorIds: ids && ids.length > 0 ? ids : null },
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

  const openOptCreatorFromHost = (hostRow?: TuningRow) => {
    setEditingOptId(null);
    const src = hostRow || profiledRows[0];
    const settings: { k: string; v: string }[] = [];
    if (src?.profile?.findings) {
      src.profile.findings.forEach((f) => {
        if (f.key && f.recommended) {
          settings.push({ k: f.key, v: f.recommended });
        }
      });
    }
    if (settings.length === 0) {
      settings.push(
        { k: 'net.ipv4.ip_local_port_range', v: '10240 65535' },
        { k: 'net.ipv4.tcp_tw_reuse', v: '1' },
        { k: 'net.core.somaxconn', v: '65535' }
      );
    }
    setProfName(src ? `Профиль ${src.name}` : 'High-RPS Linux');
    setProfDesc(src ? `Рекомендации по итогам профилирования ${src.host}` : '');
    setProfSettingsRows(settings);
    setNewParamKey('');
    setNewParamVal('');
    setOptEditorOpen(true);
  };

  const openOptEditor = (op: OptProfileDto) => {
    setEditingOptId(op.id);
    setProfName(op.name);
    setProfDesc(op.description || '');
    setProfSettingsRows(Object.entries(op.settings || {}).map(([k, v]) => ({ k, v })));
    setNewParamKey('');
    setNewParamVal('');
    setOptEditorOpen(true);
  };

  if (isLoading && !data) return <Spinner label="Загрузка тюнинга хостов…" />;

  const filteredRows = rows.filter((r) => {
    const q = hostSearch.trim().toLowerCase();
    if (!q) return true;
    return `${r.name} ${r.host}`.toLowerCase().includes(q);
  });

  const allSelected =
    filteredRows.length > 0 && filteredRows.every((r) => selectedIds.includes(r.id));

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

  const refRow = rows.find((r) => String(r.id) === refHostId);
  const refSettingsFindings = (refRow?.profile?.findings || []).filter((f) => f.key && f.recommended);

  const usedOptKeys = new Set(profSettingsRows.map((r) => r.k));
  const freeOptKeys = allKeys.filter((k) => !usedOptKeys.has(k));

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
          <Button variant="outline" size="sm" onClick={() => openOptCreatorFromHost(refRow)}>
            <BookmarkPlus className="h-3.5 w-3.5" />
            Создать профиль оптимизации
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

      {/* Карточка Эталонной настройки (когда есть запрофилированный хост) */}
      {profiledRows.length > 0 && (
        <Card className="border-indigo-200/80 bg-indigo-50/30">
          <CardHeader>
            <div className="flex flex-wrap items-center gap-2">
              <Sparkles className="h-4 w-4 text-indigo-600" />
              <span className="font-semibold text-sm text-slate-900">Эталонная настройка кластера</span>
              <Select
                value={refHostId}
                onChange={(e) => setRefHostId(e.target.value)}
                className="h-7 text-xs w-56 bg-white"
              >
                {profiledRows.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} ({r.host})
                  </option>
                ))}
              </Select>
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              {refSettingsFindings.length > 0 && (
                <Button variant="outline" size="xs" onClick={() => openOptCreatorFromHost(refRow)}>
                  <BookmarkPlus className="h-3.5 w-3.5" />
                  Сохранить как профиль
                </Button>
              )}
              <Button
                size="xs"
                disabled={!refHostId}
                loading={rolloutMut.isPending}
                onClick={() =>
                  rolloutMut.mutate({
                    sourceId: Number(refHostId),
                    ids: selectedIds.length > 0 ? selectedIds : null,
                  })
                }
              >
                <Share2 className="h-3.5 w-3.5" />
                {selectedIds.length > 0
                  ? `Раскатать на выбранные (${selectedIds.length})`
                  : `Раскатать на все хосты (${rows.length})`}
              </Button>
            </div>
          </CardHeader>
          {refSettingsFindings.length > 0 ? (
            <CardBody className="p-0 overflow-x-auto">
              <table className="w-full text-xs">
                <thead className="bg-white/70 text-slate-500 border-b border-indigo-100">
                  <tr>
                    <th className="text-left px-4 py-2 font-medium">Параметр ядра</th>
                    <th className="text-left px-3 py-2 font-medium">Сейчас → Будет</th>
                    <th className="text-left px-4 py-2 font-medium">Почему важно для нагрузки</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-indigo-100/60 bg-white/50">
                  {refSettingsFindings.map((f, idx) => (
                    <tr key={idx}>
                      <td className="px-4 py-2 font-mono font-semibold text-slate-800">{f.key}</td>
                      <td className="px-3 py-2 font-mono whitespace-nowrap">
                        <span className="text-slate-500">{f.current ?? '?'}</span> →{' '}
                        <span className="font-bold text-emerald-700">{f.recommended}</span>
                      </td>
                      <td className="px-4 py-2 text-slate-600">{f.title}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardBody>
          ) : (
            <CardBody className="py-2.5 text-xs text-slate-600">
              На выбранном эталонном хосте узких мест sysctl не обнаружено.
            </CardBody>
          )}
        </Card>
      )}

      {/* Панель действий над хостами + поиск */}
      <Card className="p-3.5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-slate-600">
              Выбрано: <strong className="text-slate-900">{selectedIds.length}</strong>
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
                loading={profileMut.isPending}
                onClick={() => profileMut.mutate(selectedIds.length > 0 ? selectedIds : null)}
              >
                <Activity className="h-3.5 w-3.5" />
                {selectedIds.length > 0 ? `Профилировать (${selectedIds.length})` : 'Профилировать все'}
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

          <div className="relative w-60">
            <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
            <Input
              value={hostSearch}
              onChange={(e) => setHostSearch(e.target.value)}
              placeholder="Поиск хоста…"
              className="pl-8 h-8 text-xs"
            />
          </div>
        </div>
      </Card>

      {/* Таблица хостов */}
      <Card className="overflow-hidden">
        {filteredRows.length === 0 ? (
          <EmptyState
            icon={<Sliders className="h-6 w-6" />}
            title={rows.length === 0 ? 'Нет генераторов для тюнинга' : 'Ничего не найдено'}
            description="Подключите генераторы нагрузки или сбросьте строку поиска."
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
                      onChange={() => {
                        if (allSelected) {
                          const visSet = new Set(filteredRows.map((r) => r.id));
                          setSelectedIds((prev) => prev.filter((id) => !visSet.has(id)));
                        } else {
                          const next = new Set(selectedIds);
                          filteredRows.forEach((r) => next.add(r.id));
                          setSelectedIds(Array.from(next));
                        }
                      }}
                      className="rounded border-slate-300"
                    />
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">Хост</th>
                  <th className="px-3 py-2.5 text-left font-medium">Статус</th>
                  <th className="px-3 py-2.5 text-left font-medium">Последнее профилирование</th>
                  <th className="px-3 py-2.5 text-left font-medium">Находки</th>
                  <th className="px-3 py-2.5 text-left font-medium">Тюнинг</th>
                  <th className="px-4 py-2.5 text-right font-medium">Отчёт ОС</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filteredRows.map((r) => {
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
                        {(r.profile || r.tuning) && (
                          <Button variant="outline" size="xs" onClick={() => setInspectRow(r)}>
                            <Eye className="h-3.5 w-3.5" />
                            Метрики и sysctl
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

      {/* Сохранённые профили оптимизации с редактированием и раскаткой */}
      {(data?.optProfiles?.length ?? 0) > 0 && (
        <Card>
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">
              Сохранённые профили оптимизации ({data!.optProfiles.length})
            </div>
          </CardHeader>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2 text-left font-medium">Название</th>
                  <th className="px-3 py-2 text-left font-medium">Набор настроек</th>
                  <th className="px-3 py-2 text-left font-medium">Автор</th>
                  <th className="px-4 py-2 text-right font-medium">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data!.optProfiles.map((op) => {
                  const keys = Object.keys(op.settings || {});
                  return (
                    <tr key={op.id} className="hover:bg-slate-50/70">
                      <td className="px-4 py-2.5">
                        <div className="font-semibold text-slate-900">{op.name}</div>
                        {op.description && <div className="text-xs text-slate-500">{op.description}</div>}
                      </td>
                      <td className="px-3 py-2.5 text-xs">
                        <Badge variant="info">{keys.length} парам.</Badge>
                        <span className="ml-2 font-mono text-[11px] text-slate-500">
                          {keys.slice(0, 3).join(', ')}
                          {keys.length > 3 ? '…' : ''}
                        </span>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-slate-500">{op.createdBy}</td>
                      <td className="px-4 py-2.5 text-right whitespace-nowrap space-x-1.5">
                        <Button variant="outline" size="xs" onClick={() => openOptEditor(op)}>
                          <Pencil className="h-3 w-3" />
                          Править
                        </Button>
                        <Button
                          variant="secondary"
                          size="xs"
                          loading={applyOptMut.isPending}
                          onClick={() =>
                            applyOptMut.mutate({
                              id: op.id,
                              ids: selectedIds.length > 0 ? selectedIds : null,
                            })
                          }
                        >
                          <Play className="h-3 w-3" />
                          {selectedIds.length > 0
                            ? `Раскатать (${selectedIds.length})`
                            : 'Раскатать на все'}
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
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* История применений с диффом было -> стало */}
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
                  <th className="px-3 py-2 text-left font-medium">Изменённые параметры</th>
                  <th className="px-4 py-2 text-left font-medium">Кто / Примечание</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data!.history.map((h) => {
                  const chEntries = Object.entries(h.changes || {});
                  return (
                    <tr key={h.id} className="text-xs align-top">
                      <td className="px-4 py-2 text-slate-500 whitespace-nowrap">{fmtDateTime(h.appliedAt)}</td>
                      <td className="px-3 py-2 font-medium text-slate-800 whitespace-nowrap">{h.generatorName}</td>
                      <td className="px-3 py-2">
                        <Badge
                          variant={
                            h.status === 'APPLIED' ? 'success' : h.status === 'FAILED' ? 'danger' : 'default'
                          }
                        >
                          {h.status}
                        </Badge>
                      </td>
                      <td className="px-3 py-2 font-mono text-[11px] text-slate-600 max-w-md">
                        {chEntries.length > 0 ? (
                          <div className="flex flex-wrap gap-x-3 gap-y-0.5">
                            {chEntries.slice(0, 6).map(([k, val]: [string, any]) => (
                              <span key={k}>
                                <b>{k}</b>:{' '}
                                {val && typeof val === 'object' && ('before' in val || 'previous' in val)
                                  ? `${val.before ?? val.previous ?? '?'} → ${val.after ?? val.applied ?? '?'}`
                                  : String(val)}
                              </span>
                            ))}
                            {chEntries.length > 6 && <span>+{chEntries.length - 6} ещё</span>}
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="px-4 py-2 text-slate-600">
                        <div>{h.appliedBy}</div>
                        {h.error && <div className="text-rose-600">{h.error}</div>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Полный отчёт профилирования хоста (метрики ОС + находки + sysctl) */}
      <Modal
        open={!!inspectRow}
        onClose={() => setInspectRow(null)}
        title={`Профиль ОС генератора — ${inspectRow?.name || ''} (${inspectRow?.host || ''})`}
        maxWidth="max-w-4xl"
      >
        {inspectRow && (
          <div className="space-y-4 text-xs">
            {/* Сводка телеметрии ОС */}
            {inspectRow.profile?.metrics && (() => {
              const m = inspectRow.profile!.metrics!;
              return (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5">
                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 space-y-0.5">
                    <div className="text-[11px] text-slate-500">CPU и ядро</div>
                    <div className="font-bold text-sm text-slate-900">
                      {m.cpus || '?'} ядер · busy {fmtNum(m.cpuBusyPct)}%
                    </div>
                    <div className="text-[11px] text-slate-500">
                      usr {fmtNum(m.cpuUserPct)}% · sys {fmtNum(m.cpuSystemPct)}% · sirq {fmtNum(m.cpuSoftirqPct)}%
                    </div>
                    <div className="text-[10px] font-mono text-slate-400 truncate">
                      {m.kernel || ''} · gov: {m.governor || '?'}
                    </div>
                  </div>

                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 space-y-0.5">
                    <div className="text-[11px] text-slate-500">Память и THP</div>
                    <div className="font-bold text-sm text-slate-900">
                      {m.memTotalKb ? `${(m.memTotalKb / 1048576).toFixed(1)} ГБ` : '—'}
                    </div>
                    <div className="text-[11px] text-slate-500">
                      свободно: {m.memAvailKb ? `${(m.memAvailKb / 1048576).toFixed(1)} ГБ` : '—'}
                    </div>
                    <div className="text-[10px] font-mono text-slate-400">THP: {m.thp || '?'}</div>
                  </div>

                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 space-y-0.5">
                    <div className="text-[11px] text-slate-500">Сеть ({m.iface || 'NIC'})</div>
                    <div className="font-bold text-sm text-slate-900">
                      ↓ {fmtNum(m.rxMbit)} / ↑ {fmtNum(m.txMbit)} Мбит/с
                    </div>
                    <div className="text-[11px] text-slate-500">
                      линк: {m.speedMbit ? `${m.speedMbit} Мбит/с` : '—'} · drops: {m.nicDrops ?? 0}
                    </div>
                    <div className="text-[10px] font-mono text-slate-400">
                      softnet drop: {m.softnetDropped ?? 0}
                    </div>
                  </div>

                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 space-y-0.5">
                    <div className="text-[11px] text-slate-500">Стек TCP и сокеты</div>
                    <div className="font-bold text-sm text-slate-900">
                      inUse: {m.tcpInUse ?? '—'} · TW: {m.tcpTimeWait ?? '—'}
                    </div>
                    <div className="text-[11px] text-slate-500">
                      retrans: {fmtNum(m.tcpRetransPct, 2)}% · out: {fmtNum(m.tcpOutSegsPerSec, 0)}/с
                    </div>
                    {m.conntrackMax != null && (
                      <div className="text-[10px] font-mono text-slate-400">
                        conntrack: {m.conntrackCount ?? 0} / {m.conntrackMax}
                      </div>
                    )}
                  </div>
                </div>
              );
            })()}

            {/* Процессы k6 во время замера */}
            {inspectRow.profile?.metrics?.k6 && inspectRow.profile.metrics.k6.length > 0 && (
              <div className="rounded-xl border border-slate-200 p-3 space-y-1.5">
                <div className="font-semibold text-slate-700 flex items-center gap-1.5">
                  <Cpu className="h-3.5 w-3.5 text-indigo-600" />
                  Процессы k6 в окне измерения
                </div>
                <div className="flex flex-wrap gap-2">
                  {inspectRow.profile.metrics.k6.map((proc) => (
                    <span
                      key={proc.pid}
                      className="rounded-lg bg-slate-100 px-2.5 py-1 font-mono text-[11px] text-slate-700"
                    >
                      PID {proc.pid}: открыто FD <b>{proc.fds}</b> (ulimit {proc.soft} / {proc.hard})
                    </span>
                  ))}
                </div>
              </div>
            )}

            {/* Находки анализатора */}
            {Array.isArray(inspectRow.profile?.findings) && inspectRow.profile!.findings!.length > 0 && (
              <div className="space-y-2">
                <div className="font-semibold uppercase tracking-wide text-slate-500">
                  Рекомендации и узкие места
                </div>
                {inspectRow.profile!.findings!.map((f, i) => (
                  <div key={i} className="rounded-xl border border-slate-200 p-3 space-y-1">
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

            {/* Снимок sysctl хоста */}
            {inspectRow.profile?.metrics?.sysctl &&
              Object.keys(inspectRow.profile.metrics.sysctl).length > 0 && (
                <details className="rounded-xl border border-slate-200 overflow-hidden">
                  <summary className="px-3 py-2 bg-slate-50 font-semibold text-slate-700 cursor-pointer">
                    Текущие значения sysctl на хосте ({Object.keys(inspectRow.profile.metrics.sysctl).length})
                  </summary>
                  <div className="max-h-56 overflow-y-auto divide-y divide-slate-100">
                    {Object.entries(inspectRow.profile.metrics.sysctl).map(([k, v]) => (
                      <div key={k} className="px-3 py-1.5 flex items-center justify-between font-mono text-[11px]">
                        <span className="text-slate-700">{k}</span>
                        <span className="text-slate-900 font-semibold">{v}</span>
                      </div>
                    ))}
                  </div>
                </details>
              )}

            {inspectRow.tuning?.changes && (
              <div>
                <div className="font-semibold uppercase tracking-wide text-slate-500 mb-1.5">
                  Применённый тюнинг (с исходными значениями для отката)
                </div>
                <pre className="rounded-xl bg-slate-950 text-slate-100 p-3 font-mono text-xs overflow-x-auto">
                  {JSON.stringify(inspectRow.tuning.changes, null, 2)}
                </pre>
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Конструктор / редактор профиля оптимизации ОС */}
      <Modal
        open={optEditorOpen}
        onClose={() => setOptEditorOpen(false)}
        title={
          editingOptId
            ? `Редактирование профиля оптимизации #${editingOptId}`
            : 'Новый профиль оптимизации ОС'
        }
        maxWidth="max-w-3xl"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setOptEditorOpen(false)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={!profName.trim() || profSettingsRows.length === 0}
              loading={saveOptMut.isPending}
              onClick={() => saveOptMut.mutate()}
            >
              {editingOptId ? 'Сохранить изменения' : 'Создать профиль'}
            </Button>
          </>
        }
      >
        <div className="space-y-3.5 text-xs">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className="block font-medium text-slate-700 mb-1">Название профиля *</label>
              <Input
                value={profName}
                onChange={(e) => setProfName(e.target.value)}
                placeholder="High-RPS 10Gbps Linux"
              />
            </div>
            <div>
              <label className="block font-medium text-slate-700 mb-1">Описание</label>
              <Input
                value={profDesc}
                onChange={(e) => setProfDesc(e.target.value)}
                placeholder="Оптимальные буферы TCP и порты для генераторов"
              />
            </div>
          </div>

          {!editingOptId && profiledRows.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
              <span className="text-slate-600">Заполнить из рекомендаций запрофилированного хоста:</span>
              <Select
                value=""
                onChange={(e) => {
                  const hr = profiledRows.find((x) => String(x.id) === e.target.value);
                  if (!hr) return;
                  const recs = (hr.profile?.findings || [])
                    .filter((f) => f.key && f.recommended)
                    .map((f) => ({ k: f.key!, v: f.recommended! }));
                  if (recs.length > 0) {
                    setProfSettingsRows(recs);
                    toast.info(`Загружено рекомендаций: ${recs.length}`);
                  } else {
                    toast.info('На этом хосте нет рекомендаций sysctl');
                  }
                }}
                className="h-7 text-xs w-56 bg-white"
              >
                <option value="">— выбрать хост —</option>
                {profiledRows.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} ({r.host})
                  </option>
                ))}
              </Select>
            </div>
          )}

          {/* Таблица редактируемых параметров sysctl / sys.* */}
          <div className="space-y-2 max-h-72 overflow-y-auto rounded-xl border border-slate-200 p-2.5 bg-slate-50/50">
            {profSettingsRows.map((row, idx) => (
              <div key={row.k} className="flex flex-wrap items-center gap-2 rounded-lg bg-white border border-slate-200 px-2.5 py-1.5">
                <div className="min-w-[210px] flex-1">
                  <div className="font-mono font-semibold text-slate-800">{row.k}</div>
                  {keyDesc[row.k] && (
                    <div className="text-[10px] text-slate-400">{keyDesc[row.k]}</div>
                  )}
                </div>

                {row.k === 'sys.thp' ? (
                  <Select
                    value={row.v}
                    onChange={(e) =>
                      setProfSettingsRows((prev) =>
                        prev.map((it, i) => (i === idx ? { ...it, v: e.target.value } : it))
                      )
                    }
                    className="h-7 w-40 font-mono text-xs"
                  >
                    <option value="never">never</option>
                    <option value="madvise">madvise</option>
                    <option value="always">always</option>
                  </Select>
                ) : row.k === 'sys.cpu_governor' ? (
                  <Select
                    value={row.v}
                    onChange={(e) =>
                      setProfSettingsRows((prev) =>
                        prev.map((it, i) => (i === idx ? { ...it, v: e.target.value } : it))
                      )
                    }
                    className="h-7 w-40 font-mono text-xs"
                  >
                    <option value="performance">performance</option>
                    <option value="schedutil">schedutil</option>
                    <option value="ondemand">ondemand</option>
                    <option value="powersave">powersave</option>
                  </Select>
                ) : (
                  <Input
                    value={row.v}
                    onChange={(e) =>
                      setProfSettingsRows((prev) =>
                        prev.map((it, i) => (i === idx ? { ...it, v: e.target.value } : it))
                      )
                    }
                    placeholder="Значение (например 65535)"
                    className="h-7 w-48 font-mono text-xs"
                  />
                )}

                <button
                  type="button"
                  onClick={() =>
                    setProfSettingsRows((prev) => prev.filter((_, i) => i !== idx))
                  }
                  className="text-slate-400 hover:text-rose-600 cursor-pointer"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            ))}
          </div>

          {/* Добавление разрешённого параметра из списка allKeys */}
          {freeOptKeys.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 pt-1">
              <Select
                value={newParamKey}
                onChange={(e) => {
                  const k = e.target.value;
                  setNewParamKey(k);
                  if (k === 'sys.thp') setNewParamVal('never');
                  else if (k === 'sys.cpu_governor') setNewParamVal('performance');
                  else if (k === 'net.ipv4.ip_local_port_range') setNewParamVal('10240 65535');
                  else if (k === 'net.ipv4.tcp_tw_reuse') setNewParamVal('1');
                  else setNewParamVal('');
                }}
                className="h-8 flex-1 text-xs font-mono"
              >
                <option value="">+ Добавить параметр ядра из белого списка…</option>
                {freeOptKeys.map((k) => (
                  <option key={k} value={k}>
                    {k} {keyDesc[k] ? `— ${keyDesc[k]}` : ''}
                  </option>
                ))}
              </Select>
              {newParamKey && (
                <>
                  <Input
                    value={newParamVal}
                    onChange={(e) => setNewParamVal(e.target.value)}
                    placeholder="Значение"
                    className="h-8 w-40 font-mono text-xs"
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      if (!newParamKey || !newParamVal.trim()) return;
                      setProfSettingsRows((prev) => [
                        ...prev,
                        { k: newParamKey, v: newParamVal.trim() },
                      ]);
                      setNewParamKey('');
                      setNewParamVal('');
                    }}
                  >
                    <Plus className="h-3.5 w-3.5" />
                    Добавить
                  </Button>
                </>
              )}
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}
