import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { cn } from '@/lib/utils';
import { AlertCircle, Play, X } from 'lucide-react';

interface SuggestionItem {
  value: string;
  insertText: string;
  cursorBack?: number;
  kind: 'fn' | 'agg' | 'kw' | 'metric' | 'label' | 'var' | 'range';
  detail?: string;
}

const PROMQL_FUNCTIONS: SuggestionItem[] = [
  { value: 'rate()', insertText: 'rate()', cursorBack: 1, kind: 'fn', detail: 'Скорость роста счётчика за окно, /с' },
  { value: 'irate()', insertText: 'irate()', cursorBack: 1, kind: 'fn', detail: 'Мгновенная скорость по двум последним точкам' },
  { value: 'increase()', insertText: 'increase()', cursorBack: 1, kind: 'fn', detail: 'Прирост счётчика за окно' },
  { value: 'histogram_quantile(0.95, )', insertText: 'histogram_quantile(0.95, )', cursorBack: 1, kind: 'fn', detail: 'Перцентиль по гистограмме' },
  { value: 'histogram_count()', insertText: 'histogram_count()', cursorBack: 1, kind: 'fn', detail: 'Число наблюдений native histogram' },
  { value: 'histogram_sum()', insertText: 'histogram_sum()', cursorBack: 1, kind: 'fn', detail: 'Сумма наблюдений native histogram' },
  { value: 'avg_over_time()', insertText: 'avg_over_time()', cursorBack: 1, kind: 'fn', detail: 'Среднее gauge за окно' },
  { value: 'max_over_time()', insertText: 'max_over_time()', cursorBack: 1, kind: 'fn', detail: 'Максимум gauge за окно' },
  { value: 'min_over_time()', insertText: 'min_over_time()', cursorBack: 1, kind: 'fn', detail: 'Минимум gauge за окно' },
  { value: 'quantile_over_time(0.95, )', insertText: 'quantile_over_time(0.95, )', cursorBack: 1, kind: 'fn', detail: 'Квантиль gauge во времени' },
  { value: 'clamp_min(, 0)', insertText: 'clamp_min(, 0)', cursorBack: 4, kind: 'fn', detail: 'Ограничить снизу' },
  { value: 'clamp_max(, 100)', insertText: 'clamp_max(, 100)', cursorBack: 6, kind: 'fn', detail: 'Ограничить сверху' },
  { value: 'changes()', insertText: 'changes()', cursorBack: 1, kind: 'fn', detail: 'Число изменений значения за окно' },
  { value: 'deriv()', insertText: 'deriv()', cursorBack: 1, kind: 'fn', detail: 'Производная gauge по МНК' },
  { value: 'delta()', insertText: 'delta()', cursorBack: 1, kind: 'fn', detail: 'Разница gauge на краях окна' },
  { value: 'absent()', insertText: 'absent()', cursorBack: 1, kind: 'fn', detail: '1, если вектор пуст' },
  { value: 'vector(0)', insertText: 'vector(0)', cursorBack: 1, kind: 'fn', detail: 'Скаляр как мгновенный вектор' },
  { value: 'round()', insertText: 'round()', cursorBack: 1, kind: 'fn', detail: 'Округление значений серий' },
  { value: 'sum()', insertText: 'sum()', cursorBack: 1, kind: 'agg', detail: 'Сумма по сериям' },
  { value: 'sum by () ()', insertText: 'sum by () ()', cursorBack: 4, kind: 'agg', detail: 'Сумма в разрезе меток' },
  { value: 'avg()', insertText: 'avg()', cursorBack: 1, kind: 'agg', detail: 'Среднее по сериям' },
  { value: 'avg by () ()', insertText: 'avg by () ()', cursorBack: 4, kind: 'agg', detail: 'Среднее в разрезе меток' },
  { value: 'max()', insertText: 'max()', cursorBack: 1, kind: 'agg', detail: 'Максимум по сериям' },
  { value: 'max by () ()', insertText: 'max by () ()', cursorBack: 4, kind: 'agg', detail: 'Максимум в разрезе меток' },
  { value: 'min()', insertText: 'min()', cursorBack: 1, kind: 'agg', detail: 'Минимум по сериям' },
  { value: 'count()', insertText: 'count()', cursorBack: 1, kind: 'agg', detail: 'Количество серий' },
  { value: 'topk(5, )', insertText: 'topk(5, )', cursorBack: 1, kind: 'agg', detail: 'Топ K наибольших серий' },
  { value: 'bottomk(5, )', insertText: 'bottomk(5, )', cursorBack: 1, kind: 'agg', detail: 'Топ K наименьших серий' },
  { value: 'by ()', insertText: 'by ()', cursorBack: 1, kind: 'kw', detail: 'Группировка по меткам' },
  { value: 'without ()', insertText: 'without ()', cursorBack: 1, kind: 'kw', detail: 'Исключить метки из группировки' },
  { value: 'ignoring ()', insertText: 'ignoring ()', cursorBack: 1, kind: 'kw', detail: 'Игнорировать метки при бинарной операции' },
  { value: 'group_left ()', insertText: 'group_left ()', cursorBack: 1, kind: 'kw', detail: 'Many-to-one объединение меток' },
  { value: 'or', insertText: 'or ', kind: 'kw', detail: 'Объединение/фолбэк векторов' },
  { value: 'unless', insertText: 'unless ', kind: 'kw', detail: 'Исключение совпадающих серий' },
];

