// Таймлайн занятости генераторов: строки - генераторы, полосы - идущие и запланированные запуски (с профилем нагрузки в подписи).
// Используется на форме планирования (interactive: выбор времени кликом/перетаскиванием и проверка «хватит ли свободных») и на странице расписания (только просмотр).
// Разметка: .tl-root[data-url, data-run-url, data-schedule-url] с .tl-body, .tl-note, .tl-detail, .tl-range, .tl-next.
(function () {
    function pad(n) { return n < 10 ? '0' + n : '' + n; }
    function hm(ms) { var d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
    function dm(ms) { var d = new Date(ms); return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + ' ' + hm(ms); }
    function localVal(ms) { var d = new Date(ms); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + hm(ms); }
    function esc(s) {
        return window.K6 && window.K6.escapeHtml ? window.K6.escapeHtml(s)
            : String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
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
            var head = (b.kind === 'run' ? '<span class="badge text-bg-primary">идёт сейчас</span>' : '<span class="badge" style="background:#c2410c;color:#fff">запланирован</span>')
                + ' <b>' + esc(b.script) + '</b>';
            row('Профиль нагрузки', b.profile ? esc(b.profile) : 'нагрузка скрипта');
            row(b.kind === 'run' ? 'Идёт' : 'Старт', dm(b.from) + ' — ' + dm(b.to) + ' (занимает генераторы, ~' + minutes((b.to - b.from) / 1000) + ' мин с запасом)');
            row('Статус', b.status === 'STARTING' ? 'запускается' : b.status === 'PENDING' ? 'ожидает назначенного времени' : esc(b.status));
            row('Генераторов', b.genCount ? b.genCount + (b.genNames ? ': ' + esc(b.genNames) : ' (любые свободные на момент старта)') : '');
            row('Интенсивность', b.intensity ? esc(b.intensity) : '');
            row('Переменные', b.env ? esc(b.env) : '');
            row('Назначил', b.by ? esc(b.by) : '');
            if (b.kind === 'run' && b.scheduled) { row('Источник', 'запуск по расписанию'); }
            row('Ссылка', b.runId ? '<a href="' + runUrl + b.runId + '">запуск #' + b.runId + '</a>' : b.scheduleId ? '<a href="#sch-row-' + b.scheduleId + '" data-tl-jump="' + b.scheduleId + '">запись расписания #' + b.scheduleId + ' (показать в таблице)</a>' : '');
            return '<div class="card card-body py-2 mt-2"><div class="d-flex align-items-center justify-content-between gap-2 mb-1"><div>' + head + '</div><button type="button" class="btn-close btn-sm" data-tl-close aria-label="Закрыть"></button></div><dl>' + rows.join('') + '</dl></div>';
        }
        function showDetail(b) {
            if (!detail) { return; }
            shown = b ? (b.runId ? 'r' + b.runId : 's' + b.scheduleId) : null;
            detail.innerHTML = b ? barDetail(b) : '';
            if (b && b.scheduleId && !interactive) {
                var tr = document.querySelector('tr.sch-row[data-schedule-id="' + b.scheduleId + '"]');
                if (tr) {
                    document.querySelectorAll('tr.sch-row.sch-row-highlight').forEach(function (r) { r.classList.remove('sch-row-highlight'); });
                    tr.classList.add('sch-row-highlight');
                }
            }
        }
        if (detail) {
            detail.addEventListener('click', function (ev) {
                if (ev.target.closest('[data-tl-close]')) {
                    showDetail(null);
                    render();
                    return;
                }
                var jump = ev.target.closest('[data-tl-jump]');
                if (!jump) { return; }
                ev.preventDefault();
                var sid = jump.dataset.tlJump;
                var tr = document.querySelector('tr.sch-row[data-schedule-id="' + sid + '"]');
                if (tr) {
                    if (tr.style.display === 'none') {
                        var schSearch = document.getElementById('sch-search');
                        if (schSearch && schSearch.value) { schSearch.value = ''; schSearch.dispatchEvent(new Event('input')); }
                        var allBtn = document.querySelector('[data-sch-filter="all"]');
                        if (allBtn) { allBtn.click(); }
                    }
                    tr.scrollIntoView({behavior: 'smooth', block: 'center'});
                    document.querySelectorAll('tr.sch-row.sch-row-highlight').forEach(function (r) { r.classList.remove('sch-row-highlight'); });
                    tr.classList.add('sch-row-highlight');
                } else {
                    location.href = (root.dataset.scheduleUrl || '/schedule') + '#sch-row-' + encodeURIComponent(sid);
                }
            });
        }

        function render() {
            if (!data) { return; }
            var span = hours * 3600000;
            var w1 = w0 + span, rows = 0, html = '';
            var stepMs = hours <= 3 ? 1800000 : hours <= 12 ? 3600000 : hours <= 24 ? 10800000 : 43200000;
            var lblEl = body.querySelector('.tl-lbl');
            var lblW = lblEl ? lblEl.offsetWidth : Math.min(220, Math.max(115, Math.round((body.clientWidth || 700) * 0.26)));
            var trkW = Math.max(120, (body.clientWidth || 700) - lblW);
            var minTickPx = hours > 24 ? 88 : 54;
            while ((span / stepMs) > 2 && (trkW / (span / stepMs)) < minTickPx) { stepMs *= 2; }
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
            html += '<div class="tl-ov" style="height:' + (rows * ROW) + 'px"></div>';
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
                var posEl = document.getElementById('sch-pos');
                var posVal = posEl ? posEl.value : '';
                note.className = 'tl-note small mt-1 ' + (posVal ? 'text-primary' : 'text-muted');
                if (posVal === 'front') {
                    note.textContent = 'Тест встанет в начало очереди сразу после текущего запуска (~' + minutes(data.durationSec) + ' мин).';
                } else if (posVal === 'back') {
                    note.textContent = 'Тест встанет в конец очереди после всех ожидающих (~' + minutes(data.durationSec) + ' мин).';
                } else {
                    note.textContent = 'Время не выбрано — подберётся ближайшее свободное окно. Длительность: ~' + minutes(data.durationSec) + ' мин.';
                }
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

        var loadCtrl = null, loadSeq = 0, pollId = null;
        function load() {
            if (!root.isConnected) {
                if (pollId) { clearInterval(pollId); pollId = null; }
                return;
            }
            if (loadCtrl) { try { loadCtrl.abort(); } catch (_) {} }
            loadCtrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
            var mySeq = ++loadSeq;
            var q = [];
            if (prof && prof.value) { q.push('profile=' + encodeURIComponent(prof.value)); }
            if (cnt && cnt.value) { q.push('count=' + encodeURIComponent(cnt.value)); }
            fetch(root.dataset.url + (q.length ? '?' + q.join('&') : ''), {
                credentials: 'same-origin',
                signal: loadCtrl ? loadCtrl.signal : undefined
            })
                .then(function (r) { if (!r.ok) { throw new Error(r.status); } return r.json(); })
                .then(function (d) {
                    if (mySeq !== loadSeq || !root.isConnected) { return; }
                    data = d;
                    w0 = Math.floor(d.nowMs / 60000) * 60000 - (opts.past || 0);
                    render();
                })
                .catch(function (err) {
                    if (err && err.name === 'AbortError') { return; }
                    if (mySeq !== loadSeq || !root.isConnected) { return; }
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
        pollId = setInterval(function () {
            if (document.hidden) { return; }
            load();
        }, 30000);
        var onReload = function () {
            if (root.isConnected) { load(); }
        };
        document.addEventListener('k6:timeline-reload', onReload);
        window.addEventListener('beforeunload', function () {
            if (pollId) { clearInterval(pollId); pollId = null; }
            if (loadCtrl) { try { loadCtrl.abort(); } catch (_) {} }
        });
    }

    window.K6Timeline = {
        init: function (root, opts) {
            init(root, opts);
        }
    };
})();
