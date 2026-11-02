// admin.js - helper-модуль k6-lab. Кладётся рядом со скриптом: __admin/admin.js
// Импорт:  import { op } from './__admin/admin.js';
//
// Одна операция = одна экспортируемая функция:
//   export function createPayment() { op(() => { ... }); }
//
// op(fn):
//   * пишет длительность fn (без pacing) в Trend  op_duration (секунды при выгрузке в Prometheus);
//   * неуспех = исключение, явный fail() или проваленный check (из этого модуля) внутри fn -> Rate op_failed;
//   * дополнительно ведёт Counter op_count / op_errors: Rate нельзя корректно агрегировать между
//     генераторами, а счётчики можно (процент ошибок = sum(op_errors) / sum(op_count));
//   * если задан __ENV.PACING_MS, досыпает до конца периода (если fn уже дольше pacing - ничего не делает).
import { Trend, Rate, Counter } from 'k6/metrics';
import { sleep, check as k6check } from 'k6';

export const opDuration = new Trend('op_duration', true);
export const opFailed = new Rate('op_failed');
export const opCount = new Counter('op_count');
export const opErrors = new Counter('op_errors');

let failedChecks = 0;

// k6 не позволяет читать значения метрик из скрипта (в том числе счётчик checks), а обычный
// check из 'k6' прозрачно не перехватить (импорт - неизменяемая привязка). Поэтому внутри op()
// используйте check из этого модуля: он полностью совместим по сигнатуре и считает провалы.
// Исключения и явный fail() ловятся всегда.
export function check(val, sets, tags) {
    const ok = tags === undefined ? k6check(val, sets) : k6check(val, sets, tags);
    if (!ok) {
        failedChecks++;
    }
    return ok;
}

function pacingMs() {
    const raw = __ENV.PACING_MS;
    if (raw === undefined || raw === '') {
        return 0;
    }
    const v = parseInt(raw, 10);
    return isNaN(v) || v < 0 ? 0 : v;
}

export function op(fn) {
    const pacing = pacingMs();
    const checksBefore = failedChecks;
    const started = Date.now();
    let failed = false;
    let error;
    try {
        fn();
    } catch (e) {
        failed = true;
        error = e;
    }
    const elapsed = Date.now() - started;
    if (failedChecks > checksBefore) {
        failed = true;
    }

    opDuration.add(elapsed);
    opFailed.add(failed);
    opCount.add(1);
    if (failed) {
        opErrors.add(1);
    }

    if (pacing > 0 && elapsed < pacing) {
        sleep((pacing - elapsed) / 1000);
    }

    if (error !== undefined) {
        // исключение не глотаем: k6 учтёт его в iteration-ошибках и покажет в логе
        throw error;
    }
}
