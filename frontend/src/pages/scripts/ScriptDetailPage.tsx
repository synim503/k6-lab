import React, { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, apiUpload } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Input, Modal, Select, Spinner } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { ScriptHead } from './ScriptHead';
import { fmtBytes, fmtShortDate, runStatusMeta } from '@/lib/utils';
import {
  ChevronLeft,
  ChevronRight,
  Code2,
  Download,
  FileUp,
  GitBranch,
  Play,
  RefreshCw,
  Trash2,
} from 'lucide-react';
import { toast } from 'sonner';

export function ScriptDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [gitOpen, setGitOpen] = useState(false);

  // Upload version state
  const [upFile, setUpFile] = useState<File | null>(null);
  const [upEntry, setUpEntry] = useState('');
  const [upComment, setUpComment] = useState('');
  const [uploading, setUploading] = useState(false);

  // Git import state
  const [gitRepoId, setGitRepoId] = useState('');
  const [gitRef, setGitRef] = useState('main');
  const [gitEntry, setGitEntry] = useState('');
  const [gitComment, setGitComment] = useState('');
  const [gitCommits, setGitCommits] = useState<any[] | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['script', id, page, size, statusFilter],
    queryFn: () => {
      const sp = new URLSearchParams({ page: String(page), size: String(size) });
      if (statusFilter) sp.set('status', statusFilter);
      return apiFetch(`/api/v1/scripts/${id}?${sp.toString()}`);
    },
    refetchInterval: 8000,
  });

  const gitLatestMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/git-latest`, { method: 'POST' }),
    onSuccess: (res) => {
      toast.success(`Импортирована версия v${res.version.number}`);
      setGitCommits(null);
      qc.invalidateQueries({ queryKey: ['script', id] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const gitCheckMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/git-check`),
    onSuccess: (res) => {
      setGitCommits(res.commits || []);
      if (!res.commits?.length) toast.info('Новых коммитов в ветке нет');
    },
    onError: (err: any) => toast.error(err.message),
  });

  const deleteMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Скрипт удалён');
      qc.invalidateQueries({ queryKey: ['scripts'] });
      navigate('/scripts');
    },
    onError: (err: any) => toast.error(err.message),
  });

  if (isLoading || !data) return <Spinner label="Загрузка скрипта…" />;

  const { script, latestVersion, versions = [], runs, gitRepos = [] } = data;

  return (
    <div>
      <ScriptHead
        script={script}
        rightActions={
          <div className="flex items-center gap-2">
            {latestVersion && (
              <Link to={`/scripts/${script.id}/versions/${latestVersion.id}/edit`}>
                <Button variant="outline" size="sm">
                  <Code2 className="h-3.5 w-3.5" />
                  Редактировать код
                </Button>
              </Link>
            )}
            <Link to={`/scripts/${script.id}/run`}>
              <Button variant="success" size="sm">
                <Play className="h-3.5 w-3.5 fill-current" />
                Запустить
              </Button>
            </Link>
          </div>
        }
      />

      {/* Git-ветка и проверка обновлений */}
      {script.gitRepoId && script.gitBranch && (
        <Card className="mb-4 p-3.5 flex flex-wrap items-center justify-between gap-3 bg-slate-50/70">
          <div className="flex items-center gap-2 text-xs text-slate-700">
            <GitBranch className="h-4 w-4 text-indigo-600" />
            <span>
              Привязан к Git: <b>{script.gitRepoName || `#${script.gitRepoId}`}</b> · ветка{' '}
              <code className="font-semibold">{script.gitBranch}</code> ({script.gitEntrypoint})
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="xs" loading={gitCheckMut.isPending} onClick={() => gitCheckMut.mutate()}>
              <RefreshCw className="h-3.5 w-3.5" />
              Проверить коммиты
            </Button>
            <Button variant="primary" size="xs" loading={gitLatestMut.isPending} onClick={() => gitLatestMut.mutate()}>
              Подтянуть последнюю версию
            </Button>
          </div>
          {gitCommits && gitCommits.length > 0 && (
            <div className="w-full border-t border-slate-200 pt-2 mt-1 space-y-1">
              <div className="text-xs font-semibold text-slate-700">Новые коммиты ({gitCommits.length}):</div>
              {gitCommits.slice(0, 6).map((c) => (
                <div key={c.hash} className="text-xs text-slate-600 flex items-center gap-2">
                  <code className="text-indigo-600">{c.hash.slice(0, 8)}</code>
                  <span className="truncate flex-1">{c.message}</span>
                  <span className="text-slate-400 shrink-0">{c.author}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* История запусков скрипта */}
        <Card className="lg:col-span-7">
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">
              Запуски скрипта <span className="text-slate-400 font-normal">({runs.totalElements})</span>
            </div>
            <div className="flex items-center gap-2">
              <Select
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value);
                  setPage(0);
                }}
                className="h-8 text-xs w-40"
              >
                <option value="">Все статусы</option>
                <option value="RUNNING">Выполняется</option>
                <option value="QUEUED">В очереди</option>
                <option value="FINISHED">Завершён</option>
                <option value="FAILED">Ошибка</option>
                <option value="STOPPED">Остановлен</option>
              </Select>
              <Select
                value={String(size)}
                onChange={(e) => {
                  setSize(Number(e.target.value));
                  setPage(0);
                }}
                className="h-8 text-xs w-20"
              >
                <option value="10">10</option>
                <option value="20">20</option>
                <option value="50">50</option>
              </Select>
            </div>
          </CardHeader>
          <CardBody className="p-0">
            {runs.items.length === 0 ? (
              <EmptyState title="Запусков пока нет" />
            ) : (
              <>
                <div className="divide-y divide-slate-100">
                  {runs.items.map((r: any) => {
                    const st = runStatusMeta(r.status, r.thresholdsFailed);
                    return (
                      <Link
                        key={r.id}
                        to={`/runs/${r.id}`}
                        className="flex items-center justify-between gap-3 px-4 py-2.5 hover:bg-slate-50"
                      >
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="font-semibold text-sm text-slate-900">#{r.id}</span>
                            <Badge variant={st.variant}>{st.label}</Badge>
                            {r.loadProfileName && <Badge variant="info">{r.loadProfileName}</Badge>}
                          </div>
                          <div className="text-xs text-slate-400 mt-0.5">
                            {fmtShortDate(r.startedAt || r.queuedAt)}
                            {r.createdBy ? ` · ${r.createdBy}` : ''}
                            {r.stopReason ? ` · ${r.stopReason}` : ''}
                          </div>
                        </div>
                        <span className="text-xs text-indigo-600 shrink-0">Открыть →</span>
                      </Link>
                    );
                  })}
                </div>
                {runs.totalPages > 1 && (
                  <div className="flex items-center justify-between border-t border-slate-100 px-4 py-2.5 text-xs text-slate-500">
                    <span>
                      Стр. {runs.page + 1} из {runs.totalPages}
                    </span>
                    <div className="flex gap-1">
                      <Button variant="outline" size="xs" disabled={runs.page <= 0} onClick={() => setPage((p) => p - 1)}>
                        <ChevronLeft className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="outline"
                        size="xs"
                        disabled={runs.page + 1 >= runs.totalPages}
                        onClick={() => setPage((p) => p + 1)}
                      >
                        <ChevronRight className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </CardBody>
        </Card>

        {/* Версии скрипта и управление */}
        <div className="lg:col-span-5 space-y-4">
          <Card>
            <CardHeader>
              <div className="font-semibold text-sm text-slate-900">Версии ({versions.length})</div>
              <div className="flex items-center gap-1.5">
                <Button variant="outline" size="xs" onClick={() => setUploadOpen(true)}>
                  <FileUp className="h-3.5 w-3.5" />
                  Загрузить
                </Button>
                {gitRepos.length > 0 && (
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => {
                      setGitRepoId(script.gitRepoId ? String(script.gitRepoId) : String(gitRepos[0].id));
                      setGitRef(script.gitBranch || 'main');
                      setGitEntry(script.gitEntrypoint || latestVersion?.entrypoint || 'main.js');
                      setGitOpen(true);
                    }}
                  >
                    <GitBranch className="h-3.5 w-3.5" />
                    Из Git
                  </Button>
                )}
              </div>
            </CardHeader>
            <CardBody className="p-0 max-h-[480px] overflow-y-auto">
              <div className="divide-y divide-slate-100">
                {versions.map((v: any, idx: number) => (
                  <div key={v.id} className="px-4 py-3 flex items-start justify-between gap-2 hover:bg-slate-50/70">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <Link
                          to={`/scripts/${script.id}/versions/${v.id}`}
                          className="font-semibold text-sm text-slate-900 hover:text-indigo-600"
                        >
                          Версия {v.number}
                        </Link>
                        {idx === 0 && <Badge variant="success">Текущая</Badge>}
                        <Badge variant="default">{v.sourceLabel}</Badge>
                        {!v.validForModel && <Badge variant="warning">Опции</Badge>}
                      </div>
                      <div className="text-xs text-slate-500 mt-0.5 truncate">
                        <code>{v.entrypoint}</code> · {v.operationsCount} оп. · {fmtBytes(v.sizeBytes)}
                      </div>
                      {v.comment && <div className="text-xs text-slate-600 mt-0.5">{v.comment}</div>}
                      <div className="text-[11px] text-slate-400 mt-0.5">
                        {fmtShortDate(v.createdAt)} · {v.createdBy}
                      </div>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <Link to={`/scripts/${script.id}/versions/${v.id}/edit`} title="Открыть в редакторе">
                        <Button variant="ghost" size="icon">
                          <Code2 className="h-4 w-4" />
                        </Button>
                      </Link>
                      <a href={`/scripts/${script.id}/versions/${v.id}/download`} title="Скачать zip">
                        <Button variant="ghost" size="icon">
                          <Download className="h-4 w-4" />
                        </Button>
                      </a>
                    </div>
                  </div>
                ))}
              </div>
            </CardBody>
          </Card>

          <Card className="p-4 border-rose-200/70 bg-rose-50/30 flex items-center justify-between gap-3">
            <div>
              <div className="text-xs font-semibold text-slate-800">Удаление скрипта</div>
              <div className="text-xs text-slate-500">Удалит скрипт и все его версии без возможности возврата</div>
            </div>
            <Button
              variant="danger"
              size="sm"
              loading={deleteMut.isPending}
              onClick={async () => {
                const ok = await confirm({
                  title: 'Удалить скрипт и все его версии?',
                  message: `Скрипт «${script.name}» и все его версии будут удалены безвозвратно.`,
                  confirmText: 'Удалить скрипт',
                  variant: 'danger',
                });
                if (ok) deleteMut.mutate();
              }}
            >
              <Trash2 className="h-3.5 w-3.5" />
              Удалить
            </Button>
          </Card>
        </div>
      </div>

      {/* Модалка загрузки новой версии */}
      <Modal open={uploadOpen} onClose={() => setUploadOpen(false)} title="Загрузить новую версию">
        <form
          className="space-y-3"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!upFile) return;
            setUploading(true);
            try {
              const fd = new FormData();
              fd.append('file', upFile);
              fd.append('entrypoint', upEntry);
              fd.append('comment', upComment);
              const res = await apiUpload(`/api/v1/scripts/${script.id}/versions`, fd);
              toast.success(`Загружена версия v${res.version.number}`);
              setUploadOpen(false);
              setUpFile(null);
              setUpComment('');
              qc.invalidateQueries({ queryKey: ['script', id] });
            } catch (err: any) {
              toast.error(err.message);
            } finally {
              setUploading(false);
            }
          }}
        >
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Файл (.js / .zip) *</label>
            <input
              type="file"
              required
              accept=".js,.mjs,.ts,.zip"
              onChange={(e) => setUpFile(e.target.files?.[0] || null)}
              className="block w-full text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-50 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-indigo-700"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Entrypoint (необязательно)</label>
            <Input value={upEntry} onChange={(e) => setUpEntry(e.target.value)} placeholder={latestVersion?.entrypoint || 'main.js'} />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий</label>
            <Input value={upComment} onChange={(e) => setUpComment(e.target.value)} placeholder="Что изменилось" />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setUploadOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={uploading}>
              Загрузить версию
            </Button>
          </div>
        </form>
      </Modal>

      {/* Модалка импорта версии из Git */}
      <Modal open={gitOpen} onClose={() => setGitOpen(false)} title="Импортировать версию из Git">
        <form
          className="space-y-3"
          onSubmit={async (e) => {
            e.preventDefault();
            setUploading(true);
            try {
              const res = await apiFetch(`/api/v1/scripts/${script.id}/git-import`, {
                method: 'POST',
                body: {
                  repoId: Number(gitRepoId),
                  ref: gitRef.trim(),
                  entrypoint: gitEntry.trim(),
                  comment: gitComment,
                },
              });
              toast.success(`Импортирована версия v${res.version.number}`);
              setGitOpen(false);
              qc.invalidateQueries({ queryKey: ['script', id] });
            } catch (err: any) {
              toast.error(err.message);
            } finally {
              setUploading(false);
            }
          }}
        >
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Репозиторий</label>
            <Select required value={gitRepoId} onChange={(e) => setGitRepoId(e.target.value)}>
              {gitRepos.map((r: any) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </Select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Ветка / тег / коммит</label>
              <Input required value={gitRef} onChange={(e) => setGitRef(e.target.value)} />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Entrypoint</label>
              <Input required value={gitEntry} onChange={(e) => setGitEntry(e.target.value)} />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий</label>
            <Input value={gitComment} onChange={(e) => setGitComment(e.target.value)} />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setGitOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={uploading}>
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
