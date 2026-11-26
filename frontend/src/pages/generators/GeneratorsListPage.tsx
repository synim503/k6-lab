import React, { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Activity,
  AlertTriangle,
  ArrowUpDown,
  Cpu,
  HardDrive,
  Network,
  Plus,
  RefreshCw,
  Search,
  Server,
  Sliders,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { fmtShortDate, genStatusMeta } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Modal,
  Select,
  Spinner,
  Switch,
  Textarea,
} from '@/components/ui/primitives';

interface GeneratorItem {
  id: number;
  name: string;
  host: string;
  sshPort: number;
  status: string;
  statusLabel: string;
  maintenance: boolean;
  busy: boolean;
  connected: boolean;
  availableForRuns: boolean;
  cpu?: number | null;
  memPct?: number | null;
  netRx?: string | null;
  netTx?: string | null;
  load1?: number | null;
  diskFreeGb?: number | null;
  sockets?: number | null;
  cpuHot: boolean;
  clockOffsetMs?: number | null;
  clockWarn: boolean;
  clockBad: boolean;
  agentVersion?: string | null;
  k6Version?: string | null;
  agentDrift: boolean;
  k6Drift: boolean;
  hasDrift: boolean;
  defaultAgent?: string | null;
  defaultK6?: string | null;
  activeRuns: number;
  maxConcurrentRuns: number;
  lastHeartbeatAt?: string | null;
}

interface SshCredOption {
  id: number;
  name: string;
  username: string;
  authType: string;
}

interface DistOption {
  id: number;
  version: string;
  arch: string;
  label?: string;
  isDefault: boolean;
}

interface GeneratorsListResponse {
  items: GeneratorItem[];
  outdatedCount: number;
  defaultServerUrl: string;
  sshCredentials: SshCredOption[];
  agents: DistOption[];
  k6s: DistOption[];
}

type SortKey = 'name' | 'status' | 'cpu' | 'mem' | 'load' | 'disk';