const BUILTIN_METRICS: { name: string; detail: string }[] = [
  { name: 'k6_http_req_duration_seconds', detail: 'k6: время HTTP-запроса (гистограмма)' },
  { name: 'k6_http_reqs_total', detail: 'k6: счётчик HTTP-запросов' },
  { name: 'k6_http_req_failed_rate', detail: 'k6: доля неуспешных HTTP-запросов' },
  { name: 'k6_iterations_total', detail: 'k6: завершённые итерации сценариев' },
  { name: 'k6_iteration_duration_seconds', detail: 'k6: длительность полной итерации' },
  { name: 'k6_dropped_iterations_total', detail: 'k6: пропущенные итерации (нехватка VU/CPU)' },
  { name: 'k6_vus', detail: 'k6: текущее число активных VU' },
  { name: 'k6_vus_max', detail: 'k6: выделенный пул VU' },
  { name: 'k6_checks_rate', detail: 'k6: доля успешных check()' },
  { name: 'k6_data_sent_bytes_total', detail: 'k6: отправленный трафик, байт' },
  { name: 'k6_data_received_bytes_total', detail: 'k6: полученный трафик, байт' },
  { name: 'k6_op_duration_seconds', detail: 'k6 helper: время операции op()' },
  { name: 'k6_op_count_total', detail: 'k6 helper: счётчик вызовов op()' },
  { name: 'k6_op_errors_total', detail: 'k6 helper: ошибки операций op()' },
  { name: 'node_cpu_seconds_total', detail: 'Host: время CPU по режимам (idle, user, system, iowait)' },
  { name: 'node_memory_MemAvailable_bytes', detail: 'Host: доступная оперативная память, байт' },
  { name: 'node_memory_MemTotal_bytes', detail: 'Host: общий объём RAM, байт' },
  { name: 'node_load1', detail: 'Host: Load Average за 1 минуту' },
  { name: 'node_load5', detail: 'Host: Load Average за 5 минут' },
  { name: 'node_filesystem_avail_bytes', detail: 'Host: свободное место на ФС, байт' },
  { name: 'node_filesystem_size_bytes', detail: 'Host: общий размер ФС, байт' },
  { name: 'node_disk_io_time_seconds_total', detail: 'Host: время занятости диска I/O' },
  { name: 'node_disk_read_bytes_total', detail: 'Host: чтение с диска, байт' },
  { name: 'node_disk_written_bytes_total', detail: 'Host: запись на диск, байт' },
  { name: 'node_network_receive_bytes_total', detail: 'Host: входящий сетевой трафик, байт' },
  { name: 'node_network_transmit_bytes_total', detail: 'Host: исходящий сетевой трафик, байт' },
  { name: 'node_netstat_Tcp_CurrEstab', detail: 'Host: активные TCP ESTABLISHED соединения' },
  { name: 'node_sockstat_TCP_tw', detail: 'Host: сокеты в состоянии TIME_WAIT' },
  { name: 'jvm_memory_used_bytes', detail: 'JVM: используемая память (heap/nonheap)' },
  { name: 'jvm_memory_max_bytes', detail: 'JVM: максимальный размер памяти' },
  { name: 'jvm_gc_pause_seconds_sum', detail: 'JVM: суммарное время пауз GC' },
  { name: 'jvm_gc_pause_seconds_count', detail: 'JVM: количество пауз GC' },
  { name: 'jvm_threads_live_threads', detail: 'JVM: живые потоки' },
  { name: 'hikaricp_connections_active', detail: 'DB Pool: активные соединения HikariCP' },
  { name: 'hikaricp_connections_max', detail: 'DB Pool: максимум соединений HikariCP' },
  { name: 'hikaricp_connections_pending', detail: 'DB Pool: потоки в очереди за соединением' },
  { name: 'http_server_requests_seconds_count', detail: 'Spring/Micrometer: счётчик HTTP-запросов' },
  { name: 'http_server_requests_seconds_sum', detail: 'Spring/Micrometer: суммарное время HTTP-запросов' },
  { name: 'process_cpu_usage', detail: 'Process: загрузка CPU процессом (0..1)' },
  { name: 'go_goroutines', detail: 'Go: количество горутин' },
  { name: 'up', detail: 'Prometheus: доступность таргета (1/0)' },
];

