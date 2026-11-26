import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ArrowLeft, CheckCircle2, Circle, Loader2, ShieldAlert, XCircle } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { deployOpLabel, deployStatusMeta, fmtDateTime } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import { Badge, Button, Card, CardBody, CardHeader, Spinner } from '@/components/ui/primitives';

interface DeploymentStep {
  name: string;
  status: string;
  message?: string;
}

interface DeploymentDetail {
  id: number;
  generatorId?: number | null;
  targetHost: string;
  operation: string;
  status: string;
  error?: string | null;
  startedBy?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  steps?: DeploymentStep[] | null;
  log?: string | null;
  final: boolean;
  hostKeyType?: string | null;
  hostKeyFingerprint?: string | null;
  hostKeyHost?: string | null;
  hostKeyPort?: number | null;
}

export function DeploymentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const { isAdmin } = useAuth();

  const { data, isLoading } = useQuery<DeploymentDetail>({
    queryKey: ['deployment', id],
    queryFn: () => apiFetch(`/api/v1/deployments/${id}`),
    refetchInterval: (q) => (q.state.data?.final ? false : 2000),
    enabled: !!id,
  });

  const confirmKeyMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/deployments/${id}/confirm-host-key`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Отпечаток SSH-ключа подтверждён, операция продолжается');
      qc.invalidateQueries({ queryKey: ['deployment', id] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (isLoading || !data) return <Spinner label="Загрузка операции…" />;

  const st = deployStatusMeta(data.status);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Link to="/deployments">
            <Button variant="outline" size="icon">
              <ArrowLeft className="h-4 w-4" />
            </Button>
          </Link>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-bold text-slate-900">
                Операция #{data.id}: {deployOpLabel(data.operation)}
              </h1>
              <Badge variant={st.variant}>{st.label}</Badge>
            </div>
            <div className="text-xs text-slate-500 mt-0.5">
              Хост <span className="font-mono text-slate-700">{data.targetHost}</span> · Запущено:{' '}
              {fmtDateTime(data.startedAt)} ({data.startedBy || 'system'})
            </div>
          </div>
        </div>
        {data.generatorId && (
          <Link to={`/generators/${data.generatorId}`}>
            <Button variant="outline" size="sm">
              Открыть генератор
            </Button>
          </Link>
        )}
      </div>

      {/* Подтверждение ключа хоста */}
      {data.status === 'WAITING_HOST_KEY' && (
        <Card className="border-amber-300 bg-amber-50/70">
          <CardBody className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-start gap-3">
              <ShieldAlert className="h-5 w-5 text-amber-600 shrink-0 mt-0.5" />
              <div>
                <div className="text-sm font-semibold text-amber-950">
                  Требуется подтверждение SSH-ключа хоста {data.hostKeyHost}:{data.hostKeyPort || 22}
                </div>
                <div className="text-xs text-amber-800 mt-1 font-mono">
                  Тип: {data.hostKeyType || 'SSH'} · SHA256: {data.hostKeyFingerprint}
                </div>
              </div>
            </div>
            {isAdmin && (
              <Button
                variant="warning"
                size="sm"
                loading={confirmKeyMut.isPending}
                onClick={() => confirmKeyMut.mutate()}
              >
                Доверять ключу и продолжить
              </Button>
            )}
          </CardBody>
        </Card>
      )}

      {data.error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
          {data.error}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        {/* Шаги */}
        <Card className="lg:col-span-1">
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">Этапы выполнения</div>
          </CardHeader>
          <CardBody className="space-y-3">
            {!data.steps || data.steps.length === 0 ? (
              <div className="text-xs text-slate-400">Ожидание начала выполнения…</div>
            ) : (
              data.steps.map((s, idx) => (
                <div key={idx} className="flex items-start gap-2.5 text-xs">
                  {s.status === 'DONE' || s.status === 'SUCCEEDED' ? (
                    <CheckCircle2 className="h-4 w-4 text-emerald-500 shrink-0 mt-0.5" />
                  ) : s.status === 'RUNNING' ? (
                    <Loader2 className="h-4 w-4 text-indigo-600 animate-spin shrink-0 mt-0.5" />
                  ) : s.status === 'FAILED' ? (
                    <XCircle className="h-4 w-4 text-rose-500 shrink-0 mt-0.5" />
                  ) : (
                    <Circle className="h-4 w-4 text-slate-300 shrink-0 mt-0.5" />
                  )}
                  <div className="min-w-0">
                    <div className="font-medium text-slate-800">{s.name}</div>
                    {s.message && <div className="text-slate-500 break-words mt-0.5">{s.message}</div>}
                  </div>
                </div>
              ))
            )}
          </CardBody>
        </Card>

        {/* Вывод терминала */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">Журнал SSH</div>
            {!data.final && <Badge variant="info">Обновляется…</Badge>}
          </CardHeader>
          <CardBody className="p-0">
            <pre className="rounded-b-xl bg-slate-950 text-slate-100 p-4 text-xs font-mono overflow-x-auto max-h-[65vh] whitespace-pre-wrap">
              {data.log || 'Подключение…'}
            </pre>
          </CardBody>
        </Card>
      </div>
    </div>
  );
}