export function GeneratorsListPage() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ONLINE' | 'BUSY' | 'OFFLINE' | 'ERROR' | 'MAINT'>('ALL');
  const [onlyDrift, setOnlyDrift] = useState(false);
  const [sortBy, setSortBy] = useState<SortKey>('name');
  const [sortAsc, setSortAsc] = useState(true);

  const [addOpen, setAddOpen] = useState(false);
  const [addTab, setAddTab] = useState<'quick' | 'bulk' | 'advanced'>('quick');

  // Quick Add state
  const [qName, setQName] = useState('');
  const [qHost, setQHost] = useState('');
  const [qPort, setQPort] = useState('22');
  const [qUser, setQUser] = useState('root');
  const [qAuthType, setQAuthType] = useState<'PASSWORD' | 'KEY'>('PASSWORD');
  const [qPassword, setQPassword] = useState('');
  const [qKey, setQKey] = useState('');
  const [qPassphrase, setQPassphrase] = useState('');
  const [qSudo, setQSudo] = useState('');
  const [qServerUrl, setQServerUrl] = useState('');
  const [qTuneOs, setQTuneOs] = useState(true);

  // Bulk Add state
  const [bHosts, setBHosts] = useState('');
  const [bCredId, setBCredId] = useState('');
  const [bMode, setBMode] = useState<'SYSTEM' | 'USER'>('SYSTEM');
  const [bServerUrl, setBServerUrl] = useState('');
  const [bTuneOs, setBTuneOs] = useState(true);

  // Advanced Add state
  const [aName, setAName] = useState('');
  const [aHost, setAHost] = useState('');
  const [aPort, setAPort] = useState('22');
  const [aCredId, setACredId] = useState('');
  const [aMode, setAMode] = useState<'SYSTEM' | 'USER'>('SYSTEM');
  const [aDir, setADir] = useState('');
  const [aServerUrl, setAServerUrl] = useState('');
  const [aTuneOs, setATuneOs] = useState(true);
  const [aMaxRuns, setAMaxRuns] = useState('1');
  const [aAgentId, setAAgentId] = useState('');
  const [aK6Id, setAK6Id] = useState('');

  const { data, isLoading } = useQuery<GeneratorsListResponse>({
    queryKey: ['generators'],
    queryFn: () => apiFetch('/api/v1/generators'),
    refetchInterval: 5000,
  });

  const openAddModal = () => {
    const defUrl = data?.defaultServerUrl || '';
    const firstCred = data?.sshCredentials?.[0]?.id ? String(data.sshCredentials[0].id) : '';
    if (!qServerUrl) setQServerUrl(defUrl);
    if (!bServerUrl) setBServerUrl(defUrl);
    if (!aServerUrl) setAServerUrl(defUrl);
    if (!bCredId && firstCred) setBCredId(firstCred);
    if (!aCredId && firstCred) setACredId(firstCred);
    setAddOpen(true);
  };

  const updateAllMut = useMutation({
    mutationFn: (force: boolean) => apiFetch(`/api/v1/generators/update-all?force=${force}`, { method: 'POST' }),
    onSuccess: (res: { count: number }) => {
      toast.success(res.count > 0 ? `Запущено обновление на ${res.count} генераторах` : 'Нет свободных генераторов для обновления');
      qc.invalidateQueries({ queryKey: ['generators'] });
      if (res.count > 0) navigate('/deployments');
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const quickMut = useMutation({
    mutationFn: () =>
      apiFetch<{ deploymentId: number }>('/api/v1/generators/quick-add', {
        method: 'POST',
        body: {
          name: qName.trim() || undefined,
          host: qHost.trim(),
          port: Number(qPort) || 22,
          username: qUser.trim(),
          authType: qAuthType,
          password: qAuthType === 'PASSWORD' ? qPassword : '',
          privateKey: qAuthType === 'KEY' ? qKey : '',
          passphrase: qPassphrase || undefined,
          sudoPassword: qSudo || undefined,
          serverUrl: qServerUrl.trim() || undefined,
          tuneOs: qTuneOs,
        },
      }),
    onSuccess: (res) => {
      toast.success('Установка генератора запущена');
      setAddOpen(false);
      qc.invalidateQueries({ queryKey: ['generators'] });
      navigate(`/deployments/${res.deploymentId}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const bulkMut = useMutation({
    mutationFn: () =>
      apiFetch<{ deploymentIds: number[] }>('/api/v1/generators/bulk', {
        method: 'POST',
        body: {
          hosts: bHosts,
          credentialId: Number(bCredId),
          mode: bMode,
          serverUrl: bServerUrl.trim() || undefined,
          tuneOs: bTuneOs,
        },
      }),
    onSuccess: (res) => {
      toast.success(`Запущена установка ${res.deploymentIds.length} хостов`);
      setAddOpen(false);
      qc.invalidateQueries({ queryKey: ['generators'] });
      if (res.deploymentIds.length === 1) {
        navigate(`/deployments/${res.deploymentIds[0]}`);
      } else {
        navigate('/deployments');
      }
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const advMut = useMutation({
    mutationFn: () =>
      apiFetch<{ deploymentId: number }>('/api/v1/generators', {
        method: 'POST',
        body: {
          name: aName.trim() || aHost.trim(),
          host: aHost.trim(),
          sshPort: Number(aPort) || 22,
          credentialId: Number(aCredId),
          mode: aMode,
          dir: aDir.trim() || undefined,
          serverUrl: aServerUrl.trim() || undefined,
          tuneOs: aTuneOs,
          maxConcurrentRuns: Math.max(1, Number(aMaxRuns) || 1),
          agentArtifactId: aAgentId ? Number(aAgentId) : null,
          k6ArtifactId: aK6Id ? Number(aK6Id) : null,
        },
      }),
    onSuccess: (res) => {
      toast.success('Установка генератора запущена');
      setAddOpen(false);
      qc.invalidateQueries({ queryKey: ['generators'] });
      navigate(`/deployments/${res.deploymentId}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const toggleSort = (key: SortKey) => {
    if (sortBy === key) {
      setSortAsc(!sortAsc);
    } else {
      setSortBy(key);
      setSortAsc(key === 'name');
    }
  };

  const items = data?.items || [];

  const counts = useMemo(() => {
    return {
      total: items.length,
      online: items.filter((g) => g.status === 'ONLINE' && !g.maintenance && !g.busy).length,
      busy: items.filter((g) => g.busy).length,
      offline: items.filter((g) => g.status === 'OFFLINE').length,
      error: items.filter((g) => g.status === 'ERROR').length,
      maint: items.filter((g) => g.maintenance).length,
    };
  }, [items]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = items.filter((g) => {
      if (onlyDrift && !g.hasDrift) return false;
      if (statusFilter === 'ONLINE' && !(g.status === 'ONLINE' && !g.maintenance && !g.busy)) return false;
      if (statusFilter === 'BUSY' && !g.busy) return false;
      if (statusFilter === 'OFFLINE' && g.status !== 'OFFLINE') return false;
      if (statusFilter === 'ERROR' && g.status !== 'ERROR') return false;
      if (statusFilter === 'MAINT' && !g.maintenance) return false;
      if (q) {
        const hay = `${g.name} ${g.host} ${g.agentVersion || ''} ${g.k6Version || ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });

    list.sort((a, b) => {
      let cmp = 0;
      switch (sortBy) {
        case 'name':
          cmp = a.name.localeCompare(b.name, 'ru');
          break;
        case 'status':
          cmp = a.status.localeCompare(b.status);
          break;
        case 'cpu':
          cmp = (a.cpu ?? -1) - (b.cpu ?? -1);
          break;
        case 'mem':
          cmp = (a.memPct ?? -1) - (b.memPct ?? -1);
          break;
        case 'load':
          cmp = (a.load1 ?? -1) - (b.load1 ?? -1);
          break;
        case 'disk':
          cmp = (a.diskFreeGb ?? -1) - (b.diskFreeGb ?? -1);
          break;
      }
      return sortAsc ? cmp : -cmp;
    });
    return list;
  }, [items, search, statusFilter, onlyDrift, sortBy, sortAsc]);

  if (isLoading && !data) return <Spinner label="Загрузка генераторов…" />;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Генераторы нагрузки</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Серверы с агентом k6, телеметрия ресурсов в реальном времени и управление версиями
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {isAdmin && (
            <>
              <Link to="/tuning">
                <Button variant="outline" size="sm">
                  <Sliders className="h-3.5 w-3.5" />
                  Тюнинг ОС
                </Button>
              </Link>
              <Link to="/deployments">
                <Button variant="outline" size="sm">
                  <Activity className="h-3.5 w-3.5" />
                  Журнал операций
                </Button>
              </Link>
              {(data?.outdatedCount ?? 0) > 0 ? (
                <Button
                  variant="warning"
                  size="sm"
                  loading={updateAllMut.isPending}
                  onClick={() => updateAllMut.mutate(false)}
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  Обновить устаревшие ({data?.outdatedCount})
                </Button>
              ) : (
                items.length > 0 && (
                  <Button
                    variant="outline"
                    size="sm"
                    loading={updateAllMut.isPending}
                    onClick={() => updateAllMut.mutate(true)}
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    Обновить агент на всех
                  </Button>
                )
              )}
              <Button variant="primary" size="sm" onClick={openAddModal}>
                <Plus className="h-4 w-4" />
                Добавить сервер
              </Button>
            </>
          )}
        </div>
      </div>

      {/* Панель фильтров */}
      <Card className="p-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            {([
              ['ALL', `Все (${counts.total})`],
              ['ONLINE', `Свободны (${counts.online})`],
              ['BUSY', `Заняты (${counts.busy})`],
              ['OFFLINE', `Не в сети (${counts.offline})`],
              ['ERROR', `Ошибки (${counts.error})`],
              ['MAINT', `Обслуживание (${counts.maint})`],
            ] as const).map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setStatusFilter(key)}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors cursor-pointer ${
                  statusFilter === key
                    ? 'bg-indigo-600 text-white'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}
              >
                {label}
              </button>
            ))}
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Switch
              checked={onlyDrift}
              onChange={setOnlyDrift}
              label={<span className="text-xs">Только устаревшие ({data?.outdatedCount ?? 0})</span>}
            />
            <div className="relative w-60">
              <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Поиск по имени или IP…"
                className="pl-8 h-8 text-xs"
              />
            </div>
          </div>
        </div>
      </Card>

      {/* Таблица генераторов */}
      <Card className="overflow-hidden">
        {filtered.length === 0 ? (
          <EmptyState
            icon={<Server className="h-6 w-6" />}
            title={items.length === 0 ? 'Нет подключённых генераторов' : 'Ничего не найдено по фильтру'}
            description={
              items.length === 0
                ? 'Добавьте Linux-сервер по SSH — админка автоматически установит агент и k6.'
                : 'Попробуйте сбросить фильтры поиска.'
            }
            action={
              items.length === 0 && isAdmin ? (
                <Button size="sm" onClick={openAddModal}>
                  <Plus className="h-4 w-4" />
                  Добавить первый сервер
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">
                    <button type="button" onClick={() => toggleSort('name')} className="inline-flex items-center gap-1 hover:text-slate-800 cursor-pointer">
                      Сервер <ArrowUpDown className="h-3 w-3" />
                    </button>
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">
                    <button type="button" onClick={() => toggleSort('status')} className="inline-flex items-center gap-1 hover:text-slate-800 cursor-pointer">
                      Статус <ArrowUpDown className="h-3 w-3" />
                    </button>
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">
                    <button type="button" onClick={() => toggleSort('cpu')} className="inline-flex items-center gap-1 hover:text-slate-800 cursor-pointer">
                      CPU <ArrowUpDown className="h-3 w-3" />
                    </button>
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">
                    <button type="button" onClick={() => toggleSort('mem')} className="inline-flex items-center gap-1 hover:text-slate-800 cursor-pointer">
                      RAM <ArrowUpDown className="h-3 w-3" />
                    </button>
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">Сеть RX / TX</th>
                  <th className="px-3 py-2.5 text-left font-medium">
                    <button type="button" onClick={() => toggleSort('load')} className="inline-flex items-center gap-1 hover:text-slate-800 cursor-pointer">
                      Load / Сокеты <ArrowUpDown className="h-3 w-3" />
                    </button>
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">
                    <button type="button" onClick={() => toggleSort('disk')} className="inline-flex items-center gap-1 hover:text-slate-800 cursor-pointer">
                      Диск <ArrowUpDown className="h-3 w-3" />
                    </button>
                  </th>
                  <th className="px-3 py-2.5 text-left font-medium">Агент / k6</th>
                  <th className="px-4 py-2.5 text-right font-medium">Слоты</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filtered.map((g) => {
                  const st = genStatusMeta(g.status, g.maintenance, g.busy);
                  const cpu = g.cpu != null ? Math.round(g.cpu) : null;
                  const mem = g.memPct != null ? Math.round(g.memPct) : null;
                  return (
                    <tr key={g.id} className="hover:bg-slate-50/70 transition-colors">
                      <td className="px-4 py-3">
                        <Link to={`/generators/${g.id}`} className="font-semibold text-slate-900 hover:text-indigo-600">
                          {g.name}
                        </Link>
                        <div className="text-xs text-slate-500 font-mono">
                          {g.host}:{g.sshPort}
                        </div>
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex flex-wrap items-center gap-1">
                          <Badge variant={st.variant}>{st.label}</Badge>
                          {g.clockBad && (
                            <Badge variant="danger" className="text-[10px]">
                              Рассинхрон {g.clockOffsetMs} мс
                            </Badge>
                          )}
                          {!g.clockBad && g.clockWarn && (
                            <Badge variant="warning" className="text-[10px]">
                              Часы {g.clockOffsetMs} мс
                            </Badge>
                          )}
                        </div>
                        {g.lastHeartbeatAt && (
                          <div className="text-[11px] text-slate-400 mt-0.5">{fmtShortDate(g.lastHeartbeatAt)}</div>
                        )}
                      </td>
                      <td className="px-3 py-3 w-32">
                        {cpu != null ? (
                          <div>
                            <div className="flex items-center justify-between text-xs mb-1">
                              <span className={g.cpuHot ? 'font-semibold text-rose-600' : 'text-slate-700'}>{cpu}%</span>
                              {g.cpuHot && <AlertTriangle className="h-3 w-3 text-rose-500" />}
                            </div>
                            <div className="h-1.5 rounded-full bg-slate-100 overflow-hidden">
                              <div
                                className={`h-full rounded-full ${
                                  cpu >= 85 ? 'bg-rose-500' : cpu >= 65 ? 'bg-amber-500' : 'bg-emerald-500'
                                }`}
                                style={{ width: `${Math.min(100, cpu)}%` }}
                              />
                            </div>
                          </div>
                        ) : (
                          <span className="text-xs text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-3 w-28">
                        {mem != null ? (
                          <div>
                            <div className="text-xs text-slate-700 mb-1">{mem}%</div>
                            <div className="h-1.5 rounded-full bg-slate-100 overflow-hidden">
                              <div
                                className={`h-full rounded-full ${
                                  mem >= 88 ? 'bg-rose-500' : mem >= 70 ? 'bg-amber-500' : 'bg-indigo-500'
                                }`}
                                style={{ width: `${Math.min(100, mem)}%` }}
                              />
                            </div>
                          </div>
                        ) : (
                          <span className="text-xs text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-xs font-mono text-slate-600">
                        {g.netRx || g.netTx ? (
                          <div className="space-y-0.5">
                            <div className="flex items-center gap-1">
                              <Network className="h-3 w-3 text-emerald-500" /> {g.netRx || '0'}
                            </div>
                            <div className="text-slate-400">↑ {g.netTx || '0'}</div>
                          </div>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-xs text-slate-700">
                        {g.load1 != null ? (
                          <div>
                            <span className="font-mono">{g.load1.toFixed(2)}</span>
                            {g.sockets != null && (
                              <div className="text-[11px] text-slate-400">сокетов: {g.sockets}</div>
                            )}
                          </div>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-xs text-slate-700">
                        {g.diskFreeGb != null ? (
                          <span className="inline-flex items-center gap-1 font-mono">
                            <HardDrive className="h-3 w-3 text-slate-400" />
                            {g.diskFreeGb.toFixed(1)} ГБ
                          </span>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-xs">
                        <div className="flex items-center gap-1">
                          <span className="font-mono text-slate-700">{g.agentVersion || '—'}</span>
                          {g.agentDrift && <Badge variant="warning">устар.</Badge>}
                        </div>
                        <div className="flex items-center gap-1 text-slate-500 mt-0.5">
                          <Cpu className="h-3 w-3" />
                          <span className="font-mono">k6 {g.k6Version || '—'}</span>
                          {g.k6Drift && <Badge variant="warning">устар.</Badge>}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-right text-xs font-mono text-slate-700">
                        {g.activeRuns} / {g.maxConcurrentRuns}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Модальное окно добавления генератора */}
      <Modal
        open={addOpen}
        onClose={() => setAddOpen(false)}
        title="Добавить генератор нагрузки"
        maxWidth="max-w-2xl"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setAddOpen(false)}>
              Отмена
            </Button>
            {addTab === 'quick' && (
              <Button
                size="sm"
                loading={quickMut.isPending}
                disabled={!qHost.trim() || !qUser.trim()}
                onClick={() => quickMut.mutate()}
              >
                Установить по SSH
              </Button>
            )}
            {addTab === 'bulk' && (
              <Button
                size="sm"
                loading={bulkMut.isPending}
                disabled={!bHosts.trim() || !bCredId}
                onClick={() => bulkMut.mutate()}
              >
                Запустить массовую установку
              </Button>
            )}
            {addTab === 'advanced' && (
              <Button
                size="sm"
                loading={advMut.isPending}
                disabled={!aHost.trim() || !aCredId}
                onClick={() => advMut.mutate()}
              >
                Установить генератор
              </Button>
            )}
          </>
        }
      >
        <div className="space-y-4">
          <div className="flex gap-1 rounded-lg bg-slate-100 p-1">
            {([
              ['quick', 'Быстрое добавление'],
              ['bulk', 'Массовое добавление'],
              ['advanced', 'Расширенная форма'],
            ] as const).map(([id, title]) => (
              <button
                key={id}
                type="button"
                onClick={() => setAddTab(id)}
                className={`flex-1 rounded-md py-1.5 text-xs font-medium transition-colors cursor-pointer ${
                  addTab === id ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                {title}
              </button>
            ))}
          </div>

          {addTab === 'quick' && (
            <div className="space-y-3">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div className="sm:col-span-2">
                  <label className="block text-xs font-medium text-slate-700 mb-1">IP или DNS хоста *</label>
                  <Input value={qHost} onChange={(e) => setQHost(e.target.value)} placeholder="10.0.1.25" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">SSH-порт</label>
                  <Input type="number" value={qPort} onChange={(e) => setQPort(e.target.value)} />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Имя в админке (необязательно)</label>
                  <Input value={qName} onChange={(e) => setQName(e.target.value)} placeholder="По умолчанию = IP" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Пользователь SSH *</label>
                  <Input value={qUser} onChange={(e) => setQUser(e.target.value)} placeholder="root / ubuntu" />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Способ входа</label>
                  <Select value={qAuthType} onChange={(e) => setQAuthType(e.target.value as 'PASSWORD' | 'KEY')}>
                    <option value="PASSWORD">Пароль</option>
                    <option value="KEY">Приватный SSH-ключ</option>
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Пароль sudo (если не root)</label>
                  <Input type="password" value={qSudo} onChange={(e) => setQSudo(e.target.value)} placeholder="Необязательно" />
                </div>
              </div>
              {qAuthType === 'PASSWORD' ? (
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Пароль SSH</label>
                  <Input type="password" value={qPassword} onChange={(e) => setQPassword(e.target.value)} />
                </div>
              ) : (
                <div className="space-y-2">
                  <div>
                    <label className="block text-xs font-medium text-slate-700 mb-1">Приватный ключ (PEM / OpenSSH)</label>
                    <Textarea rows={4} className="font-mono text-xs" value={qKey} onChange={(e) => setQKey(e.target.value)} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-700 mb-1">Passphrase ключа</label>
                    <Input type="password" value={qPassphrase} onChange={(e) => setQPassphrase(e.target.value)} placeholder="Если ключ защищён паролем" />
                  </div>
                </div>
              )}
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">WebSocket URL админки для агента</label>
                <Input value={qServerUrl} onChange={(e) => setQServerUrl(e.target.value)} className="font-mono text-xs" />
              </div>
              <Switch checked={qTuneOs} onChange={setQTuneOs} label="Применить базовый сетевой тюнинг Linux (ulimit / sysctl)" />
            </div>
          )}

          {addTab === 'bulk' && (
            <div className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Список хостов (по одному на строку: `host` или `host:port`) *
                </label>
                <Textarea
                  rows={6}
                  className="font-mono text-xs"
                  value={bHosts}
                  onChange={(e) => setBHosts(e.target.value)}
                  placeholder={'10.0.1.11\n10.0.1.12:2222\n# комментарий'}
                />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">SSH-учётка *</label>
                  <Select value={bCredId} onChange={(e) => setBCredId(e.target.value)}>
                    <option value="">— Выберите учётку —</option>
                    {data?.sshCredentials.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name} ({c.username}, {c.authType})
                      </option>
                    ))}
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Режим установки</label>
                  <Select value={bMode} onChange={(e) => setBMode(e.target.value as 'SYSTEM' | 'USER')}>
                    <option value="SYSTEM">SYSTEM (systemd, root/sudo)</option>
                    <option value="USER">USER (без sudo, в домашний каталог)</option>
                  </Select>
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">WebSocket URL админки</label>
                <Input value={bServerUrl} onChange={(e) => setBServerUrl(e.target.value)} className="font-mono text-xs" />
              </div>
              {bMode === 'SYSTEM' && (
                <Switch checked={bTuneOs} onChange={setBTuneOs} label="Применить базовый сетевой тюнинг Linux" />
              )}
            </div>
          )}

          {addTab === 'advanced' && (
            <div className="space-y-3">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Имя генератора</label>
                  <Input value={aName} onChange={(e) => setAName(e.target.value)} placeholder="gen-01" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Хост *</label>
                  <Input value={aHost} onChange={(e) => setAHost(e.target.value)} placeholder="10.0.1.15" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">SSH-порт</label>
                  <Input type="number" value={aPort} onChange={(e) => setAPort(e.target.value)} />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">SSH-учётка *</label>
                  <Select value={aCredId} onChange={(e) => setACredId(e.target.value)}>
                    <option value="">— Выберите учётку —</option>
                    {data?.sshCredentials.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name} ({c.username}, {c.authType})
                      </option>
                    ))}
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Режим установки</label>
                  <Select value={aMode} onChange={(e) => setAMode(e.target.value as 'SYSTEM' | 'USER')}>
                    <option value="SYSTEM">SYSTEM (systemd)</option>
                    <option value="USER">USER (без root)</option>
                  </Select>
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Каталог установки (опц.)</label>
                  <Input value={aDir} onChange={(e) => setADir(e.target.value)} placeholder="/opt/k6-agent" className="font-mono text-xs" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Макс. параллельных запусков</label>
                  <Input type="number" min={1} max={16} value={aMaxRuns} onChange={(e) => setAMaxRuns(e.target.value)} />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Дистрибутив агента</label>
                  <Select value={aAgentId} onChange={(e) => setAAgentId(e.target.value)}>
                    <option value="">По умолчанию для архитектуры</option>
                    {data?.agents.map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.version} ({d.arch}) {d.isDefault ? '★' : ''}
                      </option>
                    ))}
                  </Select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Дистрибутив k6</label>
                  <Select value={aK6Id} onChange={(e) => setAK6Id(e.target.value)}>
                    <option value="">По умолчанию для архитектуры</option>
                    {data?.k6s.map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.version} ({d.arch}) {d.isDefault ? '★' : ''}
                      </option>
                    ))}
                  </Select>
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">WebSocket URL админки</label>
                <Input value={aServerUrl} onChange={(e) => setAServerUrl(e.target.value)} className="font-mono text-xs" />
              </div>
              {aMode === 'SYSTEM' && (
                <Switch checked={aTuneOs} onChange={setATuneOs} label="Применить базовый сетевой тюнинг Linux" />
              )}
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}