const BUILTIN_LABELS: { name: string; detail: string }[] = [
  { name: 'test_run_id', detail: 'ID запуска в k6-lab ({run_id})' },
  { name: 'scenario', detail: 'Имя сценария / операции k6' },
  { name: 'instance', detail: 'Хост:порт таргета Prometheus' },
  { name: 'job', detail: 'Имя scrape-job в Prometheus' },
  { name: 'name', detail: 'Имя запроса / URL-группы в k6' },
  { name: 'status', detail: 'HTTP статус-код ответа' },
  { name: 'method', detail: 'HTTP метод (GET, POST…)' },
  { name: 'expected_response', detail: 'Ожидаемый ответ k6 ("true" / "false")' },
  { name: 'mode', detail: 'Режим CPU (idle, system, user, iowait)' },
  { name: 'area', detail: 'Область памяти JVM (heap / nonheap)' },
  { name: 'mountpoint', detail: 'Точка монтирования ФС (/ , /var…)' },
  { name: 'fstype', detail: 'Тип файловой системы' },
  { name: 'device', detail: 'Устройство диска или сети' },
  { name: 'le', detail: 'Верхняя граница корзины гистограммы' },
  { name: 'phase', detail: 'Фаза нагрузки (warmup)' },
];

function checkBrackets(expr: string): string | null {
  const stack: string[] = [];
  const pairs: Record<string, string> = { ')': '(', '}': '{', ']': '[' };
  const expectedClose: Record<string, string> = { '(': ')', '{': '}', '[': ']' };
  let inSingle = false;
  let inDouble = false;
  for (let i = 0; i < expr.length; i++) {
    const ch = expr[i];
    const prev = i > 0 ? expr[i - 1] : '';
    if (ch === "'" && !inDouble && prev !== '\\') {
      inSingle = !inSingle;
      continue;
    }
    if (ch === '"' && !inSingle && prev !== '\\') {
      inDouble = !inDouble;
      continue;
    }
    if (inSingle || inDouble) continue;
    if (ch === '(' || ch === '{' || ch === '[') {
      stack.push(ch);
    } else if (ch === ')' || ch === '}' || ch === ']') {
      const top = stack.pop();
      if (top !== pairs[ch]) {
        return `Лишняя или непарная «${ch}»`;
      }
    }
  }
  if (inSingle || inDouble) return 'Незакрытая кавычка';
  if (stack.length > 0) {
    const last = stack[stack.length - 1];
    return `Не закрыта «${last}» → ожидается «${expectedClose[last]}»`;
  }
  return null;
}

