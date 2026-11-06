// Вкладка «Метрики» live-страницы: SSE metrics (раз в 5 с) -> Chart.js. Перцентили приходят готовыми из Prometheus
// (histogram_quantile по всем генераторам), здесь они только рисуются.
// Легенда справа, как в Grafana: строка на операцию с Last / Mean / Max. Клик - показать только эту операцию,
// Ctrl/Cmd/Shift+клик - добавить её к выбору или убрать; повторный клик по единственной выбранной - показать всё.
// Выбор общий для всех графиков запуска: так одну операцию видно сразу по интенсивности, времени ответа и ошибкам.
(function () {
    var root = document.getElementById('run-live');
    if (!root || !root.dataset.metricsStream || typeof Chart === 'undefined') { return; }

    var charts = {};
    var MAX_POINTS = 720; // час при шаге 5 с
    var palette = ['#4f46e5', '#16a34a', '#dc2626', '#ea580c', '#9333ea', '#0891b2', '#64748b', '#db2777'];
    var selection = null; // null - видны все операции; иначе Set имён операций

    // прогрев: полоса на графике по точкам, у которых фаза «warmup» (ошибки и SLA в это время не оцениваются)
    var warmPlugin = {
        id: 'warmup',
        beforeDatasetsDraw: function (chart) {
            var w = chart.$warm || [], xs = chart.scales && chart.scales.x, area = chart.chartArea;
            if (!xs || !area) { return; }
            var first = -1, last = -1;
            for (var i = 0; i < w.length; i++) { if (w[i]) { if (first < 0) { first = i; } last = i; } }
            if (first < 0) { return; }
            var x0 = xs.getPixelForValue(first), x1 = xs.getPixelForValue(Math.min(last + 1, Math.max(0, w.length - 1)));
            var ctx = chart.ctx;
            ctx.save();
            ctx.fillStyle = 'rgba(245, 158, 11, .14)';
            ctx.fillRect(x0, area.top, Math.max(2, x1 - x0), area.bottom - area.top);
            ctx.fillStyle = '#b45309'; ctx.font = '600 11px sans-serif';
            ctx.fillText('Прогрев', x0 + 6, area.top + 14);
            ctx.restore();
        }
    };

    function esc(v) {
        return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
    }
    function fmt(v, d) { return v === undefined || v === null || !isFinite(v) ? '—' : Number(v).toFixed(d === undefined ? 1 : d); }

    function ensure(key, title, unit, decimals) {
        if (charts[key]) { return charts[key]; }
        var box = document.createElement('div');
        box.className = 'col-12';
        box.innerHTML = '<div class="card"><div class="card-header small">' + title + '</div>'
            + '<div class="chart-wrap"><div class="chart-canvas"><canvas></canvas></div>'
            + '<div class="chart-legend"><table class="legend-table"><thead><tr><th></th><th>Операция</th><th>Last</th><th>Mean</th><th>Max</th></tr></thead><tbody></tbody></table>'
            + '<div class="legend-hint">Клик — только эта операция. Ctrl/Cmd+клик — добавить к выбору.</div></div></div></div>';
        document.getElementById('metric-charts').appendChild(box);
        var c = new Chart(box.querySelector('canvas'), {
            type: 'line',
            plugins: [warmPlugin],
            data: { labels: [], datasets: [] },
            options: {
                animation: false, responsive: true, maintainAspectRatio: false, spanGaps: true,
                interaction: { mode: 'index', intersect: false },
                plugins: { legend: { display: false } },
                scales: { y: { beginAtZero: true, title: { display: !!unit, text: unit } }, x: { ticks: { maxTicksLimit: 12 } } },
                elements: { point: { radius: 0, hoverRadius: 3 }, line: { borderWidth: 2 } }
            }
        });
        c.$box = box;
        c.$warm = [];
        c.$decimals = decimals === undefined ? 1 : decimals;
        box.querySelector('tbody').addEventListener('click', onLegendClick);
        box.querySelector('tbody').addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onLegendClick(e); }
        });
        charts[key] = c;
        return c;
    }
    function series(chart, op, label, color, dashed) {
        var ds = chart.data.datasets.find(function (d) { return d.label === label; });
        if (!ds) {
            ds = { label: label, data: [], borderColor: color, backgroundColor: color, borderDash: dashed ? [6, 4] : [],
                grp: op, main: !dashed, borderWidth: dashed ? 1.5 : 2 };
            ds.hidden = !!selection && !selection.has(op);
            chart.data.datasets.push(ds);
        }
        return ds;
    }
    function push(chart, op, label, color, dashed, idx, value) {
        var ds = series(chart, op, label, color, dashed);
        while (ds.data.length < idx) { ds.data.push(null); }
        ds.data[idx] = value === undefined ? null : value;
    }

    // ---- легенда
    function stats(ds) {
        var n = 0, sum = 0, max = null, last = null;
        ds.data.forEach(function (v) {
            if (v === null || v === undefined || !isFinite(v)) { return; }
            n++; sum += v; last = v;
            if (max === null || v > max) { max = v; }
        });
        return { last: last, mean: n ? sum / n : null, max: max };
    }
    function renderLegend(c) {
        var tb = c.$box.querySelector('tbody'), html = '';
        c.data.datasets.forEach(function (ds) {
            if (!ds.main) { return; }
            var s = stats(ds), off = !!selection && !selection.has(ds.grp);
            html += '<tr class="legend-row' + (off ? ' off' : '') + (selection && !off ? ' on' : '') + '" data-grp="' + esc(ds.grp) + '" tabindex="0" role="button" aria-pressed="' + (!off) + '" title="' + esc(ds.label) + '">'
                + '<td><span class="legend-swatch" style="background:' + ds.borderColor + '"></span></td>'
                + '<td class="legend-name">' + esc(ds.grp) + '</td>'
                + '<td>' + fmt(s.last, c.$decimals) + '</td><td>' + fmt(s.mean, c.$decimals) + '</td><td>' + fmt(s.max, c.$decimals) + '</td></tr>';
        });
        tb.innerHTML = html;
    }
    function applySelection() {
        Object.keys(charts).forEach(function (k) {
            var c = charts[k];
            c.data.datasets.forEach(function (ds) { ds.hidden = !!selection && !selection.has(ds.grp); });
            c.update('none');
            renderLegend(c);
        });
    }
    function onLegendClick(e) {
        var row = e.target.closest('.legend-row');
        if (!row) { return; }
        var g = row.dataset.grp;
        if (e.ctrlKey || e.metaKey || e.shiftKey) {
            if (!selection) { selection = new Set([g]); }
            else if (selection.has(g)) { selection.delete(g); if (!selection.size) { selection = null; } }
            else { selection.add(g); }
        } else if (selection && selection.size === 1 && selection.has(g)) {
            selection = null;
        } else {
            selection = new Set([g]);
        }
        applySelection();
    }

    var lastTs = 0;   // точки не дублируются: live-поток при подключении повторяет последнюю
    function onMetrics(m, quiet) {
        if (m.ts && m.ts <= lastTs) { return; }
        lastTs = m.ts || lastTs;
        // подпись по времени самих данных: метрики приходят с задержкой, и «сейчас» на графике было бы неверным
        var t = new Date(m.dataTs || m.ts).toTimeString().substring(0, 8);
        var any = Object.keys(charts)[0];
        var idx = any ? charts[any].data.labels.length : 0;
        (m.rows || []).forEach(function (r, i) {
            var color = palette[i % palette.length];
            var rate = ensure('rate', 'Интенсивность, оп/с: факт (сплошная) и план (пунктир)', 'оп/с', 1);
            var pct = ensure('pct', 'Время запросов (HTTP), мс: перцентиль SLA (сплошная) и порог (пунктир)', 'мс', 0);
            var iter = ensure('iter', 'Время метода целиком (итерация: запросы, паузы, работа генератора), мс — перцентиль SLA', 'мс', 0);
            var err = ensure('err', 'Ошибки, %: факт (сплошная) и допустимый порог (пунктир)', '%', 2);
            push(rate, r.op, r.op + ' факт', color, false, idx, r.actual);
            push(rate, r.op, r.op + ' план', color, true, idx, r.target);
            push(pct, r.op, r.op + ' p' + r.pctN, color, false, idx, r.pct);
            push(pct, r.op, r.op + ' SLA', color, true, idx, r.sla);
            push(iter, r.op, r.op + ' p' + r.pctN, color, false, idx, r.iterPct);
            push(err, r.op, r.op, color, false, idx, r.err);
            push(err, r.op, r.op + ' порог', color, true, idx, r.maxErr);
        });
        Object.keys(charts).forEach(function (k) {
            var c = charts[k];
            while (c.data.labels.length < idx) { c.data.labels.push(''); }
            c.data.labels[idx] = t;
            c.$warm[idx] = m.phase === 'warmup';
            c.data.datasets.forEach(function (d) { while (d.data.length <= idx) { d.data.push(null); } });
            if (c.data.labels.length > MAX_POINTS) {
                c.data.labels.shift();
                c.$warm.shift();
                c.data.datasets.forEach(function (d) { d.data.shift(); });
            }
            if (!quiet) { c.update('none'); renderLegend(c); }
        });
        if (quiet) { return; }
        renderCurrent(m, t);
    }

    function renderCurrent(m, t) {
        // таблица текущих значений с подсветкой нарушений
        var tb = document.getElementById('metric-table');
        if (tb) {
            var html = '';
            (m.rows || []).forEach(function (r) {
                html += '<tr class="' + (r.violation ? 'table-danger' : '') + '"><td>' + esc(r.op) + '</td><td>' + fmt(r.actual) + ' / ' + fmt(r.target) +
                    '</td><td>' + fmt(r.pct, 0) + ' / ' + r.sla + ' мс (p' + r.pctN + ')</td><td>' + fmt(r.err, 2) + ' / ' + r.maxErr +
                    ' %</td><td>' + fmt(r.dropped, 0) + '</td><td class="small">' + (r.reasons || []).join(', ') + '</td></tr>';
            });
            tb.innerHTML = html || '<tr><td colspan="6" class="text-muted">Нет данных</td></tr>';
        }
        var ph = document.getElementById('metric-phase');
        if (ph) {
            var lag = m.lagSec === undefined ? '' : '. Данные на ' + t + ', задержка метрик ' + m.lagSec + ' с';
            ph.textContent = (m.phase === 'warmup' ? 'Идёт прогрев: ошибки и время ответа пока не оцениваются'
                : m.phase ? 'Идёт нагрузка, окно расчёта ' + m.windowSec + ' с' : 'Ожидание данных') + lag;
        }
    }

    // сначала вся история с начала теста (одним куском), затем live-поток
    function startLive() {
        var es = new EventSource(root.dataset.metricsStream);
        es.addEventListener('metrics', function (ev) { onMetrics(JSON.parse(ev.data)); });
        if (root.dataset.final === 'true') { es.close(); }
    }
    var historyUrl = root.dataset.metricsHistory;
    if (!historyUrl) { startLive(); return; }
    fetch(historyUrl, {credentials: 'same-origin'}).then(function (r) { return r.ok ? r.json() : []; }).catch(function () { return []; })
        .then(function (points) {
            points.forEach(function (m) { onMetrics(m, true); });
            if (points.length) {
                Object.keys(charts).forEach(function (k) { charts[k].update('none'); renderLegend(charts[k]); });
                var last = points[points.length - 1];
                renderCurrent(last, new Date(last.dataTs || last.ts).toTimeString().substring(0, 8));
            }
            startLive();
        });
})();
