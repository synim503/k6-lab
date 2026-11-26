import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Edit2, KeyRound, Plus, Trash2 } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { fmtDateTime } from '@/lib/utils';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { Button, Card, EmptyState, Input, Modal, Spinner, Textarea } from '@/components/ui/primitives';

interface SecretItem {
  id: number;
  name: string;
  description?: string | null;
  updatedAt?: string | null;
}

export function SecretsPage() {
  const qc = useQueryClient();
  const confirm = useConfirm();

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SecretItem | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [value, setValue] = useState('');

  const { data, isLoading } = useQuery<{ items: SecretItem[] }>({
    queryKey: ['secrets'],
    queryFn: () => apiFetch('/api/v1/secrets'),
  });

  const saveMut = useMutation({
    mutationFn: () =>
      apiFetch('/api/v1/secrets', {
        method: 'POST',
        body: { name: name.trim(), description: description.trim(), value },
      }),
    onSuccess: () => {
      toast.success('Секрет сохранён');
      setModalOpen(false);
      qc.invalidateQueries({ queryKey: ['secrets'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/secrets/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Секрет удалён');
      qc.invalidateQueries({ queryKey: ['secrets'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const openCreate = () => {
    setEditing(null);
    setName('');
    setDescription('');
    setValue('');
    setModalOpen(true);
  };

  const openEdit = (s: SecretItem) => {
    setEditing(s);
    setName(s.name);
    setDescription(s.description || '');
    setValue('');
    setModalOpen(true);
  };

  if (isLoading && !data) return <Spinner label="Загрузка секретов…" />;

  const items = data?.items || [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Хранилище секретов</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Зашифрованные токены, пароли БД и API-ключи для подстановки в переменные окружения k6
          </p>
        </div>
        <Button size="sm" onClick={openCreate}>
          <Plus className="h-4 w-4" />
          Добавить секрет
        </Button>
      </div>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState
            icon={<KeyRound className="h-6 w-6" />}
            title="Секретов пока нет"
            description="Добавьте токены и пароли, чтобы безопасно привязывать их к переменным окружения в профилях нагрузки."
            action={
              <Button size="sm" onClick={openCreate}>
                <Plus className="h-4 w-4" />
                Создать секрет
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">Имя</th>
                  <th className="px-3 py-2.5 text-left font-medium">Описание</th>
                  <th className="px-3 py-2.5 text-left font-medium">Обновлён</th>
                  <th className="px-4 py-2.5 text-right font-medium">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((s) => (
                  <tr key={s.id} className="hover:bg-slate-50/70">
                    <td className="px-4 py-3 font-mono font-semibold text-slate-900">{s.name}</td>
                    <td className="px-3 py-3 text-xs text-slate-600">{s.description || '—'}</td>
                    <td className="px-3 py-3 text-xs text-slate-500">{fmtDateTime(s.updatedAt)}</td>
                    <td className="px-4 py-3 text-right space-x-1">
                      <Button variant="ghost" size="xs" onClick={() => openEdit(s)}>
                        <Edit2 className="h-3.5 w-3.5" />
                        Обновить
                      </Button>
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={async () => {
                          const ok = await confirm({
                            title: 'Удалить секрет',
                            message: `Удалить секрет «${s.name}»? Значение восстановить нельзя.`,
                            confirmText: 'Удалить',
                          });
                          if (ok) deleteMut.mutate(s.id);
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

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? `Обновить секрет «${editing.name}»` : 'Новый секрет'}
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setModalOpen(false)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={!name.trim() || (!editing && !value)}
              loading={saveMut.isPending}
              onClick={() => saveMut.mutate()}
            >
              Сохранить
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Имя секрета *</label>
            <Input
              value={name}
              disabled={!!editing}
              onChange={(e) => setName(e.target.value)}
              placeholder="API_BEARER_TOKEN"
              className="font-mono"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Описание</label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Токен авторизации тестового стенда"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">
              {editing ? 'Новое значение секрета *' : 'Значение секрета *'}
            </label>
            <Textarea
              rows={3}
              className="font-mono text-xs"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder="Секретное значение (хранится в AES-GCM)"
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}
