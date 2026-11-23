(function () {
    'use strict';

    // ---- описание полей сценария k6 (подписи, типы, подсказки)
    var F = {
        vus: {label: 'VU (всего)', type: 'int', ph: '10', hint: 'Сколько виртуальных пользователей работают одновременно (всего, на все генераторы)'},
        duration: {label: 'Длительность', type: 'dur', ph: '5m', hint: 'Сколько длится сценарий: 30s, 5m, 1h'},
        iterations: {label: 'Итераций', type: 'int', ph: '100', hint: 'Сколько итераций выполнить (в per-vu — на каждого VU, в shared — всего)'},
        maxDuration: {label: 'Макс. длительность', type: 'dur', ph: '10m', hint: 'Если итерации не закончились за это время, k6 остановит сценарий'},
        startVUs: {label: 'Стартовые VU', type: 'int', ph: '0', hint: 'Сколько VU работает в момент старта'},
        gracefulRampDown: {label: 'Плавное снижение VU', type: 'dur', ph: '30s', hint: 'Сколько ждать завершения итерации при уменьшении числа VU'},
        rate: {label: 'Интенсивность', type: 'num', ph: '50', hint: 'Сколько итераций запускать за один «Интервал» (всего, на все генераторы)'},
        timeUnit: {label: 'Интервал', type: 'dur', ph: '1s', hint: 'Период, к которому относится интенсивность (1s — «в секунду»)'},
        startRate: {label: 'Начальная интенсивность', type: 'num', ph: '0', hint: 'Итераций за интервал в момент старта'},
        preAllocatedVUs: {label: 'preAllocatedVUs / ген.', type: 'int', ph: '20', hint: 'Сколько VU подготовить заранее на ОДНОМ генераторе. При запуске умножается на число генераторов'},
        maxVUs: {label: 'maxVUs / ген.', type: 'int', ph: '100', hint: 'Максимум VU на ОДНОМ генераторе, до которого k6 может добавлять, если не успевает'},
        startTime: {label: 'Старт через', type: 'dur', ph: '0s', hint: 'Задержка старта сценария относительно начала теста'},
        gracefulStop: {label: 'Ожидание завершения', type: 'dur', ph: '30s', hint: 'Сколько ждать, пока текущие итерации закончатся после остановки'},
        stages: {label: 'Стадии нагрузки', type: 'stages'},
        warmup: {label: 'Прогрев', type: 'warmup'},
        env: {label: 'Переменные окружения сценария', type: 'kv', ph: ['ИМЯ', 'значение']},
        tags: {label: 'Теги метрик сценария', type: 'kv', ph: ['тег', 'значение']}
    };

    var ADV_KEYS = ['startTime', 'gracefulStop', 'gracefulRampDown', 'env', 'tags'];
    var TAIL = ['startTime', 'gracefulStop', 'env', 'tags'];

    var EX = {
        'constant-arrival-rate': {
            real: 'constant-arrival-rate', short: 'Постоянная интенсивность',
            desc: 'k6 запускает фиксированное число итераций за интервал независимо от времени ответа.',
            main: ['warmup', 'rate', 'timeUnit', 'duration', 'preAllocatedVUs', 'maxVUs'],
            adv: TAIL,
            fields: ['warmup', 'rate', 'timeUnit', 'duration', 'preAllocatedVUs', 'maxVUs'].concat(TAIL),
            def: {rate: 50, timeUnit: '1s', duration: '5m', preAllocatedVUs: 20, maxVUs: 100}
        },
        'ramping-arrival-rate': {
            real: 'ramping-arrival-rate', short: 'Интенсивность по стадиям',
            desc: 'Интенсивность плавно меняется по заданным стадиям (ступеньки, пики, спады).',
            main: ['startRate', 'timeUnit', 'preAllocatedVUs', 'maxVUs', 'stages'],
            adv: TAIL,
            fields: ['startRate', 'timeUnit', 'stages', 'preAllocatedVUs', 'maxVUs'].concat(TAIL),
            def: {startRate: 0, timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 100, stages: [{duration: '1m', target: 50}, {duration: '5m', target: 50}]}
        },
        'constant-vus': {
            real: 'constant-vus', short: 'Постоянное число VU',
            desc: 'Фиксированное число VU работает заданное время, каждый VU повторяет итерации без пауз.',
            main: ['warmup', 'vus', 'duration'],
            adv: TAIL,
            fields: ['warmup', 'vus', 'duration'].concat(TAIL),
            def: {vus: 10, duration: '5m'}
        },
        'ramping-vus': {
            real: 'ramping-vus', short: 'VU по стадиям',
            desc: 'Число VU меняется по стадиям: на каждой стадии задаётся длительность и целевое число VU.',
            main: ['startVUs', 'stages'],
            adv: ['gracefulRampDown'].concat(TAIL),
            fields: ['startVUs', 'stages', 'gracefulRampDown'].concat(TAIL),
            def: {startVUs: 0, stages: [{duration: '1m', target: 20}, {duration: '5m', target: 20}]}
        },
        'per-vu-iterations': {
            real: 'per-vu-iterations', short: 'Итерации на VU',
            desc: 'Каждый VU выполняет заданное число итераций; сценарий завершается, когда все закончили.',
            main: ['vus', 'iterations', 'maxDuration'],
            adv: TAIL,
            fields: ['vus', 'iterations', 'maxDuration'].concat(TAIL),
            def: {vus: 10, iterations: 20, maxDuration: '10m'}
        },
        'shared-iterations': {
            real: 'shared-iterations', short: 'Общее число итераций',
            desc: 'Заданное число итераций делится между VU: кто свободен, тот и выполняет.',
            main: ['vus', 'iterations', 'maxDuration'],
            adv: TAIL,
            fields: ['vus', 'iterations', 'maxDuration'].concat(TAIL),
            def: {vus: 10, iterations: 200, maxDuration: '10m'}
        },
        'externally-controlled': {
            real: 'externally-controlled', short: 'Через REST API k6',
            desc: 'Число VU меняется во время теста через REST API k6 (пауза, продолжение, масштабирование).',
            main: ['vus', 'maxVUs', 'duration'],
            adv: TAIL,
            fields: ['vus', 'maxVUs', 'duration'].concat(TAIL),
            def: {vus: 10, maxVUs: 50, duration: '10m'}
        }
    };

    var COLORS = ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];
    var STAGE_PRESETS = [
        {label: 'Поиск максимума: 5 ступеней скачком (0s)', fn: function (base) {
            var b = Math.max(1, base || 100), out = [];
            [1, 1.2, 1.4, 1.6, 1.8].forEach(function (k) {
                var t = Math.round(b * k);
                out.push({duration: '0s', target: t});
                out.push({duration: '5m', target: t});
            });
            return out;
        }},
        {label: 'Поиск максимума: 5 ступеней плавно (30s)', fn: function (base) {
            var b = Math.max(1, base || 40), out = [];
            [1, 1.2, 1.4, 1.6, 1.8].forEach(function (k, idx) {
                var t = Math.round(b * k);
                out.push({duration: idx === 0 ? '1m' : '30s', target: t});
                out.push({duration: '3m', target: t});
            });
            return out;
        }},
        {label: '3 ступени (100 / 120 / 140%)', fn: function (base) {
            var b = Math.max(1, base || 50);
            return [
                {duration: '1m', target: b}, {duration: '3m', target: b},
                {duration: '30s', target: Math.round(b * 1.2)}, {duration: '3m', target: Math.round(b * 1.2)},
                {duration: '30s', target: Math.round(b * 1.4)}, {duration: '3m', target: Math.round(b * 1.4)}
            ];
        }},
        {label: 'Разгон 1m + полка 5m', fn: function (base) { var b = Math.max(1, base || 50); return [{duration: '1m', target: b}, {duration: '5m', target: b}]; }},
        {label: 'Пиковый всплеск (Spike)', fn: function (base) {
            var b = Math.max(1, base || 30);
            return [
                {duration: '1m', target: b}, {duration: '2m', target: b},
                {duration: '30s', target: b * 3}, {duration: '2m', target: b * 3},
                {duration: '30s', target: b}, {duration: '2m', target: b}
            ];
        }}
    ];

    var body = document.getElementById('ops-body');
    if (!body) { return; }

    var gwOnEl = document.getElementById('gw-on');
    var gwDEl = document.getElementById('gw-d');
    var gwBox = document.getElementById('gw-box');
    var isRamp = function (ex) { return ex.indexOf('ramping') === 0; };
    var isRateEx = function (ex) { return ex.indexOf('arrival') >= 0; };

    var scenarioClipboard = null;
    try { scenarioClipboard = JSON.parse(sessionStorage.getItem('k6lab-scenario') || 'null'); } catch (e) { scenarioClipboard = null; }

    // ==================== Utilities ====================
    function esc(v) {
        return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
    }

    function calc(text) {
        var src = String(text == null ? '' : text).replace(/,/g, '.').replace(/[x×]/gi, '*').replace(/÷/g, '/').replace(/\s+/g, '');
        if (src === '') { return NaN; }
        if (!/^[0-9.+\-*\/()]+$/.test(src)) { return NaN; }
        var i = 0;
        function num() {
            if (src[i] === '(') { i++; var v = add(); if (src[i] !== ')') { throw 0; } i++; return v; }
            var m = /^[0-9]*\.?[0-9]+/.exec(src.slice(i));
            if (!m) { throw 0; }
            i += m[0].length;
            return parseFloat(m[0]);
        }
        function unary() {
            if (src[i] === '-') { i++; return -unary(); }
            if (src[i] === '+') { i++; return unary(); }
            return num();
        }
        function mul() {
            var v = unary();
            while (src[i] === '*' || src[i] === '/') { var op = src[i++]; var r = unary(); v = op === '*' ? v * r : v / r; }
            return v;
        }
        function add() {
            var v = mul();
            while (src[i] === '+' || src[i] === '-') { var op = src[i++]; var r = mul(); v = op === '+' ? v + r : v - r; }
            return v;
        }
        try { var v = add(); return i === src.length && isFinite(v) ? v : NaN; } catch (e) { return NaN; }
    }

    function calcOr0(text) { var v = calc(text); return isNaN(v) ? 0 : v; }

    function durSec(text) {
        var s = String(text == null ? '' : text).trim();
        if (!s) { return null; }
        var re = /([0-9]*\.?[0-9]+)\s*(ms|s|m|h)/g;
        var m, sum = 0, end = 0;
        while ((m = re.exec(s)) !== null) {
            if (s.slice(end, m.index).trim()) { return null; }
            sum += parseFloat(m[1]) * ({ms: 0.001, s: 1, m: 60, h: 3600})[m[2]];
            end = re.lastIndex;
        }
        return end === 0 || s.slice(end).trim() ? null : sum;
    }

    function durText(sec) {
        if (!sec || sec <= 0) { return '0 с'; }
        var t = Math.round(sec), h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
        return [h ? h + ' ч' : '', m ? m + ' мин' : '', s || (!h && !m) ? s + ' с' : ''].filter(Boolean).join(' ');
    }

    function fmtNum(v) {
        if (!isFinite(v)) { return '0'; }
        if (Math.abs(v) >= 100) { return String(Math.round(v)); }
        if (Math.abs(v) >= 10) { return String(Math.round(v * 10) / 10); }
        return String(Math.round(v * 100) / 100);
    }

    function detailOf(row) { return row.querySelector('.op-detail'); }
    function warmOf(row) { return parseInt(row.dataset.warm, 10) || 0; }
    function gwDur() { return gwDEl && gwDEl.value.trim() ? gwDEl.value.trim() : '2m'; }
    function gwActive() { return !!(gwOnEl && gwOnEl.checked); }
    function warmStage(base) { return {duration: gwDur(), target: Math.max(1, Math.round((Number(base) || 0) * 0.3))}; }

    function readStages(container) {
        return Array.prototype.slice.call(container.querySelectorAll('.op-stage')).map(function (r) {
            return {duration: r.querySelector('.op-st-d').value.trim(), target: calcOr0(r.querySelector('.op-st-t').value)};
        }).filter(function (x) { return x.duration !== ''; });
    }

    function flash(row, text) {
        var st = row.querySelector('.op-state');
        if (!st) { return; }
        st.textContent = text;
        clearTimeout(row._t);
        row._t = setTimeout(function () { refreshRow(row); }, 2200);
    }

    // ==================== Step Builder <-> k6 Stages Conversion ====================
    function stepUnitShort(exKey, timeUnit) {
        if (!isRateEx(exKey)) { return 'VU'; }
        var tu = String(timeUnit || '1s').trim();
        return (!tu || tu === '1s') ? 'оп/с' : ('оп/' + tu);
    }

    function parseStepsFromStages(stages, startVal, wn) {
        var list = stages || [];
        var warmN = Math.min(wn || 0, list.length);
        var warm = warmN > 0 ? {duration: list[0].duration || gwDur(), target: Number(list[0].target) || 0} : null;
        var main = list.slice(warmN);
        var prev = warm ? warm.target : (Number(startVal) || 0);
        var steps = [];
        var exact = true;
        var i = 0;
        while (i < main.length) {
            var s = main[i];
            var sd = durSec(s.duration);
            var st = Number(s.target) || 0;
            if (sd !== null && sd > 0 && st > 0 && Math.abs(st - prev) < 1e-9) {
                steps.push({target: st, hold: s.duration, ramp: '0s'});
                prev = st;
                i += 1;
                continue;
            }
            if (i + 1 < main.length) {
                var n = main[i + 1];
                var nd = durSec(n.duration);
                var nt = Number(n.target) || 0;
                if (nd !== null && nd > 0 && nt > 0 && Math.abs(nt - st) < 1e-9) {
                    steps.push({target: nt, hold: n.duration, ramp: s.duration || '0s'});
                    prev = nt;
                    i += 2;
                    continue;
                }
            }
            exact = false;
            if (sd !== null && sd > 0 && st > 0) {
                steps.push({target: st, hold: s.duration, ramp: '0s'});
            }
            prev = st;
            i += 1;
        }
        return {exact: exact && steps.length > 0, warm: warm, steps: steps};
    }

    function stepsToStages(warm, steps) {
        var out = [];
        if (warm) {
            out.push({duration: warm.duration || gwDur(), target: Number(warm.target) || 0});
        }
        (steps || []).forEach(function (st) {
            var hold = String(st.hold || '').trim();
            if (!hold) { return; }
            var ramp = String(st.ramp || '').trim() || '0s';
            var tg = Number(st.target) || 0;
            out.push({duration: ramp, target: tg});
            out.push({duration: hold, target: tg});
        });
        return out;
    }

    function stepRowHtml(tg, hold, ramp, unitShort) {
        return '<div class="input-group input-group-sm op-step">' +
            '<span class="input-group-text op-stage-num">#</span>' +
            '<span class="input-group-text">Уровень</span>' +
            '<input class="form-control op-stp-t op-calc" style="min-width:74px" type="text" inputmode="decimal" autocomplete="off"' +
                ' title="Нагрузка на ступени. Можно выражение: 150*1.2, 95+95" value="' + esc(tg) + '">' +
            '<span class="input-group-text op-stp-unit">' + esc(unitShort) + '</span>' +
            '<span class="input-group-text">Полка</span>' +
            '<input class="form-control op-stp-h" style="max-width:86px" placeholder="5m" value="' + esc(hold) + '" title="Длительность удержания ступени: 3m, 5m, 10m">' +
            '<span class="input-group-text">Переход</span>' +
            '<input class="form-control op-stp-r" style="max-width:76px" placeholder="0s" value="' + esc(ramp) + '" title="Время выхода на уровень: 0s — скачком, 30s — плавный разгон">' +
            '<span class="input-group-text op-stp-pct" title="Доля от первой ступени">—</span>' +
            '<button type="button" class="btn btn-outline-secondary op-step-up" title="Переместить выше"><i class="bi bi-arrow-up"></i></button>' +
            '<button type="button" class="btn btn-outline-secondary op-step-down" title="Переместить ниже"><i class="bi bi-arrow-down"></i></button>' +
            '<button type="button" class="btn btn-outline-danger op-step-del" title="Убрать ступень" aria-label="Убрать ступень">×</button>' +
            '</div>';
    }

    function rowStartVal(row) {
        var exKey = row.querySelector('.op-exec').value;
        var inp = detailOf(row).querySelector('.op-f[data-k="' + (isRateEx(exKey) ? 'startRate' : 'startVUs') + '"]');
        return inp ? calcOr0(inp.value) : 0;
    }

    function rowUnitShort(row) {
        var exKey = row.querySelector('.op-exec').value;
        var tuInp = detailOf(row).querySelector('.op-f[data-k="timeUnit"]');
        return stepUnitShort(exKey, tuInp ? tuInp.value : '1s');
    }

    function updateRowUnits(row) {
        var u = rowUnitShort(row);
        detailOf(row).querySelectorAll('.op-stp-unit').forEach(function (el) { el.textContent = u; });
    }

    function renderStepsInto(row, warm, steps) {
        var panel = detailOf(row).querySelector('.op-panel');
        var box = panel.querySelector('.op-steps');
        if (!box) { return; }
        var u = rowUnitShort(row);
        var wEl = panel.querySelector('.op-step-warm');
        if (wEl) {
            var showW = !!warm && warmOf(row) > 0;
            wEl.classList.toggle('d-none', !showW);
            if (showW) {
                wEl.querySelector('.op-stw-d').value = warm.duration || gwDur();
                wEl.querySelector('.op-stw-t').value = warm.target;
            }
        }
        box.innerHTML = (steps || []).map(function (st) {
            return stepRowHtml(st.target, st.hold, st.ramp, u);
        }).join('');
        refreshStepsMeta(row);
    }

    function refreshStepsMeta(row) {
        var panel = detailOf(row).querySelector('.op-panel');
        var box = panel.querySelector('.op-steps');
        if (!box) { return; }
        var rows = Array.prototype.slice.call(box.querySelectorAll('.op-step'));
        var base = rows.length ? calcOr0(rows[0].querySelector('.op-stp-t').value) : 0;
        rows.forEach(function (r, i) {
            var numEl = r.querySelector('.op-stage-num');
            if (numEl) { numEl.textContent = '#' + (i + 1); }
            var pctEl = r.querySelector('.op-stp-pct');
            if (pctEl) {
                var v = calcOr0(r.querySelector('.op-stp-t').value);
                if (base > 0 && v > 0) {
                    var p = Math.round((v / base) * 1000) / 10;
                    pctEl.textContent = (p === Math.floor(p) ? p.toFixed(0) : p.toFixed(1)) + '%';
                } else {
                    pctEl.textContent = '—';
                }
            }
            var upBtn = r.querySelector('.op-step-up');
            var dnBtn = r.querySelector('.op-step-down');
            if (upBtn) { upBtn.disabled = i === 0; }
            if (dnBtn) { dnBtn.disabled = i >= rows.length - 1; }
        });
        var note = panel.querySelector('.op-step-note');
        if (note) {
            if (rows.length >= 2) {
                note.innerHTML = '<i class="bi bi-check2-circle text-success"></i><span>В отчёте автоматически появится блок «Поиск максимума» и сводка по ' + rows.length + ' ступеням. Пары стадий k6 формируются сами.</span>';
            } else {
                note.innerHTML = '<i class="bi bi-info-circle text-primary"></i><span>Задавайте только уровень и длительность полки — пары стадий k6 (<code>переход + полка</code>) формируются автоматически.</span>';
            }
        }
    }

    function readStepsUi(row) {
        var panel = detailOf(row).querySelector('.op-panel');
        var warm = null;
        var wEl = panel.querySelector('.op-step-warm');
        if (warmOf(row) > 0 && wEl && !wEl.classList.contains('d-none')) {
            warm = {duration: wEl.querySelector('.op-stw-d').value.trim() || gwDur(), target: calcOr0(wEl.querySelector('.op-stw-t').value)};
        }
        var steps = Array.prototype.slice.call(panel.querySelectorAll('.op-steps .op-step')).map(function (r) {
            return {
                target: calcOr0(r.querySelector('.op-stp-t').value),
                hold: r.querySelector('.op-stp-h').value.trim(),
                ramp: r.querySelector('.op-stp-r').value.trim() || '0s'
            };
        }).filter(function (s) { return s.hold !== ''; });
        return {warm: warm, steps: steps};
    }

    function syncStepsToRaw(row) {
        var panel = detailOf(row).querySelector('.op-panel');
        var rawBox = panel.querySelector('.op-stages');
        if (!rawBox) { return; }
        var ui = readStepsUi(row);
        var list = stepsToStages(ui.warm, ui.steps);
        rawBox.innerHTML = list.map(function (s) { return stageRow(s.duration, s.target, rawBox.dataset.unit); }).join('');
        refreshStages(row);
        refreshStepsMeta(row);
    }

    function syncRawToSteps(row) {
        var panel = detailOf(row).querySelector('.op-panel');
        var rawBox = panel.querySelector('.op-stages');
        if (!rawBox) { return; }
        var parsed = parseStepsFromStages(readStages(rawBox), rowStartVal(row), warmOf(row));
        renderStepsInto(row, parsed.warm, parsed.steps);
    }

    function refreshStepWarmupFromRaw(row) {
        var panel = detailOf(row).querySelector('.op-panel');
        var wEl = panel.querySelector('.op-step-warm');
        var rawBox = panel.querySelector('.op-stages');
        if (!wEl || !rawBox) { return; }
        var showW = warmOf(row) > 0;
        wEl.classList.toggle('d-none', !showW);
        if (showW) {
            var first = rawBox.querySelector('.op-stage');
            wEl.querySelector('.op-stw-d').value = first ? first.querySelector('.op-st-d').value.trim() : gwDur();
            wEl.querySelector('.op-stw-t').value = first ? first.querySelector('.op-st-t').value.trim() : '0';
        }
    }

    function setStageMode(row, mode, skipSync) {
        var panel = detailOf(row).querySelector('.op-panel');
        var stepsPane = panel.querySelector('.op-steps-pane');
        var rawPane = panel.querySelector('.op-raw-pane');
        if (!stepsPane || !rawPane) { return; }
        var prevMode = row.dataset.stageMode || 'raw';
        if (!skipSync) {
            if (mode === 'steps' && prevMode !== 'steps') { syncRawToSteps(row); }
            else if (mode === 'raw' && prevMode === 'steps') { syncStepsToRaw(row); }
        }
        row.dataset.stageMode = mode;
        stepsPane.classList.toggle('d-none', mode !== 'steps');
        rawPane.classList.toggle('d-none', mode !== 'raw');
        (mode === 'steps' ? rawPane : stepsPane).querySelectorAll('.is-invalid').forEach(function (el) {
            el.classList.remove('is-invalid');
        });
        panel.querySelectorAll('.op-sm-btn').forEach(function (b) {
            b.classList.toggle('active', b.dataset.sm === mode);
        });
    }

    // ==================== Field & Stage Rendering ====================
    function fieldHtml(key, val, exKey, state) {
        var f = F[key];
        if (f.type === 'warmup') {
            return '<div class="col-12"><div class="op-warm op-cwarm d-none"><i class="bi bi-fire text-warning me-1"></i><span class="small op-cwarm-text"></span></div></div>';
        }
        if (f.type === 'stages') {
            var unit = isRateEx(exKey) ? 'Итераций за интервал' : 'VU';
            var unitShort = stepUnitShort(exKey, state && state.timeUnit);
            var rows = (val || []).map(function (s) { return stageRow(s.duration, s.target, unit); }).join('');
            var presetMenu = STAGE_PRESETS.map(function (p, idx) {
                return '<li><button type="button" class="dropdown-item small op-stage-preset" data-sp-idx="' + idx + '">' + esc(p.label) + '</button></li>';
            }).join('');
            return '<div class="col-12 mt-1">' +
                '<div class="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">' +
                    '<div class="d-flex align-items-center flex-wrap gap-2">' +
                        '<span class="small fw-semibold"><i class="bi bi-bar-chart-steps me-1 text-primary"></i>' + f.label + '</span>' +
                        '<div class="seg op-stage-mode">' +
                            '<button type="button" class="btn btn-sm op-sm-btn active" data-sm="steps" title="Удобный ввод полок нагрузки без ручных 0s-стадий">Ступени</button>' +
                            '<button type="button" class="btn btn-sm op-sm-btn" data-sm="raw" title="Полный список стадий k6 {duration, target}">Стадии k6</button>' +
                        '</div>' +
                    '</div>' +
                    '<span class="small text-muted op-stage-total" aria-live="polite"></span>' +
                '</div>' +
                '<div class="op-steps-pane">' +
                    '<div class="op-step-bar">' +
                        '<div class="op-step-bar-grp">' +
                            '<span class="op-step-bar-lbl">Переход для всех:</span>' +
                            '<button type="button" class="op-step-chip op-all-ramp" data-val="0s" title="Мгновенный скачок на каждую ступень">0s</button>' +
                            '<button type="button" class="op-step-chip op-all-ramp" data-val="15s">15s</button>' +
                            '<button type="button" class="op-step-chip op-all-ramp" data-val="30s">30s</button>' +
                            '<button type="button" class="op-step-chip op-all-ramp" data-val="1m">1m</button>' +
                        '</div>' +
                        '<div class="op-step-bar-grp">' +
                            '<span class="op-step-bar-lbl">Полка для всех:</span>' +
                            '<button type="button" class="op-step-chip op-all-hold" data-val="3m">3m</button>' +
                            '<button type="button" class="op-step-chip op-all-hold" data-val="5m">5m</button>' +
                            '<button type="button" class="op-step-chip op-all-hold" data-val="10m">10m</button>' +
                        '</div>' +
                        '<button type="button" class="btn btn-sm btn-outline-primary py-0 px-2 ms-auto op-ladder-toggle" style="font-size:.76rem">' +
                            '<i class="bi bi-sliders me-1"></i>Генератор лесенки</button>' +
                    '</div>' +
                    '<div class="op-ladder-box d-none">' +
                        '<div class="op-ladder-grid">' +
                            '<div class="op-ladder-f"><label>Старт</label><input class="form-control form-control-sm op-lad-start" style="width:86px" type="text" inputmode="decimal" value="150"></div>' +
                            '<div class="op-ladder-f"><label>Шаг</label><div class="input-group input-group-sm" style="width:146px">' +
                                '<input class="form-control op-lad-inc" type="text" inputmode="decimal" value="20">' +
                                '<select class="form-select op-lad-kind" style="max-width:74px"><option value="pct" selected>+%</option><option value="abs">+abs</option></select>' +
                            '</div></div>' +
                            '<div class="op-ladder-f"><label>Ступеней</label><input class="form-control form-control-sm op-lad-cnt" style="width:70px" type="number" min="1" max="20" value="5"></div>' +
                            '<div class="op-ladder-f"><label>Полка</label><input class="form-control form-control-sm op-lad-hold" style="width:76px" value="5m" placeholder="5m"></div>' +
                            '<div class="op-ladder-f"><label>Переход</label><input class="form-control form-control-sm op-lad-ramp" style="width:76px" value="0s" placeholder="0s"></div>' +
                            '<button type="button" class="btn btn-sm btn-primary op-lad-apply"><i class="bi bi-check2 me-1"></i>Собрать</button>' +
                        '</div>' +
                    '</div>' +
                    '<div class="input-group input-group-sm op-step-warm d-none">' +
                        '<span class="input-group-text op-stage-num"><i class="bi bi-fire"></i></span>' +
                        '<span class="input-group-text stage-lbl">Прогрев</span>' +
                        '<input class="form-control op-stw-d" style="max-width:96px" readonly title="Время прогрева задаётся в блоке «Общий прогрев» сверху">' +
                        '<span class="input-group-text">Уровень</span>' +
                        '<input class="form-control op-stw-t op-calc" type="text" inputmode="decimal" autocomplete="off" title="Нагрузка во время прогрева">' +
                        '<span class="input-group-text op-stp-unit">' + esc(unitShort) + '</span>' +
                    '</div>' +
                    '<div class="op-steps"></div>' +
                    '<div class="op-stage-actions">' +
                        '<button type="button" class="btn btn-sm btn-outline-primary op-step-add"><i class="bi bi-plus-lg me-1"></i>Ступень</button>' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary op-step-add20" title="Добавить ступень на +20% от предыдущей"><i class="bi bi-graph-up-arrow me-1"></i>Ступень +20%</button>' +
                        '<div class="dropdown d-inline-block">' +
                            '<button type="button" class="btn btn-sm btn-outline-secondary dropdown-toggle" data-bs-toggle="dropdown"><i class="bi bi-magic me-1"></i>Шаблон кривой</button>' +
                            '<ul class="dropdown-menu">' + presetMenu + '</ul>' +
                        '</div>' +
                    '</div>' +
                    '<div class="op-step-note"></div>' +
                '</div>' +
                '<div class="op-raw-pane d-none">' +
                    '<div class="op-stages" data-k="stages" data-unit="' + esc(unit) + '">' + rows + '</div>' +
                    '<div class="op-stage-actions">' +
                        '<button type="button" class="btn btn-sm btn-outline-primary op-stage-add"><i class="bi bi-plus-lg me-1"></i>Стадия</button>' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary op-stage-hold" title="Добавить полку с тем же уровнем нагрузки"><i class="bi bi-dash-lg me-1"></i>Полка 5m</button>' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary op-stage-step" title="Добавить разгон 30s + полку 3m на +20%"><i class="bi bi-graph-up-arrow me-1"></i>Ступень +20%</button>' +
                        '<div class="dropdown d-inline-block">' +
                            '<button type="button" class="btn btn-sm btn-outline-secondary dropdown-toggle" data-bs-toggle="dropdown"><i class="bi bi-magic me-1"></i>Шаблон кривой</button>' +
                            '<ul class="dropdown-menu">' + presetMenu + '</ul>' +
                        '</div>' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary op-stage-first d-none"><i class="bi bi-plus-lg me-1"></i>Первая стадия</button>' +
                    '</div>' +
                '</div></div>';
        }
        if (f.type === 'kv') {
            var kvRows = Object.keys(val || {}).map(function (k) { return kvRow(k, val[k], f.ph); }).join('');
            return '<div class="col-md-6"><div class="small fw-semibold mb-1">' + f.label + '</div>' +
                '<div class="op-kv" data-k="' + key + '">' + kvRows + '</div>' +
                '<button type="button" class="btn btn-sm btn-outline-secondary op-kv-add mt-1" data-ph="' + esc(f.ph.join('|')) + '"><i class="bi bi-plus-lg me-1"></i>Добавить</button></div>';
        }
        var isDur = f.type === 'dur';
        var colCls = (key === 'preAllocatedVUs' || key === 'maxVUs' || key === 'timeUnit' || key === 'startTime' || key === 'gracefulStop' || key === 'gracefulRampDown')
            ? 'col-6 col-md-3 col-xl-2' : 'col-6 col-md-3';
        return '<div class="' + colCls + '">' +
            '<label class="form-label small mb-1" title="' + esc(f.hint) + '">' + f.label + '</label>' +
            '<input class="form-control form-control-sm op-f' + (isDur ? '' : ' op-calc') + '" data-k="' + key + '" data-t="' + f.type + '" type="text"' +
            (isDur ? '' : ' inputmode="decimal" autocomplete="off"') +
            ' placeholder="' + esc(f.ph || '') + '" value="' + esc(val) + '" title="' + esc(f.hint + (isDur ? '' : '. Можно выражение: 50*1.5, 95+95')) + '">' +
            (isDur ? '' : '<div class="op-calc-hint"></div>') +
            '</div>';
    }

    function stageRow(d, tg, unit) {
        return '<div class="input-group input-group-sm op-stage">' +
            '<span class="input-group-text op-stage-num">#</span>' +
            '<span class="input-group-text stage-lbl">Длительность</span>' +
            '<input class="form-control op-st-d" style="max-width:110px" placeholder="1m" value="' + esc(d) + '">' +
            '<span class="input-group-text">' + esc(unit) + '</span>' +
            '<input class="form-control op-st-t op-calc" type="text" inputmode="decimal" autocomplete="off" title="Число или выражение: 95+95, 50*1.5, (100+20)/2" value="' + esc(tg) + '">' +
            '<button type="button" class="btn btn-outline-secondary op-stage-up" title="Переместить выше"><i class="bi bi-arrow-up"></i></button>' +
            '<button type="button" class="btn btn-outline-secondary op-stage-down" title="Переместить ниже"><i class="bi bi-arrow-down"></i></button>' +
            '<button type="button" class="btn btn-outline-danger op-stage-del" title="Убрать стадию" aria-label="Убрать стадию">×</button>' +
            '<button type="button" class="stage-ins stage-ins-above" title="Добавить стадию выше (копия этой)" aria-label="Добавить стадию выше"><span>+</span></button>' +
            '<button type="button" class="stage-ins stage-ins-below" title="Добавить стадию ниже (копия этой)" aria-label="Добавить стадию ниже"><span>+</span></button>' +
            '</div>';
    }

    function kvRow(k, v, ph) {
        return '<div class="input-group input-group-sm mb-1 op-kvrow">' +
            '<input class="form-control op-kv-k" placeholder="' + esc(ph[0]) + '" value="' + esc(k) + '">' +
            '<input class="form-control op-kv-v" placeholder="' + esc(ph[1]) + '" value="' + esc(v) + '">' +
            '<button type="button" class="btn btn-outline-danger op-kv-del" title="Убрать">×</button></div>';
    }

    function countAdvValues(ex, state) {
        var n = 0;
        ex.adv.forEach(function (k) {
            var v = state[k];
            if (v == null || v === '') { return; }
            if (typeof v === 'object') { n += Object.keys(v).length; }
            else { n++; }
        });
        return n;
    }

    function normalizeWarm(exKey, state) {
        if (!state.warmup) { return state; }
        if (!isRamp(exKey)) {
            if (!state.warmup.duration) { delete state.warmup; }
            return state;
        }
        var w = state.warmup;
        var st = (state.stages || []).map(function (x) { return Object.assign({}, x); });
        if (w.stages) {
            state.warmup = st.length ? {stages: 1} : undefined;
        } else if (w.target) {
            st.unshift({duration: w.duration, target: Number(w.target)});
            state.warmup = {stages: 1};
        } else {
            state.warmup = undefined;
        }
        if (!state.warmup) { delete state.warmup; }
        state.stages = st;
        return state;
    }

    function render(row, exKey, state) {
        var ex = EX[exKey];
        state = normalizeWarm(exKey, state);
        if (isRamp(exKey)) {
            if (gwActive()) {
                var st = (state.stages || []).map(function (x) { return Object.assign({}, x); });
                if (!(state.warmup && state.warmup.stages)) { st.unshift(warmStage(st.length ? st[0].target : 0)); }
                st[0].duration = gwDur();
                state.stages = st;
                state.warmup = {stages: 1};
            }
        } else if (ex.fields.indexOf('warmup') >= 0 && gwActive()) {
            state.warmup = {duration: gwDur()};
        }
        row.dataset.warm = isRamp(exKey) && state.warmup && state.warmup.stages ? 1 : 0;
        row.dataset.cwarm = !isRamp(exKey) && ex.fields.indexOf('warmup') >= 0 && state.warmup && state.warmup.duration ? state.warmup.duration : '';

        var advCount = countAdvValues(ex, state);
        var advOpen = advCount > 0;
        var panel = detailOf(row).querySelector('.op-panel');
        panel.innerHTML =
            '<div class="op-desc mb-2">' + esc(ex.desc) + '</div>' +
            '<div class="row g-2">' + ex.main.map(function (k) { return fieldHtml(k, state[k], exKey, state); }).join('') + '</div>' +
            '<div class="mt-3">' +
                '<button type="button" class="op-adv-toggle" aria-expanded="' + advOpen + '">' +
                    '<i class="bi bi-sliders"></i><span>Доп. параметры k6 (старт, остановка, env, теги)</span>' +
                    '<span class="badge text-bg-primary op-adv-cnt' + (advCount > 0 ? '' : ' d-none') + '">' + advCount + '</span>' +
                '</button>' +
                '<div class="op-adv-body' + (advOpen ? '' : ' d-none') + '">' +
                    '<div class="row g-2">' + ex.adv.map(function (k) { return fieldHtml(k, state[k], exKey, state); }).join('') + '</div>' +
                '</div>' +
            '</div>';

        row.dataset.prev = exKey;
        refreshRow(row);
        refreshStages(row);
        if (isRamp(exKey) && panel.querySelector('.op-steps')) {
            var parsed = parseStepsFromStages(state.stages || [], isRateEx(exKey) ? (state.startRate || 0) : (state.startVUs || 0), warmOf(row));
            renderStepsInto(row, parsed.warm, parsed.steps);
            setStageMode(row, parsed.exact ? 'steps' : 'raw', true);
        }
    }

    function refreshStages(row) {
        var panel = detailOf(row).querySelector('.op-panel');
        var cw = panel.querySelector('.op-cwarm');
        if (cw) {
            var d = row.dataset.cwarm || '';
            cw.classList.toggle('d-none', !d);
            cw.querySelector('.op-cwarm-text').textContent = d ? 'Прогрев: первые ' + d + ' сценария идут на заданной ниже нагрузке и не попадают в итоги и SLA.' : '';
        }
        var box = panel.querySelector('.op-stages');
        if (!box) { return; }
        var rows = Array.prototype.slice.call(box.querySelectorAll('.op-stage'));
        var wn = Math.min(warmOf(row), rows.length);
        row.dataset.warm = wn;
        rows.forEach(function (r, i) {
            var isW = i < wn;
            r.classList.toggle('is-warm', isW);
            var numEl = r.querySelector('.op-stage-num');
            if (numEl) { numEl.innerHTML = isW ? '<i class="bi bi-fire"></i>' : '#' + (i + 1 - wn); }
            r.querySelector('.stage-lbl').textContent = isW ? 'Прогрев' : 'Длительность';
            var din = r.querySelector('.op-st-d');
            din.readOnly = isW;
            din.title = isW ? 'Время прогрева общее для всех сценариев и задаётся в обзорной панели сверху' : '';
            r.querySelector('.stage-ins-above').hidden = isW;
            r.querySelector('.op-stage-del').disabled = wn === 1 && i === 0;
            var upBtn = r.querySelector('.op-stage-up');
            var dnBtn = r.querySelector('.op-stage-down');
            if (upBtn) { upBtn.disabled = isW || i <= wn; }
            if (dnBtn) { dnBtn.disabled = isW || i >= rows.length - 1; }
        });
        var first = panel.querySelector('.op-stage-first');
        if (first) { first.classList.toggle('d-none', rows.length > 0); }
        var total = panel.querySelector('.op-stage-total');
        if (total) {
            var warm = 0, main = 0, bad = 0;
            rows.forEach(function (r, i) {
                var v = durSec(r.querySelector('.op-st-d').value);
                if (v === null) { bad++; }
                else if (i < wn) { warm += v; }
                else { main += v; }
            });
            var stepCnt = row.dataset.stageMode === 'steps' ? panel.querySelectorAll('.op-steps .op-step').length : 0;
            var stepPrefix = stepCnt >= 2 ? (stepCnt + ' ступ. · ') : '';
            total.textContent = !rows.length ? 'Нет стадий' : stepPrefix + 'Всего: ' + durText(warm + main)
                + (wn > 0 ? ' (прогрев ' + durText(warm) + ' + нагрузка ' + durText(main) + ')' : '')
                + (bad ? ' · не распознано: ' + bad : '');
        }
    }

    // ==================== Collect & Sync ====================
    function collect(row) {
        var exKey = row.querySelector('.op-exec').value;
        var panel = detailOf(row).querySelector('.op-panel');
        var o = {};
        panel.querySelectorAll('.op-f').forEach(function (inp) {
            var v = inp.value.trim();
            if (v === '') { return; }
            o[inp.dataset.k] = inp.dataset.t === 'dur' ? v : calcOr0(v);
        });
        var st = panel.querySelector('.op-stages');
        if (st) {
            o.stages = readStages(st);
            var wn = Math.min(warmOf(row), o.stages.length);
            if (isRamp(exKey) && wn > 0) { o.warmup = {stages: wn}; }
        }
        if (panel.querySelector('.op-cwarm') && row.dataset.cwarm) {
            o.warmup = {duration: row.dataset.cwarm};
        }
        panel.querySelectorAll('.op-kv').forEach(function (c) {
            var m = {};
            c.querySelectorAll('.op-kvrow').forEach(function (r) {
                var k = r.querySelector('.op-kv-k').value.trim();
                if (k) { m[k] = r.querySelector('.op-kv-v').value; }
            });
            if (Object.keys(m).length) { o[c.dataset.k] = m; }
        });
        if (exKey === 'ramping-arrival-rate') {
            if (o.timeUnit === undefined) { o.timeUnit = '1s'; }
            if (o.startRate === undefined) { o.startRate = 0; }
            if (!o.stages) { o.stages = []; }
        }
        return o;
    }

    function sync(row) {
        var exKey = row.querySelector('.op-exec').value;
        var o = collect(row);
        row.querySelector('.op-exec-val').value = EX[exKey].real;
        detailOf(row).querySelector('.op-json').value = Object.keys(o).length ? JSON.stringify(o) : '';
        // update advanced badge count
        var cnt = countAdvValues(EX[exKey], o);
        var badge = detailOf(row).querySelector('.op-adv-cnt');
        if (badge) {
            badge.textContent = String(cnt);
            badge.classList.toggle('d-none', cnt === 0);
        }
        updateRowSummary(row, exKey, o);
    }

    function updateRowSummary(row, exKey, o) {
        var box = row.querySelector('.op-summary-pills');
        if (!box) { return; }
        var ex = EX[exKey];
        var loadStr = '';
        if (exKey === 'constant-arrival-rate') {
            loadStr = fmtNum(o.rate || 0) + ' / ' + (o.timeUnit || '1s') + ' · ' + (o.duration || '—');
        } else if (exKey === 'ramping-arrival-rate') {
            var totalS = (o.stages || []).reduce(function (a, s) { return a + (durSec(s.duration) || 0); }, 0);
            var parsedR = parseStepsFromStages(o.stages || [], o.startRate || 0, warmOf(row));
            if (parsedR.exact && parsedR.steps.length >= 2) {
                var stNums = parsedR.steps.map(function (st) { return fmtNum(st.target); });
                loadStr = parsedR.steps.length + ' ступ.: ' + stNums.slice(0, 5).join(' → ') + (stNums.length > 5 ? '…' : '')
                    + ' ' + stepUnitShort(exKey, o.timeUnit) + ' · ' + durText(totalS);
            } else {
                var targets = (o.stages || []).map(function (s) { return fmtNum(s.target); });
                loadStr = (targets.length ? fmtNum(o.startRate || 0) + ' → ' + targets.slice(0, 4).join(' → ') + (targets.length > 4 ? '…' : '') : 'нет стадий')
                    + ' / ' + (o.timeUnit || '1s') + ' · ' + durText(totalS);
            }
        } else if (exKey === 'constant-vus' || exKey === 'externally-controlled') {
            loadStr = fmtNum(o.vus || 0) + ' VU · ' + (o.duration || '—');
        } else if (exKey === 'ramping-vus') {
            var vTotal = (o.stages || []).reduce(function (a, s) { return a + (durSec(s.duration) || 0); }, 0);
            var parsedV = parseStepsFromStages(o.stages || [], o.startVUs || 0, warmOf(row));
            if (parsedV.exact && parsedV.steps.length >= 2) {
                var vstNums = parsedV.steps.map(function (st) { return fmtNum(st.target); });
                loadStr = parsedV.steps.length + ' ступ.: ' + vstNums.slice(0, 5).join(' → ') + (vstNums.length > 5 ? '…' : '') + ' VU · ' + durText(vTotal);
            } else {
                var vt = (o.stages || []).map(function (s) { return fmtNum(s.target); });
                loadStr = (vt.length ? fmtNum(o.startVUs || 0) + ' → ' + vt.slice(0, 4).join(' → ') + (vt.length > 4 ? '…' : '') + ' VU' : 'нет стадий') + ' · ' + durText(vTotal);
            }
        } else {
            loadStr = fmtNum(o.vus || 0) + ' VU · ' + fmtNum(o.iterations || 0) + ' итер.';
        }
        var pctEl = row.querySelector('select[name$=".slaPercentile"]');
        var msEl = row.querySelector('input[name$=".slaThresholdMs"]');
        var errEl = row.querySelector('input[name$=".maxErrorPct"]');
        var slaParts = [];
        if (msEl && msEl.value) { slaParts.push('p' + (pctEl ? pctEl.value : '95') + ' ≤ ' + msEl.value + ' мс'); }
        if (errEl && errEl.value) { slaParts.push('ошибки ≤ ' + errEl.value + '%'); }

        var hasWarm = (warmOf(row) > 0) || !!row.dataset.cwarm;
        box.innerHTML =
            '<span class="op-sum-pill">' + esc(ex.short) + '</span>' +
            '<span class="op-sum-pill"><i class="bi bi-speedometer2 text-primary"></i>' + esc(loadStr) + '</span>' +
            (hasWarm ? '<span class="op-sum-pill is-warm"><i class="bi bi-fire"></i>Прогрев ' + esc(gwDur()) + '</span>' : '') +
            (slaParts.length ? '<span class="op-sum-pill is-sla">' + esc(slaParts.join(' · ')) + '</span>' : '');
    }

    function refreshRow(row) {
        var on = row.querySelector('.op-on').checked;
        row.classList.toggle('is-off', !on);
        var st = row.querySelector('.op-state');
        if (st) { st.textContent = on ? '' : 'Выключена'; }
    }

    function refreshAll() {
        var rows = Array.prototype.slice.call(body.querySelectorAll('.op-row'));
        var count = {};
        rows.forEach(function (r, idx) {
            count[r.dataset.fn] = (count[r.dataset.fn] || 0) + 1;
            var dot = r.querySelector('.op-color-dot');
            if (dot) { dot.style.background = COLORS[idx % COLORS.length]; }
        });
        rows.forEach(function (r) { r.querySelector('.op-del').classList.toggle('d-none', count[r.dataset.fn] < 2); });
        document.querySelectorAll('.op-paste').forEach(function (b) { b.disabled = !scenarioClipboard; });
        scheduleChartUpdate();
    }

    // ==================== Global Warmup ====================
    function gwRefresh() {
        if (!gwOnEl) { return; }
        gwDEl.disabled = !gwOnEl.checked;
        if (gwBox) { gwBox.classList.toggle('is-off', !gwOnEl.checked); }
    }
    function gwValid() {
        var sec = durSec(gwDur());
        var ok = sec !== null && sec >= 5 && sec <= 3600;
        gwDEl.classList.toggle('is-invalid', gwActive() && !ok);
        gwDEl.title = ok ? '' : 'От 5 секунд до 1 часа, например 2m или 90s';
        return ok;
    }
    function gwAdopt(durations) {
        if (!gwOnEl) { return; }
        var best = null;
        durations.forEach(function (d) { var s = durSec(d); if (s !== null && (best === null || s > durSec(best))) { best = d; } });
        gwOnEl.checked = durations.length > 0;
        if (best !== null) { gwDEl.value = best; }
        gwRefresh();
    }
    function gwFromJson() {
        var found = [];
        body.querySelectorAll('.op-row').forEach(function (row) {
            var o = {};
            try { o = JSON.parse(detailOf(row).querySelector('.op-json').value || '{}') || {}; } catch (e) { o = {}; }
            var w = o.warmup;
            if (!w || !row.querySelector('.op-on').checked) { return; }
            if (w.stages && o.stages && o.stages.length) { found.push(String(o.stages[0].duration || '')); }
            else if (w.duration) { found.push(String(w.duration)); }
        });
        gwAdopt(found);
    }
    function gwFromRows() {
        var found = [];
        body.querySelectorAll('.op-row').forEach(function (row) {
            if (warmOf(row) > 0) {
                var d = row.querySelector('.op-stage .op-st-d');
                if (d) { found.push(d.value.trim()); }
            } else if (row.dataset.cwarm) {
                found.push(row.dataset.cwarm);
            }
        });
        gwAdopt(found);
    }
    function insertStages(row, atStart, list) {
        var c = row.querySelector('.op-stages');
        if (!c) { return; }
        var html = list.map(function (s) { return stageRow(s.duration, s.target, c.dataset.unit); }).join('');
        if (atStart === 0) { c.insertAdjacentHTML('afterbegin', html); }
        else {
            var rows = c.querySelectorAll('.op-stage');
            if (atStart <= rows.length && atStart > 0) { rows[atStart - 1].insertAdjacentHTML('afterend', html); }
            else { c.insertAdjacentHTML('beforeend', html); }
        }
    }
    function gwApply() {
        gwRefresh();
        var on = gwOnEl.checked;
        if (on && !gwValid()) { return; }
        if (!on) { gwDEl.classList.remove('is-invalid'); }
        var g = gwDur();
        body.querySelectorAll('.op-row').forEach(function (row) {
            var exKey = row.querySelector('.op-exec').value;
            var c = row.querySelector('.op-stages');
            if (isRamp(exKey) && c) {
                if (!on) {
                    row.dataset.warm = 0;
                    if (row.dataset.stageMode === 'steps') {
                        refreshStepWarmupFromRaw(row);
                        syncStepsToRaw(row);
                    }
                } else {
                    if (warmOf(row) === 0) {
                        var first = c.querySelector('.op-stage .op-st-t');
                        insertStages(row, 0, [warmStage(first ? first.value : 0)]);
                    }
                    c.querySelector('.op-stage .op-st-d').value = g;
                    row.dataset.warm = 1;
                    if (row.dataset.stageMode === 'steps') {
                        refreshStepWarmupFromRaw(row);
                    }
                }
            } else if (detailOf(row).querySelector('.op-cwarm')) {
                row.dataset.cwarm = on ? g : '';
            }
            refreshStages(row);
            sync(row);
        });
        scheduleChartUpdate();
    }
    if (gwOnEl) {
        gwOnEl.addEventListener('change', function () { gwApply(); markDirty(); });
        gwDEl.addEventListener('input', function () { if (gwOnEl.checked) { gwApply(); markDirty(); } });
        document.querySelectorAll('[data-gw-preset]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                gwOnEl.checked = true;
                gwDEl.value = btn.dataset.gwPreset;
                gwApply();
                markDirty();
            });
        });
    }

    // ==================== Live Load Profile Chart & KPIs ====================
    var chartCanvas = document.getElementById('ld-chart');
    var chartEmpty = document.getElementById('ld-chart-empty');
    var chartModeEl = document.getElementById('ld-chart-mode');
    var chartStackBtn = document.getElementById('ld-chart-stack');
    var chartMetricBtns = document.querySelectorAll('[data-ld-metric]');
    var liveChart = null;
    var chartMetricMode = 'auto'; // 'auto' | 'rate' | 'vus'
    var chartStacked = true;
    var chartTimer = null;

    function scheduleChartUpdate() {
        clearTimeout(chartTimer);
        chartTimer = setTimeout(updateDashboard, 60);
    }

    function opTrajectory(row, idx) {
        var on = row.querySelector('.op-on').checked;
        var name = row.querySelector('input[name$=".name"]').value.trim() || row.dataset.fn;
        var exKey = row.querySelector('.op-exec').value;
        var o = collect(row);
        var startSec = durSec(o.startTime) || 0;
        var isRate = isRateEx(exKey);
        var pts = []; // [{x: sec, y: val}]
        var endSec = startSec;
        var warmEndSec = 0;
        var peak = 0;
        var maxVus = 0;

        if (exKey === 'constant-arrival-rate') {
            var tu = durSec(o.timeUnit) || 1;
            var rps = (Number(o.rate) || 0) / tu;
            var d = durSec(o.duration) || 0;
            endSec = startSec + d;
            if (d > 0) { pts.push({x: startSec, y: rps}, {x: endSec, y: rps}); }
            peak = rps;
            maxVus = Number(o.maxVUs) || Number(o.preAllocatedVUs) || 0;
            if (o.warmup && o.warmup.duration) { warmEndSec = startSec + (durSec(o.warmup.duration) || 0); }
        } else if (exKey === 'ramping-arrival-rate') {
            var tu2 = durSec(o.timeUnit) || 1;
            var cur = (Number(o.startRate) || 0) / tu2;
            var t = startSec;
            pts.push({x: t, y: cur});
            peak = Math.max(peak, cur);
            (o.stages || []).forEach(function (s, si) {
                var sd = durSec(s.duration) || 0;
                var tg = (Number(s.target) || 0) / tu2;
                t += sd;
                pts.push({x: t, y: tg});
                peak = Math.max(peak, tg);
                if (si === 0 && o.warmup && o.warmup.stages) { warmEndSec = t; }
            });
            endSec = t;
            maxVus = Number(o.maxVUs) || Number(o.preAllocatedVUs) || 0;
        } else if (exKey === 'constant-vus' || exKey === 'externally-controlled') {
            var vus = Number(o.vus) || 0;
            var d2 = durSec(o.duration) || 0;
            endSec = startSec + d2;
            if (d2 > 0) { pts.push({x: startSec, y: vus}, {x: endSec, y: vus}); }
            peak = vus;
            maxVus = Number(o.maxVUs) || vus;
            if (o.warmup && o.warmup.duration) { warmEndSec = startSec + (durSec(o.warmup.duration) || 0); }
        } else if (exKey === 'ramping-vus') {
            var curV = Number(o.startVUs) || 0;
            var t2 = startSec;
            pts.push({x: t2, y: curV});
            peak = Math.max(peak, curV);
            (o.stages || []).forEach(function (s, si) {
                var sd = durSec(s.duration) || 0;
                var tg = Number(s.target) || 0;
                t2 += sd;
                pts.push({x: t2, y: tg});
                peak = Math.max(peak, tg);
                if (si === 0 && o.warmup && o.warmup.stages) { warmEndSec = t2; }
            });
            endSec = t2;
            maxVus = peak;
        } else {
            var vus2 = Number(o.vus) || 0;
            var md = durSec(o.maxDuration) || 600;
            endSec = startSec + md;
            pts.push({x: startSec, y: vus2}, {x: endSec, y: vus2});
            peak = vus2;
            maxVus = vus2;
        }

        return {
            on: on, name: name, exKey: exKey, isRate: isRate,
            color: COLORS[idx % COLORS.length],
            pts: pts, startSec: startSec, endSec: endSec,
            warmEndSec: warmEndSec, peak: peak, maxVus: maxVus
        };
    }

    // Вычислить значение кусочно-линейной кривой в точке x (секунды)
    function sampleAt(pts, x) {
        if (!pts.length) { return 0; }
        if (x < pts[0].x || x > pts[pts.length - 1].x) { return 0; }
        for (var i = 0; i < pts.length - 1; i++) {
            var a = pts[i], b = pts[i + 1];
            if (x >= a.x && x <= b.x) {
                if (b.x === a.x) { return b.y; }
                return a.y + (b.y - a.y) * ((x - a.x) / (b.x - a.x));
            }
        }
        return pts[pts.length - 1].y;
    }

    var warmupPlugin = {
        id: 'ldWarmupBand',
        beforeDatasetsDraw: function (chart) {
            var wMin = chart.$warmupMin || 0;
            if (wMin <= 0) { return; }
            var xScale = chart.scales.x;
            var area = chart.chartArea;
            if (!xScale || !area) { return; }
            var x0 = Math.max(area.left, xScale.getPixelForValue(0));
            var x1 = Math.min(area.right, xScale.getPixelForValue(wMin));
            if (x1 <= x0) { return; }
            var ctx = chart.ctx;
            ctx.save();
            ctx.fillStyle = 'rgba(245, 158, 11, 0.13)';
            ctx.fillRect(x0, area.top, x1 - x0, area.bottom - area.top);
            ctx.strokeStyle = 'rgba(217, 119, 6, 0.55)';
            ctx.setLineDash([4, 4]);
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.moveTo(x1, area.top);
            ctx.lineTo(x1, area.bottom);
            ctx.stroke();
            ctx.setLineDash([]);
            ctx.fillStyle = '#b45309';
            ctx.font = '600 11px Inter, system-ui, sans-serif';
            if (x1 - x0 > 44) { ctx.fillText('Прогрев', x0 + 6, area.top + 14); }
            ctx.restore();
        }
    };

    function updateDashboard() {
        var rows = Array.prototype.slice.call(body.querySelectorAll('.op-row'));
        var infos = rows.map(opTrajectory);
        var active = infos.filter(function (x) { return x.on; });

        var rateOps = active.filter(function (x) { return x.isRate; });
        var vuOps = active.filter(function (x) { return !x.isRate; });

        var maxDurSec = active.reduce(function (m, x) { return Math.max(m, x.endSec); }, 0);
        var maxWarmSec = active.reduce(function (m, x) { return Math.max(m, x.warmEndSec); }, 0);
        var totalPeakRate = rateOps.reduce(function (s, x) { return s + x.peak; }, 0);
        var totalPeakVu = vuOps.reduce(function (s, x) { return s + x.peak; }, 0);
        var estMaxVus = active.reduce(function (s, x) { return s + x.maxVus; }, 0);

        // KPIs
        var elKpiOps = document.getElementById('ld-kpi-ops');
        var elKpiOpsSub = document.getElementById('ld-kpi-ops-sub');
        var elKpiPeak = document.getElementById('ld-kpi-peak');
        var elKpiPeakSub = document.getElementById('ld-kpi-peak-sub');
        var elKpiDur = document.getElementById('ld-kpi-dur');
        var elKpiDurSub = document.getElementById('ld-kpi-dur-sub');
        var elKpiVus = document.getElementById('ld-kpi-vus');
        var elNavOpsCnt = document.getElementById('ld-nav-ops-cnt');

        if (elKpiOps) { elKpiOps.textContent = active.length + ' из ' + rows.length; }
        if (elKpiOpsSub) {
            elKpiOpsSub.textContent = active.length === 0 ? 'включите хотя бы одну'
                : (rateOps.length && vuOps.length ? rateOps.length + ' RPS + ' + vuOps.length + ' VU'
                    : (rateOps.length ? 'по интенсивности' : 'по числу VU'));
        }
        if (elKpiPeak) {
            if (rateOps.length > 0) {
                elKpiPeak.textContent = fmtNum(totalPeakRate) + ' оп/с';
                if (elKpiPeakSub) { elKpiPeakSub.textContent = vuOps.length > 0 ? '+ ' + fmtNum(totalPeakVu) + ' VU в др. операциях' : 'суммарный пик по операциям'; }
            } else if (vuOps.length > 0) {
                elKpiPeak.textContent = fmtNum(totalPeakVu) + ' VU';
                if (elKpiPeakSub) { elKpiPeakSub.textContent = 'суммарный пик пользователей'; }
            } else {
                elKpiPeak.textContent = '—';
                if (elKpiPeakSub) { elKpiPeakSub.textContent = 'нет активных операций'; }
            }
        }
        if (elKpiDur) { elKpiDur.textContent = maxDurSec > 0 ? durText(maxDurSec) : '—'; }
        if (elKpiDurSub) {
            elKpiDurSub.textContent = maxWarmSec > 0
                ? 'прогрев ' + durText(maxWarmSec) + ' + оценка ' + durText(Math.max(0, maxDurSec - maxWarmSec))
                : 'без прогрева';
        }
        if (elKpiVus) { elKpiVus.textContent = estMaxVus > 0 ? '~' + Math.round(estMaxVus) : '—'; }
        if (elNavOpsCnt) { elNavOpsCnt.textContent = active.length + '/' + rows.length; }

        // Determine chart metric mode
        var effectiveMode = chartMetricMode;
        if (effectiveMode === 'auto') { effectiveMode = rateOps.length > 0 ? 'rate' : 'vus'; }
        if (effectiveMode === 'rate' && rateOps.length === 0 && vuOps.length > 0) { effectiveMode = 'vus'; }
        if (effectiveMode === 'vus' && vuOps.length === 0 && rateOps.length > 0) { effectiveMode = 'rate'; }

        if (chartModeEl) {
            chartModeEl.classList.toggle('d-none', !(rateOps.length > 0 && vuOps.length > 0));
            chartMetricBtns.forEach(function (b) { b.classList.toggle('active', b.dataset.ldMetric === effectiveMode); });
        }

        var shown = effectiveMode === 'rate' ? rateOps : vuOps;
        if (!chartCanvas || !window.Chart) { return; }

        if (shown.length === 0 || maxDurSec <= 0) {
            if (liveChart) { liveChart.destroy(); liveChart = null; }
            if (chartEmpty) { chartEmpty.classList.remove('d-none'); }
            return;
        }
        if (chartEmpty) { chartEmpty.classList.add('d-none'); }

        // Collect all unique time breakpoints across shown operations for exact stacked alignment
        var xSet = {};
        var eps = Math.max(0.001, Math.min(0.05, maxDurSec * 0.0005));
        shown.forEach(function (op) {
            op.pts.forEach(function (p, pi) {
                var px = Math.round(p.x * 1000) / 1000;
                xSet[px] = true;
                // При резком скачке (duration: '0s') добавляем точки слева и справа, чтобы график рисовал чёткую 90°-ступеньку
                if (pi > 0 && Math.abs(op.pts[pi - 1].x - p.x) < 1e-6 && Math.abs(op.pts[pi - 1].y - p.y) > 1e-9) {
                    if (px > eps) { xSet[Math.round((px - eps) * 1000) / 1000] = true; }
                    if (px + eps < maxDurSec) { xSet[Math.round((px + eps) * 1000) / 1000] = true; }
                }
            });
            if (op.startSec > 0) { xSet[Math.max(0, op.startSec - eps)] = true; }
            if (op.endSec < maxDurSec) { xSet[op.endSec + eps] = true; }
        });
        if (maxWarmSec > 0 && maxWarmSec < maxDurSec) { xSet[maxWarmSec] = true; }
        xSet[0] = true;
        xSet[maxDurSec] = true;
        var xs = Object.keys(xSet).map(Number).sort(function (a, b) { return a - b; });

        var datasets = shown.map(function (op) {
            return {
                label: op.name,
                data: xs.map(function (sec) { return {x: sec / 60.0, y: Math.round(sampleAt(op.pts, sec) * 1000) / 1000}; }),
                borderColor: op.color,
                backgroundColor: op.color + (chartStacked ? '33' : '18'),
                fill: chartStacked ? 'origin' : false,
                borderWidth: 2,
                pointRadius: xs.length <= 16 ? 2.5 : 0,
                pointHoverRadius: 4,
                tension: 0
            };
        });

        var yTitle = effectiveMode === 'rate' ? 'Интенсивность, оп/с' : 'Пользователи, VU';
        if (!liveChart) {
            liveChart = new Chart(chartCanvas, {
                type: 'line',
                data: {datasets: datasets},
                plugins: [warmupPlugin],
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: false,
                    interaction: {mode: 'index', intersect: false},
                    plugins: {
                        legend: {position: 'bottom', labels: {boxWidth: 12, font: {size: 11}}},
                        tooltip: {
                            callbacks: {
                                title: function (items) {
                                    if (!items.length) { return ''; }
                                    return 'Время: ' + durText(items[0].parsed.x * 60);
                                },
                                label: function (item) {
                                    return ' ' + item.dataset.label + ': ' + fmtNum(item.parsed.y) + (effectiveMode === 'rate' ? ' оп/с' : ' VU');
                                }
                            }
                        }
                    },
                    scales: {
                        x: {
                            type: 'linear',
                            min: 0,
                            max: maxDurSec / 60.0,
                            title: {display: true, text: 'Минуты от старта', font: {size: 11}},
                            ticks: {callback: function (v) { return fmtNum(v) + 'м'; }, font: {size: 10}},
                            grid: {color: '#eef0f6'}
                        },
                        y: {
                            beginAtZero: true,
                            stacked: chartStacked,
                            title: {display: true, text: yTitle, font: {size: 11}},
                            ticks: {font: {size: 10}},
                            grid: {color: '#eef0f6'}
                        }
                    }
                }
            });
        } else {
            liveChart.data.datasets = datasets;
            liveChart.options.scales.x.max = maxDurSec / 60.0;
            liveChart.options.scales.y.stacked = chartStacked;
            liveChart.options.scales.y.title.text = yTitle;
        }
        liveChart.$warmupMin = maxWarmSec / 60.0;
        liveChart.update('none');
    }

    if (chartStackBtn) {
        chartStackBtn.addEventListener('click', function () {
            chartStacked = !chartStacked;
            chartStackBtn.classList.toggle('active', chartStacked);
            chartStackBtn.innerHTML = chartStacked ? '<i class="bi bi-layers-fill me-1"></i>Суммарно' : '<i class="bi bi-graph-up me-1"></i>По линиям';
            updateDashboard();
        });
    }
    chartMetricBtns.forEach(function (b) {
        b.addEventListener('click', function () {
            chartMetricMode = b.dataset.ldMetric;
            updateDashboard();
        });
    });

    // ==================== Operations Initialization & Events ====================
    function initRow(row) {
        var o = {};
        try { o = JSON.parse(detailOf(row).querySelector('.op-json').value || '{}') || {}; } catch (e) { o = {}; }
        var exKey = row.querySelector('.op-exec-val').value || 'constant-arrival-rate';
        if (!EX[exKey]) { exKey = 'constant-arrival-rate'; }
        row.querySelector('.op-exec').value = exKey;
        render(row, exKey, Object.keys(o).length ? o : Object.assign({}, EX[exKey].def));
        sync(row);
    }

    function nextIndex() {
        var max = -1;
        body.querySelectorAll('input[name$=".execFn"]').forEach(function (i) {
            var m = i.name.match(/ops\[(\d+)\]/);
            if (m) { max = Math.max(max, +m[1]); }
        });
        return max + 1;
    }

    function addScenario(row) {
        var idx = nextIndex();
        var r2 = row.cloneNode(true);
        r2.classList.remove('is-collapsed');
        r2.querySelectorAll('[name]').forEach(function (f) { f.name = f.name.replace(/ops\[\d+\]/, 'ops[' + idx + ']'); });
        var fn = row.dataset.fn;
        var names = new Set(Array.prototype.slice.call(body.querySelectorAll('input[name$=".name"]')).map(function (i) { return i.value; }));
        var n = 2;
        while (names.has(fn + n)) { n++; }
        r2.querySelector('input[name$=".name"]').value = fn + n;
        r2.querySelector('.op-on').checked = true;
        r2.querySelector('.op-exec').value = 'constant-arrival-rate';
        row.after(r2);
        render(r2, 'constant-arrival-rate', Object.assign({}, EX['constant-arrival-rate'].def));
        sync(r2);
        refreshAll();
        r2.querySelector('input[name$=".name"]').focus();
    }

    function markCalc(el) {
        var txt = el.value.trim();
        var v = calc(txt);
        var bad = txt !== '' && isNaN(v);
        el.classList.toggle('is-invalid', bad);
        var hint = el.nextElementSibling && el.nextElementSibling.classList.contains('op-calc-hint') ? el.nextElementSibling : null;
        if (hint) {
            if (bad) {
                hint.innerHTML = '<span class="text-danger">Некорректное выражение</span>';
            } else if (/[+\-*\/×÷()]/.test(txt) && !isNaN(v)) {
                hint.textContent = '= ' + (Math.round(v * 1e6) / 1e6);
            } else {
                hint.textContent = '';
            }
        }
        return !bad;
    }

    body.addEventListener('click', function (e) {
        var t = e.target;
        var row = t.closest('.op-row');
        if (!row) { return; }

        if (t.closest('.op-collapse-btn')) {
            row.classList.toggle('is-collapsed');
            return;
        }
        if (t.closest('.op-adv-toggle')) {
            var btn = t.closest('.op-adv-toggle');
            var advBody = btn.nextElementSibling;
            var open = advBody.classList.toggle('d-none') === false;
            btn.setAttribute('aria-expanded', String(open));
            return;
        }
        if (t.closest('.op-add')) { addScenario(row); markDirty(); return; }
        if (t.closest('.op-del')) { row.remove(); refreshAll(); markDirty(); return; }
        if (t.closest('.op-copy')) {
            scenarioClipboard = {executor: row.querySelector('.op-exec').value, scenario: collect(row)};
            try { sessionStorage.setItem('k6lab-scenario', JSON.stringify(scenarioClipboard)); } catch (err) {}
            refreshAll();
            flash(row, 'Скопировано');
            return;
        }
        if (t.closest('.op-paste')) {
            if (!scenarioClipboard || !EX[scenarioClipboard.executor]) { return; }
            row.querySelector('.op-exec').value = scenarioClipboard.executor;
            render(row, scenarioClipboard.executor, JSON.parse(JSON.stringify(scenarioClipboard.scenario)));
            if (gwOnEl && !gwOnEl.checked && (warmOf(row) > 0 || row.dataset.cwarm)) { gwFromRows(); gwApply(); }
            sync(row);
            flash(row, 'Вставлено');
            markDirty();
            scheduleChartUpdate();
            return;
        }

        // Mode switch: Ступени <-> Стадии k6
        if (t.closest('.op-sm-btn')) {
            var smBtn = t.closest('.op-sm-btn');
            setStageMode(row, smBtn.dataset.sm, false);
            refreshStages(row);
            sync(row);
            scheduleChartUpdate();
            return;
        }

        // Ladder generator toggle
        if (t.closest('.op-ladder-toggle')) {
            var lBox = row.querySelector('.op-ladder-box');
            if (lBox) {
                var opening = lBox.classList.contains('d-none');
                lBox.classList.toggle('d-none');
                if (opening) {
                    var firstSt = row.querySelector('.op-steps .op-step');
                    if (firstSt) {
                        var fsVal = calcOr0(firstSt.querySelector('.op-stp-t').value);
                        if (fsVal > 0) { lBox.querySelector('.op-lad-start').value = fsVal; }
                        var fsH = firstSt.querySelector('.op-stp-h').value.trim();
                        if (fsH) { lBox.querySelector('.op-lad-hold').value = fsH; }
                        var fsR = firstSt.querySelector('.op-stp-r').value.trim();
                        if (fsR) { lBox.querySelector('.op-lad-ramp').value = fsR; }
                    }
                }
            }
            return;
        }

        // Step builder actions
        var sBox = row.querySelector('.op-steps');
        var stepChanged = false;
        if (sBox && t.closest('.op-all-ramp')) {
            var rv = t.closest('.op-all-ramp').dataset.val;
            sBox.querySelectorAll('.op-stp-r').forEach(function (inp) { inp.value = rv; });
            stepChanged = true;
        } else if (sBox && t.closest('.op-all-hold')) {
            var hv = t.closest('.op-all-hold').dataset.val;
            sBox.querySelectorAll('.op-stp-h').forEach(function (inp) { inp.value = hv; });
            stepChanged = true;
        } else if (sBox && t.closest('.op-lad-apply')) {
            var lb = row.querySelector('.op-ladder-box');
            var lStart = Math.max(0.01, calcOr0(lb.querySelector('.op-lad-start').value) || (isRateEx(row.querySelector('.op-exec').value) ? 100 : 20));
            var lInc = calcOr0(lb.querySelector('.op-lad-inc').value);
            var lKind = lb.querySelector('.op-lad-kind').value;
            var lCnt = Math.max(1, Math.min(20, parseInt(lb.querySelector('.op-lad-cnt').value, 10) || 5));
            var lHold = lb.querySelector('.op-lad-hold').value.trim() || '5m';
            var lRamp = lb.querySelector('.op-lad-ramp').value.trim() || '0s';
            var uShort = rowUnitShort(row);
            var genHtml = '';
            for (var li = 0; li < lCnt; li++) {
                var lv = lKind === 'pct' ? lStart * (1 + (lInc / 100) * li) : Math.max(0.01, lStart + lInc * li);
                var rounded = lv >= 10 ? Math.round(lv) : Math.round(lv * 100) / 100;
                genHtml += stepRowHtml(rounded, lHold, lRamp, uShort);
            }
            sBox.innerHTML = genHtml;
            lb.classList.add('d-none');
            stepChanged = true;
        } else if (sBox && (t.closest('.op-step-add') || t.closest('.op-step-add20'))) {
            var is20 = !!t.closest('.op-step-add20');
            var curSteps = Array.prototype.slice.call(sBox.querySelectorAll('.op-step'));
            var lastStep = curSteps.length ? curSteps[curSteps.length - 1] : null;
            var prevStep = curSteps.length >= 2 ? curSteps[curSteps.length - 2] : null;
            var lastV = lastStep ? calcOr0(lastStep.querySelector('.op-stp-t').value) : (isRateEx(row.querySelector('.op-exec').value) ? 100 : 20);
            var nextV;
            if (!lastStep) {
                nextV = lastV;
            } else if (!is20 && prevStep) {
                var prevV = calcOr0(prevStep.querySelector('.op-stp-t').value);
                var delta = lastV - prevV;
                nextV = delta > 0 ? Math.round((lastV + delta) * 100) / 100 : Math.max(1, Math.round(lastV * 1.2));
            } else {
                nextV = Math.max(1, Math.round(lastV * 1.2));
            }
            var nHold = lastStep ? (lastStep.querySelector('.op-stp-h').value.trim() || '5m') : '5m';
            var nRamp = lastStep ? (lastStep.querySelector('.op-stp-r').value.trim() || '0s') : '0s';
            sBox.insertAdjacentHTML('beforeend', stepRowHtml(nextV, nHold, nRamp, rowUnitShort(row)));
            stepChanged = true;
        } else if (sBox && t.closest('.op-step-del')) {
            t.closest('.op-step').remove();
            stepChanged = true;
        } else if (sBox && t.closest('.op-step-up')) {
            var sUp = t.closest('.op-step');
            if (sUp.previousElementSibling) { sUp.previousElementSibling.before(sUp); stepChanged = true; }
        } else if (sBox && t.closest('.op-step-down')) {
            var sDn = t.closest('.op-step');
            if (sDn.nextElementSibling) { sDn.nextElementSibling.after(sDn); stepChanged = true; }
        }
        if (stepChanged) {
            syncStepsToRaw(row);
            sync(row);
            markDirty();
            scheduleChartUpdate();
            return;
        }

        // Stage actions
        var c = row.querySelector('.op-stages');
        if (t.closest('.stage-ins')) {
            var cur = t.closest('.op-stage'), above = !!t.closest('.stage-ins-above');
            var idx = Array.prototype.slice.call(c.querySelectorAll('.op-stage')).indexOf(cur);
            if (above && idx < warmOf(row)) { return; }
            cur.insertAdjacentHTML(above ? 'beforebegin' : 'afterend', stageRow(cur.querySelector('.op-st-d').value, cur.querySelector('.op-st-t').value, c.dataset.unit));
        } else if (t.closest('.op-stage-add')) {
            var last = c.querySelector('.op-stage:last-child');
            var ld = last ? last.querySelector('.op-st-d').value : '2m';
            var lt = last ? last.querySelector('.op-st-t').value : 20;
            c.insertAdjacentHTML('beforeend', stageRow(ld, lt, c.dataset.unit));
        } else if (t.closest('.op-stage-hold')) {
            var lastH = c.querySelector('.op-stage:last-child');
            var ltH = lastH ? lastH.querySelector('.op-st-t').value : 20;
            c.insertAdjacentHTML('beforeend', stageRow('5m', ltH, c.dataset.unit));
        } else if (t.closest('.op-stage-step')) {
            var lastS = c.querySelector('.op-stage:last-child');
            var baseS = lastS ? calcOr0(lastS.querySelector('.op-st-t').value) : 50;
            var nextT = Math.max(1, Math.round(baseS * 1.2));
            c.insertAdjacentHTML('beforeend', stageRow('30s', nextT, c.dataset.unit) + stageRow('3m', nextT, c.dataset.unit));
        } else if (t.closest('.op-stage-preset')) {
            var pIdx = +t.closest('.op-stage-preset').dataset.spIdx;
            var firstMainSt = row.querySelector('.op-steps .op-step .op-stp-t');
            var lastP = c.querySelector('.op-stage:last-child');
            var baseP = (row.dataset.stageMode === 'steps' && firstMainSt)
                ? calcOr0(firstMainSt.value)
                : (lastP ? calcOr0(lastP.querySelector('.op-st-t').value) : (isRateEx(row.querySelector('.op-exec').value) ? 50 : 20));
            var list = STAGE_PRESETS[pIdx].fn(baseP);
            if (warmOf(row) > 0) {
                var wRow = c.querySelector('.op-stage');
                var wT = wRow ? calcOr0(wRow.querySelector('.op-st-t').value) : Math.max(1, Math.round(baseP * 0.3));
                list.unshift({duration: gwDur(), target: wT});
            }
            c.innerHTML = list.map(function (s) { return stageRow(s.duration, s.target, c.dataset.unit); }).join('');
            if (row.dataset.stageMode === 'steps') { syncRawToSteps(row); }
        } else if (t.closest('.op-stage-first')) {
            c.insertAdjacentHTML('beforeend', stageRow('1m', 1, c.dataset.unit));
        } else if (t.closest('.op-stage-del')) {
            var curD = t.closest('.op-stage');
            var idxD = Array.prototype.slice.call(c.querySelectorAll('.op-stage')).indexOf(curD);
            if (idxD < warmOf(row)) { return; }
            curD.remove();
        } else if (t.closest('.op-stage-up')) {
            var curU = t.closest('.op-stage');
            var idxU = Array.prototype.slice.call(c.querySelectorAll('.op-stage')).indexOf(curU);
            if (idxU > warmOf(row) && curU.previousElementSibling) { curU.previousElementSibling.before(curU); }
        } else if (t.closest('.op-stage-down')) {
            var curDn = t.closest('.op-stage');
            var idxDn = Array.prototype.slice.call(c.querySelectorAll('.op-stage')).indexOf(curDn);
            if (idxDn >= warmOf(row) && curDn.nextElementSibling) { curDn.nextElementSibling.after(curDn); }
        } else if (t.closest('.op-kv-add')) {
            var b = t.closest('.op-kv-add');
            b.previousElementSibling.insertAdjacentHTML('beforeend', kvRow('', '', b.dataset.ph.split('|')));
        } else if (t.closest('.op-kv-del')) {
            t.closest('.op-kvrow').remove();
        } else {
            return;
        }
        refreshStages(row);
        sync(row);
        markDirty();
        scheduleChartUpdate();
    });

    body.addEventListener('change', function (e) {
        var row = e.target.closest('.op-row');
        if (!row) { return; }
        if (e.target.closest('.op-ladder-box')) { return; }
        if (e.target.classList.contains('op-on')) {
            refreshRow(row);
            scheduleChartUpdate();
            return;
        }
        if (e.target.classList.contains('op-exec')) {
            var exKey = e.target.value, old = collect(row), ex = EX[exKey];
            var state = Object.assign({}, ex.def);
            ex.fields.forEach(function (k) {
                if (k in old && old[k] !== undefined && !(k === 'stages' && !old[k].length)) { state[k] = old[k]; }
            });
            if (isRamp(exKey) && old.warmup && old.warmup.stages && state.stages === old.stages) { state.warmup = {stages: 1}; }
            render(row, exKey, state);
            sync(row);
            scheduleChartUpdate();
            return;
        }
        if (e.target.classList.contains('op-calc')) {
            if (markCalc(e.target) && e.target.value.trim() !== '') {
                var v = calc(e.target.value);
                if (!isNaN(v)) { e.target.value = String(Math.round(v * 1e6) / 1e6); }
                markCalc(e.target);
                if (e.target.closest('.op-steps-pane')) { syncStepsToRaw(row); }
                else { refreshStages(row); }
                sync(row);
                scheduleChartUpdate();
            }
            return;
        }
        if (e.target.closest('.op-steps-pane')) { syncStepsToRaw(row); }
        sync(row);
        scheduleChartUpdate();
    });

    body.addEventListener('input', function (e) {
        if (e.target.closest && e.target.closest('.op-ladder-box')) { return; }
        if (e.target.classList && e.target.classList.contains('op-calc')) { markCalc(e.target); }
        var row = e.target.closest('.op-row');
        if (!row) { return; }
        if (e.target.dataset && e.target.dataset.k === 'timeUnit') { updateRowUnits(row); }
        if (e.target.closest('.op-steps-pane')) { syncStepsToRaw(row); }
        else if (e.target.classList.contains('op-st-d')) { refreshStages(row); }
        sync(row);
        scheduleChartUpdate();
    });

    body.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && e.target.closest && e.target.closest('.op-ladder-box')) {
            e.preventDefault();
            var applyBtn = e.target.closest('.op-ladder-box').querySelector('.op-lad-apply');
            if (applyBtn) { applyBtn.click(); }
        }
    });

    // Search & collapse-all operations
    var opSearch = document.getElementById('ld-op-search');
    if (opSearch) {
        opSearch.addEventListener('input', function () {
            var q = opSearch.value.trim().toLowerCase();
            body.querySelectorAll('.op-row').forEach(function (r) {
                var name = (r.querySelector('input[name$=".name"]').value || '').toLowerCase();
                var fn = (r.dataset.fn || '').toLowerCase();
                r.classList.toggle('d-none', !!q && name.indexOf(q) < 0 && fn.indexOf(q) < 0);
            });
        });
    }
    var btnCollapseAll = document.getElementById('ld-collapse-all');
    if (btnCollapseAll) {
        btnCollapseAll.addEventListener('click', function () {
            var rows = Array.prototype.slice.call(body.querySelectorAll('.op-row'));
            var anyOpen = rows.some(function (r) { return !r.classList.contains('is-collapsed'); });
            rows.forEach(function (r) { r.classList.toggle('is-collapsed', anyOpen); });
            btnCollapseAll.innerHTML = anyOpen
                ? '<i class="bi bi-arrows-expand me-1"></i>Развернуть все'
                : '<i class="bi bi-arrows-collapse me-1"></i>Свернуть все';
        });
    }

    // ==================== Dual-Mode Env & Secrets Editor ====================
    var envTa = document.getElementById('ld-env-ta');
    var envRowsBox = document.getElementById('ld-env-rows');
    var envTablePane = document.getElementById('ld-env-table-pane');
    var envRawPane = document.getElementById('ld-env-raw-pane');
    var envNavCnt = document.getElementById('ld-nav-env-cnt');
    var lastEnvValInput = null;

    function parseEnvText(text) {
        var out = [];
        String(text || '').split(/\r?\n/).forEach(function (line) {
            var s = line.trim();
            if (!s) { return; }
            var eq = s.indexOf('=');
            if (eq > 0) { out.push({k: s.slice(0, eq).trim(), v: s.slice(eq + 1)}); }
            else { out.push({k: s, v: ''}); }
        });
        return out;
    }

    function renderEnvRows() {
        if (!envRowsBox || !envTa) { return; }
        var pairs = parseEnvText(envTa.value);
        if (envNavCnt) { envNavCnt.textContent = String(pairs.length); }
        if (pairs.length === 0) {
            envRowsBox.innerHTML = '<div class="text-muted small py-1">Переменных пока нет — нажмите «+ Переменная» или выберите секрет ниже.</div>';
            return;
        }
        envRowsBox.innerHTML = pairs.map(function (p) {
            return envRowHtml(p.k, p.v);
        }).join('');
    }

    function envRowHtml(k, v) {
        return '<div class="ld-env-row">' +
            '<input class="form-control form-control-sm font-monospace ld-env-k" placeholder="BASE_URL" value="' + esc(k) + '">' +
            '<input class="form-control form-control-sm font-monospace ld-env-v" placeholder="https://stand.example или ${secret:TOKEN}" value="' + esc(v) + '">' +
            '<button type="button" class="btn btn-sm btn-outline-danger ld-env-del" title="Удалить"><i class="bi bi-x-lg"></i></button>' +
            '</div>';
    }

    function syncEnvToTextarea() {
        if (!envRowsBox || !envTa) { return; }
        var lines = [];
        envRowsBox.querySelectorAll('.ld-env-row').forEach(function (r) {
            var k = r.querySelector('.ld-env-k').value.trim();
            var v = r.querySelector('.ld-env-v').value;
            if (k) { lines.push(k + '=' + v); }
        });
        envTa.value = lines.join('\n');
        if (envNavCnt) { envNavCnt.textContent = String(lines.length); }
    }

    if (envRowsBox && envTa) {
        renderEnvRows();
        envRowsBox.addEventListener('focusin', function (e) {
            if (e.target.classList.contains('ld-env-v')) { lastEnvValInput = e.target; }
        });
        envRowsBox.addEventListener('input', function () { syncEnvToTextarea(); markDirty(); });
        envRowsBox.addEventListener('click', function (e) {
            if (e.target.closest('.ld-env-del')) {
                e.target.closest('.ld-env-row').remove();
                syncEnvToTextarea();
                if (!envRowsBox.querySelector('.ld-env-row')) { renderEnvRows(); }
                markDirty();
            }
        });
        document.getElementById('ld-env-add').addEventListener('click', function () {
            if (!envRowsBox.querySelector('.ld-env-row')) { envRowsBox.innerHTML = ''; }
            envRowsBox.insertAdjacentHTML('beforeend', envRowHtml('', ''));
            var lastK = envRowsBox.querySelector('.ld-env-row:last-child .ld-env-k');
            if (lastK) { lastK.focus(); }
        });
        envTa.addEventListener('input', function () {
            if (envNavCnt) { envNavCnt.textContent = String(parseEnvText(envTa.value).length); }
        });
        document.querySelectorAll('[data-env-mode]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var raw = btn.dataset.envMode === 'raw';
                document.querySelectorAll('[data-env-mode]').forEach(function (b) { b.classList.toggle('active', b === btn); });
                if (raw) { syncEnvToTextarea(); }
                else { renderEnvRows(); }
                envTablePane.classList.toggle('d-none', raw);
                envRawPane.classList.toggle('d-none', !raw);
            });
        });
        document.querySelectorAll('[data-secret-name]').forEach(function (chip) {
            chip.addEventListener('click', function () {
                var name = chip.dataset.secretName;
                var token = '${secret:' + name + '}';
                if (!envRawPane.classList.contains('d-none')) {
                    envTa.value = (envTa.value.trim() ? envTa.value.trim() + '\n' : '') + name + '=' + token;
                    if (envNavCnt) { envNavCnt.textContent = String(parseEnvText(envTa.value).length); }
                    markDirty();
                    return;
                }
                if (lastEnvValInput && document.body.contains(lastEnvValInput)) {
                    lastEnvValInput.value = token;
                    lastEnvValInput.focus();
                } else {
                    if (!envRowsBox.querySelector('.ld-env-row')) { envRowsBox.innerHTML = ''; }
                    envRowsBox.insertAdjacentHTML('beforeend', envRowHtml(name, token));
                }
                syncEnvToTextarea();
                markDirty();
            });
        });
    }

    // ==================== Autostop Cards & Custom Prometheus Checks ====================
    var stopCards = document.querySelectorAll('.ld-stop-card');
    var stopNavCnt = document.getElementById('ld-nav-stop-cnt');
    var stopConsecEl = document.querySelector('input[name="stop.consecutive"]');
    var stopEtaEl = document.getElementById('ld-stop-eta');
    var ccRowsBox = document.getElementById('ld-cc-rows');
    var ccAddBtn = document.getElementById('ld-cc-add');
    var ccPresetsMenu = document.getElementById('ld-cc-presets');

    var CC_PRESETS = {
        cpu: {
            name: 'Утилизация CPU серверов',
            promql: '100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[{range}])) * 100)',
            cmp: 'LTE', threshold: 80, unit: '%', stepSla: true, autoStop: false
        },
        ram: {
            name: 'Использование RAM серверов',
            promql: '(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100',
            cmp: 'LTE', threshold: 85, unit: '%', stepSla: true, autoStop: false
        },
        disk: {
            name: 'Утилизация диска (I/O)',
            promql: 'max by (instance) (rate(node_disk_io_time_seconds_total[{range}])) * 100',
            cmp: 'LTE', threshold: 85, unit: '%', stepSla: true, autoStop: false
        },
        jvm: {
            name: 'JVM Heap Used',
            promql: 'sum by (instance) (jvm_memory_used_bytes{area="heap"}) / sum by (instance) (jvm_memory_max_bytes{area="heap"}) * 100',
            cmp: 'LTE', threshold: 85, unit: '%', stepSla: true, autoStop: false
        },
        db: {
            name: 'Активные соединения БД (HikariCP)',
            promql: 'hikaricp_connections_active / hikaricp_connections_max * 100',
            cmp: 'LTE', threshold: 90, unit: '%', stepSla: true, autoStop: false
        }
    };

    function reindexCustomChecks() {
        if (!ccRowsBox) { return; }
        ccRowsBox.querySelectorAll('.ld-cc-row').forEach(function (row, idx) {
            var p = 'stop.customChecks[' + idx + ']';
            var n = row.querySelector('.ld-cc-name'); if (n) { n.name = p + '.name'; }
            var q = row.querySelector('.ld-cc-promql'); if (q) { q.name = p + '.promql'; }
            var c = row.querySelector('.ld-cc-cmp'); if (c) { c.name = p + '.cmp'; }
            var t = row.querySelector('.ld-cc-thr'); if (t) { t.name = p + '.threshold'; }
            var u = row.querySelector('.ld-cc-unit'); if (u) { u.name = p + '.unit'; }
            var s = row.querySelector('.ld-cc-sla'); if (s) { s.name = p + '.stepSla'; }
            var a = row.querySelector('.ld-cc-stop'); if (a) { a.name = p + '.autoStop'; }
        });
    }

    function addCustomCheckRow(preset) {
        if (!ccRowsBox) { return; }
        var p = preset || { name: '', promql: '', cmp: 'LTE', threshold: 80, unit: '%', stepSla: true, autoStop: false };
        var idx = ccRowsBox.querySelectorAll('.ld-cc-row').length;
        var html = '<div class="ld-cc-row p-2 rounded border bg-light-subtle">' +
            '<div class="row g-2 align-items-center">' +
                '<div class="col-md-3">' +
                    '<input class="form-control form-control-sm ld-cc-name" name="stop.customChecks[' + idx + '].name" value="' + esc(p.name) + '" maxlength="120" placeholder="Название (например CPU серверов)">' +
                '</div>' +
                '<div class="col-md-4">' +
                    '<input class="form-control form-control-sm font-monospace ld-cc-promql" name="stop.customChecks[' + idx + '].promql" value="' + esc(p.promql) + '" placeholder="PromQL (доступны {range}, {run_id}, $var)">' +
                '</div>' +
                '<div class="col-md-2">' +
                    '<div class="input-group input-group-sm">' +
                        '<select class="form-select ld-cc-cmp" style="max-width:64px" name="stop.customChecks[' + idx + '].cmp">' +
                            '<option value="LTE"' + (p.cmp === 'LTE' ? ' selected' : '') + '>≤</option>' +
                            '<option value="LT"' + (p.cmp === 'LT' ? ' selected' : '') + '>&lt;</option>' +
                            '<option value="GTE"' + (p.cmp === 'GTE' ? ' selected' : '') + '>≥</option>' +
                            '<option value="GT"' + (p.cmp === 'GT' ? ' selected' : '') + '>&gt;</option>' +
                        '</select>' +
                        '<input class="form-control ld-cc-thr" type="number" step="any" name="stop.customChecks[' + idx + '].threshold" value="' + esc(p.threshold) + '" placeholder="80">' +
                        '<input class="form-control ld-cc-unit" style="max-width:52px" name="stop.customChecks[' + idx + '].unit" value="' + esc(p.unit) + '" maxlength="16" placeholder="%">' +
                    '</div>' +
                '</div>' +
                '<div class="col-md-3 d-flex align-items-center justify-content-end gap-2">' +
                    '<div class="form-check form-check-inline m-0" title="Влияет на успешность ступени в отчёте и поиск максимума">' +
                        '<input class="form-check-input ld-cc-sla" type="checkbox" value="true" name="stop.customChecks[' + idx + '].stepSla"' + (p.stepSla !== false ? ' checked' : '') + '>' +
                        '<label class="form-check-label small">SLA отчёта</label>' +
                    '</div>' +
                    '<div class="form-check form-check-inline m-0" title="Останавливать тест при нарушении N проверок подряд">' +
                        '<input class="form-check-input ld-cc-stop" type="checkbox" value="true" name="stop.customChecks[' + idx + '].autoStop"' + (p.autoStop ? ' checked' : '') + '>' +
                        '<label class="form-check-label small">Автостоп</label>' +
                    '</div>' +
                    '<button type="button" class="btn btn-sm btn-outline-danger py-0 px-2 ld-cc-del" title="Удалить правило"><i class="bi bi-x-lg"></i></button>' +
                '</div>' +
            '</div>' +
        '</div>';
        ccRowsBox.insertAdjacentHTML('beforeend', html);
        refreshAutostop();
        markDirty();
        var lastInp = ccRowsBox.querySelector('.ld-cc-row:last-child .ld-cc-name');
        if (lastInp) { lastInp.focus(); }
    }

    if (ccAddBtn) {
        ccAddBtn.addEventListener('click', function () { addCustomCheckRow(null); });
    }
    if (ccPresetsMenu) {
        ccPresetsMenu.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-cc-preset]');
            if (!btn) { return; }
            var p = CC_PRESETS[btn.dataset.ccPreset];
            if (p) { addCustomCheckRow(p); }
        });
    }
    if (ccRowsBox) {
        ccRowsBox.addEventListener('click', function (e) {
            var del = e.target.closest('.ld-cc-del');
            if (del) {
                del.closest('.ld-cc-row').remove();
                reindexCustomChecks();
                refreshAutostop();
                markDirty();
            }
        });
        ccRowsBox.addEventListener('change', refreshAutostop);
    }

    function refreshAutostop() {
        var onCount = 0;
        stopCards.forEach(function (card) {
            var cb = card.querySelector('input[type="checkbox"]');
            var on = !!(cb && cb.checked);
            card.classList.toggle('is-on', on);
            if (on) { onCount++; }
        });
        if (ccRowsBox) {
            onCount += ccRowsBox.querySelectorAll('.ld-cc-row').length;
        }
        if (stopNavCnt) { stopNavCnt.textContent = String(onCount); }
        if (stopEtaEl && stopConsecEl) {
            var n = Math.max(1, parseInt(stopConsecEl.value, 10) || 4);
            stopEtaEl.textContent = '≈ ' + durText(n * 15) + ' после начала нарушения';
        }
    }
    stopCards.forEach(function (card) {
        var cb = card.querySelector('input[type="checkbox"]');
        if (cb) { cb.addEventListener('change', refreshAutostop); }
    });
    if (stopConsecEl) { stopConsecEl.addEventListener('input', refreshAutostop); }
    refreshAutostop();

    // ==================== Import Dropzone ====================
    var impDz = document.getElementById('ld-import-dz');
    var impFile = document.getElementById('ld-import-file');
    var impLbl = document.getElementById('ld-import-dz-lbl');
    if (impDz && impFile) {
        impDz.addEventListener('click', function () { impFile.click(); });
        impDz.addEventListener('dragover', function (e) { e.preventDefault(); impDz.classList.add('drag'); });
        impDz.addEventListener('dragleave', function () { impDz.classList.remove('drag'); });
        impDz.addEventListener('drop', function (e) {
            e.preventDefault();
            impDz.classList.remove('drag');
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                impFile.files = e.dataTransfer.files;
                impLbl.innerHTML = '<span class="picked"><i class="bi bi-file-earmark-check me-1"></i>' + esc(e.dataTransfer.files[0].name) + '</span>';
            }
        });
        impFile.addEventListener('change', function () {
            if (impFile.files && impFile.files.length > 0) {
                impLbl.innerHTML = '<span class="picked"><i class="bi bi-file-earmark-check me-1"></i>' + esc(impFile.files[0].name) + '</span>';
            }
        });
    }
    var impModal = document.getElementById('ld-import-modal');
    if (impModal && impModal.dataset.autoOpen === 'true') {
        bootstrap.Modal.getOrCreateInstance(impModal).show();
    }
    var impSeqCb = document.getElementById('ld-imp-seq');
    var impSeqOpts = document.getElementById('ld-imp-seq-opts');
    var impSeqBtn = document.getElementById('ld-imp-submit');
    var impAt = document.getElementById('ld-imp-at');
    var impIso = document.getElementById('ld-imp-iso');
    if (impSeqCb && impSeqOpts) {
        impSeqCb.addEventListener('change', function () {
            var on = impSeqCb.checked;
            impSeqOpts.classList.toggle('d-none', !on);
            if (impSeqBtn) {
                impSeqBtn.innerHTML = on
                    ? '<i class="bi bi-calendar-plus me-1"></i>Импортировать и запланировать'
                    : '<i class="bi bi-upload me-1"></i>Импортировать';
            }
        });
    }
    if (impAt && impIso) {
        impAt.addEventListener('input', function () {
            var d = new Date(impAt.value);
            impIso.value = isNaN(d) ? '' : d.toISOString();
        });
    }
    document.querySelectorAll('[data-fill-example]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var src = document.getElementById(btn.dataset.fillExample);
            var dst = document.getElementById(btn.dataset.fillTarget);
            if (src && dst) { dst.value = src.textContent.trim(); dst.focus(); }
        });
    });
    document.querySelectorAll('[data-copy-example]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var src = document.getElementById(btn.dataset.copyExample);
            if (!src || !navigator.clipboard) { return; }
            navigator.clipboard.writeText(src.textContent.trim()).then(function () {
                var prev = btn.innerHTML;
                btn.innerHTML = '<i class="bi bi-check2 me-1"></i>Скопировано';
                setTimeout(function () { btn.innerHTML = prev; }, 1600);
            });
        });
    });

    // ==================== Dirty State, Submit & Profiles ====================
    var form = body.closest('form');
    var dirty = false, submitting = false;
    function markDirty() { dirty = true; if (form) { form.classList.add('is-dirty'); } }
    if (form) {
        var onFormMutate = function (e) {
            if (e.target && e.target.closest && e.target.closest('.op-ladder-box')) { return; }
            markDirty();
        };
        form.addEventListener('input', onFormMutate);
        form.addEventListener('change', onFormMutate);
        form.addEventListener('submit', function (ev) {
            var bad = Array.prototype.slice.call(body.querySelectorAll('.op-calc.is-invalid')).find(function (el) {
                return !el.closest('.d-none');
            });
            if (bad) {
                ev.preventDefault();
                var card = bad.closest('.op-row');
                if (card) { card.classList.remove('is-collapsed'); }
                bad.focus();
                alert('Не удаётся вычислить выражение в числовом поле. Допустимы числа и знаки + - * / ( ).');
                return;
            }
            if (envTablePane && !envTablePane.classList.contains('d-none')) { syncEnvToTextarea(); }
            submitting = true;
            body.querySelectorAll('.op-row').forEach(sync);
        });
        document.addEventListener('keydown', function (e) {
            if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
                if (document.activeElement && document.activeElement.closest('#config-editor')) { return; }
                e.preventDefault();
                form.requestSubmit ? form.requestSubmit() : form.submit();
            }
        });
        window.addEventListener('beforeunload', function (e) {
            if (dirty && !submitting) { e.preventDefault(); e.returnValue = ''; }
        });
    }

    gwFromJson();
    body.querySelectorAll('.op-row').forEach(initRow);
    refreshAll();
    updateDashboard();

    dirty = false;
    if (form) { form.classList.remove('is-dirty'); }
    if (form && form.dataset.fromProfile) { markDirty(); }

    var pick = document.getElementById('profile-pick');
    if (pick) {
        var openProfile = function () {
            if (!pick.value) { return; }
            if (dirty && !confirm('Несохранённые изменения формы будут заменены профилем. Продолжить?')) {
                pick.value = pick.dataset.current || '';
                return;
            }
            dirty = false;
            location.href = pick.dataset.openUrl + '?profile=' + encodeURIComponent(pick.value);
        };
        pick.dataset.current = pick.value;
        pick.addEventListener('change', openProfile);
        document.getElementById('profile-delete').addEventListener('click', function (e) {
            var opt = pick.options[pick.selectedIndex];
            if (!pick.value) { e.preventDefault(); return; }
            if (!confirm('Удалить профиль «' + opt.textContent + '»?')) { e.preventDefault(); return; }
            document.getElementById('profile-id').value = pick.value;
        });
        document.getElementById('profile-save').addEventListener('click', function (e) {
            var name = document.getElementById('profile-name');
            if (!name.value.trim()) { e.preventDefault(); name.focus(); name.classList.add('is-invalid'); return; }
            name.classList.remove('is-invalid');
            if (envTablePane && !envTablePane.classList.contains('d-none')) { syncEnvToTextarea(); }
            body.querySelectorAll('.op-row').forEach(sync);
        });
    }

    // ==================== Profile Manager Cards Gallery ====================
    var profMgrBtn = document.getElementById('ld-prof-mgr-btn');
    var profWrap = document.getElementById('ld-prof-gallery-wrap');
    var profGallery = document.getElementById('ld-prof-gallery');
    var profInitEl = document.getElementById('ld-prof-init');
    if (profMgrBtn && profWrap && profGallery && profInitEl) {
        var profList = [];
        try { profList = JSON.parse(profInitEl.textContent || '[]'); } catch (e) { profList = []; }
        var loadUrl = profWrap.dataset.loadUrl;
        var runUrl = profWrap.dataset.runUrl;
        var loadedId = profWrap.dataset.loadedId ? Number(profWrap.dataset.loadedId) : null;

        function summarizeProfile(p) {
            var d = p.data || {};
            var ops = Array.isArray(d.operations) ? d.operations.filter(function (o) { return o && o.enabled !== false; }) : [];
            var envCnt = d.env && typeof d.env === 'object' ? Object.keys(d.env).length : 0;
            var peakRate = 0, peakVu = 0, maxDur = 0, warmStr = '';
            ops.forEach(function (o) {
                var sc = o.scenario || {};
                var ex = o.executor || 'constant-arrival-rate';
                var startS = durSec(sc.startTime) || 0;
                if (ex === 'constant-arrival-rate') {
                    var tu = durSec(sc.timeUnit) || 1;
                    peakRate += (Number(sc.rate != null ? sc.rate : o.ratePerSec) || 0) / tu;
                    maxDur = Math.max(maxDur, startS + (durSec(sc.duration) || 0));
                    if (sc.warmup && sc.warmup.duration) { warmStr = sc.warmup.duration; }
                } else if (ex === 'ramping-arrival-rate') {
                    var tu2 = durSec(sc.timeUnit) || 1;
                    var best = (Number(sc.startRate) || 0) / tu2, t = startS;
                    (sc.stages || []).forEach(function (s, idx) {
                        t += durSec(s.duration) || 0;
                        best = Math.max(best, (Number(s.target) || 0) / tu2);
                        if (idx === 0 && sc.warmup && sc.warmup.stages) { warmStr = s.duration || ''; }
                    });
                    peakRate += best;
                    maxDur = Math.max(maxDur, t);
                } else if (ex === 'ramping-vus') {
                    var bestV = Number(sc.startVUs) || 0, t2 = startS;
                    (sc.stages || []).forEach(function (s, idx) {
                        t2 += durSec(s.duration) || 0;
                        bestV = Math.max(bestV, Number(s.target) || 0);
                        if (idx === 0 && sc.warmup && sc.warmup.stages) { warmStr = s.duration || ''; }
                    });
                    peakVu += bestV;
                    maxDur = Math.max(maxDur, t2);
                } else {
                    peakVu += Number(sc.vus) || 0;
                    maxDur = Math.max(maxDur, startS + (durSec(sc.duration || sc.maxDuration) || 0));
                    if (sc.warmup && sc.warmup.duration) { warmStr = sc.warmup.duration; }
                }
            });
            return {ops: ops.length, envCnt: envCnt, peakRate: peakRate, peakVu: peakVu, maxDur: maxDur, warmStr: warmStr};
        }

        function postForm(action, fields) {
            var f = document.createElement('form');
            f.method = 'post';
            f.action = action;
            var meta = document.querySelector('meta[name="_csrf"]');
            if (meta) { fields._csrf = meta.content; }
            Object.keys(fields).forEach(function (k) {
                var i = document.createElement('input');
                i.type = 'hidden'; i.name = k; i.value = fields[k];
                f.appendChild(i);
            });
            document.body.appendChild(f);
            submitting = true;
            f.submit();
        }

        function renderProfiles() {
            profGallery.innerHTML = profList.map(function (p) {
                var s = summarizeProfile(p);
                var isCur = loadedId === p.id;
                var upd = p.updatedAt ? new Date(p.updatedAt).toLocaleString('ru-RU', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'}) : '';
                return '<div class="ld-prof-card' + (isCur ? ' active' : '') + '" data-pid="' + p.id + '" data-pname="' + esc(p.name) + '">' +
                    '<div class="ld-prof-head">' +
                        '<div class="ld-prof-name">' + esc(p.name) + '</div>' +
                        (isCur ? '<span class="badge text-bg-primary">Открыт</span>' : '') +
                    '</div>' +
                    '<div class="ld-prof-badges">' +
                        '<span class="ld-prof-badge"><i class="bi bi-layers text-primary"></i>' + s.ops + ' оп.</span>' +
                        (s.peakRate > 0 ? '<span class="ld-prof-badge"><i class="bi bi-lightning-charge text-primary"></i>' + fmtNum(s.peakRate) + ' оп/с</span>' : '') +
                        (s.peakVu > 0 ? '<span class="ld-prof-badge"><i class="bi bi-people text-primary"></i>' + fmtNum(s.peakVu) + ' VU</span>' : '') +
                        (s.maxDur > 0 ? '<span class="ld-prof-badge"><i class="bi bi-clock"></i>' + durText(s.maxDur) + '</span>' : '') +
                        (s.warmStr ? '<span class="ld-prof-badge warm"><i class="bi bi-fire"></i>' + esc(s.warmStr) + '</span>' : '') +
                        (s.envCnt > 0 ? '<span class="ld-prof-badge">Env: ' + s.envCnt + '</span>' : '') +
                    '</div>' +
                    (upd || p.updatedBy ? '<div class="ld-prof-meta">' + (upd ? 'Обновлён ' + esc(upd) : '') + (p.updatedBy ? ' · ' + esc(p.updatedBy) : '') + '</div>' : '') +
                    '<div class="ld-prof-actions">' +
                        '<button type="button" class="btn btn-outline-primary ld-p-open"><i class="bi bi-folder2-open me-1"></i>Открыть</button>' +
                        '<a class="btn btn-outline-success" href="' + esc(runUrl) + '?profile=' + p.id + '" title="Запустить сразу с этим профилем"><i class="bi bi-play-fill"></i></a>' +
                        '<a class="btn btn-outline-primary" href="' + esc(runUrl) + '?profile=' + p.id + '#schedule" title="Запланировать запуск с этим профилем"><i class="bi bi-calendar-plus"></i></a>' +
                        '<button type="button" class="btn btn-outline-secondary ld-p-dup" title="Дублировать профиль"><i class="bi bi-copy"></i></button>' +
                        '<button type="button" class="btn btn-outline-secondary ld-p-ren" title="Переименовать профиль"><i class="bi bi-pencil"></i></button>' +
                        '<button type="button" class="btn btn-outline-danger ms-auto ld-p-del" title="Удалить профиль"><i class="bi bi-trash"></i></button>' +
                    '</div>' +
                '</div>';
            }).join('');
        }

        renderProfiles();
        profMgrBtn.addEventListener('click', function () {
            var open = profWrap.classList.toggle('d-none') === false;
            profMgrBtn.classList.toggle('active', open);
        });
        profGallery.addEventListener('click', function (e) {
            var card = e.target.closest('.ld-prof-card');
            if (!card) { return; }
            var pid = card.dataset.pid, pname = card.dataset.pname;
            if (e.target.closest('.ld-p-open')) {
                if (dirty && !confirm('Несохранённые изменения формы будут заменены профилем «' + pname + '». Продолжить?')) { return; }
                dirty = false;
                location.href = loadUrl + '?profile=' + encodeURIComponent(pid);
            } else if (e.target.closest('.ld-p-dup')) {
                postForm(loadUrl + '/profile/' + pid + '/duplicate', {});
            } else if (e.target.closest('.ld-p-ren')) {
                var next = prompt('Новое название профиля:', pname);
                if (next != null && next.trim() && next.trim() !== pname) {
                    postForm(loadUrl + '/profile/' + pid + '/rename', {name: next.trim()});
                }
            } else if (e.target.closest('.ld-p-del')) {
                if (!confirm('Удалить профиль «' + pname + '»?')) { return; }
                postForm(loadUrl + '/profile/delete', {profile: pid});
            }
        });
    }
})();
