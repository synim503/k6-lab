import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, EmptyState, Input, Modal, Select, Spinner } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { useAuth } from '@/context/AuthContext';
import { fmtShortDate } from '@/lib/utils';
import { GitBranch, Pencil, Plus, RefreshCw, ShieldAlert, Trash2 } from 'lucide-react';
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

  const { data, isLoading } = useQuery({
    queryKey: ['git-repos'],
    queryFn: () => apiFetch('/api/v1/git-repos'),
  });

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
      toast.success('Репозиторий удалён');
    },
    onError: (err: any) => toast.error(err.message),
  });

  if (isLoading) return <Spinner label="Загрузка Git-репозиториев…" />;

  const items: any[] = data?.items || [];
  const sshCreds: any[] = data?.sshCredentials || [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Git-репозитории</h1>
          <p className="text-xs text-slate-500">Подключённые репозитории со сценариями k6 (HTTPS и SSH)</p>
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

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState
            icon={<GitBranch className="h-6 w-6" />}
            title="Git-репозитории не добавлены"
            description="Добавьте репозиторий, чтобы импортировать и обновлять скрипты из веток и тегов"
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
                    <td className="px-4 py-3 font-semibold text-slate-900">{r.name}</td>
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
