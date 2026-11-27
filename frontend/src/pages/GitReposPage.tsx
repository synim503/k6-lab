import React, { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
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
  Switch,
} from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { useAuth } from '@/context/AuthContext';
import { fmtShortDate } from '@/lib/utils';
import {
  ArchiveRestore,
  CheckCircle2,
  CloudUpload,
  FolderGit2,
  GitBranch,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  ShieldAlert,
  Trash2,
} from 'lucide-react';
import { toast } from 'sonner';

export function GitReposPage() {
  const { isAdmin } = useAuth();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const [editModal, setEditModal] = useState<any | null>(null);
  const [refsModal, setRefsModal] = useState<{ repo: any; refs: any[] } | null>(null);
  const [hostKeyPrompt, setHostKeyPrompt] = useState<any | null>(null);
  const [confirmingKey, setConfirmingKey] = useState(false);
  const [refreshingId, setRefreshingId] = useState<number | null>(null);

  // Git Backup config state
  const [bkRepoId, setBkRepoId] = useState<string>('');
  const [bkBranch, setBkBranch] = useState<string>('main');
  const [bkSubdir, setBkSubdir] = useState<string>('');
  const [bkAutoSync, setBkAutoSync] = useState<boolean>(false);
  const [bkPruneDeleted, setBkPruneDeleted] = useState<boolean>(false);
  const bkHydratedRef = useRef(false);

  // Restore modal state
  const [restoreOpen, setRestoreOpen] = useState(false);
  const [remoteScripts, setRemoteScripts] = useState<any[]>([]);
  const [selectedSlugs, setSelectedSlugs] = useState<Set<string>>(new Set());
  const [overwriteExisting, setOverwriteExisting] = useState<boolean>(true);
  const [inspectingRemote, setInspectingRemote] = useState<boolean>(false);
  const [pushingAll, setPushingAll] = useState<boolean>(false);
  const [restoring, setRestoring] = useState<boolean>(false);

  const { data, isLoading } = useQuery({
    queryKey: ['git-repos'],
    queryFn: () => apiFetch('/api/v1/git-repos'),
  });

  const { data: bkData } = useQuery({
    queryKey: ['git-backup'],
    queryFn: () => apiFetch('/api/v1/git-backup'),
    refetchInterval: 30000,
  });

  useEffect(() => {
    if (!bkData?.config || bkHydratedRef.current) return;
    bkHydratedRef.current = true;
    const c = bkData.config;
    setBkRepoId(c.repoId != null ? String(c.repoId) : '');
    setBkBranch(c.branch || 'main');
    setBkSubdir(c.subdir || '');
    setBkAutoSync(!!c.autoSync);
    setBkPruneDeleted(!!c.pruneDeleted);
  }, [bkData]);

  const handleRefresh = async (repo: any, silentSuccessModal = false) => {
    setRefreshingId(repo.id);
    try {
      const res = await apiFetch(`/api/v1/git-repos/${repo.id}/refresh`, { method: 'POST' });
      qc.invalidateQueries({ queryKey: ['git-repos'] });
      if (!silentSuccessModal) {
        setRefsModal({ repo, refs: res.refs || [] });
      } else {
        toast.success(`Доступ к «${repo.name}» проверен: веток и тегов ${res.refs?.length || 0}`);
      }
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setRefreshingId(null);
    }
  };

  const saveMut = useMutation({
    mutationFn: (payload: any) => apiFetch('/api/v1/git-repos', { method: 'POST', body: payload }),
    onSuccess: (res) => {
      setEditModal(null);
      qc.invalidateQueries({ queryKey: ['git-repos'] });
      qc.invalidateQueries({ queryKey: ['git-backup'] });
      toast.success('Репозиторий сохранён — проверяем подключение…');
      if (res?.repo) {
        void handleRefresh(res.repo, true);
      }
    },
    onError: (err: any) => toast.error(err.message),
  });

  const delMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/git-repos/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['git-repos'] });
      qc.invalidateQueries({ queryKey: ['git-backup'] });
      toast.success('Репозиторий удалён');
    },
    onError: (err: any) => toast.error(err.message),
  });

  const saveBackupCfgMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/git-backup/config', {
        method: 'POST',
        body: {
          repoId: bkRepoId ? Number(bkRepoId) : null,
          branch: bkBranch.trim() || 'main',
          subdir: bkSubdir.trim(),
          autoSync: bkAutoSync,
          pruneDeleted: bkPruneDeleted,
        },
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['git-backup'] });
      toast.success('Настройки Git-бэкапа сохранены');
    },
    onError: (err: any) => toast.error(err.message),
  });

  const handlePushAllBackup = async () => {
    if (!bkRepoId) {
      toast.error('Сначала выберите Git-репозиторий для хранения бэкапов');
      return;
    }
    setPushingAll(true);
    try {
      if (isAdmin) {
        await apiFetch('/api/v1/git-backup/config', {
          method: 'POST',
          body: {
            repoId: Number(bkRepoId),
            branch: bkBranch.trim() || 'main',
            subdir: bkSubdir.trim(),
            autoSync: bkAutoSync,
            pruneDeleted: bkPruneDeleted,
          },
        });
      }
      const res = await apiFetch('/api/v1/git-backup/push-all', {
        method: 'POST',
        body: {
          repoId: Number(bkRepoId),
          branch: bkBranch.trim() || 'main',
          subdir: bkSubdir.trim(),
          pruneDeleted: bkPruneDeleted,
        },
      });
      qc.invalidateQueries({ queryKey: ['git-backup'] });
      qc.invalidateQueries({ queryKey: ['git-repos'] });
      toast.success(res.outcome?.message || 'Бэкап скриптов в Git завершён');
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setPushingAll(false);
    }
  };

  const handleOpenRestore = async () => {
    if (!bkRepoId) {
      toast.error('Выберите Git-репозиторий с резервной копией');
      return;
    }
    setInspectingRemote(true);
    try {
      const res = await apiFetch('/api/v1/git-backup/inspect', {
        method: 'POST',
        body: {
          repoId: Number(bkRepoId),
          branch: bkBranch.trim() || 'main',
          subdir: bkSubdir.trim(),
        },
      });
      const list = res.scripts || [];
      setRemoteScripts(list);
      setSelectedSlugs(new Set(list.map((s: any) => s.slug)));
      setRestoreOpen(true);
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setInspectingRemote(false);
    }
  };

  const handleExecuteRestore = async () => {
    if (selectedSlugs.size === 0) {
      toast.error('Отметьте хотя бы один скрипт для восстановления');
      return;
    }
    setRestoring(true);
    try {
      const res = await apiFetch('/api/v1/git-backup/restore', {
        method: 'POST',
        body: {
          repoId: Number(bkRepoId),
          branch: bkBranch.trim() || 'main',
          subdir: bkSubdir.trim(),
          slugs: Array.from(selectedSlugs),
          overwriteExisting,
        },
      });
      const o = res.outcome || {};
      setRestoreOpen(false);
      qc.invalidateQueries({ queryKey: ['scripts'] });
      toast.success(
        `Восстановлено: создано ${o.created || 0}, обновлено ${o.updated || 0}, профилей ${o.profilesRestored || 0}, графиков ${o.chartsRestored || 0}`
      );
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setRestoring(false);
    }
  };

  if (isLoading) return <Spinner label="Загрузка Git-репозиториев…" />;

  const items: any[] = data?.items || [];
  const sshCreds: any[] = data?.sshCredentials || [];
  const bkCfg = bkData?.config || {};

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Git-репозитории и бэкап скриптов</h1>
          <p className="text-xs text-slate-500">
            Подключение репозиториев со сценариями k6 (HTTPS и SSH) и централизованная резервная копия всех скриптов в Git
          </p>
        </div>
        {isAdmin && (
          <Button
            size="sm"
            onClick={() =>
              setEditModal({
                id: null,
                name: '',
                url: '',
                authType: 'NONE',
                token: '',
                sshCredentialId: '',
                subdir: '',
                clearToken: false,
              })
            }
          >
            <Plus className="h-4 w-4" />
            Добавить репозиторий
          </Button>
        )}
      </div>

      {/* Блок резервного копирования и синхронизации скриптов в Git */}
      <Card id="backup" className="overflow-hidden border-indigo-200/80">
        <CardHeader className="bg-slate-50/70 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <FolderGit2 className="h-4 w-4 text-indigo-600" />
            <span className="font-semibold text-sm text-slate-900">
              Хранилище бэкапа скриптов в Git
            </span>
            {bkCfg.lastStatus === 'OK' && <Badge variant="success">синхронизировано</Badge>}
            {bkCfg.lastStatus === 'UP_TO_DATE' && <Badge variant="info">актуально</Badge>}
            {bkCfg.lastStatus === 'ERROR' && <Badge variant="danger">ошибка синхронизации</Badge>}
            {bkCfg.autoSync && <Badge variant="purple">автобэкап включён</Badge>}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={!bkRepoId}
              loading={inspectingRemote}
              onClick={handleOpenRestore}
              title="Просмотреть скрипты в ветке Git и восстановить выбранные в k6-lab"
            >
              <ArchiveRestore className="h-3.5 w-3.5" />
              Восстановить из Git…
            </Button>
            <Button
              variant="primary"
              size="sm"
              disabled={!bkRepoId}
              loading={pushingAll}
              onClick={handlePushAllBackup}
              title="Выгрузить все скрипты, профили нагрузки и графики отчёта коммитом в выбранную ветку"
            >
              <CloudUpload className="h-3.5 w-3.5" />
              Синхронизировать все скрипты ({bkData?.localScriptCount ?? 0})
            </Button>
          </div>
        </CardHeader>
        <CardBody className="space-y-3.5">
          <p className="text-xs text-slate-600">
            Сохраняет в выбранный Git-репозиторий полный снимок каждого скрипта в человекочитаемом виде: распакованный код{' '}
            <code className="px-1 py-0.5 rounded bg-slate-100">src/</code>, базовую нагрузку, все профили с папками и
            шаблоны графиков отчёта. При повторной выгрузке коммит создаётся только если содержимое действительно изменилось.
          </p>

          <div className="grid grid-cols-1 md:grid-cols-12 gap-3 items-end">
            <div className="md:col-span-5">
              <label className="block text-xs font-medium text-slate-700 mb-1">Репозиторий для бэкапа</label>
              <Select
                disabled={!isAdmin}
                value={bkRepoId}
                onChange={(e) => setBkRepoId(e.target.value)}
              >
                <option value="">— не выбран —</option>
                {items.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} ({r.url})
                  </option>
                ))}
              </Select>
            </div>
            <div className="md:col-span-3">
              <label className="block text-xs font-medium text-slate-700 mb-1">Ветка</label>
              <Input
                disabled={!isAdmin}
                value={bkBranch}
                onChange={(e) => setBkBranch(e.target.value)}
                placeholder="main или k6-backup"
                className="font-mono text-xs"
              />
            </div>
            <div className="md:col-span-4">
              <label className="block text-xs font-medium text-slate-700 mb-1">
                Подкаталог в репозитории (необязательно)
              </label>
              <Input
                disabled={!isAdmin}
                value={bkSubdir}
                onChange={(e) => setBkSubdir(e.target.value)}
                placeholder="Корень репозитория или backups/k6-lab"
                className="font-mono text-xs"
              />
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
            <div className="flex flex-wrap items-center gap-5">
              <Switch
                disabled={!isAdmin || !bkRepoId}
                checked={bkAutoSync}
                onChange={setBkAutoSync}
                label={
                  <span className="text-xs">
                    Автоматически синхронизировать изменения в фоне
                  </span>
                }
              />
              <Switch
                disabled={!isAdmin || !bkRepoId}
                checked={bkPruneDeleted}
                onChange={setBkPruneDeleted}
                label={
                  <span className="text-xs text-slate-600">
                    Удалять из ветки каталоги удалённых в админке скриптов
                  </span>
                }
              />
            </div>

            {isAdmin && (
              <Button
                variant="outline"
                size="sm"
                loading={saveBackupCfgMut.isPending}
                onClick={() => saveBackupCfgMut.mutate()}
              >
                <Save className="h-3.5 w-3.5" />
                Сохранить настройки бэкапа
              </Button>
            )}
          </div>

          {(bkCfg.lastSyncAt || bkCfg.lastMessage) && (
            <div
              className={`rounded-lg border px-3 py-2 text-xs flex flex-wrap items-center justify-between gap-2 ${
                bkCfg.lastStatus === 'ERROR'
                  ? 'border-rose-200 bg-rose-50 text-rose-800'
                  : 'border-slate-200 bg-slate-50 text-slate-700'
              }`}
            >
              <div className="flex items-center gap-2 min-w-0">
                {bkCfg.lastStatus !== 'ERROR' && <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600 shrink-0" />}
                <span className="break-words">{bkCfg.lastMessage || 'Готово'}</span>
              </div>
              <div className="text-slate-500 font-mono text-[11px] shrink-0">
                {bkCfg.lastCommit && <span className="mr-2">commit {String(bkCfg.lastCommit).slice(0, 8)}</span>}
                {bkCfg.lastSyncAt && <span>{fmtShortDate(bkCfg.lastSyncAt)}</span>}
              </div>
            </div>
          )}
        </CardBody>
      </Card>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState
            icon={<GitBranch className="h-6 w-6" />}
            title="Git-репозитории не добавлены"
            description="Добавьте репозиторий, чтобы импортировать сценарии и настроить резервное копирование скриптов в Git"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-xs text-slate-500 border-b border-slate-200">
                <tr>
                  <th className="text-left font-medium px-4 py-3">Название</th>
                  <th className="text-left font-medium px-3 py-3">URL / Каталог</th>
                  <th className="text-left font-medium px-3 py-3">Авторизация</th>
                  <th className="text-left font-medium px-3 py-3">Обновлён</th>
                  <th className="text-right font-medium px-4 py-3">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((r) => (
                  <tr key={r.id} className="hover:bg-slate-50/70">
                    <td className="px-4 py-3 font-semibold text-slate-900">
                      <div className="flex items-center gap-2">
                        <span>{r.name}</span>
                        {bkCfg.repoId === r.id && <Badge variant="info">хранилище бэкапа</Badge>}
                      </div>
                    </td>
                    <td className="px-3 py-3">
                      <code className="text-xs text-slate-700 break-all">{r.url}</code>
                      {r.subdir && <div className="text-xs text-slate-400">subdir: {r.subdir}</div>}
                    </td>
                    <td className="px-3 py-3">
                      <Badge variant="default">{r.authType}</Badge>
                    </td>
                    <td className="px-3 py-3 text-xs text-slate-500">{fmtShortDate(r.updatedAt)}</td>
                    <td className="px-4 py-3 text-right whitespace-nowrap">
                      <div className="inline-flex items-center gap-1.5">
                        <Button
                          variant="outline"
                          size="xs"
                          loading={refreshingId === r.id}
                          onClick={() => handleRefresh(r)}
                        >
                          <RefreshCw className="h-3.5 w-3.5" />
                          Ветки ({r.cachedRefs?.length || 0})
                        </Button>
                        {isAdmin && (
                          <>
                            <Button
                              variant="ghost"
                              size="icon"
                              onClick={() =>
                                setEditModal({
                                  id: r.id,
                                  name: r.name,
                                  url: r.url,
                                  authType: r.authType,
                                  token: '',
                                  hasToken: r.hasToken,
                                  sshCredentialId: r.sshCredentialId || '',
                                  subdir: r.subdir || '',
                                  clearToken: false,
                                })
                              }
                            >
                              <Pencil className="h-4 w-4" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              onClick={async () => {
                                const ok = await confirm({
                                  title: 'Удалить репозиторий?',
                                  message: `Удалить «${r.name}»? Уже загруженные версии скриптов останутся.`,
                                  confirmText: 'Удалить',
                                });
                                if (ok) delMut.mutate(r.id);
                              }}
                            >
                              <Trash2 className="h-4 w-4 text-rose-600" />
                            </Button>
                          </>
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

      {/* Модалка восстановления скриптов из Git-бэкапа */}
      <Modal
        open={restoreOpen}
        onClose={() => setRestoreOpen(false)}
        title={`Восстановление скриптов из Git (${bkBranch || 'main'})`}
        maxWidth="max-w-3xl"
      >
        <div className="space-y-3 text-xs">
          {remoteScripts.length === 0 ? (
            <div className="py-8 text-center text-slate-500">
              В выбранной ветке пока нет резервных копий скриптов в формате <code className="font-mono">scripts/*/k6lab-script.json</code>.
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-slate-600">
                  Найдено скриптов в Git: <b>{remoteScripts.length}</b> (выбрано: <b>{selectedSlugs.size}</b>)
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    size="xs"
                    onClick={() => setSelectedSlugs(new Set(remoteScripts.map((s) => s.slug)))}
                  >
                    Выбрать все
                  </Button>
                  <Button variant="ghost" size="xs" onClick={() => setSelectedSlugs(new Set())}>
                    Снять все
                  </Button>
                </div>
              </div>

              <div className="max-h-80 overflow-y-auto rounded-xl border border-slate-200 divide-y divide-slate-100">
                {remoteScripts.map((rs) => {
                  const checked = selectedSlugs.has(rs.slug);
                  return (
                    <label
                      key={rs.slug}
                      className="flex items-center justify-between gap-3 px-3.5 py-2.5 hover:bg-slate-50 cursor-pointer"
                    >
                      <div className="flex items-center gap-2.5 min-w-0">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={(e) => {
                            const next = new Set(selectedSlugs);
                            if (e.target.checked) next.add(rs.slug);
                            else next.delete(rs.slug);
                            setSelectedSlugs(next);
                          }}
                        />
                        <div className="min-w-0">
                          <div className="font-semibold text-slate-900 flex items-center gap-2 flex-wrap">
                            <span>{rs.name}</span>
                            {!rs.existsLocally ? (
                              <Badge variant="success">новый</Badge>
                            ) : rs.filesMissingLocally ? (
                              <Badge variant="danger">нет файлов на диске</Badge>
                            ) : rs.codeDiffers ? (
                              <Badge variant="warning">код отличается</Badge>
                            ) : (
                              <Badge variant="default">уже есть</Badge>
                            )}
                          </div>
                          <div className="text-[11px] text-slate-500 truncate">
                            <code>{rs.entrypoint || 'auto'}</code> · файлов: {rs.fileCount} · профилей: {rs.profileCount} ·
                            графиков: {rs.chartCount}
                          </div>
                        </div>
                      </div>
                      <code className="text-[11px] text-slate-400 shrink-0">scripts/{rs.slug}</code>
                    </label>
                  );
                })}
              </div>

              <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                <Switch
                  checked={overwriteExisting}
                  onChange={setOverwriteExisting}
                  label={
                    <span className="text-xs">
                      Обновлять уже существующие в k6-lab скрипты (добавить новую версию при отличии кода и синхронизировать профили/графики)
                    </span>
                  }
                />
              </div>

              <div className="flex items-center justify-end gap-2 pt-1">
                <Button variant="outline" size="sm" onClick={() => setRestoreOpen(false)}>
                  Отмена
                </Button>
                {isAdmin ? (
                  <Button
                    size="sm"
                    loading={restoring}
                    disabled={selectedSlugs.size === 0}
                    onClick={handleExecuteRestore}
                  >
                    <ArchiveRestore className="h-3.5 w-3.5" />
                    Восстановить выбранные ({selectedSlugs.size})
                  </Button>
                ) : (
                  <span className="text-amber-700">Восстановление доступно администратору</span>
                )}
              </div>
            </>
          )}
        </div>
      </Modal>

      {/* Модалка добавления/редактирования */}
      <Modal
        open={!!editModal}
        onClose={() => setEditModal(null)}
        title={editModal?.id ? 'Редактировать Git-репозиторий' : 'Новый Git-репозиторий'}
      >
        {editModal && (
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveMut.mutate({
                ...editModal,
                sshCredentialId: editModal.sshCredentialId ? Number(editModal.sshCredentialId) : null,
              });
            }}
          >
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Название *</label>
              <Input
                required
                value={editModal.name}
                onChange={(e) => setEditModal({ ...editModal, name: e.target.value })}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">URL репозитория *</label>
              <Input
                required
                value={editModal.url}
                onChange={(e) => setEditModal({ ...editModal, url: e.target.value })}
                placeholder="https://git.example.com/team/k6-tests.git или git@..."
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Авторизация</label>
                <Select
                  value={editModal.authType}
                  onChange={(e) => setEditModal({ ...editModal, authType: e.target.value })}
                >
                  <option value="NONE">Без авторизации</option>
                  <option value="HTTPS_TOKEN">HTTPS токен</option>
                  <option value="SSH">SSH-учётка</option>
                </Select>
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Подкаталог (subdir)</label>
                <Input
                  value={editModal.subdir}
                  onChange={(e) => setEditModal({ ...editModal, subdir: e.target.value })}
                  placeholder="Необязательно"
                />
              </div>
            </div>

            {editModal.authType === 'HTTPS_TOKEN' && (
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Токен доступа {editModal.hasToken && '(оставьте пустым, чтобы не менять)'}
                </label>
                <Input
                  type="password"
                  value={editModal.token}
                  onChange={(e) => setEditModal({ ...editModal, token: e.target.value })}
                  placeholder="glpat-... / ghp_..."
                />
              </div>
            )}

            {editModal.authType === 'SSH' && (
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">SSH-учётка *</label>
                <Select
                  required
                  value={editModal.sshCredentialId}
                  onChange={(e) => setEditModal({ ...editModal, sshCredentialId: e.target.value })}
                >
                  <option value="">— выберите —</option>
                  {sshCreds.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} ({c.username})
                    </option>
                  ))}
                </Select>
              </div>
            )}

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" size="sm" onClick={() => setEditModal(null)}>
                Отмена
              </Button>
              <Button type="submit" size="sm" loading={saveMut.isPending}>
                Сохранить
              </Button>
            </div>
          </form>
        )}
      </Modal>

      {/* Модалка веток и тегов */}
      <Modal
        open={!!refsModal}
        onClose={() => setRefsModal(null)}
        title={`Ветки и теги: ${refsModal?.repo?.name || ''}`}
      >
        <div className="max-h-80 overflow-y-auto divide-y divide-slate-100 text-xs">
          {(refsModal?.refs || []).map((rf) => (
            <div key={`${rf.kind}-${rf.name}`} className="py-2 flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <Badge variant={rf.kind === 'branch' ? 'info' : 'default'}>{rf.kind}</Badge>
                <span className="font-mono font-medium text-slate-900">{rf.name}</span>
              </div>
              <code className="text-slate-400">{rf.commit?.slice(0, 10)}</code>
            </div>
          ))}
          {refsModal?.refs?.length === 0 && <div className="py-6 text-center text-slate-400">Пусто</div>}
        </div>
      </Modal>

      <Modal
        open={!!hostKeyPrompt}
        onClose={() => setHostKeyPrompt(null)}
        title={
          <span className="flex items-center gap-2">
            <ShieldAlert className="h-4 w-4 text-amber-500" />
            {hostKeyPrompt?.mismatch ? 'Ключ SSH-хоста изменился' : 'Подтвердите SSH-ключ хоста'}
          </span>
        }
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setHostKeyPrompt(null)}>
              Отмена
            </Button>
            {isAdmin && (
              <Button
                size="sm"
                variant={hostKeyPrompt?.mismatch ? 'danger' : 'primary'}
                loading={confirmingKey}
                onClick={async () => {
                  setConfirmingKey(true);
                  try {
                    const res = await apiFetch('/api/v1/git-repos/confirm-host-key', {
                      method: 'POST',
                      body: { ...hostKeyPrompt, replace: !!hostKeyPrompt.mismatch },
                    });
                    setHostKeyPrompt(null);
                    qc.invalidateQueries({ queryKey: ['git-repos'] });
                    if (res.refreshError) {
                      toast.warning(`Ключ подтверждён, но не удалось загрузить ветки: ${res.refreshError}`);
                    } else {
                      toast.success(`Ключ хоста подтверждён (веток и тегов: ${res.refs?.length || 0})`);
                    }
                  } catch (err: any) {
                    if (err?.data?.hostKeyRequired) {
                      setHostKeyPrompt(err.data);
                    } else {
                      toast.error(err.message);
                    }
                  } finally {
                    setConfirmingKey(false);
                  }
                }}
              >
                {hostKeyPrompt?.mismatch ? 'Заменить ключ и подключить' : 'Подтвердить и подключить'}
              </Button>
            )}
          </>
        }
      >
        {hostKeyPrompt && (
          <div className="text-xs space-y-2.5 text-slate-700">
            <p className="text-slate-600">
              {hostKeyPrompt.mismatch
                ? 'Сервер предъявил новый SSH-ключ, отличный от ранее сохранённого в known_hosts.'
                : 'Первое подключение к Git-серверу по SSH. Проверьте отпечаток ключа и добавьте хост в доверенные:'}
            </p>
            <div>
              Хост: <code className="font-semibold text-slate-900">{hostKeyPrompt.host}:{hostKeyPrompt.port}</code>{' '}
              {hostKeyPrompt.keyType && `(${hostKeyPrompt.keyType})`}
            </div>
            {hostKeyPrompt.mismatch && hostKeyPrompt.expectedFingerprint && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-2 text-amber-900">
                Прежний SHA-256: <code className="break-all">{hostKeyPrompt.expectedFingerprint}</code>
              </div>
            )}
            <div>
              SHA-256: <code className="break-all bg-slate-100 px-1.5 py-0.5 rounded font-semibold">{hostKeyPrompt.fingerprint}</code>
            </div>
            {!isAdmin && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-2 text-amber-900">
                Подтвердить SSH-ключ хоста может только администратор.
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
