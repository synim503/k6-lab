import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { KeyRound, Plus, Trash2, Users } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { fmtDateTime } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import { useConfirm } from '@/components/ui/ConfirmContext';
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
} from '@/components/ui/primitives';

interface UserDto {
  id: number;
  username: string;
  role: 'ADMIN' | 'USER';
  enabled: boolean;
  createdAt?: string | null;
}

export function UsersPage() {
  const qc = useQueryClient();
  const confirm = useConfirm();
  const { username: currentUser } = useAuth();

  const [createOpen, setCreateOpen] = useState(false);
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newRole, setNewRole] = useState<'ADMIN' | 'USER'>('USER');

  const [pwdUser, setPwdUser] = useState<UserDto | null>(null);
  const [nextPassword, setNextPassword] = useState('');

  const { data, isLoading } = useQuery<{ items: UserDto[] }>({
    queryKey: ['users'],
    queryFn: () => apiFetch('/api/v1/users'),
  });

  const createMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/users', {
        method: 'POST',
        body: { username: newUsername.trim(), password: newPassword, role: newRole },
      }),
    onSuccess: () => {
      toast.success('Пользователь создан');
      setCreateOpen(false);
      setNewUsername('');
      setNewPassword('');
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const updateMut = useMutation({
    mutationFn: ({ id, role, enabled }: { id: number; role: 'ADMIN' | 'USER'; enabled: boolean }) =>
      apiFetch(`/api/v1/users/${id}`, {
        method: 'POST',
        body: { role, enabled },
      }),
    onSuccess: () => {
      toast.success('Параметры пользователя обновлены');
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const pwdMut = useMutation({
    mutationFn: () =>
      apiFetch(`/api/v1/users/${pwdUser!.id}/password`, {
        method: 'POST',
        body: { password: nextPassword },
      }),
    onSuccess: () => {
      toast.success('Пароль обновлён');
      setPwdUser(null);
      setNextPassword('');
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/users/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Пользователь удалён');
      qc.invalidateQueries({ queryKey: ['users'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (isLoading && !data) return <Spinner label="Загрузка пользователей…" />;

  const items = data?.items || [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Пользователи и роли</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Управление учётными записями инженеров (`USER`) и администраторов (`ADMIN`)
          </p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4" />
          Создать пользователя
        </Button>
      </div>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState icon={<Users className="h-6 w-6" />} title="Пользователей нет" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">Логин</th>
                  <th className="px-3 py-2.5 text-left font-medium">Роль</th>
                  <th className="px-3 py-2.5 text-left font-medium">Активен</th>
                  <th className="px-3 py-2.5 text-left font-medium">Создан</th>
                  <th className="px-4 py-2.5 text-right font-medium">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((u) => {
                  const isSelf = u.username === currentUser;
                  return (
                    <tr key={u.id} className="hover:bg-slate-50/70">
                      <td className="px-4 py-3 font-semibold text-slate-900">
                        {u.username}{' '}
                        {isSelf && <Badge variant="info" className="ml-1">Вы</Badge>}
                      </td>
                      <td className="px-3 py-3 w-40">
                        <Select
                          value={u.role}
                          disabled={isSelf || updateMut.isPending}
                          onChange={(e) =>
                            updateMut.mutate({
                              id: u.id,
                              role: e.target.value as 'ADMIN' | 'USER',
                              enabled: u.enabled,
                            })
                          }
                          className="h-8 text-xs"
                        >
                          <option value="ADMIN">ADMIN</option>
                          <option value="USER">USER</option>
                        </Select>
                      </td>
                      <td className="px-3 py-3">
                        <Switch
                          checked={u.enabled}
                          disabled={isSelf || updateMut.isPending}
                          onChange={(next) => updateMut.mutate({ id: u.id, role: u.role, enabled: next })}
                        />
                      </td>
                      <td className="px-3 py-3 text-xs text-slate-500">{fmtDateTime(u.createdAt)}</td>
                      <td className="px-4 py-3 text-right space-x-1">
                        <Button
                          variant="ghost"
                          size="xs"
                          onClick={() => {
                            setPwdUser(u);
                            setNextPassword('');
                          }}
                        >
                          <KeyRound className="h-3.5 w-3.5" />
                          Пароль
                        </Button>
                        {!isSelf && (
                          <Button
                            variant="ghost"
                            size="xs"
                            onClick={async () => {
                              const ok = await confirm({
                                title: 'Удалить пользователя',
                                message: `Удалить учётную запись «${u.username}»?`,
                                confirmText: 'Удалить',
                              });
                              if (ok) deleteMut.mutate(u.id);
                            }}
                          >
                            <Trash2 className="h-3.5 w-3.5 text-rose-500" />
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

      {/* Создание пользователя */}
      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Новый пользователь"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setCreateOpen(false)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={!newUsername.trim() || newPassword.length < 4}
              loading={createMut.isPending}
              onClick={() => createMut.mutate()}
            >
              Создать
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Логин *</label>
            <Input value={newUsername} onChange={(e) => setNewUsername(e.target.value)} placeholder="engineer1" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Пароль *</label>
            <Input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Роль</label>
            <Select value={newRole} onChange={(e) => setNewRole(e.target.value as 'ADMIN' | 'USER')}>
              <option value="USER">USER (скрипты, тесты, расписание, отчёты)</option>
              <option value="ADMIN">ADMIN (полный доступ к инфраструктуре и настройкам)</option>
            </Select>
          </div>
        </div>
      </Modal>

      {/* Смена пароля */}
      <Modal
        open={!!pwdUser}
        onClose={() => setPwdUser(null)}
        title={`Смена пароля — ${pwdUser?.username || ''}`}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setPwdUser(null)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={nextPassword.length < 4}
              loading={pwdMut.isPending}
              onClick={() => pwdMut.mutate()}
            >
              Сохранить пароль
            </Button>
          </>
        }
      >
        <div>
          <label className="block text-xs font-medium text-slate-700 mb-1">Новый пароль *</label>
          <Input
            type="password"
            value={nextPassword}
            onChange={(e) => setNextPassword(e.target.value)}
            placeholder="Минимум 4 символа"
          />
        </div>
      </Modal>
    </div>
  );
}
