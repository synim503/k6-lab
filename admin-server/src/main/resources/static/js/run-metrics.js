// Вкладка «Метрики» live-страницы: SSE metrics (раз в 5 с) -> Chart.js. Перцентили приходят готовыми из Prometheus
// (histogram_quantile по всем генераторам), здесь они только рисуются.
(function () {
    var root = document.getElementById('run-live');
    if (!root || !root.dataset.metricsStream || typeof Chart === 'undefined') { return; }

    var charts = {};
    var MAX_POINTS = 720; // час при шаге 5 с
    var palette = ['#0d6efd', '#198754', '#dc3545', '#fd7e14', '#6f42c1', '#20c997', '#6c757d', '#d63384'];

    function ensure(key, title, unit) {
        if (charts[key]) { return charts[key]; }
        var box = document.createElement('div');
        box.className = 'col-lg-6';
        box.innerHTML = '<div class="card"><div class="card-header small">' + title + '</div><div class="card-body p-2"><canvas height="150"></canvas></div></div>';
        document.getElementById('metric-charts').appendChild(box);
        var c = new Chart(box.querySelector('canvas'), {
            type: 'line',
            data: { labels: [], datasets: [] },
            options: {
                animation: false, responsive: true, spanGaps: true,
                plugins: { legend: { position: 'bottom', labels: { boxWidth: 12 } } },
                scales: { y: { beginAtZero: true, title: { display: !!unit, text: unit } } },
                elements: { point: { radius: 0 }, line: { borderWidth: 2 } }
            }
        });
        charts[key] = c;
        return c;
    }
    function series(chart, label, color, dashed) {
        var ds = chart.data.datasets.find(function (d) { return d.label === label; });
        if (!ds) {
            ds = { label: label, data: [], borderColor: color, backgroundColor: color, borderDash: dashed ? [6, 4] : [] };
            chart.data.datasets.push(ds);
        }
        return ds;
    }
    function push(chart, label, color, dashed, idx, value) {
        var ds = series(chart, label, color, dashed);
        while (ds.data.length < idx) { ds.data.push(null); }
        ds.data[idx] = value === undefined ? null : value;
    }
    function fmt(v, d) { return v === undefined || v === null ? '—' : Number(v).toFixed(d === undefined ? 1 : d); }

    function onMetrics(m) {
        // подпись по времени самих данных: метрики приходят с задержкой, и «сейчас» на графике было бы неверным
        var t = new Date(m.dataTs || m.ts).toTimeString().substring(0, 8);
        var any = Object.keys(charts)[0];
        var idx = any ? charts[any].data.labels.length : 0;
        var firstOp = true;
        (m.rows || []).forEach(function (r, i) {
            var color = palette[i % palette.length];
            var rate = ensure('rate', 'Интенсивность: факт (сплошная) и план (пунктир), оп/с', 'оп/с');
            var pct = ensure('pct', 'Время ответа (перцентиль SLA, мс): факт и порог SLA', 'мс');
            var err = ensure('err', 'Ошибки, %', '%');
            push(rate, r.op + ' факт', color, false, idx, r.actual);
            push(rate, r.op + ' план', color, true, idx, r.target);
            push(pct, r.op + ' p' + r.pctN, color, false, idx, r.pct);
            push(pct, r.op + ' SLA', color, true, idx, r.sla);
            push(err, r.op, color, false, idx, r.err);
            push(err, r.op + ' порог', color, true, idx, r.maxErr);
        });
        Object.keys(charts).forEach(function (k) {
            var c = charts[k];
            while (c.data.labels.length < idx) { c.data.labels.push(''); }
            c.data.labels[idx] = t;
            c.data.datasets.forEach(function (d) { while (d.data.length <= idx) { d.data.push(null); } });
            if (c.data.labels.length > MAX_POINTS) {
                c.data.labels.shift();
                c.data.datasets.forEach(function (d) { d.data.shift(); });
            }
            c.update('none');
        });

        // таблица текущих значений с подсветкой нарушений
        var tb = document.getElementById('metric-table');
        if (tb) {
            var html = '';
            (m.rows || []).forEach(function (r) {
                html += '<tr class="' + (r.violation ? 'table-danger' : '') + '"><td>' + r.op + '</td><td>' + fmt(r.actual) + ' / ' + fmt(r.target) +
                    '</td><td>' + fmt(r.pct, 0) + ' / ' + r.sla + ' мс (p' + r.pctN + ')</td><td>' + fmt(r.err, 2) + ' / ' + r.maxErr +
                    ' %</td><td>' + fmt(r.dropped, 0) + '</td><td class="small">' + (r.reasons || []).join(', ') + '</td></tr>';
            });
            tb.innerHTML = html || '<tr><td colspan="6" class="text-muted">Нет данных</td></tr>';
        }
        var ph = document.getElementById('metric-phase');
        if (ph) {
            var lag = m.lagSec === undefined ? '' : '. Данные на ' + t + ', задержка метрик ' + m.lagSec + ' с: фаза ниже относится к этому моменту, а не к текущему';
            ph.textContent = (m.phase ? 'ступень ' + m.step + ', ' + (m.phase === 'hold' ? 'удержание' : 'разгон') + ', окно ' + m.windowSec + ' с' : 'ожидание данных первой ступени') + lag;
        }
    }

    var es = new EventSource(root.dataset.metricsStream);
    es.addEventListener('metrics', function (ev) { onMetrics(JSON.parse(ev.data)); });
    if (root.dataset.final === 'true') { es.close(); }
})();
