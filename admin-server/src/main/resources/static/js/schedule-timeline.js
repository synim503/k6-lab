// Таймлайн занятости генераторов: строки - генераторы, полосы - идущие и запланированные запуски (с профилем нагрузки в подписи).
// Используется на форме планирования (interactive: выбор времени кликом/перетаскиванием и проверка «хватит ли свободных») и на странице расписания (только просмотр).
// Разметка: .tl-root[data-url, data-run-url, data-schedule-url] с .tl-body, .tl-note, .tl-detail, .tl-range, .tl-next.
(function () {
    var CSS = ''
        + '.tl { border: 1px solid var(--bs-border-color); border-radius: .375rem; padding: .25rem; font-size: .8rem; user-select: none; }'
        + '.tl-body { position: relative; max-height: 560px; overflow-y: auto; }'
        + '.tl-body.pick { cursor: crosshair; touch-action: none; }'
        + '.tl-axis, .tl-row { display: flex; align-items: stretch; height: 26px; }'
        + '.tl-axis { position: sticky; top: 0; z-index: 3; background: var(--bs-body-bg); }'
        + '.tl-lbl { width: 230px; flex: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 4px 8px 0 4px; color: var(--bs-secondary-color); }'
        + '.tl-trk { position: relative; flex: 1; min-width: 0; }'
        + '.tl-row .tl-trk { background: var(--bs-tertiary-bg); border-bottom: 2px solid var(--bs-body-bg); }'
        + '.tl-row.off .tl-trk { background: repeating-linear-gradient(45deg, var(--bs-tertiary-bg), var(--bs-tertiary-bg) 6px, var(--bs-secondary-bg) 6px, var(--bs-secondary-bg) 12px); }'
        + '.tl-row.off .tl-lbl { opacity: .55; }'
        + '.tl-row.pool .tl-lbl { color: #c2410c; font-weight: 600; }'
        + '.tl-tick { position: absolute; top: 4px; transform: translateX(-50%); font-size: .72rem; color: var(--bs-secondary-color); white-space: nowrap; }'
        + '.tl-line { position: absolute; top: 0; bottom: 0; width: 1px; background: var(--bs-border-color); opacity: .7; }'
        + '.tl-now { position: absolute; top: 0; bottom: 0; width: 2px; background: #dc3545; opacity: .8; z-index: 1; }'
        + '.tl-bar { position: absolute; top: 3px; bottom: 3px; border-radius: 3px; color: #fff; padding: 0 5px; overflow: hidden; white-space: nowrap;'
        + '  text-overflow: ellipsis; line-height: 20px; font-size: .74rem; cursor: pointer; z-index: 1; }'
        + '.tl-bar.sel-bar { outline: 2px solid var(--bs-body-color); }'
        + '.tl-bar.run, .tl-key.run { background: #0d6efd; }'
        + '.tl-bar.scheduled, .tl-key.scheduled { background: repeating-linear-gradient(45deg, #fd7e14, #fd7e14 6px, #e8590c 6px, #e8590c 12px); }'
        + '.tl-ov { position: absolute; left: 230px; right: 0; top: 26px; pointer-events: none; z-index: 2; }'
        + '.tl-sel { position: absolute; top: 0; bottom: 0; border: 2px solid; border-radius: 3px; }'
        + '.tl-sel.ok { background: rgba(25, 135, 84, .18); border-color: #198754; }'
        + '.tl-sel.bad { background: rgba(220, 53, 69, .18); border-color: #dc3545; }'
        + '.tl-key { display: inline-block; width: 14px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: -1px; }'
        + '.tl-key.off { background: repeating-linear-gradient(45deg, var(--bs-tertiary-bg), var(--bs-tertiary-bg) 3px, var(--bs-secondary-bg) 3px, var(--bs-secondary-bg) 6px); border: 1px solid var(--bs-border-color); }'
        + '.tl-key.sel { background: rgba(25, 135, 84, .25); border: 2px solid #198754; }'
        + '.tl-key.now { background: #dc3545; width: 3px; }'
        + '.tl-detail { font-size: .85rem; }'
        + '.tl-detail dl { display: grid; grid-template-columns: 150px 1fr; gap: .15rem .75rem; margin: 0; }'
        + '.tl-detail dt { color: var(--bs-secondary-color); font-weight: 400; }'
        + '.tl-detail dd { margin: 0; word-break: break-word; }';

    function pad(n) { return n < 10 ? '0' + n : '' + n; }
    function hm(ms) { var d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
    function dm(ms) { var d = new Date(ms); return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + ' ' + hm(ms); }
    function localVal(ms) { var d = new Date(ms); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + hm(ms); }
    function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
    function minutes(sec) { return Math.round(sec / 60); }

    function init(root, opts) {
        opts = opts || {};
        var body = root.querySelector('.tl-body'), note = root.querySelector('.tl-note'), detail = root.querySelector('.tl-detail');
        var at = opts.at || null, cnt = opts.cnt || null, prof = opts.prof || null;
        var interactive = !!at;
        var ROW = 26, STEP = 5 * 60000, LEAD = 60000;
        var data = null, hours = Number(opts.hours || 6), w0 = 0, ov = null, dragging = false, timer = null, shown = null;
        if (interactive) { body.classList.add('pick'); }

        function chosen() { if (!at) { return null; } var d = new Date(at.value); return isNaN(d) ? null : d.getTime(); }
        function picks() {
            var ids = [];
            document.querySelectorAll('#schedule input[name=generatorId]:checked').forEach(function (c) { ids.push(Number(c.value)); });
            return ids;
        }
        function need() {
            var ids = picks();
            if (ids.length) { return {ids: ids, n: ids.length}; }
            var n = parseInt(cnt && cnt.value, 10);
            return {ids: [], n: n > 0 ? n : 1};
        }
        function ceil5(ms) { return Math.ceil(ms / STEP) * STEP; }
        function overlap(b, s, e) { return b.from < e && b.to > s; }
        function mineEnd(s) { return s + (data.durationSec + data.tailSec) * 1000; }

        // сколько генераторов свободно на весь интервал запуска, начиная с s
        function freeAt(s) {
            var e = mineEnd(s), nd = need();
            var free = 0, freeIds = {}, pool = 0, online = 0;
            data.generators.forEach(function (g) {
                if (!g.online) { return; }
                online++;
                var n = 0;
                data.bars.forEach(function (b) { if (b.generatorId === g.id && overlap(b, s, e)) { n++; } });
                if (n < g.slots) { free++; freeIds[g.id] = 1; }
            });
            // запланированный запуск «любые свободные» занимает count генераторов на своё время
            var seen = {};
            data.bars.forEach(function (b) { if (b.generatorId === null && overlap(b, s, e) && !seen[b.scheduleId]) { seen[b.scheduleId] = 1; pool += b.count; } });
            var avail = Math.max(0, free - pool), ok, bad = [];
            if (nd.ids.length) {
                nd.ids.forEach(function (id) { if (!freeIds[id]) { bad.push(id); } });
                ok = bad.length === 0;
            } else {
                ok = avail >= nd.n;
            }
            return {ok: ok, avail: avail, need: nd.n, online: online, bad: bad, pool: pool, end: e};
        }

        function barDetail(b) {
            var rows = [];
            function row(k, v) { if (v !== undefined && v !== null && v !== '') { rows.push('<dt>' + k + '</dt><dd>' + v + '</dd>'); } }
            var runUrl = root.dataset.runUrl || '/runs/', schUrl = root.dataset.scheduleUrl || '/schedule';
            var head = (b.kind === 'run' ? '<span class="badge text-bg-primary">идёт сейчас</span>' : '<span class="badge" style="background:#fd7e14">запланирован</span>')
                + ' <b>' + esc(b.script) + '</b>';
            row('Профиль нагрузки', b.profile ? esc(b.profile) : 'нагрузка скрипта');
            row(b.kind === 'run' ? 'Идёт' : 'Старт', dm(b.from) + ' — ' + dm(b.to) + ' (занимает генераторы, ~' + minutes((b.to - b.from) / 1000) + ' мин с запасом)');
            row('Статус', b.status === 'STARTING' ? 'запускается' : b.status === 'PENDING' ? 'ожидает назначенного времени' : b.status);
            row('Генераторов', b.genCount ? b.genCount + (b.genNames ? ': ' + esc(b.genNames) : ' (любые свободные на момент старта)') : '');
            row('Интенсивность', b.intensity ? esc(b.intensity) : '');
            row('Переменные', b.env ? esc(b.env) : '');
            row('Назначил', b.by ? esc(b.by) : '');
            if (b.kind === 'run' && b.scheduled) { row('Источник', 'запуск по расписанию'); }
            row('Ссылка', b.runId ? '<a href="' + runUrl + b.runId + '">запуск #' + b.runId + '</a>' : b.scheduleId ? '<a href="' + schUrl + '">запись расписания #' + b.scheduleId + '</a>' : '');
            return '<div class="card card-body py-2 mt-2"><div class="mb-1">' + head + '</div><dl>' + rows.join('') + '</dl></div>';
        }
        function showDetail(b) {
            if (!detail) { return; }
            shown = b ? (b.runId ? 'r' + b.runId : 's' + b.scheduleId) : null;
            detail.innerHTML = b ? barDetail(b) : '';
        }

        function render() {
            if (!data) { return; }
            var span = hours * 3600000;
            var w1 = w0 + span, rows = 0, html = '';
            var stepMs = hours <= 3 ? 1800000 : hours <= 12 ? 3600000 : hours <= 24 ? 10800000 : 43200000;
            var mid = new Date(w0); mid.setHours(0, 0, 0, 0);
            var t = mid.getTime() + Math.ceil((w0 - mid.getTime()) / stepMs) * stepMs, ticks = [];
            for (; t < w1; t += stepMs) { ticks.push(t); }
            function pct(ms) { return (ms - w0) / span * 100; }
            var lines = ticks.map(function (x) { return '<div class="tl-line" style="left:' + pct(x) + '%"></div>'; }).join('');
            var nowLine = data.nowMs >= w0 && data.nowMs <= w1 ? '<div class="tl-now" style="left:' + pct(data.nowMs) + '%" title="сейчас"></div>' : '';
            html += '<div class="tl-axis"><div class="tl-lbl"></div><div class="tl-trk">'
                + ticks.map(function (x) { return '<div class="tl-tick" style="left:' + pct(x) + '%">' + (hours > 24 ? dm(x) : hm(x)) + '</div>'; }).join('')
                + '</div></div>';
            function bar(b, text) {
                var l = Math.max(0, pct(b.from)), r = Math.min(100, pct(b.to));
                if (r <= 0 || l >= 100 || r <= l) { return ''; }
                var key = b.runId ? 'r' + b.runId : 's' + b.scheduleId;
                var title = (b.kind === 'run' ? 'Идёт сейчас' : 'Запланирован') + ': ' + b.label + '\n' + dm(b.from) + ' — ' + dm(b.to)
                    + (b.by ? '\nНазначил: ' + b.by : '') + '\nКлик — подробности';
                return '<div class="tl-bar ' + b.kind + (shown === key ? ' sel-bar' : '') + '" data-key="' + key + '" style="left:' + l + '%;width:' + (r - l) + '%" title="'
                    + esc(title) + '">' + esc(text || b.label) + '</div>';
            }
            // запланированные «любые свободные» - сверху, чтобы их было видно сразу (по одной строке на запись)
            var seen = {};
            data.bars.filter(function (b) { return b.generatorId === null; }).forEach(function (b) {
                if (seen[b.scheduleId]) { return; }
                seen[b.scheduleId] = 1;
                rows++;
                html += '<div class="tl-row pool"><div class="tl-lbl" title="Запись расписания займёт любые свободные генераторы на момент старта">любые свободные ×' + b.count
                    + '</div><div class="tl-trk">' + lines + nowLine + bar(b, b.label + ' · ×' + b.count) + '</div></div>';
            });
            data.generators.forEach(function (g) {
                rows++;
                var bars = data.bars.filter(function (b) { return b.generatorId === g.id; }).map(function (b) { return bar(b); }).join('');
                html += '<div class="tl-row' + (g.online ? '' : ' off') + '"><div class="tl-lbl" title="' + esc(g.name) + (g.online ? '' : ' (не в сети)') + '">'
                    + esc(g.name) + '</div><div class="tl-trk">' + lines + nowLine + bars + '</div></div>';
            });
            if (!data.generators.length) { html += '<div class="text-muted small p-2">Серверов нет.</div>'; }
            html += '<div class="tl-ov" id="tl-ov" style="height:' + (rows * ROW) + 'px"></div>';
            var st = body.scrollTop;
            body.innerHTML = html;
            body.scrollTop = st;
            ov = body.querySelector('.tl-ov');
            drawSel();
        }

        function drawSel() {
            if (!ov || !data || !interactive) { return; }
            var s = chosen(), span = hours * 3600000;
            ov.innerHTML = '';
            if (s === null) {
                note.className = 'tl-note small mt-1 text-muted';
                note.textContent = 'Время не выбрано. Длительность запуска: ~' + minutes(data.durationSec) + ' мин.';
                return;
            }
            var f = freeAt(s), e = f.end;
            var l = (s - w0) / span * 100, r = (e - w0) / span * 100;
            if (r > 0 && l < 100) {
                var cl = Math.max(0, l), cr = Math.min(100, r);
                ov.innerHTML = '<div class="tl-sel ' + (f.ok ? 'ok' : 'bad') + '" style="left:' + cl + '%;width:' + (cr - cl) + '%"></div>';
            }
            var when = dm(s) + ' — ' + hm(e) + ' (~' + minutes(data.durationSec) + ' мин)';
            if (f.ok) {
                note.className = 'tl-note small mt-1 text-success';
                note.textContent = '✓ ' + when + ': свободно ' + f.avail + ' из ' + f.online + ' генераторов в сети, нужно ' + f.need
                    + (f.pool ? ' (уже запланировано на это время: ' + f.pool + ')' : '') + '.';
            } else {
                note.className = 'tl-note small mt-1 text-danger';
                var names = f.bad.map(function (id) {
                    var g = data.generators.filter(function (x) { return x.id === id; })[0]; return g ? g.name : '#' + id;
                });
                note.textContent = '✗ ' + when + ': ' + (f.bad.length ? 'заняты или не в сети: ' + names.join(', ')
                    : 'свободно ' + f.avail + ', нужно ' + f.need + (f.pool ? ' (запланировано другими запусками: ' + f.pool + ')' : ''))
                    + '. Запуск будет пропущен — выберите другое время или нажмите «Ближайшее свободное».';
            }
            if (r < 0 || l > 100) { note.textContent += ' (выбранное время вне шкалы)'; }
        }

        function setTime(ms) {
            at.value = localVal(ms);
            at.dispatchEvent(new Event('input'));
            drawSel();
        }
        function fromEvent(ev) {
            if (!ov) { return; }
            var rect = ov.getBoundingClientRect();
            var frac = Math.min(1, Math.max(0, (ev.clientX - rect.left) / rect.width));
            var ms = Math.round((w0 + frac * hours * 3600000) / STEP) * STEP;
            setTime(Math.max(ms, ceil5(Date.now() + LEAD)));
        }
        function barAt(ev) { return ev.target.closest ? ev.target.closest('.tl-bar') : null; }
        function barByKey(key) {
            return data && data.bars.filter(function (b) { return (b.runId ? 'r' + b.runId : 's' + b.scheduleId) === key; })[0];
        }

        body.addEventListener('click', function (ev) {
            var el = barAt(ev);
            if (!el) { return; }
            var b = barByKey(el.dataset.key);
            showDetail(shown === el.dataset.key ? null : b);
            render();
        });
        if (interactive) {
            body.addEventListener('pointerdown', function (ev) {
                if (barAt(ev) || (ev.button !== 0 && ev.pointerType === 'mouse')) { return; }
                dragging = true;
                try { body.setPointerCapture(ev.pointerId); } catch (x) { /* не критично */ }
                fromEvent(ev);
            });
            body.addEventListener('pointermove', function (ev) { if (dragging) { fromEvent(ev); } });
            body.addEventListener('pointerup', function () { dragging = false; });
            body.addEventListener('pointercancel', function () { dragging = false; });
        }

        var range = root.querySelector('.tl-range');
        if (range) {
            range.addEventListener('click', function (ev) {
                var b = ev.target.closest('button[data-h]');
                if (!b) { return; }
                hours = Number(b.dataset.h);
                range.querySelectorAll('button').forEach(function (x) { x.classList.toggle('active', x === b); });
                render();
            });
        }

        var next = root.querySelector('.tl-next');
        if (next) {
            next.addEventListener('click', function () {
                if (!data) { return; }
                var nd = need(), t = ceil5(Date.now() + LEAD), limit = t + 7 * 86400000;
                for (; t < limit; t += STEP) {
                    if (freeAt(t).ok) {
                        setTime(t);
                        if (mineEnd(t) > w0 + hours * 3600000) { note.textContent += ' Время за пределами текущей шкалы: выберите больший масштаб.'; }
                        return;
                    }
                }
                note.className = 'tl-note small mt-1 text-danger';
                note.textContent = 'В ближайшие 7 дней не нашлось времени, когда свободно ' + nd.n + ' генераторов.';
            });
        }

        function load() {
            var q = [];
            if (prof && prof.value) { q.push('profile=' + encodeURIComponent(prof.value)); }
            if (cnt && cnt.value) { q.push('count=' + encodeURIComponent(cnt.value)); }
            fetch(root.dataset.url + (q.length ? '?' + q.join('&') : ''), {credentials: 'same-origin'})
                .then(function (r) { if (!r.ok) { throw new Error(r.status); } return r.json(); })
                .then(function (d) {
                    data = d;
                    w0 = Math.floor(d.nowMs / 60000) * 60000 - (opts.past || 0);
                    render();
                })
                .catch(function () {
                    note.className = 'tl-note small mt-1 text-danger';
                    note.textContent = 'Не удалось загрузить занятость генераторов.';
                });
        }
        function reloadSoon() { clearTimeout(timer); timer = setTimeout(load, 400); }

        if (interactive) {
            at.addEventListener('input', drawSel);
            if (cnt) { cnt.addEventListener('input', function () { drawSel(); reloadSoon(); }); }
            if (prof) { prof.addEventListener('change', load); }
            document.querySelectorAll('#schedule input[name=generatorId]').forEach(function (c) {
                c.addEventListener('change', function () { drawSel(); reloadSoon(); });
            });
        }
        load();
        setInterval(load, 30000);
    }

    window.K6Timeline = {
        init: function (root, opts) {
            if (!document.getElementById('tl-css')) {
                var st = document.createElement('style');
                st.id = 'tl-css';
                st.textContent = CSS;
                document.head.appendChild(st);
            }
            init(root, opts);
        }
    };
})();
