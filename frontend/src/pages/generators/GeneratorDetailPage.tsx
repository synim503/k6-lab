import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  ArrowLeft,
  Clock,
  Cpu,
  FileText,
  HardDrive,
  Network,
  RefreshCw,
  RotateCcw,
  Server,
  Trash2,
  Wrench,
} from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { deployOpLabel, deployStatusMeta, fmtDateTime, genStatusMeta } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import { useConfirm } from '@/components/ui/ConfirmContext';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  Input,
  Modal,
  Select,
  Spinner,
  Switch,
} from '@/components/ui/primitives';

interface GeneratorDetailDto {
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
  installMode: string;
  installDir?: string | null;
  supervisor?: string | null;
  osName?: string | null;
  arch?: string | null;
  serverUrl?: string | null;
}

interface DeploymentItem {
  id: number;
  operation: string;
  status: string;
  error?: string | null;
  startedBy?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

interface DistOption {
  id: number;
  version: string;
  arch: string;
  isDefault: boolean;
}

interface GeneratorDetailResponse {
  generator: GeneratorDetailDto;
  history: DeploymentItem[];
  agents: DistOption[];
  k6s: DistOption[];
}

export function GeneratorDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { isAdmin } = useAuth();
  const confirm = useConfirm();

  const [maxRuns, setMaxRuns] = useState('1');
  const [agentArtifactId, setAgentArtifactId] = useState('');
  const [k6ArtifactId, setK6ArtifactId] = useState('');
  const [logOpen, setLogOpen] = useState(false);
  const [logLines, setLogLines] = useState(200);

  const { data, isLoading } = useQuery<GeneratorDetailResponse>({
    queryKey: ['generator', id],
    queryFn: () => apiFetch(`/api/v1/generators/${id}`),
    refetchInterval: 5000,
    enabled: !!id,
  });

  useEffect(() => {
    if (data?.generator) {
      setMaxRuns(String(data.generator.maxConcurrentRuns || 1));
    }
  }, [data?.generator?.maxConcurrentRuns]);

  const logQuery = useQuery<{ log: string }>({
    queryKey: ['generator-log', id, logLines],
    queryFn: () => apiFetch(`/api/v1/generators/${id}/agent-log?lines=${logLines}`),
    enabled: logOpen && !!id && isAdmin,
  });

