// Локальная проверка: k6 run k6-helpers/test/helper-smoke.js  (из корня проекта)
// Ожидается: op_count=8, op_errors=2 (проваленный check и fail()), op_duration заполнен, pacing ~200 мс.
import { fail } from 'k6';
import { op, check } from '../src/main/resources/k6/admin.js';

export const options = {
    vus: 1,
    iterations: 3,
    thresholds: { op_count: ['count==8'], op_errors: ['count==2'] },
};

export function good() {
    op(() => {
        check(1, { 'always ok': (v) => v === 1 });
    });
}

export function badCheck() {
    op(() => {
        check(1, { 'always bad': (v) => v === 2 });
    });
}

export default function () {
    good();
    if (__ITER === 0) {
        badCheck();
    } else if (__ITER === 1) {
        try {
            op(() => fail('boom'));
        } catch (e) { /* ожидаемо */ }
        good();
        return;
    }
    good();
}
