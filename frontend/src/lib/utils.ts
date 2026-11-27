import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function fmtDateTime(val?: string | number | null): string {
  if (!val) return '—';
  const d = new Date(val);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function fmtShortDate(val?: string | number | null): string {
  if (!val) return '—';
  const d = new Date(val);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function fmtClockTime(val?: string | number | null): string {
  if (!val) return '—';
  const d = new Date(val);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleTimeString('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function fmtDayHeading(val?: string | number | null): { key: string; label: string } {
  if (!val) return { key: 'unknown', label: 'Без даты' };
  const d = new Date(val);
  if (Number.isNaN(d.getTime())) return { key: 'unknown', label: 'Без даты' };
  const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const now = new Date();
  const todayKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  const yest = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  const yestKey = `${yest.getFullYear()}-${String(yest.getMonth() + 1).padStart(2, '0')}-${String(yest.getDate()).padStart(2, '0')}`;

  const full = d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: d.getFullYear() !== now.getFullYear() ? 'numeric' : undefined });
  if (key === todayKey) return { key, label: `Сегодня, ${full}` };
  if (key === yestKey) return { key, label: `Вчера, ${full}` };
  return { key, label: full };
}

export function conclusionVariant(conclusion?: string | null): BadgeVariant {
  if (!conclusion) return 'default';
  const c = conclusion.trim().toLowerCase();
  if (c === 'pass' || c.includes('выдерживается') || c.includes('успеш')) return 'success';
  if (c === 'fail' || c.includes('деградац') || c.includes('провал') || c.includes('ошибк')) return 'danger';
  if (c.includes('недостовер')) return 'neutral';
  if (c.includes('нарушен') || c.includes('sla') || c.includes('превыш')) return 'warning';
  return 'info';
}

export function fmtDurationSec(sec?: number | null): string {
  if (sec == null || sec < 0 || Number.isNaN(sec)) return '—';
  const s = Math.round(sec);
  if (s < 60) return `${s} с`;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rem = s % 60;
  if (h > 0) return m > 0 ? `${h} ч ${m} мин` : `${h} ч`;
  return rem > 0 ? `${m} мин ${rem} с` : `${m} мин`;
}

export function fmtBytes(bytes?: number | null): string {
  if (bytes == null || Number.isNaN(bytes)) return '—';
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} МБ`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} ГБ`;
}

export function parseDurationToSec(dur?: string | null): number {
  if (!dur) return 0;
  const s = String(dur).trim();
  const re = /(\d+(?:\.\d+)?)(ms|s|m|h)/gi;
  let total = 0;
  let matched = false;
  let m: RegExpExecArray | null;
  while ((m = re.exec(s)) !== null) {
    matched = true;
    const n = parseFloat(m[1]);
    const u = m[2].toLowerCase();
    if (u === 'ms') total += n / 1000;
    else if (u === 's') total += n;
    else if (u === 'm') total += n * 60;
    else if (u === 'h') total += n * 3600;
  }
  if (!matched && /^\d+$/.test(s)) return parseInt(s, 10);
  return total;
}

export type BadgeVariant = 'default' | 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'purple';

export function runStatusMeta(status?: string, thresholdsFailed?: boolean): { label: string; variant: BadgeVariant; active: boolean } {
  switch (status) {
    case 'QUEUED':
      return { label: 'В очереди', variant: 'info', active: true };
    case 'PREPARING':
      return { label: 'Подготовка', variant: 'info', active: true };
    case 'RUNNING':
      return { label: 'Выполняется', variant: 'success', active: true };
    case 'STOPPING':
      return { label: 'Останавливается', variant: 'warning', active: true };
    case 'FINISHED':
    case 'SUCCEEDED':
      return thresholdsFailed
        ? { label: 'Пороги не пройдены', variant: 'warning', active: false }
        : { label: 'Завершён', variant: 'success', active: false };
    case 'STOPPED':
    case 'CANCELLED':
    case 'ABORTED':
      return { label: 'Прерван', variant: 'default', active: false };
    case 'FAILED':
      return { label: 'Ошибка', variant: 'danger', active: false };
    default:
      return { label: status || '—', variant: 'default', active: false };
  }
}

