(function () {
    'use strict';

    var root = document.getElementById('cb-root');
    if (!root) { return; }

    var scriptId = root.dataset.scriptId;
    var baseUrl = root.dataset.baseUrl; // e.g. /scripts/5/charts
    var promConfigured = root.dataset.promConfigured === 'true';
    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfToken = csrfMeta ? csrfMeta.content : '';

    var PALETTE = ['#73BF69', '#FADE2A', '#5794F2', '#FF9830', '#F2495C', '#B877D9', '#8AB8FF', '#FF780A'];
    var BUILTIN_VARS = { __interval: 1, __rate_interval: 1, __range: 1, __range_s: 1, __interval_ms: 1, __range_ms: 1, __all: 1 };
    var VAR_RE = /\$\{(\w+)(?::[^}]*)?}|\$(\w+)|\[\[(\w+)(?::\w+)?]]/g;

    var UNIT_PRESETS = [
        { val: '', label: 'без ед.' },
        { val: '%', label: '%' },
        { val: 'мс', label: 'мс' },
        { val: 'с', label: 'с' },
        { val: 'оп/с', label: 'оп/с' },
        { val: 'Б', label: 'Б' },
        { val: 'Б/с', label: 'Б/с' },
        { val: 'шт', label: 'шт' }
    ];

    var CHART_PRESETS = [
        {
            group: 'k6 (нагрузка)',
            items: [
                {
                    name: 'HTTP RPS по статусам', unit: 'оп/с',
                    queries: [{ promql: 'sum by (status) (rate(k6_http_reqs_total{test_run_id="{run_id}"}[{range}]))', legend: 'HTTP {{status}}' }]
                },
                {
                    name: 'Время ответа HTTP (p50 / p95 / p99)', unit: 'мс',
                    queries: [
                        { promql: 'histogram_quantile(0.50, sum by (le) (rate(k6_http_req_duration_seconds_bucket{test_run_id="{run_id}"}[{range}]))) * 1000', legend: 'p50' },
                        { promql: 'histogram_quantile(0.95, sum by (le) (rate(k6_http_req_duration_seconds_bucket{test_run_id="{run_id}"}[{range}]))) * 1000', legend: 'p95' },
                        { promql: 'histogram_quantile(0.99, sum by (le) (rate(k6_http_req_duration_seconds_bucket{test_run_id="{run_id}"}[{range}]))) * 1000', legend: 'p99' }
                    ]
                },
                {
                    name: 'Активные виртуальные пользователи (VUs)', unit: 'шт',
                    queries: [{ promql: 'sum(k6_vus{test_run_id="{run_id}"})', legend: 'VUs' }]
                },
                {
                    name: 'Сетевой трафик k6 (генераторы)', unit: 'Б/с',
                    queries: [
                        { promql: 'sum(rate(k6_data_sent_total{test_run_id="{run_id}"}[{range}]))', legend: 'Отправлено' },
                        { promql: 'sum(rate(k6_data_received_total{test_run_id="{run_id}"}[{range}]))', legend: 'Получено' }
                    ]
                }
            ]
        },
        {
            group: 'Серверы (Node Exporter)',
            items: [
                {
                    name: 'Утилизация CPU серверов', unit: '%',
                    queries: [{ promql: '100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[{range}])) * 100)', legend: '{{instance}}' }]
                },
                {
                    name: 'Использование RAM (%)', unit: '%',
                    queries: [{ promql: '(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100', legend: '{{instance}}' }]
                },
                {
                    name: 'Доступная память (RAM)', unit: 'Б',
                    queries: [{ promql: 'node_memory_MemAvailable_bytes', legend: '{{instance}}' }]
                },
                {
                    name: 'Load Average (1m / 5m)', unit: '',
                    queries: [
                        { promql: 'node_load1', legend: '1m {{instance}}' },
                        { promql: 'node_load5', legend: '5m {{instance}}' }
                    ]
                },
                {
                    name: 'Сеть серверов (вх. / исх.)', unit: 'Б/с',
                    queries: [
                        { promql: 'sum by (instance) (rate(node_network_receive_bytes_total{device!="lo"}[{range}]))', legend: 'RX {{instance}}' },
                        { promql: 'sum by (instance) (rate(node_network_transmit_bytes_total{device!="lo"}[{range}]))', legend: 'TX {{instance}}' }
                    ]
                }
            ]
        },
        {
            group: 'Приложения (JVM / Go)',
            items: [
                {
                    name: 'JVM Heap (используется / максимум)', unit: 'Б',
                    queries: [
                        { promql: 'sum by (instance) (jvm_memory_used_bytes{area="heap"})', legend: 'Used {{instance}}' },
                        { promql: 'sum by (instance) (jvm_memory_max_bytes{area="heap"})', legend: 'Max {{instance}}' }
                    ]
                },
                {
                    name: 'JVM GC Pause', unit: 'с',
                    queries: [{ promql: 'sum by (instance) (rate(jvm_gc_pause_seconds_sum[{range}]))', legend: '{{instance}}' }]
                },
                {
                    name: 'Go Goroutines', unit: 'шт',
                    queries: [{ promql: 'go_goroutines', legend: '{{instance}}' }]
                }
            ]
        }
    ];

    var PROMQL_SNIPPETS = [
        { label: 'rate(…[{range}])', wrap: function (s) { return 'rate(' + (s || 'metric_name') + '[{range}])'; } },
        { label: 'increase(…[{range}])', wrap: function (s) { return 'increase(' + (s || 'metric_name') + '[{range}])'; } },
        { label: 'sum by (instance) (…)', wrap: function (s) { return 'sum by (instance) (' + (s || 'metric_name') + ')'; } },
        { label: 'avg by (instance) (…)', wrap: function (s) { return 'avg by (instance) (' + (s || 'metric_name') + ')'; } },
        { label: 'histogram_quantile(0.95, …)', wrap: function (s) { return 'histogram_quantile(0.95, sum by (le) (rate(' + (s || 'metric_bucket') + '[{range}])))'; } },
        { label: 'topk(10, …)', wrap: function (s) { return 'topk(10, ' + (s || 'metric_name') + ')'; } }
    ];

    // ==================== State ====================
    var templates = parseJsonEl('cb-init-templates', []);
    var variables = parseJsonEl('cb-init-variables', []);
    var selectedId = null; // null = creating new
    var checkedIds = new Set();
    var filterMode = 'all';
    var searchText = '';
    var editorBaseline = '';
    var queryRows = []; // [{promql, legend, diag: null, labelKeys: []}]
    var chartVarRows = []; // [{name, value}] — локальные переменные конкретного графика
    var metricNames = null; // null = not fetched yet
    var metricFetchInFlight = false;
    var previewController = null;
    var previewSeq = 0;
    var dragSrcId = null;

    // ==================== DOM refs ====================
    var elSummary = document.getElementById('cb-summary');
    var elList = document.getElementById('cb-list');
    var elSearch = document.getElementById('cb-search');
    var elFilterBtns = document.querySelectorAll('[data-cb-filter]');
    var elBulkBar = document.getElementById('cb-bulk-bar');
    var elSelectAll = document.getElementById('cb-select-all');
    var elBulkActions = document.getElementById('cb-bulk-actions');
    var elBulkCount = document.getElementById('cb-bulk-count');
    var elVarsBtn = document.getElementById('cb-vars-btn');
    var elVarsBadge = document.getElementById('cb-vars-badge');
    var elVarsPanel = document.getElementById('cb-vars-panel');
    var elVarsMissing = document.getElementById('cb-vars-missing');
    var elVarsGrid = document.getElementById('cb-vars-grid');
    var elVarNewName = document.getElementById('cb-var-new-name');
    var elVarNewVal = document.getElementById('cb-var-new-val');

    var elStudio = document.getElementById('cb-studio');
    var elStudioMode = document.getElementById('cb-studio-mode');
    var elStudioEditBtns = document.getElementById('cb-studio-edit-btns');
    var elName = document.getElementById('cb-name');
    var elNameVarHint = document.getElementById('cb-name-var-hint');
    var elUnit = document.getElementById('cb-unit');
    var elUnitPills = document.getElementById('cb-unit-pills');
    var elEnabled = document.getElementById('cb-enabled');
    var elQueries = document.getElementById('cb-queries');
    var elQCount = document.getElementById('cb-q-count');
    var elCvRows = document.getElementById('cb-cv-rows');
    var elCvCount = document.getElementById('cb-cv-count');
    var elCvHints = document.getElementById('cb-cv-hints');
    var elCvAddBtn = document.getElementById('cb-cv-add-btn');
    var elSaveBtn = document.getElementById('cb-save-btn');
    var elResetBtn = document.getElementById('cb-reset-btn');
    var elStudioErr = document.getElementById('cb-studio-err');

    var elPreviewSrc = document.getElementById('cb-preview-src');
    var elPreviewBtn = document.getElementById('cb-preview-btn');
    var elAutoPreview = document.getElementById('cb-auto-preview');
    var elPreviewStage = document.getElementById('cb-preview-stage');
    var elPreviewMeta = document.getElementById('cb-preview-meta');
    var elZoomImg = document.getElementById('cb-zoom-img');
    var elZoomTitle = document.getElementById('cb-zoom-title');
    var elToastBox = document.getElementById('cb-toast-box');

    // ==================== Utilities ====================
    function parseJsonEl(id, fallback) {
        var el = document.getElementById(id);
        if (!el) { return fallback; }
        try { return JSON.parse(el.textContent || '[]'); } catch (e) { return fallback; }
    }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function toast(msg, kind) {
        if (!elToastBox) { return; }
        var d = document.createElement('div');
        d.className = 'cb-toast ' + (kind || 'ok');
        var ic = kind === 'err' ? 'bi-exclamation-triangle-fill' : kind === 'info' ? 'bi-info-circle-fill' : 'bi-check-circle-fill';
        d.innerHTML = '<i class="bi ' + ic + '"></i><span>' + esc(msg) + '</span>';
        elToastBox.appendChild(d);
        setTimeout(function () {
            d.style.opacity = '0';
            d.style.transition = 'opacity .25s';
            setTimeout(function () { d.remove(); }, 260);
        }, 3200);
    }

    function api(path, method, body, signal) {
        var opts = {
            method: method || 'POST',
            headers: { 'Accept': 'application/json' }
        };
        if (signal) { opts.signal = signal; }
        if (csrfToken) { opts.headers['X-CSRF-TOKEN'] = csrfToken; }
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        return fetch(baseUrl + path, opts).then(function (r) {
            return r.text().then(function (txt) {
                var data;
                try { data = txt ? JSON.parse(txt) : {}; } catch (e) { data = { ok: false, error: 'HTTP ' + r.status }; }
                if (!r.ok && !data.error) { data.error = 'Ошибка сервера (HTTP ' + r.status + ')'; }
                return { status: r.status, ok: r.ok, data: data };
            });
        });
    }

    function varMap() {
        var m = {};
        variables.forEach(function (v) { m[v.name] = v.value; });
        return m;
    }

    function chartVarMap() {
        var m = {};
        chartVarRows.forEach(function (r) {
            var k = (r.name || '').trim().replace(/^\$/, '');
            if (k) { m[k] = (r.value == null ? '' : String(r.value)).trim(); }
        });
        return m;
    }

    function effectiveVarMap(localMap) {
        var m = varMap();
        if (localMap && typeof localMap === 'object') {
            Object.keys(localMap).forEach(function (k) {
                var clean = k.trim().replace(/^\$/, '');
                if (clean) { m[clean] = localMap[k]; }
            });
        }
        return m;
    }

    function extractVarsFromText(text) {
        var found = [];
        if (!text) { return found; }
        var re = new RegExp(VAR_RE.source, 'g');
        var m;
        while ((m = re.exec(text)) !== null) {
            var name = m[1] || m[2] || m[3];
            if (name && !BUILTIN_VARS[name] && found.indexOf(name) === -1) {
                found.push(name);
            }
        }
        return found;
    }

    function draftReferencedVars() {
        var found = [];
        var add = function (txt) {
            extractVarsFromText(txt).forEach(function (v) {
                if (found.indexOf(v) === -1) { found.push(v); }
            });
        };
        add(elName.value);
        queryRows.forEach(function (q) { add(q.promql); });
        return found;
    }

    function missingVarsForTemplate(t) {
        var defined = effectiveVarMap(t.variables);
        var missing = [];
        var check = function (txt) {
            extractVarsFromText(txt).forEach(function (v) {
                if (!(v in defined) && missing.indexOf(v) === -1) { missing.push(v); }
            });
        };
        check(t.name);
        (t.queries || []).forEach(function (q) { check(q.promql); });
        return missing;
    }

    function allMissingVars() {
        var missing = [];
        templates.forEach(function (t) {
            missingVarsForTemplate(t).forEach(function (v) {
                if (missing.indexOf(v) === -1) { missing.push(v); }
            });
        });
        // also check currently edited draft with its own local variables
        var definedDraft = effectiveVarMap(chartVarMap());
        draftReferencedVars().forEach(function (v) {
            if (!(v in definedDraft) && missing.indexOf(v) === -1) { missing.push(v); }
        });
        return missing;
    }

    function resolveTitlePreview(name) {
        if (!name) { return ''; }
        var map = effectiveVarMap(chartVarMap());
        return name.replace(VAR_RE, function (full, g1, g2, g3) {
            var k = g1 || g2 || g3;
            if (k in map && map[k] && map[k].indexOf('{') === -1) {
                return map[k] === '.*' ? 'все' : map[k];
            }
            return full;
        });
    }

    function collectDraft() {
        return {
            id: selectedId,
            name: elName.value.trim(),
            unit: elUnit.value.trim(),
            enabled: elEnabled.checked,
            queries: queryRows.map(function (r) {
                return { promql: (r.promql || '').trim(), legend: (r.legend || '').trim() };
            }),
            variables: chartVarMap()
        };
    }

    function snapshotState() {
        return JSON.stringify({
            id: selectedId,
            name: elName.value,
            unit: elUnit.value,
            enabled: elEnabled.checked,
            queries: queryRows.map(function (r) { return [r.promql || '', r.legend || '']; }),
            variables: chartVarRows.map(function (r) { return [r.name || '', r.value || '']; })
        });
    }

    function markBaseline() {
        editorBaseline = snapshotState();
        syncDirty();
    }

    function isDirty() {
        return snapshotState() !== editorBaseline;
    }

    function syncDirty() {
        var d = isDirty();
        elStudio.classList.toggle('is-dirty', d);
        elResetBtn.classList.toggle('d-none', !d);
    }

    function showStudioError(msg) {
        if (!msg) {
            elStudioErr.classList.add('d-none');
            elStudioErr.textContent = '';
        } else {
            elStudioErr.textContent = msg;
            elStudioErr.classList.remove('d-none');
        }
    }

    function withDiscardCheck(fn) {
        if (!isDirty()) { fn(); return; }
        window.k6Confirm({
            title: 'Есть несохранённые изменения',
            message: 'В текущем графике есть несохранённые изменения. Продолжить без сохранения?',
            confirmText: 'Продолжить без сохранения',
            variant: 'warning'
        }).then(function (ok) { if (ok) { fn(); } });
    }

    // ==================== Render: Summary & Variables ====================
    function renderSummary() {
        var total = templates.length;
        var active = templates.filter(function (t) { return t.enabled; }).length;
        elSummary.innerHTML = '<i class="bi bi-graph-up"></i> В отчёте: <b>' + active + '</b> из ' + total;
    }

    function renderVariables() {
        var missing = allMissingVars();
        elVarsBadge.textContent = String(variables.length);
        if (missing.length > 0) {
            elVarsBtn.classList.remove('btn-outline-secondary');
            elVarsBtn.classList.add('btn-warning');
            elVarsBtn.title = 'Есть незаданные переменные: ' + missing.map(function (v) { return '$' + v; }).join(', ');
        } else {
            elVarsBtn.classList.add('btn-outline-secondary');
            elVarsBtn.classList.remove('btn-warning');
            elVarsBtn.title = 'Переменные скрипта для подстановки в PromQL';
        }

        if (missing.length > 0) {
            elVarsMissing.classList.remove('d-none');
            elVarsMissing.innerHTML = '<i class="bi bi-exclamation-triangle-fill"></i><span>В запросах используются незаданные переменные:</span>' +
                missing.map(function (v) {
                    return '<button type="button" class="btn btn-sm btn-outline-dark py-0 px-2" data-quick-var="' + esc(v) + '"><i class="bi bi-plus-lg me-1"></i>Задать $' + esc(v) + '</button>';
                }).join('');
        } else {
            elVarsMissing.classList.add('d-none');
            elVarsMissing.innerHTML = '';
        }

        if (variables.length === 0) {
            elVarsGrid.innerHTML = '<div class="text-muted small py-1">Переменных пока нет. Добавьте переменную ниже (например, <code>instance</code> = <code>app-1</code> или <code>testid</code> = <code>{run_id}</code>).</div>';
        } else {
            elVarsGrid.innerHTML = variables.map(function (v) {
                return '<div class="cb-var-item" data-vid="' + v.id + '" data-vname="' + esc(v.name) + '">' +
                    '<span class="cb-var-name" title="$' + esc(v.name) + '">$' + esc(v.name) + '</span>' +
                    '<input class="form-control form-control-sm font-monospace cb-var-in" value="' + esc(v.value) + '" placeholder="значение">' +
                    '<button type="button" class="btn btn-sm btn-outline-primary cb-var-save" title="Сохранить"><i class="bi bi-check-lg"></i></button>' +
                    '<button type="button" class="btn btn-sm btn-outline-danger cb-var-del" title="Удалить"><i class="bi bi-trash"></i></button>' +
                    '</div>';
            }).join('');
        }
        updateNameVarHint();
    }

    function updateNameVarHint() {
        var raw = elName.value.trim();
        var resolved = resolveTitlePreview(raw);
        if (raw && resolved !== raw) {
            elNameVarHint.innerHTML = 'В отчёте заголовок: <b>' + esc(resolved) + '</b>';
            elNameVarHint.classList.remove('d-none');
        } else {
            elNameVarHint.classList.add('d-none');
            elNameVarHint.textContent = '';
        }
    }

    // ==================== Render: Left Outline List ====================
    function filteredTemplates() {
        var q = searchText.trim().toLowerCase();
        return templates.filter(function (t) {
            if (filterMode === 'enabled' && !t.enabled) { return false; }
            if (filterMode === 'disabled' && t.enabled) { return false; }
            if (!q) { return true; }
            if ((t.name || '').toLowerCase().indexOf(q) >= 0) { return true; }
            if ((t.unit || '').toLowerCase().indexOf(q) >= 0) { return true; }
            if (t.variables && typeof t.variables === 'object') {
                var hitVar = Object.keys(t.variables).some(function (k) {
                    return k.toLowerCase().indexOf(q) >= 0 || String(t.variables[k] || '').toLowerCase().indexOf(q) >= 0;
                });
                if (hitVar) { return true; }
            }
            return (t.queries || []).some(function (qr) {
                return (qr.promql || '').toLowerCase().indexOf(q) >= 0 || (qr.legend || '').toLowerCase().indexOf(q) >= 0;
            });
        });
    }

    function canReorder() {
        return filterMode === 'all' && !searchText.trim() && templates.length > 1;
    }

    function renderList() {
        renderSummary();
        // prune stale checkedIds
        var validIds = new Set(templates.map(function (t) { return t.id; }));
        Array.from(checkedIds).forEach(function (id) { if (!validIds.has(id)) { checkedIds.delete(id); } });

        var list = filteredTemplates();
        var reorderable = canReorder();

        if (templates.length === 0) {
            elList.innerHTML = '<div class="empty-state py-4">' +
                '<i class="bi bi-graph-up"></i>' +
                '<div class="fw-semibold text-body mb-1">Своих графиков пока нет</div>' +
                '<div class="small mb-3">Создайте первый график справа, скопируйте из другого скрипта, выберите шаблон или импортируйте дашборд Grafana.</div>' +
                '<div class="d-flex justify-content-center gap-2 flex-wrap">' +
                '<button type="button" class="btn btn-sm btn-outline-primary" data-cb-open-copy-script><i class="bi bi-files me-1"></i>Из другого скрипта</button>' +
                '<button type="button" class="btn btn-sm btn-outline-primary" data-cb-open-presets><i class="bi bi-magic me-1"></i>Готовый шаблон</button>' +
                '<button type="button" class="btn btn-sm btn-outline-secondary" data-bs-toggle="modal" data-bs-target="#cb-grafana-modal"><i class="bi bi-upload me-1"></i>Импорт Grafana</button>' +
                '</div></div>';
            syncBulkBar(list);
            return;
        }

        if (list.length === 0) {
            elList.innerHTML = '<div class="empty-state py-4"><i class="bi bi-search"></i><div class="small">Ничего не найдено по фильтру</div></div>';
            syncBulkBar(list);
            return;
        }

        elList.innerHTML = list.map(function (t, idx) {
            var globalIdx = templates.indexOf(t);
            var isSel = selectedId === t.id;
            var isChecked = checkedIds.has(t.id);
            var missing = missingVarsForTemplate(t);
            var qCount = (t.queries || []).length;
            var firstQ = qCount > 0 ? t.queries[0].promql : '';
            var tVarKeys = t.variables && typeof t.variables === 'object' ? Object.keys(t.variables) : [];
            var varPill = '';
            if (tVarKeys.length > 0) {
                var firstK = tVarKeys[0], firstV = t.variables[firstK];
                var allSummary = tVarKeys.map(function (k) { return '$' + k + '=' + t.variables[k]; }).join(', ');
                var shortTxt = '$' + firstK + '=' + (firstV || '""') + (tVarKeys.length > 1 ? ' +' + (tVarKeys.length - 1) : '');
                varPill = '<span class="cb-item-pill is-var" title="Переменные графика: ' + esc(allSummary) + '"><i class="bi bi-sliders"></i>' + esc(shortTxt) + '</span>';
            }
            var cls = 'cb-item' + (isSel ? ' active' : '') + (t.enabled ? '' : ' is-off');
            return '<div class="' + cls + '" data-id="' + t.id + '"' + (reorderable ? ' draggable="true"' : '') + '>' +
                (reorderable ? '<span class="cb-drag" title="Перетащите для смены порядка в отчёте"><i class="bi bi-grip-vertical"></i></span>' : '') +
                '<input type="checkbox" class="form-check-input cb-item-check" data-check-id="' + t.id + '"' + (isChecked ? ' checked' : '') + ' title="Выбрать для массовых действий">' +
                '<div class="cb-item-body">' +
                    '<div class="cb-item-title-row">' +
                        '<span class="cb-item-pos">#' + (globalIdx + 1) + '</span>' +
                        '<span class="cb-item-name" title="' + esc(t.name) + '">' + esc(t.name) + '</span>' +
                    '</div>' +
                    '<div class="cb-item-meta">' +
                        (t.unit ? '<span class="cb-item-pill">' + esc(t.unit) + '</span>' : '') +
                        '<span class="cb-item-pill">' + qCount + ' запр.</span>' +
                        varPill +
                        (!t.enabled ? '<span class="cb-item-pill">выкл</span>' : '') +
                        (missing.length > 0 ? '<span class="cb-item-pill warn" title="Не заданы переменные: ' + esc(missing.map(function (v) { return '$' + v; }).join(', ')) + '"><i class="bi bi-exclamation-triangle-fill"></i>$' + esc(missing[0]) + (missing.length > 1 ? ' +' + (missing.length - 1) : '') + '</span>' : '') +
                    '</div>' +
                    '<div class="cb-item-preview-q" title="' + esc(firstQ) + '">' + esc(firstQ) + '</div>' +
                '</div>' +
                '<div class="cb-item-ctrl">' +
                    '<div class="form-check form-switch" title="' + (t.enabled ? 'Включён в отчёт (нажмите, чтобы скрыть)' : 'Выключен (в отчёт не попадёт)') + '">' +
                        '<input class="form-check-input cb-item-toggle" type="checkbox" data-toggle-id="' + t.id + '"' + (t.enabled ? ' checked' : '') + '>' +
                    '</div>' +
                    (reorderable ? '<div class="cb-order-btns">' +
                        '<button type="button" class="btn btn-outline-secondary cb-move-up" data-move-id="' + t.id + '" title="Выше в отчёте"' + (globalIdx === 0 ? ' disabled' : '') + '><i class="bi bi-arrow-up"></i></button>' +
                        '<button type="button" class="btn btn-outline-secondary cb-move-down" data-move-id="' + t.id + '" title="Ниже в отчёте"' + (globalIdx === templates.length - 1 ? ' disabled' : '') + '><i class="bi bi-arrow-down"></i></button>' +
                    '</div>' : '') +
                '</div>' +
            '</div>';
        }).join('');

        syncBulkBar(list);
    }

    function syncBulkBar(visibleList) {
        var n = checkedIds.size;
        elBulkBar.classList.toggle('has-sel', n > 0);
        elBulkActions.classList.toggle('d-none', n === 0);
        elBulkCount.textContent = n > 0 ? 'Выбрано: ' + n : 'Выбрать все';
        if (visibleList && visibleList.length > 0) {
            var allVisChecked = visibleList.every(function (t) { return checkedIds.has(t.id); });
            var someVisChecked = visibleList.some(function (t) { return checkedIds.has(t.id); });
            elSelectAll.checked = allVisChecked;
            elSelectAll.indeterminate = !allVisChecked && someVisChecked;
            elSelectAll.disabled = false;
        } else {
            elSelectAll.checked = false;
            elSelectAll.indeterminate = false;
            elSelectAll.disabled = true;
        }
    }

    // ==================== Render: Unit Pills ====================
    function renderUnitPills() {
        var cur = elUnit.value.trim();
        elUnitPills.innerHTML = UNIT_PRESETS.map(function (u) {
            var active = cur === u.val;
            return '<button type="button" class="cb-unit-pill' + (active ? ' active' : '') + '" data-unit="' + esc(u.val) + '">' + esc(u.label) + '</button>';
        }).join('');
    }

    // ==================== Render: Query Cards ====================
    function renderQueries() {
        elQCount.textContent = String(queryRows.length);
        var effMap = effectiveVarMap(chartVarMap());
        var effNames = Object.keys(effMap);
        elQueries.innerHTML = queryRows.map(function (q, i) {
            var letter = String.fromCharCode(65 + (i % 26));
            var color = PALETTE[i % PALETTE.length];
            var diag = q.diag;
            var statusHtml = '';
            if (diag) {
                if (diag.error) {
                    statusHtml = '<span class="cb-q-status err"><i class="bi bi-x-circle-fill"></i>Ошибка</span>';
                } else if (diag.seriesCount === 0) {
                    statusHtml = '<span class="cb-q-status warn"><i class="bi bi-exclamation-circle-fill"></i>0 рядов</span>';
                } else {
                    statusHtml = '<span class="cb-q-status ok"><i class="bi bi-check-circle-fill"></i>' + diag.seriesCount + ' ' + pluralSeries(diag.seriesCount) + '</span>';
                }
            }

            var labelChips = (q.labelKeys && q.labelKeys.length > 0) ? q.labelKeys.slice(0, 8) : ['instance', 'scenario', 'status'];
            var varChips = effNames.map(function (vName) {
                return '<button type="button" class="cb-chip-ins is-var" data-ins-q="' + i + '" data-ins-text="$' + esc(vName) + '" title="Переменная: ' + esc(effMap[vName] || '(пусто)') + '">$' + esc(vName) + '</button>';
            }).join('');

            var snippetItems = PROMQL_SNIPPETS.map(function (sn, si) {
                return '<li><button type="button" class="dropdown-item small font-monospace" data-snip-q="' + i + '" data-snip-idx="' + si + '">' + esc(sn.label) + '</button></li>';
            }).join('');

            var expandedHtml = '';
            if (diag && diag.expandedPromql && diag.expandedPromql !== diag.rawPromql) {
                expandedHtml = '<details class="cb-q-expanded"><summary class="text-muted">Итоговый PromQL после подстановки переменных</summary><pre>' + esc(diag.expandedPromql) + '</pre></details>';
            }

            return '<div class="cb-q-card" data-q-idx="' + i + '">' +
                '<div class="cb-q-top">' +
                    '<span class="cb-q-letter" style="background:' + color + '">' + letter + '</span>' +
                    '<span class="small fw-semibold">Запрос ' + letter + '</span>' +
                    statusHtml +
                    '<div class="cb-q-tools">' +
                        '<button type="button" class="btn btn-outline-secondary" data-q-up="' + i + '" title="Поднять выше"' + (i === 0 ? ' disabled' : '') + '><i class="bi bi-arrow-up"></i></button>' +
                        '<button type="button" class="btn btn-outline-secondary" data-q-down="' + i + '" title="Опустить ниже"' + (i === queryRows.length - 1 ? ' disabled' : '') + '><i class="bi bi-arrow-down"></i></button>' +
                        '<button type="button" class="btn btn-outline-secondary" data-q-dup="' + i + '" title="Дублировать запрос"><i class="bi bi-copy"></i></button>' +
                        '<button type="button" class="btn btn-outline-danger" data-q-del="' + i + '" title="' + (queryRows.length > 1 ? 'Удалить запрос' : 'Очистить запрос') + '"><i class="bi bi-x-lg"></i></button>' +
                    '</div>' +
                '</div>' +
                '<div class="cb-promql-wrap">' +
                    '<textarea class="form-control form-control-sm cb-promql" rows="2" data-q-promql="' + i + '" spellcheck="false" autocomplete="off" placeholder="Например: sum by (status) (rate(k6_http_reqs_total{test_run_id=&quot;{run_id}&quot;}[{range}]))">' + esc(q.promql) + '</textarea>' +
                    '<div class="cb-ac-menu" data-q-ac="' + i + '"></div>' +
                '</div>' +
                '<div class="cb-insert-bar">' +
                    '<span class="cb-insert-lbl">Вставить:</span>' +
                    '<button type="button" class="cb-chip-ins" data-ins-q="' + i + '" data-ins-text="{run_id}" title="ID запуска теста (метка test_run_id)">{run_id}</button>' +
                    '<button type="button" class="cb-chip-ins" data-ins-q="' + i + '" data-ins-text="{range}" title="Окно для rate/increase (зависит от интервала отправки)">{range}</button>' +
                    varChips +
                    '<div class="dropdown d-inline-block ms-auto">' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary py-0 px-2 dropdown-toggle" style="font-size:.74rem" data-bs-toggle="dropdown">Функции</button>' +
                        '<ul class="dropdown-menu dropdown-menu-end">' + snippetItems + '</ul>' +
                    '</div>' +
                '</div>' +
                '<div class="cb-legend-row">' +
                    '<div class="input-group input-group-sm cb-legend-in">' +
                        '<span class="input-group-text text-muted">Легенда</span>' +
                        '<input class="form-control form-control-sm" data-q-legend="' + i + '" maxlength="120" value="' + esc(q.legend) + '" placeholder="Авто по меткам или {{instance}}">' +
                    '</div>' +
                    '<div class="d-flex flex-wrap gap-1 align-items-center">' +
                        labelChips.map(function (k) {
                            return '<button type="button" class="cb-chip-ins is-lbl" data-ins-leg="' + i + '" data-ins-text="{{' + esc(k) + '}}" title="Подставить значение метки ' + esc(k) + '">{{' + esc(k) + '}}</button>';
                        }).join('') +
                    '</div>' +
                '</div>' +
                (diag && diag.error ? '<div class="cb-q-diag-err"><i class="bi bi-exclamation-octagon-fill me-1"></i>' + esc(diag.error) + '</div>' : '') +
                expandedHtml +
            '</div>';
        }).join('');

        elQueries.querySelectorAll('textarea.cb-promql').forEach(autoResize);
    }

    // ==================== Render: Per-Chart Variables ====================
    function renderChartVars() {
        if (!elCvRows) { return; }
        var definedLocal = chartVarMap();
        var scriptDefaults = varMap();
        var localCount = Object.keys(definedLocal).length;
        if (elCvCount) { elCvCount.textContent = String(localCount); }

        // подсказки для переменных $var, используемых в запросах/имени этого графика, но ещё не переопределённых на графике
        if (elCvHints) {
            var refVars = draftReferencedVars().filter(function (v) { return !(v in definedLocal); });
            if (refVars.length > 0) {
                elCvHints.classList.remove('d-none');
                elCvHints.innerHTML = '<span class="small text-muted me-1">Найдены в запросах:</span>' +
                    refVars.map(function (v) {
                        var hasDef = v in scriptDefaults;
                        var defHint = hasDef ? ' (общее: ' + scriptDefaults[v] + ')' : ' (не задана)';
                        var cls = hasDef ? 'btn-outline-primary' : 'btn-outline-warning text-dark';
                        return '<button type="button" class="btn btn-sm py-0 px-2 ' + cls + '" data-cv-quick="' + esc(v) + '">' +
                            '<i class="bi bi-plus-lg me-1"></i>$' + esc(v) + '<span class="opacity-75 ms-1" style="font-size:.75em">' + esc(defHint) + '</span></button>';
                    }).join('');
            } else {
                elCvHints.classList.add('d-none');
                elCvHints.innerHTML = '';
            }
        }

        if (chartVarRows.length === 0) {
            elCvRows.innerHTML = '<div class="text-muted small">Своих переменных у графика нет — используются общие переменные скрипта. Нажмите «+ Переменная» или кнопку выше, чтобы задать значение только для этого графика.</div>';
            return;
        }
        elCvRows.innerHTML = chartVarRows.map(function (r, idx) {
            return '<div class="cb-cv-row" data-cv-idx="' + idx + '">' +
                '<div class="input-group input-group-sm" style="width:190px;flex:0 0 auto">' +
                    '<span class="input-group-text text-primary fw-bold">$</span>' +
                    '<input class="form-control cb-cv-name" data-cv-name="' + idx + '" maxlength="64" value="' + esc(r.name) + '" placeholder="instance">' +
                '</div>' +
                '<input class="form-control form-control-sm cb-cv-val" data-cv-val="' + idx + '" value="' + esc(r.value) + '" placeholder="значение (например app-01, .* или {run_id})">' +
                '<div class="d-flex gap-1 align-items-center">' +
                    '<button type="button" class="cb-chip-ins" data-cv-preset="' + idx + '" data-cv-pval="{run_id}">{run_id}</button>' +
                    '<button type="button" class="cb-chip-ins" data-cv-preset="' + idx + '" data-cv-pval=".*">.*</button>' +
                '</div>' +
                '<button type="button" class="btn btn-sm btn-outline-danger py-0 px-2" data-cv-del="' + idx + '" title="Убрать переменную графика"><i class="bi bi-x-lg"></i></button>' +
            '</div>';
        }).join('');
    }

    function pluralSeries(n) {
        var m10 = n % 10, m100 = n % 100;
        if (m10 === 1 && m100 !== 11) { return 'ряд'; }
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) { return 'ряда'; }
        return 'рядов';
    }

    function autoResize(ta) {
        if (!ta) { return; }
        ta.style.height = 'auto';
        ta.style.height = Math.max(62, Math.min(240, ta.scrollHeight + 2)) + 'px';
    }

    // ==================== Editor Loading & Switching ====================
    function loadNew(preset) {
        selectedId = null;
        elStudioMode.innerHTML = '<span class="badge text-bg-primary me-1">Новый</span>Конструктор графика';
        elStudioEditBtns.classList.add('d-none');
        elSaveBtn.innerHTML = '<i class="bi bi-plus-lg me-1"></i>Создать график';
        elName.value = preset ? preset.name : '';
        elUnit.value = preset ? (preset.unit || '') : '';
        elEnabled.checked = true;
        queryRows = preset && preset.queries && preset.queries.length > 0
            ? preset.queries.map(function (q) { return { promql: q.promql || '', legend: q.legend || '', diag: null, labelKeys: [] }; })
            : [{ promql: '', legend: '', diag: null, labelKeys: [] }];
        chartVarRows = [];
        if (preset && preset.variables && typeof preset.variables === 'object') {
            Object.keys(preset.variables).forEach(function (k) { chartVarRows.push({ name: k, value: preset.variables[k] }); });
        }
        showStudioError('');
        renderUnitPills();
        renderQueries();
        renderChartVars();
        resetPreviewStage();
        renderList();
        renderVariables();
        if (preset) {
            editorBaseline = ''; // mark dirty so user knows to save the preset
            syncDirty();
        } else {
            markBaseline();
        }
        elName.focus();
    }

    function loadTemplate(id, triggerAutoPreview) {
        var t = templates.find(function (x) { return x.id === id; });
        if (!t) { loadNew(); return; }
        selectedId = t.id;
        var pos = templates.indexOf(t) + 1;
        elStudioMode.innerHTML = '<span class="badge text-bg-secondary me-1">#' + pos + '</span>' + esc(t.name);
        elStudioEditBtns.classList.remove('d-none');
        elSaveBtn.innerHTML = '<i class="bi bi-save me-1"></i>Сохранить изменения';
        elName.value = t.name || '';
        elUnit.value = t.unit || '';
        elEnabled.checked = !!t.enabled;
        queryRows = (t.queries && t.queries.length > 0 ? t.queries : [{ promql: t.promql || '', legend: '' }]).map(function (q) {
            return { promql: q.promql || '', legend: q.legend || '', diag: null, labelKeys: [] };
        });
        chartVarRows = [];
        if (t.variables && typeof t.variables === 'object') {
            Object.keys(t.variables).forEach(function (k) { chartVarRows.push({ name: k, value: t.variables[k] }); });
        }
        showStudioError('');
        renderUnitPills();
        renderQueries();
        renderChartVars();
        resetPreviewStage();
        renderList();
        renderVariables();
        markBaseline();
        if (triggerAutoPreview && elAutoPreview && elAutoPreview.checked && promConfigured) {
            runPreview();
        }
    }

    function resetPreviewStage() {
        elPreviewStage.classList.remove('has-img', 'loading');
        elPreviewStage.innerHTML = '<div class="cb-preview-loading"><span class="spinner-border spinner-border-sm"></span><span>Запрос в Prometheus и построение графика…</span></div>' +
            '<div class="cb-preview-empty">' +
                '<i class="bi bi-bar-chart-line"></i>' +
                '<div class="fw-semibold text-body mb-1">Живой предпросмотр графика отчёта</div>' +
                '<div class="small">' + (promConfigured
                    ? 'Нажмите «Проверить и построить» (или <kbd>Ctrl+Enter</kbd>), чтобы выполнить PromQL и увидеть точную картинку из отчёта.'
                    : 'Prometheus ещё не настроен — укажите URL в Настройках, чтобы проверять графики здесь.') +
                '</div>' +
            '</div>';
        elPreviewMeta.innerHTML = '';
    }

    // ==================== Live Preview ====================
    function runPreview() {
        var draft = collectDraft();
        var nonEmpty = draft.queries.filter(function (q) { return q.promql.length > 0; });
        if (nonEmpty.length === 0) {
            showStudioError('Введите хотя бы один PromQL-запрос для предпросмотра');
            return;
        }
        showStudioError('');

        var srcVal = elPreviewSrc ? elPreviewSrc.value : 'min:30';
        var runId = null;
        var minutes = 30;
        if (srcVal.indexOf('run:') === 0) {
            runId = parseInt(srcVal.slice(4), 10);
        } else if (srcVal.indexOf('min:') === 0) {
            minutes = parseInt(srcVal.slice(4), 10) || 30;
        }

        if (previewController) {
            try { previewController.abort(); } catch (e) {}
            previewController = null;
        }
        var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        previewController = ctrl;
        var mySeq = ++previewSeq;

        elPreviewStage.classList.add('loading');
        elPreviewBtn.disabled = true;

        api('/api/preview', 'POST', {
            name: draft.name || 'Предпросмотр',
            unit: draft.unit,
            queries: draft.queries,
            variables: draft.variables,
            runId: runId,
            minutes: minutes
        }, ctrl ? ctrl.signal : null).then(function (res) {
            if (mySeq !== previewSeq) { return; }
            previewController = null;
            elPreviewStage.classList.remove('loading');
            elPreviewBtn.disabled = false;
            var p = res.data;
            if (!res.ok || !p) {
                renderPreviewError((p && p.error) || 'Не удалось выполнить предпросмотр');
                return;
            }
            // attach per-query diagnostics & discovered label keys
            if (Array.isArray(p.queries)) {
                p.queries.forEach(function (d) {
                    if (queryRows[d.index]) {
                        queryRows[d.index].diag = d;
                        if (Array.isArray(d.labelKeys) && d.labelKeys.length > 0) {
                            queryRows[d.index].labelKeys = d.labelKeys;
                        }
                    }
                });
                renderQueries();
            }

            if (p.dataUri) {
                elPreviewStage.classList.add('has-img');
                elPreviewStage.innerHTML = '<div class="cb-preview-loading"><span class="spinner-border spinner-border-sm"></span><span>Обновление…</span></div>' +
                    '<img src="' + p.dataUri + '" alt="' + esc(p.title) + '" title="Нажмите, чтобы увеличить">';
                elPreviewStage.querySelector('img').addEventListener('click', function () {
                    elZoomImg.src = p.dataUri;
                    elZoomTitle.textContent = p.title || 'Предпросмотр графика';
                    bootstrap.Modal.getOrCreateInstance(document.getElementById('cb-zoom-modal')).show();
                });
                elPreviewMeta.innerHTML = '<span><i class="bi bi-clock me-1"></i>' + esc(p.windowLabel) + ' · <b>' + p.totalSeries + ' ' + pluralSeries(p.totalSeries) + '</b> · ' + p.elapsedMs + ' мс</span>' +
                    '<span class="text-muted">Нажмите на график для увеличения</span>';
            } else {
                var errMsg = p.error || (p.queries && p.queries.some(function (d) { return d.error; })
                    ? 'Ошибка в PromQL — смотрите детали под запросом выше.'
                    : 'Prometheus не вернул данных за выбранный период (' + (p.windowLabel || '') + '). Проверьте метки, {run_id}, переменные или выберите другой запуск.');
                renderPreviewError(errMsg, p.windowLabel, p.elapsedMs);
            }
        }).catch(function (err) {
            if (mySeq !== previewSeq || (err && err.name === 'AbortError')) { return; }
            previewController = null;
            elPreviewStage.classList.remove('loading');
            elPreviewBtn.disabled = false;
            renderPreviewError('Сетевая ошибка при обращении к серверу');
        });
    }

    function renderPreviewError(msg, windowLabel, elapsedMs) {
        elPreviewStage.classList.remove('has-img');
        elPreviewStage.innerHTML = '<div class="cb-preview-loading"><span class="spinner-border spinner-border-sm"></span><span>Запрос в Prometheus…</span></div>' +
            '<div class="cb-preview-empty">' +
                '<i class="bi bi-exclamation-triangle text-warning"></i>' +
                '<div class="fw-semibold text-body mb-1">График не построен</div>' +
                '<div class="small">' + esc(msg) + '</div>' +
            '</div>';
        elPreviewMeta.innerHTML = windowLabel ? '<span><i class="bi bi-clock me-1"></i>' + esc(windowLabel) + (elapsedMs ? ' · ' + elapsedMs + ' мс' : '') + '</span>' : '';
    }

    // ==================== Save / Duplicate / Delete / Reorder ====================
    function saveChart() {
        var draft = collectDraft();
        if (!draft.name) {
            showStudioError('Укажите имя графика');
            elName.focus();
            return;
        }
        var nonEmpty = draft.queries.filter(function (q) { return q.promql.length > 0; });
        if (nonEmpty.length === 0) {
            showStudioError('Введите хотя бы один PromQL-запрос');
            var firstTa = elQueries.querySelector('textarea.cb-promql');
            if (firstTa) { firstTa.focus(); }
            return;
        }
        showStudioError('');
        elSaveBtn.disabled = true;

        api('/api/save', 'POST', {
            id: selectedId,
            name: draft.name,
            unit: draft.unit,
            enabled: draft.enabled,
            queries: nonEmpty,
            variables: draft.variables
        }).then(function (res) {
            elSaveBtn.disabled = false;
            if (!res.ok || !res.data.ok) {
                showStudioError((res.data && res.data.error) || 'Не удалось сохранить график');
                return;
            }
            templates = res.data.templates || templates;
            var saved = res.data.template;
            toast(res.data.message || 'График сохранён', 'ok');
            if (saved) {
                loadTemplate(saved.id, false);
            } else {
                renderList();
            }
        }).catch(function () {
            elSaveBtn.disabled = false;
            showStudioError('Сетевая ошибка при сохранении');
        });
    }

    function duplicateCurrent() {
        if (!selectedId) { return; }
        withDiscardCheck(function () {
            api('/api/' + selectedId + '/duplicate', 'POST').then(function (res) {
                if (!res.ok || !res.data.ok) {
                    toast((res.data && res.data.error) || 'Не удалось дублировать', 'err');
                    return;
                }
                templates = res.data.templates || templates;
                toast(res.data.message || 'Создана копия', 'ok');
                if (res.data.template) { loadTemplate(res.data.template.id, false); }
            });
        });
    }

    function deleteCurrent() {
        if (!selectedId) { return; }
        var cur = templates.find(function (t) { return t.id === selectedId; });
        window.k6Confirm({
            title: 'Удалить график?',
            message: 'График «' + (cur ? cur.name : '') + '» будет удалён безвозвратно.',
            confirmText: 'Удалить',
            variant: 'danger'
        }).then(function (ok) {
            if (!ok) { return; }
            var delId = selectedId;
            api('/api/' + delId + '/delete', 'POST').then(function (res) {
                if (!res.ok) { toast('Не удалось удалить график', 'err'); return; }
                templates = res.data.templates || templates.filter(function (t) { return t.id !== delId; });
                checkedIds.delete(delId);
                toast('График удалён', 'info');
                if (templates.length > 0) { loadTemplate(templates[0].id, false); }
                else { loadNew(); }
            });
        });
    }

    function persistOrder(newList) {
        templates = newList;
        renderList();
        if (selectedId) {
            var pos = templates.findIndex(function (t) { return t.id === selectedId; }) + 1;
            var cur = templates.find(function (t) { return t.id === selectedId; });
            if (cur && pos > 0) {
                elStudioMode.innerHTML = '<span class="badge text-bg-secondary me-1">#' + pos + '</span>' + esc(cur.name);
            }
        }
        api('/api/reorder', 'POST', { ids: templates.map(function (t) { return t.id; }) }).then(function (res) {
            if (res.ok && res.data.ok) {
                if (res.data.templates) { templates = res.data.templates; renderList(); }
                toast('Порядок графиков в отчёте сохранён', 'ok');
            } else {
                toast('Не удалось сохранить порядок', 'err');
            }
        });
    }

    function moveTemplate(id, delta) {
        var idx = templates.findIndex(function (t) { return t.id === id; });
        var target = idx + delta;
        if (idx < 0 || target < 0 || target >= templates.length) { return; }
        var copy = templates.slice();
        var item = copy.splice(idx, 1)[0];
        copy.splice(target, 0, item);
        persistOrder(copy);
    }

    // ==================== Prometheus Metric Autocomplete ====================
    function ensureMetricsLoaded() {
        if (!promConfigured || metricNames !== null || metricFetchInFlight) { return; }
        metricFetchInFlight = true;
        api('/api/metrics', 'GET').then(function (res) {
            metricFetchInFlight = false;
            if (res.ok && res.data && Array.isArray(res.data.metrics)) {
                metricNames = res.data.metrics;
            } else {
                metricNames = [];
            }
        }).catch(function () {
            metricFetchInFlight = false;
            metricNames = [];
        });
    }

    function getTokenAtCursor(ta) {
        var pos = ta.selectionStart;
        var before = ta.value.slice(0, pos);
        var m = /([A-Za-z_:][A-Za-z0-9_:]*)$/.exec(before);
        if (!m) { return null; }
        var start = pos - m[1].length;
        // ignore right after $ (variable) or { or " (label/string)
        if (start > 0 && (before[start - 1] === '$' || before[start - 1] === '"')) { return null; }
        return { word: m[1], start: start, end: pos };
    }

    function closeAllAc() {
        elQueries.querySelectorAll('.cb-ac-menu.open').forEach(function (m) {
            m.classList.remove('open');
            m.innerHTML = '';
        });
    }

    function updateAc(ta, qIdx) {
        var menu = elQueries.querySelector('.cb-ac-menu[data-q-ac="' + qIdx + '"]');
        if (!menu || !metricNames || metricNames.length === 0) { return; }
        var tok = getTokenAtCursor(ta);
        if (!tok || tok.word.length < 2) {
            menu.classList.remove('open');
            menu.innerHTML = '';
            return;
        }
        var needle = tok.word.toLowerCase();
        var starts = [], contains = [];
        for (var i = 0; i < metricNames.length; i++) {
            var m = metricNames[i];
            var ml = m.toLowerCase();
            if (ml === needle) { continue; }
            if (ml.indexOf(needle) === 0) { starts.push(m); }
            else if (ml.indexOf(needle) > 0) { contains.push(m); }
            if (starts.length >= 12) { break; }
        }
        var matches = starts.concat(contains).slice(0, 12);
        if (matches.length === 0) {
            menu.classList.remove('open');
            menu.innerHTML = '';
            return;
        }
        menu.innerHTML = matches.map(function (m, idx) {
            var pos = m.toLowerCase().indexOf(needle);
            var hl = esc(m.slice(0, pos)) + '<mark>' + esc(m.slice(pos, pos + needle.length)) + '</mark>' + esc(m.slice(pos + needle.length));
            return '<button type="button" class="cb-ac-item' + (idx === 0 ? ' sel' : '') + '" data-ac-val="' + esc(m) + '">' + hl + '</button>';
        }).join('');
        menu.classList.add('open');
    }

    function applyAc(ta, menu, val) {
        var tok = getTokenAtCursor(ta);
        if (!tok) { return; }
        ta.value = ta.value.slice(0, tok.start) + val + ta.value.slice(tok.end);
        var caret = tok.start + val.length;
        ta.setSelectionRange(caret, caret);
        ta.focus();
        var idx = parseInt(ta.dataset.qPromql, 10);
        queryRows[idx].promql = ta.value;
        autoResize(ta);
        syncDirty();
        menu.classList.remove('open');
        menu.innerHTML = '';
    }

    function insertAtCursor(input, text) {
        var start = input.selectionStart != null ? input.selectionStart : input.value.length;
        var end = input.selectionEnd != null ? input.selectionEnd : input.value.length;
        input.value = input.value.slice(0, start) + text + input.value.slice(end);
        var nextPos = start + text.length;
        input.setSelectionRange(nextPos, nextPos);
        input.focus();
    }

    // ==================== Populate Presets Menu ====================
    function renderPresetsMenu() {
        var menu = document.getElementById('cb-presets-menu');
        if (!menu) { return; }
        var html = '';
        CHART_PRESETS.forEach(function (grp, gi) {
            if (gi > 0) { html += '<li><hr class="dropdown-divider"></li>'; }
            html += '<li><h6 class="dropdown-header">' + esc(grp.group) + '</h6></li>';
            grp.items.forEach(function (item, ii) {
                html += '<li><button type="button" class="dropdown-item d-flex justify-content-between align-items-center gap-3" data-preset-g="' + gi + '" data-preset-i="' + ii + '">' +
                    '<span>' + esc(item.name) + '</span>' +
                    '<span class="badge text-bg-light border">' + esc(item.unit || '—') + '</span></button></li>';
            });
        });
        menu.innerHTML = html;
        menu.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-preset-g]');
            if (!btn) { return; }
            var p = CHART_PRESETS[+btn.dataset.presetG].items[+btn.dataset.presetI];
            withDiscardCheck(function () {
                loadNew(p);
                toast('Шаблон «' + p.name + '» подставлен — проверьте и сохраните', 'info');
            });
        });
    }

    // ==================== Event Listeners ====================
    // New chart button
    document.getElementById('cb-new-btn').addEventListener('click', function () {
        withDiscardCheck(function () { loadNew(); });
    });

    // Duplicate & delete in studio header
    document.getElementById('cb-dup-btn').addEventListener('click', duplicateCurrent);
    document.getElementById('cb-del-btn').addEventListener('click', deleteCurrent);

    // Search & filter pills
    elSearch.addEventListener('input', function () {
        searchText = elSearch.value;
        renderList();
    });
    elFilterBtns.forEach(function (btn) {
        btn.addEventListener('click', function () {
            filterMode = btn.dataset.cbFilter;
            elFilterBtns.forEach(function (b) { b.classList.toggle('active', b === btn); });
            renderList();
        });
    });

    // Bulk select all
    elSelectAll.addEventListener('change', function () {
        var vis = filteredTemplates();
        if (elSelectAll.checked) {
            vis.forEach(function (t) { checkedIds.add(t.id); });
        } else {
            vis.forEach(function (t) { checkedIds.delete(t.id); });
        }
        renderList();
    });

    // Bulk actions
    document.getElementById('cb-bulk-enable').addEventListener('click', function () { bulkToggle(true); });
    document.getElementById('cb-bulk-disable').addEventListener('click', function () { bulkToggle(false); });
    document.getElementById('cb-bulk-delete').addEventListener('click', function () {
        var ids = Array.from(checkedIds);
        if (ids.length === 0) { return; }
        window.k6Confirm({
            title: 'Удалить выбранные графики?',
            message: 'Выбранные графики (' + ids.length + ' шт.) будут удалены безвозвратно.',
            confirmText: 'Удалить (' + ids.length + ')',
            variant: 'danger'
        }).then(function (ok) {
            if (!ok) { return; }
            api('/api/delete-selected', 'POST', { ids: ids }).then(function (res) {
                if (!res.ok) { toast('Не удалось удалить выбранные', 'err'); return; }
                templates = res.data.templates || [];
                checkedIds.clear();
                toast('Удалено графиков: ' + (res.data.count || ids.length), 'info');
                if (selectedId && !templates.some(function (t) { return t.id === selectedId; })) {
                    if (templates.length > 0) { loadTemplate(templates[0].id, false); }
                    else { loadNew(); }
                } else {
                    renderList();
                }
            });
        });
    });

    function bulkToggle(enabled) {
        var ids = Array.from(checkedIds);
        if (ids.length === 0) { return; }
        api('/api/bulk-toggle', 'POST', { ids: ids, enabled: enabled }).then(function (res) {
            if (!res.ok) { toast('Не удалось обновить статус', 'err'); return; }
            templates = res.data.templates || templates;
            if (selectedId) {
                var cur = templates.find(function (t) { return t.id === selectedId; });
                if (cur && !isDirty()) { elEnabled.checked = cur.enabled; markBaseline(); }
            }
            renderList();
            toast((enabled ? 'Включено в отчёт: ' : 'Скрыто из отчёта: ') + (res.data.count || ids.length), 'ok');
        });
    }

    // Overflow actions: enable all / disable all
    document.getElementById('cb-enable-all').addEventListener('click', function () {
        if (templates.length === 0) { return; }
        api('/api/bulk-toggle', 'POST', { ids: templates.map(function (t) { return t.id; }), enabled: true }).then(function (res) {
            if (res.ok && res.data.templates) {
                templates = res.data.templates;
                if (selectedId && !isDirty()) { elEnabled.checked = true; markBaseline(); }
                renderList();
                toast('Все графики включены в отчёт', 'ok');
            }
        });
    });
    document.getElementById('cb-disable-all').addEventListener('click', function () {
        if (templates.length === 0) { return; }
        api('/api/bulk-toggle', 'POST', { ids: templates.map(function (t) { return t.id; }), enabled: false }).then(function (res) {
            if (res.ok && res.data.templates) {
                templates = res.data.templates;
                if (selectedId && !isDirty()) { elEnabled.checked = false; markBaseline(); }
                renderList();
                toast('Все графики скрыты из отчёта', 'info');
            }
        });
    });

    // Left list interactions (click select, checkbox, toggle switch, move up/down, open presets)
    elList.addEventListener('click', function (e) {
        if (e.target.closest('[data-cb-open-copy-script]')) {
            openCopyFromScriptModal();
            return;
        }
        if (e.target.closest('[data-cb-open-presets]')) {
            var pBtn = document.getElementById('cb-presets-btn');
            if (pBtn) { bootstrap.Dropdown.getOrCreateInstance(pBtn).show(); }
            return;
        }
        var upBtn = e.target.closest('.cb-move-up');
        if (upBtn) { e.stopPropagation(); moveTemplate(+upBtn.dataset.moveId, -1); return; }
        var dnBtn = e.target.closest('.cb-move-down');
        if (dnBtn) { e.stopPropagation(); moveTemplate(+dnBtn.dataset.moveId, 1); return; }
        if (e.target.closest('.cb-item-check') || e.target.closest('.cb-item-toggle') || e.target.closest('.form-switch')) {
            e.stopPropagation();
            return;
        }
        var item = e.target.closest('.cb-item');
        if (!item) { return; }
        var id = +item.dataset.id;
        if (id === selectedId) { return; }
        withDiscardCheck(function () { loadTemplate(id, true); });
    });

    elList.addEventListener('change', function (e) {
        if (e.target.classList.contains('cb-item-check')) {
            var cid = +e.target.dataset.checkId;
            if (e.target.checked) { checkedIds.add(cid); } else { checkedIds.delete(cid); }
            syncBulkBar(filteredTemplates());
            return;
        }
        if (e.target.classList.contains('cb-item-toggle')) {
            var tid = +e.target.dataset.toggleId;
            var en = e.target.checked;
            api('/api/' + tid + '/toggle', 'POST', { enabled: en }).then(function (res) {
                if (!res.ok) { e.target.checked = !en; toast('Ошибка обновления', 'err'); return; }
                var t = templates.find(function (x) { return x.id === tid; });
                if (t) { t.enabled = en; }
                if (selectedId === tid && !isDirty()) { elEnabled.checked = en; markBaseline(); }
                renderList();
            });
        }
    });

    // Drag & Drop reordering in left list
    elList.addEventListener('dragstart', function (e) {
        var item = e.target.closest('.cb-item[draggable="true"]');
        if (!item) { return; }
        dragSrcId = +item.dataset.id;
        item.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
        try { e.dataTransfer.setData('text/plain', String(dragSrcId)); } catch (err) {}
    });
    elList.addEventListener('dragend', function () {
        dragSrcId = null;
        elList.querySelectorAll('.cb-item').forEach(function (el) {
            el.classList.remove('dragging', 'drag-over-top', 'drag-over-bottom');
        });
    });
    elList.addEventListener('dragover', function (e) {
        if (dragSrcId == null) { return; }
        var item = e.target.closest('.cb-item[draggable="true"]');
        if (!item || +item.dataset.id === dragSrcId) { return; }
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        var rect = item.getBoundingClientRect();
        var topHalf = (e.clientY - rect.top) < rect.height / 2;
        elList.querySelectorAll('.cb-item').forEach(function (el) {
            if (el !== item) { el.classList.remove('drag-over-top', 'drag-over-bottom'); }
        });
        item.classList.toggle('drag-over-top', topHalf);
        item.classList.toggle('drag-over-bottom', !topHalf);
    });
    elList.addEventListener('drop', function (e) {
        if (dragSrcId == null) { return; }
        var item = e.target.closest('.cb-item[draggable="true"]');
        if (!item || +item.dataset.id === dragSrcId) { return; }
        e.preventDefault();
        var targetId = +item.dataset.id;
        var insertBefore = item.classList.contains('drag-over-top');
        var copy = templates.slice();
        var fromIdx = copy.findIndex(function (t) { return t.id === dragSrcId; });
        if (fromIdx < 0) { return; }
        var moved = copy.splice(fromIdx, 1)[0];
        var toIdx = copy.findIndex(function (t) { return t.id === targetId; });
        if (toIdx < 0) { return; }
        copy.splice(insertBefore ? toIdx : toIdx + 1, 0, moved);
        dragSrcId = null;
        persistOrder(copy);
    });

    // Studio fields
    elName.addEventListener('input', function () { syncDirty(); updateNameVarHint(); renderChartVars(); renderVariables(); });
    elUnit.addEventListener('input', function () { renderUnitPills(); syncDirty(); });
    elEnabled.addEventListener('change', syncDirty);

    // Per-chart variables interactions
    if (elCvAddBtn) {
        elCvAddBtn.addEventListener('click', function () {
            chartVarRows.push({ name: '', value: '' });
            renderChartVars();
            syncDirty();
            var lastName = elCvRows.querySelector('input[data-cv-name="' + (chartVarRows.length - 1) + '"]');
            if (lastName) { lastName.focus(); }
        });
    }
    if (elCvHints) {
        elCvHints.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-cv-quick]');
            if (!btn) { return; }
            var name = btn.dataset.cvQuick;
            var defMap = varMap();
            var val = name in defMap ? defMap[name]
                : (/^(testid|test_id|run_id|runid|test_run_id)$/i.test(name) ? '{run_id}' : '.*');
            chartVarRows.push({ name: name, value: val });
            renderChartVars();
            renderQueries();
            renderVariables();
            syncDirty();
            var valInp = elCvRows.querySelector('input[data-cv-val="' + (chartVarRows.length - 1) + '"]');
            if (valInp) { valInp.focus(); valInp.select(); }
        });
    }
    if (elCvRows) {
        elCvRows.addEventListener('input', function (e) {
            if (e.target.dataset.cvName != null) {
                chartVarRows[+e.target.dataset.cvName].name = e.target.value;
                syncDirty();
                updateNameVarHint();
                renderVariables();
            } else if (e.target.dataset.cvVal != null) {
                chartVarRows[+e.target.dataset.cvVal].value = e.target.value;
                syncDirty();
                updateNameVarHint();
                renderVariables();
            }
        });
        elCvRows.addEventListener('change', function (e) {
            if (e.target.dataset.cvName != null) {
                renderChartVars();
                renderQueries();
            }
        });
        elCvRows.addEventListener('click', function (e) {
            var del = e.target.closest('[data-cv-del]');
            if (del) {
                chartVarRows.splice(+del.dataset.cvDel, 1);
                renderChartVars();
                renderQueries();
                renderVariables();
                syncDirty();
                return;
            }
            var preset = e.target.closest('[data-cv-preset]');
            if (preset) {
                var idx = +preset.dataset.cvPreset;
                chartVarRows[idx].value = preset.dataset.cvPval;
                renderChartVars();
                renderVariables();
                syncDirty();
            }
        });
    }

    elUnitPills.addEventListener('click', function (e) {
        var pill = e.target.closest('.cb-unit-pill');
        if (!pill) { return; }
        elUnit.value = pill.dataset.unit;
        renderUnitPills();
        syncDirty();
    });

    // Add query button
    document.getElementById('cb-add-q-btn').addEventListener('click', function () {
        if (queryRows.length >= 20) { toast('На одном графике не больше 20 запросов', 'err'); return; }
        queryRows.push({ promql: '', legend: '', diag: null, labelKeys: [] });
        renderQueries();
        syncDirty();
        var lastTa = elQueries.querySelector('textarea[data-q-promql="' + (queryRows.length - 1) + '"]');
        if (lastTa) { lastTa.focus(); }
    });

    // Queries interactions
    elQueries.addEventListener('focusin', function (e) {
        if (e.target.classList.contains('cb-promql')) { ensureMetricsLoaded(); }
    });

    elQueries.addEventListener('input', function (e) {
        if (e.target.dataset.qPromql != null) {
            var idx = +e.target.dataset.qPromql;
            queryRows[idx].promql = e.target.value;
            autoResize(e.target);
            updateAc(e.target, idx);
            syncDirty();
            renderChartVars();
            renderVariables();
        } else if (e.target.dataset.qLegend != null) {
            queryRows[+e.target.dataset.qLegend].legend = e.target.value;
            syncDirty();
        }
    });

    elQueries.addEventListener('keydown', function (e) {
        if (!e.target.classList.contains('cb-promql')) { return; }
        var idx = +e.target.dataset.qPromql;
        var menu = elQueries.querySelector('.cb-ac-menu[data-q-ac="' + idx + '"]');
        if (menu && menu.classList.contains('open')) {
            var items = Array.prototype.slice.call(menu.querySelectorAll('.cb-ac-item'));
            var cur = items.findIndex(function (x) { return x.classList.contains('sel'); });
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                if (cur >= 0) { items[cur].classList.remove('sel'); }
                items[(cur + 1) % items.length].classList.add('sel');
                return;
            }
            if (e.key === 'ArrowUp') {
                e.preventDefault();
                if (cur >= 0) { items[cur].classList.remove('sel'); }
                items[(cur - 1 + items.length) % items.length].classList.add('sel');
                return;
            }
            if ((e.key === 'Enter' && !e.ctrlKey && !e.metaKey) || e.key === 'Tab') {
                if (cur >= 0 && items[cur]) {
                    e.preventDefault();
                    applyAc(e.target, menu, items[cur].dataset.acVal);
                    return;
                }
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                menu.classList.remove('open');
                return;
            }
        }
    });

    elQueries.addEventListener('mousedown', function (e) {
        var acItem = e.target.closest('.cb-ac-item');
        if (!acItem) { return; }
        e.preventDefault();
        var menu = acItem.closest('.cb-ac-menu');
        var ta = elQueries.querySelector('textarea[data-q-promql="' + menu.dataset.qAc + '"]');
        if (ta) { applyAc(ta, menu, acItem.dataset.acVal); }
    });

    document.addEventListener('click', function (e) {
        if (!e.target.closest('.cb-promql-wrap')) { closeAllAc(); }
    });

    elQueries.addEventListener('click', function (e) {
        var btn;
        if ((btn = e.target.closest('[data-q-del]'))) {
            var idx = +btn.dataset.qDel;
            if (queryRows.length > 1) { queryRows.splice(idx, 1); }
            else { queryRows[0] = { promql: '', legend: '', diag: null, labelKeys: [] }; }
            renderQueries(); syncDirty(); renderChartVars(); renderVariables();
            return;
        }
        if ((btn = e.target.closest('[data-q-dup]'))) {
            if (queryRows.length >= 20) { toast('На одном графике не больше 20 запросов', 'err'); return; }
            var i = +btn.dataset.qDup;
            queryRows.splice(i + 1, 0, { promql: queryRows[i].promql, legend: queryRows[i].legend, diag: null, labelKeys: queryRows[i].labelKeys.slice() });
            renderQueries(); syncDirty();
            return;
        }
        if ((btn = e.target.closest('[data-q-up]'))) {
            var u = +btn.dataset.qUp;
            if (u > 0) { var tmp = queryRows[u - 1]; queryRows[u - 1] = queryRows[u]; queryRows[u] = tmp; renderQueries(); syncDirty(); }
            return;
        }
        if ((btn = e.target.closest('[data-q-down]'))) {
            var d = +btn.dataset.qDown;
            if (d < queryRows.length - 1) { var tmp2 = queryRows[d + 1]; queryRows[d + 1] = queryRows[d]; queryRows[d] = tmp2; renderQueries(); syncDirty(); }
            return;
        }
        if ((btn = e.target.closest('[data-ins-q]'))) {
            var qi = +btn.dataset.insQ;
            var ta = elQueries.querySelector('textarea[data-q-promql="' + qi + '"]');
            if (ta) {
                insertAtCursor(ta, btn.dataset.insText);
                queryRows[qi].promql = ta.value;
                autoResize(ta);
                syncDirty();
                renderChartVars();
                renderVariables();
            }
            return;
        }
        if ((btn = e.target.closest('[data-ins-leg]'))) {
            var li = +btn.dataset.insLeg;
            var inp = elQueries.querySelector('input[data-q-legend="' + li + '"]');
            if (inp) {
                var sep = inp.value && !/\s$/.test(inp.value) ? ' ' : '';
                insertAtCursor(inp, sep + btn.dataset.insText);
                queryRows[li].legend = inp.value;
                syncDirty();
            }
            return;
        }
        if ((btn = e.target.closest('[data-snip-q]'))) {
            var si = +btn.dataset.snipQ;
            var snip = PROMQL_SNIPPETS[+btn.dataset.snipIdx];
            var ta2 = elQueries.querySelector('textarea[data-q-promql="' + si + '"]');
            if (ta2 && snip) {
                var s0 = ta2.selectionStart, s1 = ta2.selectionEnd;
                var sel = ta2.value.slice(s0, s1).trim();
                var replacement = snip.wrap(sel);
                ta2.value = ta2.value.slice(0, s0) + replacement + ta2.value.slice(s1);
                ta2.setSelectionRange(s0, s0 + replacement.length);
                ta2.focus();
                queryRows[si].promql = ta2.value;
                autoResize(ta2);
                syncDirty();
                renderChartVars();
                renderVariables();
            }
        }
    });

    // Save / Reset / Preview buttons
    elSaveBtn.addEventListener('click', saveChart);
    elResetBtn.addEventListener('click', function () {
        if (selectedId) { loadTemplate(selectedId, false); }
        else { loadNew(); }
    });
    elPreviewBtn.addEventListener('click', runPreview);
    if (elPreviewSrc) {
        elPreviewSrc.addEventListener('change', function () {
            if (elPreviewStage.classList.contains('has-img')) { runPreview(); }
        });
    }
    if (elAutoPreview) {
        elAutoPreview.checked = localStorage.getItem('k6lab.cb.autopreview') === '1';
        elAutoPreview.addEventListener('change', function () {
            localStorage.setItem('k6lab.cb.autopreview', elAutoPreview.checked ? '1' : '0');
        });
    }

    // Keyboard shortcuts (Ctrl+Enter = preview, Ctrl+S = save)
    elStudio.addEventListener('keydown', function (e) {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            e.preventDefault();
            runPreview();
        } else if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
            e.preventDefault();
            saveChart();
        }
    });

    // ==================== Variables Panel ====================
    elVarsBtn.addEventListener('click', function () {
        elVarsPanel.classList.toggle('d-none');
    });
    document.getElementById('cb-vars-close').addEventListener('click', function () {
        elVarsPanel.classList.add('d-none');
    });

    elVarsMissing.addEventListener('click', function (e) {
        var btn = e.target.closest('[data-quick-var]');
        if (!btn) { return; }
        elVarsPanel.classList.remove('d-none');
        elVarNewName.value = btn.dataset.quickVar;
        elVarNewVal.value = /^(testid|test_id|run_id|runid|test_run_id)$/i.test(btn.dataset.quickVar) ? '{run_id}' : '.*';
        elVarNewVal.focus();
        elVarNewVal.select();
    });

    elVarsGrid.addEventListener('click', function (e) {
        var item = e.target.closest('.cb-var-item');
        if (!item) { return; }
        if (e.target.closest('.cb-var-save')) {
            saveVariable(item.dataset.vname, item.querySelector('.cb-var-in').value);
        } else if (e.target.closest('.cb-var-del')) {
            var vname = item.dataset.vname, vid = item.dataset.vid;
            window.k6Confirm({
                title: 'Удалить переменную?',
                message: 'Переменная $' + vname + ' будет удалена.',
                confirmText: 'Удалить',
                variant: 'danger'
            }).then(function (ok) {
                if (!ok) { return; }
                api('/api/variables/' + vid + '/delete', 'POST').then(function (res) {
                    if (res.ok && res.data.variables) {
                        variables = res.data.variables;
                        renderVariables(); renderChartVars(); renderQueries(); renderList();
                        toast('Переменная $' + vname + ' удалена', 'info');
                    }
                });
            });
        }
    });

    elVarsGrid.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && e.target.classList.contains('cb-var-in')) {
            e.preventDefault();
            var item = e.target.closest('.cb-var-item');
            if (item) { saveVariable(item.dataset.vname, e.target.value); }
        }
    });

    document.getElementById('cb-var-add-form').addEventListener('submit', function (e) {
        e.preventDefault();
        saveVariable(elVarNewName.value, elVarNewVal.value, function () {
            elVarNewName.value = '';
            elVarNewVal.value = '';
        });
    });

    document.querySelectorAll('[data-var-val-preset]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            elVarNewVal.value = btn.dataset.varValPreset;
            elVarNewVal.focus();
        });
    });

    function saveVariable(name, value, onSuccess) {
        api('/api/variables/save', 'POST', { name: name, value: value }).then(function (res) {
            if (!res.ok || !res.data.ok) {
                toast((res.data && res.data.error) || 'Не удалось сохранить переменную', 'err');
                return;
            }
            variables = res.data.variables || variables;
            renderVariables();
            renderChartVars();
            renderQueries();
            renderList();
            toast('Переменная $' + res.data.name + ' сохранена', 'ok');
            if (onSuccess) { onSuccess(); }
        });
    }

    // ==================== Copy Charts from Another Script ====================
    var elCopyScriptBtn = document.getElementById('cb-copy-script-btn');
    var elCsModal = document.getElementById('cb-copy-script-modal');
    var elCsLoading = document.getElementById('cb-cs-loading');
    var elCsEmpty = document.getElementById('cb-cs-empty');
    var elCsBody = document.getElementById('cb-cs-body');
    var elCsErr = document.getElementById('cb-cs-err');
    var elCsScript = document.getElementById('cb-cs-script');
    var elCsFilter = document.getElementById('cb-cs-filter');
    var elCsAll = document.getElementById('cb-cs-all');
    var elCsCountLbl = document.getElementById('cb-cs-count-lbl');
    var elCsList = document.getElementById('cb-cs-list');
    var elCsVars = document.getElementById('cb-cs-vars');
    var elCsVarsLbl = document.getElementById('cb-cs-vars-lbl');
    var elCsOverwrite = document.getElementById('cb-cs-overwrite');
    var elCsSubmit = document.getElementById('cb-cs-submit');
    var otherScriptsData = [];
    var csSelectedIds = new Set();

    function showCsError(msg) {
        if (!elCsErr) { return; }
        if (!msg) {
            elCsErr.classList.add('d-none');
            elCsErr.textContent = '';
        } else {
            elCsErr.textContent = msg;
            elCsErr.classList.remove('d-none');
        }
    }

    function currentSourceScript() {
        if (!elCsScript || !otherScriptsData.length) { return null; }
        var sid = +elCsScript.value;
        return otherScriptsData.find(function (s) { return s.id === sid; }) || otherScriptsData[0];
    }

    function filteredSourceTemplates(src) {
        if (!src || !Array.isArray(src.templates)) { return []; }
        var q = (elCsFilter ? elCsFilter.value : '').trim().toLowerCase();
        if (!q) { return src.templates; }
        return src.templates.filter(function (t) {
            if ((t.name || '').toLowerCase().indexOf(q) >= 0) { return true; }
            if ((t.unit || '').toLowerCase().indexOf(q) >= 0) { return true; }
            return (t.queries || []).some(function (qr) {
                return (qr.promql || '').toLowerCase().indexOf(q) >= 0 || (qr.legend || '').toLowerCase().indexOf(q) >= 0;
            });
        });
    }

    function selectSourceScript(src) {
        csSelectedIds.clear();
        if (src && Array.isArray(src.templates)) {
            src.templates.forEach(function (t) { csSelectedIds.add(t.id); });
        }
        if (elCsFilter) { elCsFilter.value = ''; }
        if (elCsVarsLbl && src) {
            var vCnt = Array.isArray(src.variables) ? src.variables.length : 0;
            if (vCnt > 0) {
                var names = src.variables.slice(0, 5).map(function (v) { return '$' + v.name; }).join(', ') + (vCnt > 5 ? '…' : '');
                elCsVarsLbl.innerHTML = 'Скопировать также общие переменные скрипта-источника (<b>' + vCnt + '</b>: <code>' + esc(names) + '</code>)';
                elCsVars.disabled = false;
            } else {
                elCsVarsLbl.textContent = 'Скопировать также общие переменные скрипта-источника (в этом скрипте их нет)';
                elCsVars.disabled = true;
            }
        }
        renderCsTemplatesList();
    }

    function renderCsTemplatesList() {
        var src = currentSourceScript();
        if (!src || !elCsList) { return; }
        var vis = filteredSourceTemplates(src);
        var existingNames = new Set(templates.map(function (t) { return t.name; }));
        if (vis.length === 0) {
            elCsList.innerHTML = '<div class="text-muted small text-center py-3">По фильтру графиков не найдено</div>';
        } else {
            elCsList.innerHTML = vis.map(function (t, idx) {
                var checked = csSelectedIds.has(t.id);
                var qCount = (t.queries || []).length;
                var firstQ = qCount > 0 ? t.queries[0].promql : '';
                var varKeys = t.variables && typeof t.variables === 'object' ? Object.keys(t.variables) : [];
                var clash = existingNames.has(t.name);
                return '<div class="d-flex align-items-start gap-2 p-2 rounded border bg-body" data-cs-item="' + t.id + '">' +
                    '<input class="form-check-input mt-1" type="checkbox" data-cs-check="' + t.id + '"' + (checked ? ' checked' : '') + '>' +
                    '<div class="flex-grow-1 min-w-0" style="cursor:pointer" data-cs-toggle="' + t.id + '">' +
                        '<div class="d-flex align-items-center flex-wrap gap-1">' +
                            '<span class="fw-semibold small">' + esc(t.name) + '</span>' +
                            (t.unit ? '<span class="badge text-bg-light border">' + esc(t.unit) + '</span>' : '') +
                            '<span class="badge text-bg-light border">' + qCount + ' запр.</span>' +
                            (varKeys.length > 0 ? '<span class="badge text-bg-info bg-opacity-10 text-info border border-info-subtle" title="Локальные переменные графика">' +
                                esc(varKeys.map(function (k) { return '$' + k + '=' + t.variables[k]; }).join(', ')) + '</span>' : '') +
                            (clash ? '<span class="badge text-bg-warning bg-opacity-25 text-dark border" title="В текущем скрипте уже есть график с таким именем">есть в скрипте</span>' : '') +
                        '</div>' +
                        '<div class="text-muted font-monospace text-truncate mt-1" style="font-size:.75rem" title="' + esc(firstQ) + '">' + esc(firstQ) + '</div>' +
                    '</div>' +
                    '<button type="button" class="btn btn-sm btn-outline-secondary py-0 px-2 flex-shrink-0" data-cs-draft="' + t.id + '" title="Загрузить этот график в конструктор справа без сохранения (чтобы отредактировать перед созданием)">' +
                        '<i class="bi bi-pencil-square me-1"></i>В конструктор' +
                    '</button>' +
                '</div>';
            }).join('');
        }
        syncCsHeader(src, vis);
    }

    function syncCsHeader(src, vis) {
        var total = src && src.templates ? src.templates.length : 0;
        var selCount = csSelectedIds.size;
        if (elCsCountLbl) {
            elCsCountLbl.textContent = 'Выбрано графиков: ' + selCount + ' из ' + total;
        }
        if (elCsAll && vis) {
            if (vis.length > 0) {
                var allVis = vis.every(function (t) { return csSelectedIds.has(t.id); });
                var someVis = vis.some(function (t) { return csSelectedIds.has(t.id); });
                elCsAll.checked = allVis;
                elCsAll.indeterminate = !allVis && someVis;
                elCsAll.disabled = false;
            } else {
                elCsAll.checked = false;
                elCsAll.indeterminate = false;
                elCsAll.disabled = true;
            }
        }
        if (elCsSubmit) {
            elCsSubmit.disabled = selCount === 0;
            elCsSubmit.innerHTML = '<i class="bi bi-files me-1"></i>Скопировать выбранные (' + selCount + ')';
        }
    }

    function openCopyFromScriptModal() {
        if (!elCsModal) { return; }
        showCsError('');
        elCsLoading.classList.remove('d-none');
        elCsEmpty.classList.add('d-none');
        elCsBody.classList.add('d-none');
        elCsSubmit.disabled = true;
        bootstrap.Modal.getOrCreateInstance(elCsModal).show();

        api('/api/other-scripts', 'GET').then(function (res) {
            elCsLoading.classList.add('d-none');
            if (!res.ok || !res.data || !Array.isArray(res.data.scripts)) {
                showCsError((res.data && res.data.error) || 'Не удалось загрузить список скриптов');
                return;
            }
            otherScriptsData = res.data.scripts;
            if (otherScriptsData.length === 0) {
                elCsEmpty.classList.remove('d-none');
                return;
            }
            elCsScript.innerHTML = otherScriptsData.map(function (s) {
                return '<option value="' + s.id + '">' + esc(s.name) + ' (' + s.chartCount + ' граф.' +
                    (s.variableCount > 0 ? ', ' + s.variableCount + ' перем.' : '') + ')</option>';
            }).join('');
            elCsBody.classList.remove('d-none');
            selectSourceScript(otherScriptsData[0]);
        }).catch(function () {
            elCsLoading.classList.add('d-none');
            showCsError('Сетевая ошибка при загрузке скриптов');
        });
    }

    if (elCopyScriptBtn) {
        elCopyScriptBtn.addEventListener('click', openCopyFromScriptModal);
    }
    if (elCsScript) {
        elCsScript.addEventListener('change', function () {
            selectSourceScript(currentSourceScript());
        });
    }
    if (elCsFilter) {
        elCsFilter.addEventListener('input', renderCsTemplatesList);
    }
    if (elCsAll) {
        elCsAll.addEventListener('change', function () {
            var vis = filteredSourceTemplates(currentSourceScript());
            if (elCsAll.checked) {
                vis.forEach(function (t) { csSelectedIds.add(t.id); });
            } else {
                vis.forEach(function (t) { csSelectedIds.delete(t.id); });
            }
            renderCsTemplatesList();
        });
    }
    if (elCsList) {
        elCsList.addEventListener('change', function (e) {
            if (e.target.dataset.csCheck != null) {
                var tid = +e.target.dataset.csCheck;
                if (e.target.checked) { csSelectedIds.add(tid); } else { csSelectedIds.delete(tid); }
                syncCsHeader(currentSourceScript(), filteredSourceTemplates(currentSourceScript()));
            }
        });
        elCsList.addEventListener('click', function (e) {
            var draftBtn = e.target.closest('[data-cs-draft]');
            if (draftBtn) {
                var src = currentSourceScript();
                var tpl = src && src.templates ? src.templates.find(function (t) { return t.id === +draftBtn.dataset.csDraft; }) : null;
                if (!tpl) { return; }
                withDiscardCheck(function () {
                    bootstrap.Modal.getOrCreateInstance(elCsModal).hide();
                    loadNew({
                        name: tpl.name,
                        unit: tpl.unit || '',
                        queries: tpl.queries || [],
                        variables: tpl.variables || {}
                    });
                    toast('График «' + tpl.name + '» из скрипта «' + src.name + '» подставлен в конструктор — проверьте и сохраните', 'info');
                });
                return;
            }
            var toggleArea = e.target.closest('[data-cs-toggle]');
            if (toggleArea) {
                var id = +toggleArea.dataset.csToggle;
                if (csSelectedIds.has(id)) { csSelectedIds.delete(id); } else { csSelectedIds.add(id); }
                renderCsTemplatesList();
            }
        });
    }
    if (elCsSubmit) {
        elCsSubmit.addEventListener('click', function () {
            var src = currentSourceScript();
            var ids = Array.from(csSelectedIds);
            if (!src || ids.length === 0) { return; }
            showCsError('');
            elCsSubmit.disabled = true;
            api('/api/copy-from-script', 'POST', {
                sourceScriptId: src.id,
                templateIds: ids,
                copyVariables: elCsVars ? elCsVars.checked : true,
                overwriteExisting: elCsOverwrite ? elCsOverwrite.checked : false
            }).then(function (res) {
                elCsSubmit.disabled = false;
                if (!res.ok || !res.data || !res.data.ok) {
                    showCsError((res.data && res.data.error) || 'Не удалось скопировать графики');
                    return;
                }
                templates = res.data.templates || templates;
                if (res.data.variables) { variables = res.data.variables; }
                bootstrap.Modal.getOrCreateInstance(elCsModal).hide();
                renderVariables();
                renderList();
                var copied = res.data.copied;
                if (Array.isArray(copied) && copied.length > 0 && !isDirty()) {
                    loadTemplate(copied[0].id, false);
                }
                toast(res.data.message || ('Скопировано графиков: ' + ids.length), 'ok');
            }).catch(function () {
                elCsSubmit.disabled = false;
                showCsError('Сетевая ошибка при копировании графиков');
            });
        });
    }

    // ==================== Grafana Import Dropzone ====================
    var dz = document.getElementById('cb-grafana-dz');
    var fileIn = document.getElementById('cb-grafana-file');
    var dzLabel = document.getElementById('cb-grafana-dz-lbl');
    if (dz && fileIn) {
        dz.addEventListener('click', function (e) {
            if (e.target === fileIn || (e.target && e.target.closest && e.target.closest('input[type="file"]'))) { return; }
            fileIn.click();
        });
        dz.addEventListener('dragover', function (e) { e.preventDefault(); dz.classList.add('drag'); });
        dz.addEventListener('dragleave', function () { dz.classList.remove('drag'); });
        dz.addEventListener('drop', function (e) {
            e.preventDefault();
            dz.classList.remove('drag');
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                fileIn.files = e.dataTransfer.files;
                dzLabel.innerHTML = '<span class="picked"><i class="bi bi-file-earmark-check me-1"></i>' + esc(e.dataTransfer.files[0].name) + '</span>';
            }
        });
        fileIn.addEventListener('change', function () {
            if (fileIn.files && fileIn.files.length > 0) {
                dzLabel.innerHTML = '<span class="picked"><i class="bi bi-file-earmark-check me-1"></i>' + esc(fileIn.files[0].name) + '</span>';
            }
        });
    }

    // Auto-open Grafana modal if redirected back with error & grafanaJson
    if (root.dataset.openGrafana === 'true') {
        bootstrap.Modal.getOrCreateInstance(document.getElementById('cb-grafana-modal')).show();
    }

    // Warn before leaving page with unsaved changes
    window.addEventListener('beforeunload', function (e) {
        if (isDirty()) {
            e.preventDefault();
            e.returnValue = '';
        }
    });

    // ==================== Boot ====================
    renderPresetsMenu();
    renderVariables();
    if (templates.length > 0) {
        loadTemplate(templates[0].id, false);
    } else {
        loadNew();
    }
})();