  const maintMut = useMutation({
    mutationFn: (on: boolean) =>
      apiFetch(`/api/v1/generators/${id}/maintenance`, { method: 'POST', body: { on } }),
    onSuccess: () => {
      toast.success('Режим обслуживания обновлён');
      qc.invalidateQueries({ queryKey: ['generator', id] });
      qc.invalidateQueries({ queryKey: ['generators'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const settingsMut = useMutation({
    mutationFn: () =>
      apiFetch(`/api/v1/generators/${id}/settings`, {
        method: 'POST',
        body: { maxConcurrentRuns: Math.max(1, Number(maxRuns) || 1) },
      }),
    onSuccess: () => {
      toast.success('Настройки сохранены');
      qc.invalidateQueries({ queryKey: ['generator', id] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const restartMut = useMutation({
    mutationFn: () => apiFetch<{ deploymentId: number }>(`/api/v1/generators/${id}/restart`, { method: 'POST' }),
    onSuccess: (res) => {
      toast.success('Перезапуск агента запущен');
      navigate(`/deployments/${res.deploymentId}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const updateMut = useMutation({
    mutationFn: () =>
      apiFetch<{ deploymentId: number }>(`/api/v1/generators/${id}/update`, {
        method: 'POST',
        body: {
          agentArtifactId: agentArtifactId ? Number(agentArtifactId) : null,
          k6ArtifactId: k6ArtifactId ? Number(k6ArtifactId) : null,
        },
      }),
    onSuccess: (res) => {
      toast.success('Обновление агента / k6 запущено');
      navigate(`/deployments/${res.deploymentId}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const redeployMut = useMutation({
    mutationFn: (keepMode: boolean) =>
      apiFetch<{ deploymentId: number }>(`/api/v1/generators/${id}/redeploy?keepMode=${keepMode}`, {
        method: 'POST',
      }),
    onSuccess: (res) => {
      toast.success('Операция переустановки запущена');
      navigate(`/deployments/${res.deploymentId}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const uninstallMut = useMutation({
    mutationFn: () => apiFetch<{ deploymentId: number }>(`/api/v1/generators/${id}/uninstall`, { method: 'POST' }),
    onSuccess: (res) => {
      toast.success('Удаление агента запущено');
      navigate(`/deployments/${res.deploymentId}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (isLoading || !data) return <Spinner label="Загрузка генератора…" />;

  const g = data.generator;
  const st = genStatusMeta(g.status, g.maintenance, g.busy);

  const handleHardRedeploy = async () => {
    const ok = await confirm({
      title: 'Полная переустановка генератора',
      message: `Агент и k6 на сервере «${g.name}» (${g.host}) будут полностью удалены и установлены заново.`,
      confirmText: 'Переустановить',
      variant: 'warning',
    });
    if (ok) redeployMut.mutate(false);
  };

  const handleUninstall = async () => {
    const ok = await confirm({
      title: 'Удалить генератор',
      message: `Служба агента и файлы k6 будут удалены с хоста «${g.host}», а генератор удалён из базы.`,
      confirmText: 'Удалить с сервера',
      variant: 'danger',
    });
    if (ok) uninstallMut.mutate();
  };

  return (
    <div className="space-y-5">
      {/* Шапка */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Link to="/generators">
            <Button variant="outline" size="icon">
              <ArrowLeft className="h-4 w-4" />
            </Button>
          </Link>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-bold text-slate-900">{g.name}</h1>
              <Badge variant={st.variant}>{st.label}</Badge>
              {g.hasDrift && <Badge variant="warning">Доступно обновление</Badge>}
            </div>
            <div className="text-xs text-slate-500 font-mono mt-0.5">
              {g.host}:{g.sshPort} · {g.osName || 'Linux'} ({g.arch || 'amd64'}) · режим {g.installMode}
            </div>
          </div>
        </div>

        {isAdmin && (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setLogOpen(true)}
            >
              <FileText className="h-3.5 w-3.5" />
              Лог агента
            </Button>
            {g.status === 'ERROR' && (
              <Button
                variant="primary"
                size="sm"
                loading={redeployMut.isPending}
                onClick={() => redeployMut.mutate(true)}
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Повторить установку
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              loading={restartMut.isPending}
              disabled={g.busy}
              onClick={() => restartMut.mutate()}
            >
              <RefreshCw className="h-3.5 w-3.5" />
              Перезапустить
            </Button>
            <Button
              variant="outline"
              size="sm"
              loading={redeployMut.isPending}
              disabled={g.busy}
              onClick={handleHardRedeploy}
            >
              <Wrench className="h-3.5 w-3.5" />
              Редеплой
            </Button>
            <Button
              variant="danger"
              size="sm"
              loading={uninstallMut.isPending}
              disabled={g.busy}
              onClick={handleUninstall}
            >
              <Trash2 className="h-3.5 w-3.5" />
              Удалить
            </Button>
          </div>
        )}
      </div>

      {/* Метрики ресурсов */}
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Card className="p-3.5">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>CPU</span>
            <Cpu className="h-3.5 w-3.5 text-slate-400" />
          </div>
          <div className={`text-xl font-bold mt-1 ${g.cpuHot ? 'text-rose-600' : 'text-slate-900'}`}>
            {g.cpu != null ? `${Math.round(g.cpu)}%` : '—'}
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5">Load1: {g.load1 != null ? g.load1.toFixed(2) : '—'}</div>
        </Card>

        <Card className="p-3.5">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Память RAM</span>
            <Server className="h-3.5 w-3.5 text-slate-400" />
          </div>
          <div className="text-xl font-bold text-slate-900 mt-1">
            {g.memPct != null ? `${Math.round(g.memPct)}%` : '—'}
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5">Сокетов: {g.sockets ?? '—'}</div>
        </Card>

        <Card className="p-3.5">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Сеть</span>
            <Network className="h-3.5 w-3.5 text-slate-400" />
          </div>
          <div className="text-sm font-semibold font-mono text-slate-900 mt-1.5">↓ {g.netRx || '—'}</div>
          <div className="text-xs font-mono text-slate-500">↑ {g.netTx || '—'}</div>
        </Card>

        <Card className="p-3.5">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Свободно на диске</span>
            <HardDrive className="h-3.5 w-3.5 text-slate-400" />
          </div>
          <div className="text-xl font-bold text-slate-900 mt-1">
            {g.diskFreeGb != null ? `${g.diskFreeGb.toFixed(1)} ГБ` : '—'}
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5 truncate">{g.installDir || '/opt/k6-agent'}</div>
        </Card>

        <Card className="p-3.5">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Смещение часов</span>
            <Clock className="h-3.5 w-3.5 text-slate-400" />
          </div>
          <div
            className={`text-xl font-bold mt-1 ${
              g.clockBad ? 'text-rose-600' : g.clockWarn ? 'text-amber-600' : 'text-slate-900'
            }`}
          >
            {g.clockOffsetMs != null ? `${g.clockOffsetMs} мс` : '—'}
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5">HB: {fmtDateTime(g.lastHeartbeatAt)}</div>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* Настройки и режим обслуживания */}
        <Card>
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">Параметры генератора</div>
          </CardHeader>
          <CardBody className="space-y-4">
            <div className="grid grid-cols-2 gap-3 text-xs">
              <div>
                <span className="text-slate-500">Агент:</span>{' '}
                <span className="font-mono font-medium text-slate-900">{g.agentVersion || '—'}</span>
                {g.agentDrift && <span className="text-amber-600 ml-1">(эталон {g.defaultAgent})</span>}
              </div>
              <div>
                <span className="text-slate-500">k6:</span>{' '}
                <span className="font-mono font-medium text-slate-900">{g.k6Version || '—'}</span>
                {g.k6Drift && <span className="text-amber-600 ml-1">(эталон {g.defaultK6})</span>}
              </div>
              <div>
                <span className="text-slate-500">Супервизор:</span>{' '}
                <span className="font-mono text-slate-800">{g.supervisor || '—'}</span>
              </div>
              <div>
                <span className="text-slate-500">Активных запусков:</span>{' '}
                <span className="font-mono text-slate-800">
                  {g.activeRuns} / {g.maxConcurrentRuns}
                </span>
              </div>
              <div className="col-span-2">
                <span className="text-slate-500">Адрес админки:</span>{' '}
                <span className="font-mono text-slate-800">{g.serverUrl || '—'}</span>
              </div>
            </div>

            {isAdmin && (
              <>
                <div className="border-t border-slate-100 pt-3 flex items-center justify-between">
                  <div>
                    <div className="text-sm font-medium text-slate-900">Режим обслуживания</div>
                    <div className="text-xs text-slate-500">Новые тесты не будут назначаться на этот сервер</div>
                  </div>
                  <Switch
                    checked={g.maintenance}
                    disabled={maintMut.isPending}
                    onChange={(next) => maintMut.mutate(next)}
                  />
                </div>

                <div className="border-t border-slate-100 pt-3 flex items-end gap-3">
                  <div className="flex-1">
                    <label className="block text-xs font-medium text-slate-700 mb-1">
                      Макс. параллельных тестов на хосте
                    </label>
                    <Input
                      type="number"
                      min={1}
                      max={16}
                      value={maxRuns}
                      onChange={(e) => setMaxRuns(e.target.value)}
                    />
                  </div>
                  <Button size="sm" loading={settingsMut.isPending} onClick={() => settingsMut.mutate()}>
                    Сохранить
                  </Button>
                </div>
              </>
            )}
          </CardBody>
        </Card>

        {/* Обновление версий */}
        <Card>
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">Обновление агента и k6</div>
          </CardHeader>
          <CardBody className="space-y-4">
            <p className="text-xs text-slate-500">
              Выберите конкретные версии дистрибутивов или оставьте значения по умолчанию для архитектуры{' '}
              <code className="font-mono text-slate-700">{g.arch || 'amd64'}</code>.
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Версия агента</label>
                <Select
                  value={agentArtifactId}
                  onChange={(e) => setAgentArtifactId(e.target.value)}
                  disabled={!isAdmin}
                >
                  <option value="">По умолчанию ({g.defaultAgent || 'актуальная'})</option>
                  {data.agents.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.version} ({a.arch}) {a.isDefault ? '★' : ''}
                    </option>
                  ))}
                </Select>
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Версия k6</label>
                <Select
                  value={k6ArtifactId}
                  onChange={(e) => setK6ArtifactId(e.target.value)}
                  disabled={!isAdmin}
                >
                  <option value="">По умолчанию ({g.defaultK6 || 'актуальная'})</option>
                  {data.k6s.map((k) => (
                    <option key={k.id} value={k.id}>
                      {k.version} ({k.arch}) {k.isDefault ? '★' : ''}
                    </option>
                  ))}
                </Select>
              </div>
            </div>
            {isAdmin && (
              <div className="flex justify-end">
                <Button
                  size="sm"
                  loading={updateMut.isPending}
                  disabled={g.busy}
                  onClick={() => updateMut.mutate()}
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  Запустить обновление
                </Button>
              </div>
            )}
          </CardBody>
        </Card>
      </div>

      {/* История операций */}
      <Card>
        <CardHeader>
          <div className="font-semibold text-sm text-slate-900">Последние операции SSH</div>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
              <tr>
                <th className="px-4 py-2 text-left font-medium">ID</th>
                <th className="px-3 py-2 text-left font-medium">Операция</th>
                <th className="px-3 py-2 text-left font-medium">Статус</th>
                <th className="px-3 py-2 text-left font-medium">Инициатор</th>
                <th className="px-3 py-2 text-left font-medium">Начало</th>
                <th className="px-4 py-2 text-left font-medium">Детали</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.history.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-xs text-slate-400">
                    Операций пока нет
                  </td>
                </tr>
              ) : (
                data.history.map((d) => {
                  const dst = deployStatusMeta(d.status);
                  return (
                    <tr key={d.id} className="hover:bg-slate-50/70">
                      <td className="px-4 py-2.5 font-mono text-xs">
                        <Link to={`/deployments/${d.id}`} className="text-indigo-600 hover:underline">
                          #{d.id}
                        </Link>
                      </td>
                      <td className="px-3 py-2.5 text-xs font-medium text-slate-800">{deployOpLabel(d.operation)}</td>
                      <td className="px-3 py-2.5">
                        <Badge variant={dst.variant}>{dst.label}</Badge>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-slate-600">{d.startedBy || '—'}</td>
                      <td className="px-3 py-2.5 text-xs text-slate-500">{fmtDateTime(d.startedAt)}</td>
                      <td className="px-4 py-2.5 text-xs text-rose-600 truncate max-w-xs">{d.error || ''}</td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Модальное окно просмотра лога агента */}
      <Modal
        open={logOpen}
        onClose={() => setLogOpen(false)}
        title={`Лог службы агента — ${g.name}`}
        maxWidth="max-w-4xl"
        footer={
          <div className="flex items-center justify-between w-full">
            <div className="flex items-center gap-2">
              <Select
                value={String(logLines)}
                onChange={(e) => setLogLines(Number(e.target.value))}
                className="w-36 h-8 text-xs"
              >
                <option value="100">100 строк</option>
                <option value="200">200 строк</option>
                <option value="500">500 строк</option>
                <option value="1000">1000 строк</option>
              </Select>
              <Button variant="outline" size="sm" onClick={() => logQuery.refetch()} loading={logQuery.isFetching}>
                Обновить
              </Button>
            </div>
            <Button variant="outline" size="sm" onClick={() => setLogOpen(false)}>
              Закрыть
            </Button>
          </div>
        }
      >
        {logQuery.isLoading ? (
          <Spinner label="Чтение журнала по SSH…" />
        ) : logQuery.error ? (
          <div className="rounded-lg bg-rose-50 border border-rose-200 p-3 text-xs text-rose-700">
            {(logQuery.error as Error).message}
          </div>
        ) : (
          <pre className="rounded-xl bg-slate-950 text-slate-100 p-4 text-xs font-mono overflow-x-auto max-h-[60vh] whitespace-pre-wrap">
            {logQuery.data?.log || '(пусто)'}
          </pre>
        )}
      </Modal>
    </div>
  );
}