export function genStatusMeta(status?: string, maintenance?: boolean, busy?: boolean): { label: string; variant: BadgeVariant } {
  if (maintenance) return { label: 'Обслуживание', variant: 'default' };
  if (busy) return { label: 'Занят', variant: 'info' };
  switch (status) {
    case 'ONLINE':
      return { label: 'В сети', variant: 'success' };
    case 'OFFLINE':
      return { label: 'Не в сети', variant: 'warning' };
    case 'INSTALLING':
    case 'UPDATING':
      return { label: 'Установка…', variant: 'info' };
    case 'ERROR':
      return { label: 'Ошибка', variant: 'danger' };
    default:
      return { label: status || '—', variant: 'default' };
  }
}

export function deployOpLabel(op?: string): string {
  switch (op) {
    case 'PREFLIGHT': return 'Проверка';
    case 'INSTALL': return 'Установка';
    case 'UPDATE': return 'Обновление';
    case 'RESTART': return 'Перезапуск';
    case 'UNINSTALL': return 'Удаление';
    case 'REDEPLOY': return 'Редеплой';
    case 'PROFILE': return 'Профилирование';
    case 'TUNE_APPLY': return 'Тюнинг';
    case 'TUNE_ROLLBACK': return 'Откат тюнинга';
    default: return op || '—';
  }
}

export function deployStatusMeta(status?: string): { label: string; variant: BadgeVariant } {
  switch (status) {
    case 'SUCCEEDED': return { label: 'Успешно', variant: 'success' };
    case 'FAILED': return { label: 'Ошибка', variant: 'danger' };
    case 'RUNNING': return { label: 'Выполняется', variant: 'info' };
    case 'PENDING': return { label: 'Ожидание', variant: 'default' };
    case 'WAITING_HOST_KEY': return { label: 'Ключ хоста', variant: 'warning' };
    case 'ROLLED_BACK': return { label: 'Откачено', variant: 'warning' };
    default: return { label: status || '—', variant: 'default' };
  }
}

const NATURAL_COLLATOR = new Intl.Collator('ru', { numeric: true, sensitivity: 'base' });

/** Умное сравнение строк с учётом чисел и кириллицы/латиницы без регистра («20%» < «100%», «Шаг 2» < «Шаг 10»). */
export function naturalCompare(a?: string | null, b?: string | null): number {
  return NATURAL_COLLATOR.compare(a ?? '', b ?? '');
}

/** Сортирует профили: сначала без папки по естественному порядку имени, затем по папкам и имени внутри папки. */
export function sortProfilesSmart<T extends { name?: string | null; folder?: string | null }>(list: T[]): T[] {
  return [...list].sort((a, b) => {
    const fa = (a.folder || '').trim();
    const fb = (b.folder || '').trim();
    if (Boolean(fa) !== Boolean(fb)) {
      return fa ? 1 : -1;
    }
    const fc = naturalCompare(fa, fb);
    if (fc !== 0) return fc;
    return naturalCompare(a.name, b.name);
  });
}

/** Группирует отсортированные профили по папкам (корень folder='' идёт первым). */
export function groupProfilesByFolder<T extends { name?: string | null; folder?: string | null }>(
  list: T[]
): { folder: string; items: T[] }[] {
  const sorted = sortProfilesSmart(list);
  const map = new Map<string, T[]>();
  for (const item of sorted) {
    const f = (item.folder || '').trim();
    const bucket = map.get(f);
    if (bucket) bucket.push(item);
    else map.set(f, [item]);
  }
  return Array.from(map.entries()).map(([folder, items]) => ({ folder, items }));
}
