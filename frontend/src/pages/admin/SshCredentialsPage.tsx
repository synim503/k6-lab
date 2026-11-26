import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Edit2, Key, Plus, ShieldCheck, Trash2 } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { fmtDateTime } from '@/lib/utils';
import { useConfirm } from '@/components/ui/ConfirmContext';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Input,
  Modal,
  Select,
  Spinner,
  Switch,
  Textarea,
} from '@/components/ui/primitives';

interface SshCredDto {
  id: number;
  name: string;
  username: string;
  authType: 'PASSWORD' | 'KEY';
  hasSecret: boolean;
  hasPassphrase: boolean;
  hasSudoPassword: boolean;
  jumpHost?: string | null;
  jumpPort?: number | null;
  jumpCredentialId?: number | null;
  updatedAt?: string | null;
}

interface KnownHostDto {
  id: number;
  host: string;
  port: number;
  keyType: string;
  fingerprintSha256: string;
  confirmedBy?: string | null;
  confirmedAt?: string | null;
}

export function SshCredentialsPage() {
  const qc = useQueryClient();
  const confirm = useConfirm();

  const [modalOpen, setModalOpen] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [username, setUsername] = useState('root');
  const [authType, setAuthType] = useState<'PASSWORD' | 'KEY'>('PASSWORD');
  const [secret, setSecret] = useState('');
  const [passphrase, setPassphrase] = useState('');
  const [sudoPassword, setSudoPassword] = useState('');
  const [jumpHost, setJumpHost] = useState('');
  const [jumpPort, setJumpPort] = useState('22');
  const [jumpCredId, setJumpCredId] = useState('');
  const [clearPassphrase, setClearPassphrase] = useState(false);
  const [clearSudo, setClearSudo] = useState(false);

  const { data, isLoading } = useQuery<{ credentials: SshCredDto[]; knownHosts: KnownHostDto[] }>({
    queryKey: ['ssh-credentials'],
    queryFn: () => apiFetch('/api/v1/ssh-credentials'),
  });

  const saveMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/ssh-credentials', {
        method: 'POST',
        body: {
          id: editId,
          name: name.trim(),
          username: username.trim(),
          authType,
          secret: secret || null,
          passphrase: passphrase || null,
          sudoPassword: sudoPassword || null,
          jumpHost: jumpHost.trim() || null,
          jumpPort: jumpHost.trim() ? Number(jumpPort) || 22 : null,
          jumpCredentialId: jumpCredId ? Number(jumpCredId) : null,
          clearPassphrase,
          clearSudoPassword: clearSudo,
        },
      }),
    onSuccess: () => {
      toast.success('SSH-учётка сохранена');
      setModalOpen(false);
      qc.invalidateQueries({ queryKey: ['ssh-credentials'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/ssh-credentials/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('SSH-учётка удалена');
      qc.invalidateQueries({ queryKey: ['ssh-credentials'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const forgetHostMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/ssh-credentials/known-hosts/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Отпечаток хоста удалён из доверенных');
      qc.invalidateQueries({ queryKey: ['ssh-credentials'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const openNew = () => {
    setEditId(null);
    setName('');
    setUsername('root');
    setAuthType('PASSWORD');
    setSecret('');
    setPassphrase('');
    setSudoPassword('');
    setJumpHost('');
    setJumpPort('22');
    setJumpCredId('');
    setClearPassphrase(false);
    setClearSudo(false);
    setModalOpen(true);
  };

  const openEdit = (c: SshCredDto) => {
    setEditId(c.id);
    setName(c.name);
    setUsername(c.username);
    setAuthType(c.authType);
    setSecret('');
    setPassphrase('');
    setSudoPassword('');
    setJumpHost(c.jumpHost || '');
    setJumpPort(String(c.jumpPort || 22));
    setJumpCredId(c.jumpCredentialId ? String(c.jumpCredentialId) : '');
    setClearPassphrase(false);
    setClearSudo(false);
    setModalOpen(true);
  };

  if (isLoading && !data) return <Spinner label="Загрузка SSH-учёток…" />;

  const creds = data?.credentials || [];
  const hosts = data?.knownHosts || [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">SSH-учётки и доверенные хосты</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Учётные данные для установки генераторов, Jump/Bastion хостов, Git по SSH и SSH-предохранителей
          </p>
        </div>
        <Button size="sm" onClick={openNew}>
          <Plus className="h-4 w-4" />
          Добавить SSH-учётку
        </Button>
      </div>

      {/* Учётки */}
      <Card className="overflow-hidden">
        {creds.length === 0 ? (
          <EmptyState
            icon={<Key className="h-6 w-6" />}
            title="SSH-учёток пока нет"
            description="Создайте учётную запись с паролем или приватным ключом."
            action={
              <Button size="sm" onClick={openNew}>
                <Plus className="h-4 w-4" />
                Добавить учётку
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">Название</th>
                  <th className="px-3 py-2.5 text-left font-medium">Пользователь</th>
                  <th className="px-3 py-2.5 text-left font-medium">Тип</th>
                  <th className="px-3 py-2.5 text-left font-medium">Jump / Sudo</th>
                  <th className="px-3 py-2.5 text-left font-medium">Обновлена</th>
                  <th className="px-4 py-2.5 text-right font-medium">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {creds.map((c) => (
                  <tr key={c.id} className="hover:bg-slate-50/70">
                    <td className="px-4 py-3 font-semibold text-slate-900">{c.name}</td>
                    <td className="px-3 py-3 font-mono text-xs text-slate-700">{c.username}</td>
                    <td className="px-3 py-3">
                      <Badge variant={c.authType === 'KEY' ? 'info' : 'default'}>{c.authType}</Badge>
                    </td>
                    <td className="px-3 py-3 text-xs text-slate-600 space-x-1">
                      {c.hasSudoPassword && <Badge variant="purple">sudo</Badge>}
                      {c.jumpHost && (
                        <Badge variant="warning">
                          jump: {c.jumpHost}:{c.jumpPort || 22}
                        </Badge>
                      )}
                      {!c.hasSudoPassword && !c.jumpHost && <span className="text-slate-400">—</span>}
                    </td>
                    <td className="px-3 py-3 text-xs text-slate-500">{fmtDateTime(c.updatedAt)}</td>
                    <td className="px-4 py-3 text-right space-x-1">
                      <Button variant="ghost" size="xs" onClick={() => openEdit(c)}>
                        <Edit2 className="h-3.5 w-3.5" />
                        Изменить
                      </Button>
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={async () => {
                          const ok = await confirm({
                            title: 'Удалить SSH-учётку',
                            message: `Удалить учётку «${c.name}»?`,
                            confirmText: 'Удалить',
                          });
                          if (ok) deleteMut.mutate(c.id);
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
        )}
      </Card>

      {/* Known hosts */}
      <Card className="overflow-hidden">
        <CardHeader>
          <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
            <ShieldCheck className="h-4 w-4 text-emerald-600" />
            Доверенные отпечатки хостов (known_hosts)
          </div>
        </CardHeader>
        {hosts.length === 0 ? (
          <div className="p-6 text-center text-xs text-slate-400">
            Отпечатки добавляются при подтверждении ключа хоста во время первого подключения
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2 text-left font-medium">Хост</th>
                  <th className="px-3 py-2 text-left font-medium">Тип ключа</th>
                  <th className="px-3 py-2 text-left font-medium">SHA256 Fingerprint</th>
                  <th className="px-3 py-2 text-left font-medium">Подтвердил</th>
                  <th className="px-4 py-2 text-right font-medium">Действие</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {hosts.map((h) => (
                  <tr key={h.id} className="hover:bg-slate-50/70 text-xs">
                    <td className="px-4 py-2.5 font-mono text-slate-900">
                      {h.host}:{h.port}
                    </td>
                    <td className="px-3 py-2.5 font-mono text-slate-600">{h.keyType}</td>
                    <td className="px-3 py-2.5 font-mono text-slate-600">{h.fingerprintSha256}</td>
                    <td className="px-3 py-2.5 text-slate-500">
                      {h.confirmedBy || '—'} · {fmtDateTime(h.confirmedAt)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <Button
                        variant="outline"
                        size="xs"
                        loading={forgetHostMut.isPending}
                        onClick={() => forgetHostMut.mutate(h.id)}
                      >
                        Забыть ключ
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Модальное окно создания/редактирования */}
      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editId ? 'Редактирование SSH-учётки' : 'Новая SSH-учётка'}
        maxWidth="max-w-xl"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setModalOpen(false)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={!name.trim() || !username.trim() || (!editId && !secret)}
              loading={saveMut.isPending}
              onClick={() => saveMut.mutate()}
            >
              Сохранить
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Название *</label>
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="prod-gen-ssh" />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Имя пользователя SSH *</label>
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="ubuntu" className="font-mono" />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Тип аутентификации</label>
            <Select value={authType} onChange={(e) => setAuthType(e.target.value as 'PASSWORD' | 'KEY')}>
              <option value="PASSWORD">Пароль</option>
              <option value="KEY">Приватный SSH-ключ</option>
            </Select>
          </div>
          {authType === 'PASSWORD' ? (
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">
                Пароль SSH {editId ? '(оставьте пустым, чтобы не менять)' : '*'}
              </label>
              <Input type="password" value={secret} onChange={(e) => setSecret(e.target.value)} />
            </div>
          ) : (
            <>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Приватный ключ PEM / OpenSSH {editId ? '(оставьте пустым, чтобы не менять)' : '*'}
                </label>
                <Textarea
                  rows={4}
                  className="font-mono text-xs"
                  value={secret}
                  onChange={(e) => setSecret(e.target.value)}
                  placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Passphrase ключа</label>
                <Input type="password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} />
                {editId && (
                  <Switch
                    className="mt-1.5"
                    checked={clearPassphrase}
                    onChange={setClearPassphrase}
                    label={<span className="text-xs">Очистить сохранённую passphrase</span>}
                  />
                )}
              </div>
            </>
          )}
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Пароль sudo (необязательно)</label>
            <Input type="password" value={sudoPassword} onChange={(e) => setSudoPassword(e.target.value)} />
            {editId && (
              <Switch
                className="mt-1.5"
                checked={clearSudo}
                onChange={setClearSudo}
                label={<span className="text-xs">Очистить сохранённый пароль sudo</span>}
              />
            )}
          </div>
          <div className="border-t border-slate-100 pt-3">
            <div className="text-xs font-semibold text-slate-700 mb-2">Jump / Bastion хост (необязательно)</div>
            <div className="grid grid-cols-3 gap-2">
              <div className="col-span-2">
                <Input value={jumpHost} onChange={(e) => setJumpHost(e.target.value)} placeholder="bastion.example.com" className="text-xs font-mono" />
              </div>
              <div>
                <Input type="number" value={jumpPort} onChange={(e) => setJumpPort(e.target.value)} placeholder="22" className="text-xs" />
              </div>
            </div>
            {jumpHost.trim() && (
              <div className="mt-2">
                <Select value={jumpCredId} onChange={(e) => setJumpCredId(e.target.value)} className="text-xs">
                  <option value="">— Использовать эту же учётку —</option>
                  {creds
                    .filter((x) => x.id !== editId)
                    .map((x) => (
                      <option key={x.id} value={x.id}>
                        {x.name} ({x.username})
                      </option>
                    ))}
                </Select>
              </div>
            )}
          </div>
        </div>
      </Modal>
    </div>
  );
}