interface CursorContext {
  mode: 'var' | 'range' | 'label' | 'expr';
  word: string;
  start: number;
  end: number;
  metricForLabel?: string;
}

function analyzeCursor(text: string, pos: number, allowMacros: boolean): CursorContext {
  const left = text.slice(0, pos);
  const right = text.slice(pos);

  // 1. $var
  const varMatch = left.match(/\$([A-Za-z0-9_]*)$/);
  if (varMatch) {
    const endMatch = right.match(/^[A-Za-z0-9_]*/);
    const tail = endMatch ? endMatch[0] : '';
    return {
      mode: 'var',
      word: varMatch[1],
      start: pos - varMatch[0].length,
      end: pos + tail.length,
    };
  }

  // 2. Внутри [...] (длительность окна)
  const lastOpenBracket = left.lastIndexOf('[');
  const lastCloseBracket = left.lastIndexOf(']');
  if (lastOpenBracket > lastCloseBracket) {
    const inside = left.slice(lastOpenBracket + 1);
    if (!inside.includes('\n')) {
      const endMatch = right.match(/^[A-Za-z0-9_{}]*/);
      const tail = endMatch ? endMatch[0] : '';
      return {
        mode: 'range',
        word: inside.trim(),
        start: lastOpenBracket + 1,
        end: pos + tail.length,
      };
    }
  }

  // 3. Внутри {...} — либо макрос {range}/{run_id}, либо имя метки
  const lastOpenBrace = left.lastIndexOf('{');
  const lastCloseBrace = left.lastIndexOf('}');
  if (lastOpenBrace > lastCloseBrace) {
    const insideBrace = left.slice(lastOpenBrace + 1);
    const beforeBrace = left.slice(0, lastOpenBrace);
    const metricMatch = beforeBrace.match(/([a-zA-Z_:][a-zA-Z0-9_:]*)\s*$/);
    // Если перед '{' нет имени метрики и разрешены макросы — подсказываем {range} / {run_id}
    if (!metricMatch && allowMacros && /^[a-z_]*$/i.test(insideBrace)) {
      const closeAhead = right.startsWith('}') ? 1 : 0;
      return {
        mode: 'range',
        word: insideBrace,
        start: lastOpenBrace,
        end: pos + closeAhead,
      };
    }
    // Проверяем, что мы не внутри кавычек значения метки label="value"
    const quotesCount = (insideBrace.match(/"/g) || []).length;
    if (quotesCount % 2 === 0) {
      const lblMatch = insideBrace.match(/([a-zA-Z_][a-zA-Z0-9_]*)?$/);
      const word = lblMatch?.[1] || '';
      const endMatch = right.match(/^[a-zA-Z0-9_]*/);
      const tail = endMatch ? endMatch[0] : '';
      return {
        mode: 'label',
        word,
        start: pos - word.length,
        end: pos + tail.length,
        metricForLabel: metricMatch?.[1],
      };
    }
  }

  // 4. Обычный идентификатор метрики или функции PromQL
  const idLeft = left.match(/[a-zA-Z_:][a-zA-Z0-9_:]*$/);
  const idRight = right.match(/^[a-zA-Z0-9_:]*/);
  const word = idLeft ? idLeft[0] : '';
  const tail = idLeft && idRight ? idRight[0] : '';
  return {
    mode: 'expr',
    word,
    start: pos - word.length,
    end: pos + tail.length,
  };
}

export interface PromqlEditorProps {
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  rows?: number;
  scriptId?: number | string | null;
  variables?: string[];
  varValues?: Record<string, string>;
  allowMacros?: boolean;
  extraMetrics?: string[];
  showToolbar?: boolean;
  showTestButton?: boolean;
  disabled?: boolean;
  className?: string;
  textareaRef?: (el: HTMLTextAreaElement | null) => void;
}

export function PromqlEditor({
  value,
  onChange,
  placeholder = 'sum by (scenario) (rate(k6_http_reqs_total[{range}]))',
  rows = 2,
  scriptId,
  variables = [],
  varValues,
  allowMacros = true,
  extraMetrics = [],
  showToolbar = true,
  showTestButton = true,
  disabled = false,
  className,
  textareaRef,
}: PromqlEditorProps) {
  const innerRef = useRef<HTMLTextAreaElement | null>(null);
  const [focused, setFocused] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [ctx, setCtx] = useState<CursorContext | null>(null);
  const [activeIdx, setActiveIdx] = useState(0);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<any | null>(null);

  const { data: promMetricsData } = useQuery({
    queryKey: ['prometheus-global-metrics'],
    queryFn: () => apiFetch('/api/v1/prometheus/metrics'),
    enabled: focused,
    staleTime: 60_000,
    retry: false,
  });

  const activeMetricForLabels = ctx?.mode === 'label' ? ctx.metricForLabel || '' : '';
  const { data: promLabelsData } = useQuery({
    queryKey: ['prometheus-labels', activeMetricForLabels],
    queryFn: () =>
      apiFetch(
        `/api/v1/prometheus/labels${activeMetricForLabels ? `?metric=${encodeURIComponent(activeMetricForLabels)}` : ''}`
      ),
    enabled: focused && ctx?.mode === 'label',
    staleTime: 60_000,
    retry: false,
  });

  const allMetrics = useMemo(() => {
    const map = new Map<string, string>();
    for (const bm of BUILTIN_METRICS) {
      map.set(bm.name, bm.detail);
    }
    const serverList: string[] = promMetricsData?.metrics || [];
    for (const m of serverList) {
      if (!map.has(m)) map.set(m, 'Prometheus метрика');
    }
    for (const m of extraMetrics) {
      if (m && !map.has(m)) map.set(m, 'Prometheus метрика');
    }
    return Array.from(map.entries()).map(([name, detail]) => ({ name, detail }));
  }, [promMetricsData?.metrics, extraMetrics]);

  const allLabels = useMemo(() => {
    const map = new Map<string, string>();
    for (const bl of BUILTIN_LABELS) {
      map.set(bl.name, bl.detail);
    }
    const serverLabels: string[] = promLabelsData?.labels || [];
    for (const l of serverLabels) {
      if (!map.has(l)) map.set(l, activeMetricForLabels ? `Метка ${activeMetricForLabels}` : 'Метка Prometheus');
    }
    return Array.from(map.entries()).map(([name, detail]) => ({ name, detail }));
  }, [promLabelsData?.labels, activeMetricForLabels]);

  const suggestions = useMemo<SuggestionItem[]>(() => {
    if (!focused || dismissed || !ctx) return [];
    const q = ctx.word.toLowerCase();

    if (ctx.mode === 'var') {
      return variables
        .filter((v) => !q || v.toLowerCase().includes(q))
        .slice(0, 16)
        .map((v) => ({
          value: `$${v}`,
          insertText: `$${v}`,
          kind: 'var',
          detail: varValues?.[v] ? `= ${varValues[v]}` : 'Переменная',
        }));
    }

    if (ctx.mode === 'range') {
      const baseRanges: SuggestionItem[] = allowMacros
        ? [
            { value: '{range}', insertText: '{range}', kind: 'range', detail: 'Авто-окно расчёта (напр. 60s)' },
            { value: '{run_id}', insertText: '{run_id}', kind: 'range', detail: 'ID текущего запуска' },
            { value: '15s', insertText: '15s', kind: 'range', detail: 'Окно 15 секунд' },
            { value: '30s', insertText: '30s', kind: 'range', detail: 'Окно 30 секунд' },
            { value: '1m', insertText: '1m', kind: 'range', detail: 'Окно 1 минута' },
            { value: '5m', insertText: '5m', kind: 'range', detail: 'Окно 5 минут' },
          ]
        : [
            { value: '30s', insertText: '30s', kind: 'range', detail: 'Окно 30 секунд' },
            { value: '1m', insertText: '1m', kind: 'range', detail: 'Окно 1 минута' },
            { value: '2m', insertText: '2m', kind: 'range', detail: 'Окно 2 минуты' },
            { value: '5m', insertText: '5m', kind: 'range', detail: 'Окно 5 минут' },
            { value: '15m', insertText: '15m', kind: 'range', detail: 'Окно 15 минут' },
          ];
      return baseRanges.filter((r) => !q || r.value.toLowerCase().includes(q.replace(/^\{/, '')));
    }

    if (ctx.mode === 'label') {
      const matched = allLabels.filter((l) => !q || l.name.toLowerCase().includes(q));
      matched.sort((a, b) => {
        const ap = q && a.name.toLowerCase().startsWith(q) ? 0 : 1;
        const bp = q && b.name.toLowerCase().startsWith(q) ? 0 : 1;
        return ap !== bp ? ap - bp : a.name.localeCompare(b.name);
      });
      return matched.slice(0, 18).map((l) => ({
        value: l.name,
        insertText: l.name === 'test_run_id' && allowMacros ? 'test_run_id="{run_id}"' : `${l.name}=""`,
        cursorBack: l.name === 'test_run_id' && allowMacros ? 0 : 1,
        kind: 'label',
        detail: l.detail,
      }));
    }

    // mode === 'expr': показываем подсказки при вводе >= 1 символа
    if (q.length < 1) return [];
    const fnMatches = PROMQL_FUNCTIONS.filter((f) => f.value.toLowerCase().includes(q));
    const metricMatches: SuggestionItem[] = [];
    const prefixMetrics: SuggestionItem[] = [];
    const subMetrics: SuggestionItem[] = [];
    for (const m of allMetrics) {
      const ml = m.name.toLowerCase();
      if (ml === q) continue;
      if (ml.startsWith(q)) {
        prefixMetrics.push({ value: m.name, insertText: m.name, kind: 'metric', detail: m.detail });
      } else if (ml.includes(q)) {
        subMetrics.push({ value: m.name, insertText: m.name, kind: 'metric', detail: m.detail });
      }
    }
    metricMatches.push(...prefixMetrics, ...subMetrics);

    const varItems: SuggestionItem[] = variables
      .filter((v) => v.toLowerCase().includes(q))
      .map((v) => ({
        value: `$${v}`,
        insertText: `$${v}`,
        kind: 'var',
        detail: varValues?.[v] ? `= ${varValues[v]}` : 'Переменная',
      }));

    // Сначала префиксные функции/агрегации, затем метрики по префиксу, затем остальные
    const fnPrefix = fnMatches.filter((f) => f.value.toLowerCase().startsWith(q));
    const fnSub = fnMatches.filter((f) => !f.value.toLowerCase().startsWith(q));
    return [...fnPrefix, ...prefixMetrics.slice(0, 12), ...varItems, ...fnSub, ...subMetrics.slice(0, 12)].slice(0, 20);
  }, [focused, dismissed, ctx, variables, varValues, allowMacros, allLabels, allMetrics]);

  useEffect(() => {
    setActiveIdx(0);
  }, [ctx?.word, ctx?.mode, suggestions.length]);

  const refreshCursor = (el: HTMLTextAreaElement) => {
    const pos = el.selectionStart ?? el.value.length;
    setCtx(analyzeCursor(el.value, pos, allowMacros));
  };

  const applySuggestion = (item: SuggestionItem) => {
    if (!ctx) return;
    const before = value.slice(0, ctx.start);
    const after = value.slice(ctx.end);
    const next = before + item.insertText + after;
    onChange(next);
    setDismissed(true);
    const nextCursor = before.length + item.insertText.length - (item.cursorBack || 0);
    requestAnimationFrame(() => {
      const el = innerRef.current;
      if (el) {
        el.focus();
        el.setSelectionRange(nextCursor, nextCursor);
        setDismissed(false);
        setCtx(analyzeCursor(next, nextCursor, allowMacros));
      }
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Ctrl+Space принудительно открывает подсказки
    if ((e.ctrlKey || e.metaKey) && e.code === 'Space') {
      e.preventDefault();
      setDismissed(false);
      const el = e.currentTarget;
      const pos = el.selectionStart ?? el.value.length;
      const c = analyzeCursor(el.value, pos, allowMacros);
      setCtx(c.word ? c : { ...c, word: 'k6_' });
      return;
    }

    if (suggestions.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActiveIdx((prev) => (prev + 1) % suggestions.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActiveIdx((prev) => (prev - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey)) {
        e.preventDefault();
        applySuggestion(suggestions[activeIdx] || suggestions[0]);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setDismissed(true);
        return;
      }
    }
  };

  const handleTestQuery = async () => {
    if (!value.trim()) return;
    setTesting(true);
    try {
      const res = await apiFetch('/api/v1/prometheus/test-query', {
        method: 'POST',
        body: {
          promql: value,
          scriptId: scriptId ? Number(scriptId) : null,
          rangeSec: 60,
          vars: varValues || undefined,
        },
      });
      setTestResult(res);
    } catch (err: any) {
      setTestResult({ ok: false, error: err?.message || 'Ошибка выполнения запроса' });
    } finally {
      setTesting(false);
    }
  };

  const bracketError = useMemo(() => (value.trim() ? checkBrackets(value) : null), [value]);

  const kindBadge = (kind: SuggestionItem['kind']) => {
    switch (kind) {
      case 'fn':
        return <span className="rounded bg-indigo-100 px-1.5 py-0.5 text-[10px] font-semibold text-indigo-700 shrink-0">fn</span>;
      case 'agg':
        return <span className="rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-semibold text-purple-700 shrink-0">agg</span>;
      case 'kw':
        return <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800 shrink-0">kw</span>;
      case 'label':
        return <span className="rounded bg-sky-100 px-1.5 py-0.5 text-[10px] font-semibold text-sky-700 shrink-0">label</span>;
      case 'var':
        return <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-700 shrink-0">$var</span>;
      case 'range':
        return <span className="rounded bg-slate-200 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700 shrink-0">range</span>;
      default:
        return <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-600 shrink-0">metric</span>;
    }
  };

  return (
    <div className={cn('space-y-1.5', className)}>
      <div className="relative">
        <textarea
          ref={(el) => {
            innerRef.current = el;
            textareaRef?.(el);
          }}
          rows={rows}
          disabled={disabled}
          value={value}
          spellCheck={false}
          placeholder={placeholder}
          onFocus={(e) => {
            setFocused(true);
            setDismissed(false);
            refreshCursor(e.currentTarget);
          }}
          onBlur={() => {
            setTimeout(() => setFocused(false), 180);
          }}
          onClick={(e) => {
            setDismissed(false);
            refreshCursor(e.currentTarget);
          }}
          onKeyUp={(e) => {
            if (!['ArrowDown', 'ArrowUp', 'Enter', 'Tab', 'Escape'].includes(e.key)) {
              refreshCursor(e.currentTarget);
            }
          }}
          onKeyDown={handleKeyDown}
          onChange={(e) => {
            const next = e.target.value;
            onChange(next);
            setDismissed(false);
            const pos = e.target.selectionStart ?? next.length;
            setCtx(analyzeCursor(next, pos, allowMacros));
          }}
          className={cn(
            'w-full rounded-lg border bg-white dark:bg-[#0b0f17] px-3 py-2 font-mono text-xs text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 transition-colors focus:outline-none focus:ring-2 resize-y',
            bracketError
              ? 'border-amber-400 focus:border-amber-500 focus:ring-amber-500/20'
              : 'border-slate-300 dark:border-[#323d56] focus:border-indigo-500 dark:focus:border-indigo-400 focus:ring-indigo-500/20',
            disabled && 'bg-slate-100 dark:bg-slate-900 opacity-60 cursor-not-allowed'
          )}
        />

        {/* Выпадающее меню автодополнения */}
        {suggestions.length > 0 && (
          <div className="absolute left-0 right-0 top-full z-30 mt-1 max-h-56 overflow-y-auto rounded-xl border border-slate-200 dark:border-[#2e3850] bg-white dark:bg-[#151a28] shadow-xl divide-y divide-slate-100 dark:divide-[#232b3e]">
            {suggestions.map((s, idx) => (
              <button
                key={`${s.kind}-${s.value}`}
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  applySuggestion(s);
                }}
                onMouseEnter={() => setActiveIdx(idx)}
                className={cn(
                  'flex w-full items-center justify-between gap-3 px-2.5 py-1.5 text-left text-xs transition-colors cursor-pointer',
                  idx === activeIdx
                    ? 'bg-indigo-50 dark:bg-indigo-500/20 text-indigo-900 dark:text-indigo-200'
                    : 'text-slate-800 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-[#1d2436]'
                )}
              >
                <div className="flex items-center gap-2 min-w-0">
                  {kindBadge(s.kind)}
                  <code className="font-mono font-medium truncate">{s.value}</code>
                </div>
                {s.detail && <span className="text-[11px] text-slate-400 truncate max-w-[48%]">{s.detail}</span>}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Лаконичная строка ошибки скобок и живой проверки без панели вставок и подсказок */}
      {showToolbar && !disabled && (bracketError || (showTestButton && value.trim())) && (
        <div className="flex flex-wrap items-center justify-end gap-2 text-[11px]">
          {bracketError && (
            <span className="inline-flex items-center gap-1 text-amber-700 bg-amber-50 border border-amber-200 rounded px-1.5 py-0.5 mr-auto">
              <AlertCircle className="h-3 w-3 shrink-0" />
              {bracketError}
            </span>
          )}
          {showTestButton && value.trim() && (
            <button
              type="button"
              disabled={testing}
              onClick={handleTestQuery}
              className="inline-flex items-center gap-1 rounded-md border border-indigo-200 bg-indigo-50/80 px-2 py-0.5 font-medium text-indigo-700 hover:bg-indigo-100 disabled:opacity-40 cursor-pointer"
            >
              <Play className="h-2.5 w-2.5 fill-current" />
              {testing ? 'Запрос…' : 'Проверить PromQL'}
            </button>
          )}
        </div>
      )}

      {/* Результат живой проверки PromQL */}
      {testResult && (
        <div
          className={cn(
            'rounded-lg border px-3 py-2 text-xs space-y-1',
            testResult.ok ? 'border-emerald-200 bg-emerald-50/70 text-emerald-950' : 'border-rose-200 bg-rose-50 text-rose-900'
          )}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="font-semibold">
              {testResult.ok ? `Prometheus ответил: серий ${testResult.count ?? 0}` : `Ошибка PromQL: ${testResult.error}`}
            </span>
            <button
              type="button"
              onClick={() => setTestResult(null)}
              className="rounded p-0.5 text-slate-400 hover:text-slate-700 cursor-pointer"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
          {testResult.query && testResult.query !== value && (
            <div className="text-[11px] text-slate-600 font-mono break-all">Запрос: {testResult.query}</div>
          )}
          {testResult.ok && Array.isArray(testResult.samples) && testResult.samples.length > 0 && (
            <div className="max-h-28 overflow-y-auto divide-y divide-emerald-200/60 pt-1">
              {testResult.samples.slice(0, 8).map((s: any, idx: number) => {
                const lbls = Object.entries(s.labels || {})
                  .map(([k, v]) => `${k}="${v}"`)
                  .join(', ');
                return (
                  <div key={idx} className="flex items-center justify-between gap-3 py-0.5 font-mono text-[11px]">
                    <span className="truncate text-slate-700">{lbls ? `{${lbls}}` : '{scalar}'}</span>
                    <span className="font-bold text-emerald-800 shrink-0">
                      {typeof s.value === 'number' ? Number(s.value.toFixed(4)) : 'NaN'}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
