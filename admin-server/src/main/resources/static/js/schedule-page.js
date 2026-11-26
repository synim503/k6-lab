(function () {
    'use strict';

    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function pad(n) { return n < 10 ? '0' + n : '' + n; }
    function toLocalVal(d) {
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }
    function formatCountdown(ms) {
        if (ms <= 0) { return 'сейчас'; }
        var totalMin = Math.ceil(ms / 60000);
        if (totalMin < 60) { return 'через ' + totalMin + ' мин'; }
        var h = Math.floor(totalMin / 60), m = totalMin % 60;
        if (h < 24) { return 'через ' + h + ' ч' + (m ? ' ' + m + ' м' : ''); }
        var days = Math.floor(h / 24), remH = h % 24;
        return 'через ' + days + ' д' + (remH ? ' ' + remH + ' ч' : '');
    }

    // ==================== Local Times & Countdowns ====================
    function updateTimes() {
        var now = Date.now();
        document.querySelectorAll('time.local-time').forEach(function (t) {
            var iso = t.getAttribute('datetime');
            var d = new Date(iso);
            if (!isNaN(d)) {
                t.textContent = d.toLocaleString('ru-RU', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'});
                t.title = d.toLocaleString('ru-RU');
            }
        });
        document.querySelectorAll('[data-countdown]').forEach(function (el) {
            var d = new Date(el.dataset.countdown);
            if (!isNaN(d)) { el.textContent = formatCountdown(d.getTime() - now); }
        });
    }
    updateTimes();
    setInterval(updateTimes, 30000);

    // ==================== Table Filtering, Search, Bulk & Drag-and-Drop (re-bindable after AJAX swap) ====================
    var SCH_AJAX_SEL = '#sch-head,#sch-paused-wrap,#sch-kpi-row,#sch-queue-wrap,#guards';
    var activeFilter = 'all';
    var savedSearch = '';
    var dragRow = null;

    function getTbody() { return document.getElementById('sch-tbody'); }
    function getSearchEl() { return document.getElementById('sch-search'); }

    function visibleRowCheckboxes() {
        var tbody = getTbody();
        if (!tbody) { return []; }
        var out = [];
        tbody.querySelectorAll('tr.sch-row').forEach(function (tr) {
            if (tr.style.display !== 'none') {
                var cb = tr.querySelector('.sch-row-check');
                if (cb) { out.push(cb); }
            }
        });
        return out;
    }

    function allCheckedIds() {
        var tbody = getTbody();
        if (!tbody) { return []; }
        var ids = [];
        tbody.querySelectorAll('.sch-row-check:checked').forEach(function (cb) {
            ids.push(cb.value);
        });
        return ids;
    }

    function syncBulkBar() {
        var tbody = getTbody();
        if (!tbody) { return; }
        var selectAllCb = document.getElementById('sch-select-all');
        var bulkBar = document.getElementById('sch-bulk-bar');
        var bulkCountEl = document.getElementById('sch-bulk-count');
        var checked = allCheckedIds();
        var pendingChecked = 0;
        tbody.querySelectorAll('.sch-row-check:checked').forEach(function (cb) {
            var tr = cb.closest('tr.sch-row');
            if (tr && tr.dataset.pending === 'true') { pendingChecked++; }
        });
        if (bulkBar && bulkCountEl) {
            bulkBar.classList.toggle('d-none', checked.length === 0);
            bulkCountEl.textContent = String(checked.length);
        }
        var bulkCancelBtn = document.getElementById('sch-bulk-cancel-btn');
        if (bulkCancelBtn) {
            bulkCancelBtn.disabled = pendingChecked === 0;
            bulkCancelBtn.innerHTML = pendingChecked > 0
                ? '<i class="bi bi-x-lg me-1"></i>Отменить ожидающие (' + pendingChecked + ')'
                : '<i class="bi bi-x-lg me-1"></i>Отменить ожидающие';
        }
        document.querySelectorAll('.sch-bulk-form').forEach(function (form) {
            form.querySelectorAll('input[name="ids"]').forEach(function (n) { n.remove(); });
            checked.forEach(function (id) {
                var inp = document.createElement('input');
                inp.type = 'hidden';
                inp.name = 'ids';
                inp.value = id;
                form.appendChild(inp);
            });
        });
        if (selectAllCb) {
            var vis = visibleRowCheckboxes();
            if (vis.length === 0) {
                selectAllCb.checked = false;
                selectAllCb.indeterminate = false;
                selectAllCb.disabled = true;
            } else {
                var allOn = vis.every(function (c) { return c.checked; });
                var someOn = vis.some(function (c) { return c.checked; });
                selectAllCb.checked = allOn;
                selectAllCb.indeterminate = !allOn && someOn;
                selectAllCb.disabled = false;
            }
        }
    }

    function applyFilter() {
        var tbody = getTbody();
        if (!tbody) { return; }
        var searchEl = getSearchEl();
        savedSearch = searchEl ? searchEl.value : savedSearch;
        var q = savedSearch.trim().toLowerCase();
        var shown = 0;
        tbody.querySelectorAll('tr.sch-row').forEach(function (tr) {
            var st = tr.dataset.status || '';
            var matchSt = activeFilter === 'all'
                || (activeFilter === 'pending' && st === 'PENDING')
                || (activeFilter === 'started' && (st === 'STARTED' || st === 'STARTING'))
                || (activeFilter === 'other' && (st === 'FAILED' || st === 'CANCELLED'));
            var matchQ = !q || tr.textContent.toLowerCase().indexOf(q) >= 0;
            var ok = matchSt && matchQ;
            tr.style.display = ok ? '' : 'none';
            if (ok) { shown++; }
        });
        var emptyRow = document.getElementById('sch-filter-empty');
        if (emptyRow) { emptyRow.classList.toggle('d-none', shown > 0); }
        syncBulkBar();
    }

    function setActiveFilter(mode) {
        activeFilter = mode;
        document.querySelectorAll('[data-sch-filter]').forEach(function (b) {
            b.classList.toggle('active', b.dataset.schFilter === mode);
        });
        document.querySelectorAll('.sch-kpi[data-kpi-filter]').forEach(function (k) {
            k.classList.toggle('is-active', k.dataset.kpiFilter === mode);
        });
        applyFilter();
    }

    function onKpiActivate(el, ev) {
        if (ev.target.closest('a')) { return; }
        ev.stopPropagation();
        var target = el.dataset.kpiFilter;
        setActiveFilter(activeFilter === target ? 'all' : target);
        var card = document.getElementById('sch-table-card');
        if (card) { card.scrollIntoView({behavior: 'smooth', block: 'nearest'}); }
    }

    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfToken = csrfMeta ? csrfMeta.content : '';
    function currentCsrf() {
        var m = document.querySelector('meta[name="_csrf"]');
        return (m && m.content) || csrfToken;
    }

    function bindScheduleDom() {
        var searchEl = getSearchEl();
        if (searchEl && searchEl.dataset.schBound !== '1') {
            searchEl.dataset.schBound = '1';
            if (savedSearch) { searchEl.value = savedSearch; }
            searchEl.addEventListener('input', applyFilter);
        }
        document.querySelectorAll('[data-sch-filter]').forEach(function (btn) {
            if (btn.dataset.schBound === '1') { return; }
            btn.dataset.schBound = '1';
            btn.addEventListener('click', function () {
                setActiveFilter(btn.dataset.schFilter);
            });
        });
        document.querySelectorAll('[data-kpi-filter]').forEach(function (el) {
            if (el.dataset.schBound === '1') { return; }
            el.dataset.schBound = '1';
            el.addEventListener('click', function (ev) { onKpiActivate(el, ev); });
            el.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); onKpiActivate(el, ev); }
            });
        });

        var guardsDet = document.getElementById('sch-guards-details');
        if (guardsDet && guardsDet.dataset.schBound !== '1') {
            guardsDet.dataset.schBound = '1';
            if (guardsDet.dataset.forceOpen !== 'true') {
                try { guardsDet.open = localStorage.getItem('k6lab.sch.guardsOpen') === '1'; } catch (e) {}
            }
            guardsDet.addEventListener('toggle', function () {
                try { localStorage.setItem('k6lab.sch.guardsOpen', guardsDet.open ? '1' : '0'); } catch (e) {}
            });
        }

        var selectAllCb = document.getElementById('sch-select-all');
        if (selectAllCb && selectAllCb.dataset.schBound !== '1') {
            selectAllCb.dataset.schBound = '1';
            selectAllCb.addEventListener('change', function () {
                visibleRowCheckboxes().forEach(function (cb) { cb.checked = selectAllCb.checked; });
                syncBulkBar();
            });
        }

        var tbody = getTbody();
        var tableCard = document.getElementById('sch-table-card');
        if (tbody && tbody.dataset.schBound !== '1') {
            tbody.dataset.schBound = '1';
            tbody.addEventListener('change', function (ev) {
                if (ev.target.classList.contains('sch-row-check')) { syncBulkBar(); }
            });
            if (tableCard && tableCard.dataset.reorderUrl) {
                tbody.addEventListener('dragstart', function (ev) {
                    var tr = ev.target.closest('tr.sch-row[draggable="true"]');
                    if (!tr) { return; }
                    dragRow = tr;
                    tr.classList.add('opacity-50');
                    ev.dataTransfer.effectAllowed = 'move';
                    try { ev.dataTransfer.setData('text/plain', tr.dataset.scheduleId); } catch (e) {}
                });
                tbody.addEventListener('dragend', function () {
                    dragRow = null;
                    tbody.querySelectorAll('tr.sch-row').forEach(function (r) {
                        r.classList.remove('opacity-50', 'sch-drag-top', 'sch-drag-bottom');
                    });
                });
                tbody.addEventListener('dragover', function (ev) {
                    if (!dragRow) { return; }
                    var tr = ev.target.closest('tr.sch-row[draggable="true"]');
                    if (!tr || tr === dragRow) { return; }
                    ev.preventDefault();
                    ev.dataTransfer.dropEffect = 'move';
                    var rect = tr.getBoundingClientRect();
                    var topHalf = (ev.clientY - rect.top) < rect.height / 2;
                    tbody.querySelectorAll('tr.sch-row').forEach(function (r) {
                        if (r !== tr) { r.classList.remove('sch-drag-top', 'sch-drag-bottom'); }
                    });
                    tr.classList.toggle('sch-drag-top', topHalf);
                    tr.classList.toggle('sch-drag-bottom', !topHalf);
                });
                tbody.addEventListener('drop', function (ev) {
                    if (!dragRow) { return; }
                    var tr = ev.target.closest('tr.sch-row[draggable="true"]');
                    if (!tr || tr === dragRow) { return; }
                    ev.preventDefault();
                    var before = tr.classList.contains('sch-drag-top');
                    if (before) {
                        tbody.insertBefore(dragRow, tr);
                    } else {
                        tbody.insertBefore(dragRow, tr.nextSibling);
                    }
                    var orderedIds = [];
                    tbody.querySelectorAll('tr.sch-row[data-pending="true"]').forEach(function (r) {
                        orderedIds.push(Number(r.dataset.scheduleId));
                    });
                    var headers = {'Content-Type': 'application/json', 'Accept': 'application/json'};
                    var tok = currentCsrf();
                    if (tok) { headers['X-CSRF-TOKEN'] = tok; }
                    fetch(tableCard.dataset.reorderUrl, {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: headers,
                        body: JSON.stringify({ids: orderedIds})
                    }).then(function (r) {
                        if (!r.ok) { throw new Error('HTTP ' + r.status); }
                        if (window.k6Toast) { window.k6Toast('Порядок очереди обновлён', 'ok'); }
                        if (window.k6AjaxRefresh) {
                            return window.k6AjaxRefresh(SCH_AJAX_SEL).then(function () {
                                document.dispatchEvent(new CustomEvent('k6:timeline-reload'));
                            });
                        }
                    }).catch(function () {
                        if (window.k6Toast) { window.k6Toast('Не удалось сохранить новый порядок очереди', 'err'); }
                        if (window.k6AjaxRefresh) { window.k6AjaxRefresh(SCH_AJAX_SEL); }
                    });
                });
            }
        }

        var bulkClearBtn = document.getElementById('sch-bulk-clear');
        if (bulkClearBtn && bulkClearBtn.dataset.schBound !== '1') {
            bulkClearBtn.dataset.schBound = '1';
            bulkClearBtn.addEventListener('click', function () {
                var tb = getTbody();
                if (tb) { tb.querySelectorAll('.sch-row-check:checked').forEach(function (cb) { cb.checked = false; }); }
                syncBulkBar();
            });
        }

        updateTimes();
        setActiveFilter(activeFilter);
    }

    bindScheduleDom();
    document.addEventListener('k6:swapped', function () {
        if (!document.getElementById('sch-queue-wrap')) { return; }
        bindScheduleDom();
        document.dispatchEvent(new Event('k6:timeline-reload'));
    });

    // ==================== Dropzones for Import Modals ====================
    function wireDropzone(dzId, fileId, lblId, modalId) {
        var dz = document.getElementById(dzId), fi = document.getElementById(fileId), lb = document.getElementById(lblId);
        if (dz && fi && lb) {
            dz.addEventListener('click', function (e) {
                if (e.target === fi || (e.target && e.target.closest && e.target.closest('input[type="file"]'))) { return; }
                fi.click();
            });
            dz.addEventListener('dragover', function (e) { e.preventDefault(); dz.classList.add('drag'); });
            dz.addEventListener('dragleave', function () { dz.classList.remove('drag'); });
            dz.addEventListener('drop', function (e) {
                e.preventDefault();
                dz.classList.remove('drag');
                if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                    fi.files = e.dataTransfer.files;
                    lb.innerHTML = '<span class="picked"><i class="bi bi-file-earmark-check me-1"></i>' + esc(e.dataTransfer.files[0].name) + '</span>';
                }
            });
            fi.addEventListener('change', function () {
                if (fi.files && fi.files.length > 0) {
                    lb.innerHTML = '<span class="picked"><i class="bi bi-file-earmark-check me-1"></i>' + esc(fi.files[0].name) + '</span>';
                }
            });
        }
        var m = document.getElementById(modalId);
        if (m && m.dataset.autoOpen === 'true') {
            bootstrap.Modal.getOrCreateInstance(m).show();
        }
    }
    wireDropzone('sch-imp-tests-dz', 'sch-imp-tests-file', 'sch-imp-tests-lbl', 'sch-imp-tests-modal');
    wireDropzone('sch-imp-prof-dz', 'sch-imp-prof-file', 'sch-imp-prof-lbl', 'sch-imp-prof-modal');

    // ==================== Quick Schedule Modal ====================
    var qModalEl = document.getElementById('sch-quick-modal');
    var qForm = document.getElementById('sch-quick-form');
    var qScript = document.getElementById('qm-script');
    var qProfile = document.getElementById('qm-profile');
    var qAt = document.getElementById('qm-at');
    var qIso = document.getElementById('qm-iso');
    var qPos = document.getElementById('qm-pos');
    var qCount = document.getElementById('qm-count');
    var qMeta = document.getElementById('qm-meta');
    var qErr = document.getElementById('qm-err');
    var qOpsBox = document.getElementById('qm-ops');
    var qEnv = document.getElementById('qm-env');
    var qSubmit = document.getElementById('qm-submit');
    var currentMeta = null;

    function syncQuickIso() {
        if (!qAt || !qIso) { return; }
        var d = new Date(qAt.value);
        qIso.value = isNaN(d) ? '' : d.toISOString();
    }

    function renderQuickOps(ops) {
        if (!qOpsBox) { return; }
        if (!ops || !ops.length) {
            qOpsBox.innerHTML = '<div class="text-muted small">В выбранной конфигурации нет операций.</div>';
            return;
        }
        qOpsBox.innerHTML = ops.map(function (n) {
            return '<div class="col-md-6"><div class="input-group input-group-sm">' +
                '<span class="input-group-text flex-grow-1 text-start">' + esc(n) + '</span>' +
                '<input class="form-control" style="max-width:84px" type="number" min="1" max="10000" step="any" value="100" name="pct_' + esc(n) + '">' +
                '<span class="input-group-text">%</span></div></div>';
        }).join('');
    }

    function applyQuickProfile() {
        if (!currentMeta) { return; }
        var pid = qProfile.value;
        var ops = currentMeta.ops || [];
        var dur = currentMeta.durationSec || 0;
        var envText = currentMeta.envText || '';
        var errs = Array.isArray(currentMeta.errors) ? currentMeta.errors : [];
        if (pid) {
            var p = (currentMeta.profiles || []).find(function (x) { return String(x.id) === String(pid); });
            if (p) {
                ops = p.ops || [];
                dur = p.durationSec || 0;
                envText = p.envText || '';
                errs = Array.isArray(p.errors) ? p.errors : [];
            }
        }
        renderQuickOps(ops);
        if (qEnv && !document.getElementById('qm-env-custom').checked) { qEnv.value = envText; }
        if (qMeta) {
            qMeta.innerHTML = '<span class="badge text-bg-light border"><i class="bi bi-clock me-1"></i>' +
                (dur > 0 ? (Math.round(dur / 6.0) / 10) + ' мин' : '—') + '</span>' +
                '<span class="badge text-bg-light border"><i class="bi bi-layers me-1"></i>' + ops.length + ' оп.</span>';
        }
        if (qErr) {
            var hasErr = errs.length > 0;
            qErr.textContent = hasErr ? ('Ошибки нагрузки: ' + errs.join('; ')) : '';
            qErr.classList.toggle('d-none', !hasErr);
        }
        if (qSubmit) { qSubmit.disabled = errs.length > 0; }
    }

    var pendingRepeatOverrides = null;

    var qPosNote = document.getElementById('qm-pos-note');
    var qDetHosts = document.getElementById('qm-det-hosts');
    var qDetOps = document.getElementById('qm-det-ops');
    var qDetEnv = document.getElementById('qm-det-env');
    var qHostsBadge = document.getElementById('qm-hosts-badge');
    var qOpsBadge = document.getElementById('qm-ops-badge');
    var qEnvBadge = document.getElementById('qm-env-badge');

    function syncQuickPosNote() {
        if (!qPosNote) { return; }
        var p = qPos ? qPos.value : '';
        if (p === 'front') { qPosNote.textContent = 'Позиция: в начало очереди — сразу после текущего теста.'; qPosNote.classList.remove('d-none'); }
        else if (p === 'back') { qPosNote.textContent = 'Позиция: в конец очереди — после всех ожидающих тестов.'; qPosNote.classList.remove('d-none'); }
        else if (!qAt || !qAt.value) { qPosNote.textContent = 'Ближайшее свободное окно подберётся автоматически.'; qPosNote.classList.remove('d-none'); }
        else { qPosNote.textContent = ''; qPosNote.classList.add('d-none'); }
    }
    function syncQuickHosts() {
        if (!qModalEl || !qCount) { return; }
        var n = qModalEl.querySelectorAll('.qm-host-pick:checked').length;
        if (n > 0) { qCount.value = n; }
        qCount.readOnly = n > 0;
        if (qHostsBadge) { qHostsBadge.textContent = 'выбрано: ' + n; qHostsBadge.classList.toggle('d-none', n === 0); }
    }
    function syncQuickOpsBadge() {
        if (!qOpsBadge || !qOpsBox) { return; }
        var changed = 0;
        qOpsBox.querySelectorAll('input[name^="pct_"]').forEach(function (i) {
            if (parseFloat(i.value) !== 100) { changed++; }
        });
        qOpsBadge.textContent = changed > 0 ? ('изменено: ' + changed) : '';
        qOpsBadge.classList.toggle('d-none', changed === 0);
    }
    function syncQuickEnvBadge() {
        var customOn = !!(document.getElementById('qm-env-custom') && document.getElementById('qm-env-custom').checked);
        if (qEnvBadge) { qEnvBadge.classList.toggle('d-none', !customOn); }
    }

    function applyRepeatOverrides() {
        if (!pendingRepeatOverrides) { return; }
        var ov = pendingRepeatOverrides;
        pendingRepeatOverrides = null;
        var hasPctOv = false;
        if (ov.pct && qOpsBox) {
            var inputs = Array.prototype.slice.call(qOpsBox.querySelectorAll('input[name^="pct_"]'));
            Object.keys(ov.pct).forEach(function (op) {
                var targetName = 'pct_' + op;
                var inp = inputs.find(function (el) { return el.name === targetName; });
                if (inp) {
                    inp.value = ov.pct[op];
                    if (parseFloat(ov.pct[op]) !== 100) { hasPctOv = true; }
                }
            });
        }
        if (qDetOps) { qDetOps.open = hasPctOv; }
        syncQuickOpsBadge();
        var envScriptRadio = document.getElementById('qm-env-script');
        var envCustomRadio = document.getElementById('qm-env-custom');
        if (ov.envCustom && envCustomRadio && qEnv) {
            envCustomRadio.checked = true;
            qEnv.disabled = false;
            qEnv.value = ov.envText || '';
            if (qDetEnv) { qDetEnv.open = true; }
        } else if (envScriptRadio && qEnv) {
            envScriptRadio.checked = true;
            qEnv.disabled = true;
            if (qDetEnv) { qDetEnv.open = false; }
        }
        syncQuickEnvBadge();
    }

    var metaSeq = 0;

    function loadScriptMeta(scriptId, wantedProfileId, wantedCount) {
        if (!scriptId) { return; }
        var mySeq = ++metaSeq;
        qForm.action = qForm.dataset.actionTpl.replace('__ID__', String(scriptId));
        if (qMeta) { qMeta.innerHTML = '<span class="spinner-border spinner-border-sm text-primary"></span>'; }
        if (qErr) { qErr.classList.add('d-none'); qErr.textContent = ''; }
        qSubmit.disabled = true;

        fetch(qForm.dataset.metaBase + '/' + encodeURIComponent(scriptId), {credentials: 'same-origin'})
            .then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); })
            .then(function (d) {
                if (mySeq !== metaSeq) { return; }
                currentMeta = d;
                qCount.value = wantedCount || d.defaultCount || 1;
                var baseErrs = Array.isArray(d.errors) && d.errors.length > 0;
                var opts = '<option value="">' + (baseErrs ? 'Нагрузка скрипта (не описана)' : 'Нагрузка скрипта (как сохранена)') + '</option>';
                (d.profiles || []).forEach(function (p) {
                    opts += '<option value="' + p.id + '">' + esc(p.name) + '</option>';
                });
                qProfile.innerHTML = opts;
                if (wantedProfileId) {
                    qProfile.value = String(wantedProfileId);
                } else if (baseErrs && Array.isArray(d.profiles)) {
                    var valid = d.profiles.find(function (p) { return !Array.isArray(p.errors) || p.errors.length === 0; });
                    if (valid) { qProfile.value = String(valid.id); }
                }
                applyQuickProfile();
                applyRepeatOverrides();
            })
            .catch(function () {
                if (mySeq !== metaSeq) { return; }
                if (qErr) { qErr.textContent = 'Не удалось загрузить параметры скрипта.'; qErr.classList.remove('d-none'); }
                if (qMeta) { qMeta.innerHTML = ''; }
            });
    }

    if (qModalEl && qForm && qScript) {
        qScript.addEventListener('change', function () { loadScriptMeta(qScript.value, null, null); });
        qProfile.addEventListener('change', applyQuickProfile);
        qAt.addEventListener('input', function () {
            if (qPos) { qPos.value = ''; }
            qModalEl.querySelectorAll('[data-qm-time]').forEach(function (b) { b.classList.remove('active'); });
            syncQuickIso();
            syncQuickPosNote();
        });

        qModalEl.querySelectorAll('[data-qm-time]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var p = btn.dataset.qmTime, d = new Date();
                qModalEl.querySelectorAll('[data-qm-time]').forEach(function (b) { b.classList.toggle('active', b === btn); });
                if (p === 'front' || p === 'back') {
                    qAt.value = '';
                    qIso.value = '';
                    if (qPos) { qPos.value = p; }
                    syncQuickPosNote();
                    return;
                }
                if (qPos) { qPos.value = ''; }
                if (p === 'auto') { qAt.value = ''; qIso.value = ''; syncQuickPosNote(); return; }
                if (p === '+15m') { d.setMinutes(d.getMinutes() + 15); }
                else if (p === '+1h') { d.setHours(d.getHours() + 1); }
                else if (p === '22:00') { d.setHours(22, 0, 0, 0); if (d <= new Date()) { d.setDate(d.getDate() + 1); } }
                else if (p === '02:00') { d.setHours(2, 0, 0, 0); if (d <= new Date()) { d.setDate(d.getDate() + 1); } }
                qAt.value = toLocalVal(d);
                syncQuickIso();
                syncQuickPosNote();
            });
        });

        qModalEl.querySelectorAll('[data-qm-pct]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                qOpsBox.querySelectorAll('input[name^="pct_"]').forEach(function (i) { i.value = btn.dataset.qmPct; });
                syncQuickOpsBadge();
            });
        });
        if (qOpsBox) { qOpsBox.addEventListener('input', syncQuickOpsBadge); }
        qModalEl.querySelectorAll('.qm-host-pick').forEach(function (cb) {
            cb.addEventListener('change', syncQuickHosts);
        });

        qModalEl.querySelectorAll('input[name="envMode"]').forEach(function (r) {
            r.addEventListener('change', function () {
                qEnv.disabled = !document.getElementById('qm-env-custom').checked;
                syncQuickEnvBadge();
            });
        });

        qModalEl.addEventListener('show.bs.modal', function (ev) {
            var trigger = ev.relatedTarget;
            var presetScript = trigger && trigger.dataset.repeatScript ? trigger.dataset.repeatScript : qScript.value;
            var presetProf = trigger && trigger.dataset.repeatProfile ? trigger.dataset.repeatProfile : null;
            var presetCnt = trigger && trigger.dataset.repeatCount ? trigger.dataset.repeatCount : null;
            var presetGens = trigger && trigger.dataset.repeatGens ? trigger.dataset.repeatGens.split(',').filter(Boolean) : [];
            var presetShift = trigger && trigger.dataset.repeatShift !== undefined ? trigger.dataset.repeatShift === 'true' : true;
            var presetEnvCustom = trigger && trigger.dataset.repeatEnvCustom === 'true';
            var presetEnvText = trigger && trigger.dataset.repeatEnv ? trigger.dataset.repeatEnv : '';
            var presetPct = null;
            if (trigger && trigger.dataset.repeatPct) {
                try { presetPct = JSON.parse(trigger.dataset.repeatPct); } catch (e) { presetPct = null; }
            }
            pendingRepeatOverrides = {
                envCustom: presetEnvCustom,
                envText: presetEnvText,
                pct: presetPct
            };
            var shiftEl = document.getElementById('qm-shift');
            if (shiftEl) { shiftEl.checked = presetShift; }
            qModalEl.querySelectorAll('input[name="generatorId"]').forEach(function (cb) {
                cb.checked = presetGens.indexOf(String(cb.value)) >= 0;
            });
            if (qDetHosts) { qDetHosts.open = presetGens.length > 0; }
            syncQuickHosts();
            var envScriptReset = document.getElementById('qm-env-script');
            if (envScriptReset && qEnv) {
                envScriptReset.checked = true;
                qEnv.disabled = true;
            }
            syncQuickEnvBadge();
            if (presetScript) { qScript.value = presetScript; }
            qAt.value = '';
            qIso.value = '';
            if (qPos) { qPos.value = ''; }
            qModalEl.querySelectorAll('[data-qm-time]').forEach(function (b) { b.classList.toggle('active', b.dataset.qmTime === 'auto'); });
            syncQuickPosNote();
            if (qScript.value) { loadScriptMeta(qScript.value, presetProf, presetCnt); }
        });
    }

    // Опция последовательного запуска в модалке «Импорт профилей»
    var impSeqCb = document.getElementById('sch-imp-seq');
    var impSeqOpts = document.getElementById('sch-imp-seq-opts');
    var impSeqBtn = document.getElementById('sch-imp-submit');
    var impAt = document.getElementById('sch-imp-at');
    var impIso = document.getElementById('sch-imp-iso');
    if (impSeqCb && impSeqOpts) {
        impSeqCb.addEventListener('change', function () {
            var on = impSeqCb.checked;
            impSeqOpts.classList.toggle('d-none', !on);
            if (impSeqBtn) {
                impSeqBtn.innerHTML = on
                    ? '<i class="bi bi-calendar-plus me-1"></i>Импортировать и запланировать'
                    : '<i class="bi bi-upload me-1"></i>Импортировать профили';
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
            if (!src) { return; }
            if (window.k6Copy) { window.k6Copy(src.textContent.trim(), btn); }
        });
    });

    // ==================== Schedule Guards (Prometheus -> SSH) ====================
    var guardModalEl = document.getElementById('sch-guard-modal');
    if (guardModalEl) {
        function readGuardsList() {
            var el = document.getElementById('sch-guards-json');
            try { return JSON.parse((el && el.textContent) || '[]'); } catch (e) { return []; }
        }
        var GD_PRESETS = {
            diskPct: {
                name: 'Очистка диска / (> 80%)',
                promql: '100 - (node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes{mountpoint="/",fstype!~"tmpfs|overlay"} * 100)',
                cmp: 'GT', threshold: '80', unit: '%'
            },
            diskUsedGb: {
                name: 'Очистка диска / (> 50 ГБ)',
                promql: '(node_filesystem_size_bytes{mountpoint="/",fstype!~"tmpfs|overlay"} - node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs|overlay"}) / 1073741824',
                cmp: 'GT', threshold: '50', unit: 'ГБ'
            },
            diskFreeGb: {
                name: 'Мало места на / (< 10 ГБ)',
                promql: 'node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs|overlay"} / 1073741824',
                cmp: 'LT', threshold: '10', unit: 'ГБ'
            },
            ramPct: {
                name: 'Очистка памяти RAM (> 85%)',
                promql: '(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100',
                cmp: 'GT', threshold: '85', unit: '%'
            }
        };

        var gdTitle = document.getElementById('gd-modal-title');
        var gdId = document.getElementById('gd-id');
        var gdName = document.getElementById('gd-name');
        var gdWhen = document.getElementById('gd-when');
        var gdPromql = document.getElementById('gd-promql');
        var gdCmp = document.getElementById('gd-cmp');
        var gdThr = document.getElementById('gd-thr');
        var gdUnit = document.getElementById('gd-unit');
        var gdHost = document.getElementById('gd-host');
        var gdPort = document.getElementById('gd-port');
        var gdCred = document.getElementById('gd-cred');
        var gdScript = document.getElementById('gd-script');
        var gdTimeout = document.getElementById('gd-timeout');
        var gdCooldown = document.getElementById('gd-cooldown');
        var gdSudo = document.getElementById('gd-sudo');
        var gdEnabled = document.getElementById('gd-enabled');
        var gdBlock = document.getElementById('gd-block');
        var gdPromqlBtn = document.getElementById('gd-test-promql-btn');
        var gdPromqlRes = document.getElementById('gd-promql-result');
        var gdSshBtn = document.getElementById('gd-test-ssh-btn');
        var gdSshRes = document.getElementById('gd-ssh-result');
        var gdAddAllGens = document.getElementById('gd-add-all-gens');

        var sshAbortCtrl = null;
        var sshTimerId = null;
        var sshRunSeq = 0;

        function cancelInFlightSsh() {
            if (sshTimerId) { clearInterval(sshTimerId); sshTimerId = null; }
            if (sshAbortCtrl) {
                try { sshAbortCtrl.abort(); } catch (e) {}
                sshAbortCtrl = null;
            }
        }

        function resetTestResults() {
            cancelInFlightSsh();
            if (gdPromqlRes) { gdPromqlRes.classList.add('d-none'); gdPromqlRes.innerHTML = ''; }
            if (gdSshRes) { gdSshRes.classList.add('d-none'); gdSshRes.innerHTML = ''; }
        }

        function addHostsToField(hosts) {
            if (!gdHost) { return; }
            var existing = gdHost.value.split(/[\r\n,;\s]+/).map(function (s) { return s.trim(); }).filter(Boolean);
            var set = {};
            existing.forEach(function (h) { set[h] = true; });
            hosts.forEach(function (h) {
                var clean = String(h || '').trim();
                if (clean && !set[clean]) {
                    existing.push(clean);
                    set[clean] = true;
                }
            });
            gdHost.value = existing.join('\n');
            gdHost.focus();
        }

        guardModalEl.querySelectorAll('.gd-host-chip').forEach(function (btn) {
            btn.addEventListener('click', function () { addHostsToField([btn.dataset.host]); });
        });
        if (gdAddAllGens) {
            gdAddAllGens.addEventListener('click', function () {
                var all = [];
                guardModalEl.querySelectorAll('.gd-host-chip').forEach(function (b) { if (b.dataset.host) { all.push(b.dataset.host); } });
                addHostsToField(all);
            });
        }

        function postFormJson(url, params, signal) {
            var body = new URLSearchParams();
            Object.keys(params).forEach(function (k) {
                if (params[k] !== undefined && params[k] !== null) { body.append(k, String(params[k])); }
            });
            var headers = {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'Accept': 'application/json'
            };
            var tok = currentCsrf();
            if (tok) { headers['X-CSRF-TOKEN'] = tok; }
            var opts = {method: 'POST', credentials: 'same-origin', headers: headers, body: body.toString()};
            if (signal) { opts.signal = signal; }
            return fetch(url, opts).then(function (r) {
                if (!r.ok) {
                    if (r.status === 504 || r.status === 502) {
                        throw new Error('HTTP ' + r.status + ' (прокси оборвал ожидание ответа — уменьшите таймаут скрипта или проверьте, не зависает ли команда на сервере)');
                    }
                    throw new Error('HTTP ' + r.status);
                }
                var ct = (r.headers.get('content-type') || '').toLowerCase();
                if (ct.indexOf('application/json') < 0) {
                    throw new Error('Сессия истекла или сервер вернул не JSON — обновите страницу');
                }
                return r.json();
            });
        }

        if (gdPromqlBtn && gdPromqlRes) {
            gdPromqlBtn.addEventListener('click', function () {
                var url = gdPromqlBtn.dataset.url;
                var prev = gdPromqlBtn.innerHTML;
                gdPromqlBtn.disabled = true;
                gdPromqlBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Проверяем…';
                gdPromqlRes.classList.remove('d-none');
                gdPromqlRes.innerHTML = '<div class="small text-muted">Выполняется запрос к Prometheus…</div>';
                postFormJson(url, {
                    promql: gdPromql ? gdPromql.value : '',
                    cmp: gdCmp ? gdCmp.value : 'GT',
                    threshold: gdThr ? gdThr.value : '0',
                    unit: gdUnit ? gdUnit.value : ''
                }).then(function (data) {
                    if (!data.ok) {
                        gdPromqlRes.innerHTML = '<div class="alert alert-danger py-2 px-3 mb-0 small"><i class="bi bi-exclamation-triangle me-1"></i>' + esc(data.error || 'Ошибка проверки') + '</div>';
                        return;
                    }
                    var cls = data.triggered ? 'alert-warning' : 'alert-success';
                    var icon = data.triggered ? 'bi-exclamation-circle-fill' : 'bi-check-circle-fill';
                    var html = '<div class="alert ' + cls + ' py-2 px-3 mb-0 small">' +
                        '<div class="fw-semibold"><i class="bi ' + icon + ' me-1"></i>' + esc(data.message || '') + '</div>';
                    if (Array.isArray(data.samples) && data.samples.length > 0) {
                        html += '<div class="mt-2 pt-1 border-top"><div class="text-muted mb-1" style="font-size:.74rem">Серии Prometheus (показано ' + data.samples.length + '):</div>' +
                            '<div class="d-flex flex-column gap-1 font-monospace" style="font-size:.75rem;max-height:140px;overflow:auto">';
                        data.samples.forEach(function (sp) {
                            var badge = sp.triggered
                                ? '<span class="badge text-bg-warning ms-1">сработает</span>'
                                : '<span class="badge text-bg-light border ms-1">норма</span>';
                            html += '<div><b>' + esc(sp.formattedValue) + '</b> ' + (sp.series ? '<span class="text-muted">[' + esc(sp.series) + ']</span>' : '') + badge + '</div>';
                        });
                        html += '</div></div>';
                    }
                    html += '</div>';
                    gdPromqlRes.innerHTML = html;
                }).catch(function (err) {
                    gdPromqlRes.innerHTML = '<div class="alert alert-danger py-2 px-3 mb-0 small">Ошибка сети: ' + esc(err.message) + '</div>';
                }).finally(function () {
                    gdPromqlBtn.disabled = false;
                    gdPromqlBtn.innerHTML = prev;
                });
            });
        }

        var gdSshBtnDefaultHtml = gdSshBtn ? gdSshBtn.innerHTML : '';

        function runSshTest() {
            if (!gdSshBtn || !gdSshRes) { return; }
            cancelInFlightSsh();
            var seq = ++sshRunSeq;
            var url = gdSshBtn.dataset.url;
            var confirmUrl = gdSshBtn.dataset.confirmUrl;
            var timeoutSec = Math.max(10, Math.min(1800, parseInt(gdTimeout ? gdTimeout.value : '120', 10) || 120));
            var hostCount = (gdHost ? gdHost.value : '').split(/[\r\n,;\s]+/).filter(Boolean).length || 1;
            var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
            sshAbortCtrl = ctrl;
            var abortedByUser = false;
            var abortedByTimeout = false;
            var startedAt = Date.now();

            gdSshBtn.disabled = true;
            gdSshBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Выполняем SSH… <span id="gd-ssh-sec">0</span> с';
            gdSshRes.classList.remove('d-none');
            gdSshRes.innerHTML = '<div class="d-flex flex-wrap align-items-center gap-2 small text-muted border rounded px-2 py-2 bg-light-subtle">' +
                '<span class="spinner-border spinner-border-sm text-warning"></span>' +
                '<span>Выполняем SSH-скрипт (серверов: ' + hostCount + ', таймаут ' + timeoutSec + ' с)… прошло <b id="gd-ssh-sec-box">0</b> с</span>' +
                '<button type="button" class="btn btn-sm btn-outline-danger py-0 px-2 ms-auto" id="gd-ssh-cancel-btn">Отменить</button>' +
                '</div>';

            var cancelBtn = document.getElementById('gd-ssh-cancel-btn');
            if (cancelBtn) {
                cancelBtn.addEventListener('click', function () {
                    abortedByUser = true;
                    cancelInFlightSsh();
                });
            }

            var clientDeadlineMs = (timeoutSec + 30) * 1000;
            sshTimerId = setInterval(function () {
                var elapsedMs = Date.now() - startedAt;
                var sec = Math.floor(elapsedMs / 1000);
                var s1 = document.getElementById('gd-ssh-sec');
                var s2 = document.getElementById('gd-ssh-sec-box');
                if (s1) { s1.textContent = String(sec); }
                if (s2) { s2.textContent = String(sec); }
                if (ctrl && elapsedMs >= clientDeadlineMs) {
                    abortedByTimeout = true;
                    cancelInFlightSsh();
                }
            }, 1000);

            postFormJson(url, {
                sshHost: gdHost ? gdHost.value : '',
                sshPort: gdPort ? gdPort.value : '22',
                sshCredentialId: gdCred ? gdCred.value : '',
                useSudo: gdSudo && gdSudo.checked ? 'true' : 'false',
                script: gdScript ? gdScript.value : '',
                timeoutSec: String(timeoutSec)
            }, ctrl ? ctrl.signal : null).then(function (data) {
                if (seq !== sshRunSeq) { return; }
                var cls = data.ok ? 'alert-success' : 'alert-danger';
                var icon = data.ok ? 'bi-check-circle-fill' : 'bi-x-octagon-fill';
                var html = '<div class="alert ' + cls + ' py-2 px-3 mb-2 small">' +
                    '<i class="bi ' + icon + ' me-1"></i><b>' + esc(data.message || data.error || 'Готово') + '</b></div>';

                if (Array.isArray(data.hosts) && data.hosts.length > 0) {
                    html += '<div class="d-flex flex-column gap-2">';
                    data.hosts.forEach(function (h, idx) {
                        var hBadge = h.ok
                            ? '<span class="badge text-bg-success">OK (exit=0)</span>'
                            : (h.exitCode != null ? '<span class="badge text-bg-danger">exit=' + esc(h.exitCode) + '</span>' : '<span class="badge text-bg-danger">ошибка</span>');
                        html += '<div class="border rounded p-2 bg-light-subtle small">' +
                            '<div class="d-flex flex-wrap align-items-center gap-2">' +
                                '<code class="fw-semibold">' + esc(h.label || h.host) + '</code>' +
                                hBadge +
                                '<span class="text-muted ms-auto">' + esc(h.durationMs) + ' мс</span>' +
                            '</div>';
                        if (h.hostKey) {
                            html += '<div class="mt-2 p-2 rounded border border-warning bg-warning-subtle d-flex flex-wrap align-items-center gap-2">' +
                                '<span class="me-auto"><b>Ключ хоста не подтверждён:</b> <code>' + esc(h.hostKey.keyType) + ' ' + esc(h.hostKey.fingerprint) + '</code></span>' +
                                '<button type="button" class="btn btn-sm btn-warning py-0 px-2 fw-semibold gd-confirm-hk" data-hk-idx="' + idx + '">' +
                                    '<i class="bi bi-key me-1"></i>Подтвердить ключ' +
                                '</button></div>';
                        } else if (h.error && h.exitCode == null) {
                            html += '<div class="text-danger mt-1">' + esc(h.error) + '</div>';
                        }
                        var outTxt = '';
                        if (h.stdout) { outTxt += (h.stderr ? '--- stdout ---\n' : '') + h.stdout; }
                        if (h.stderr) { outTxt += (outTxt ? '\n' : '') + '--- stderr ---\n' + h.stderr; }
                        if (outTxt) {
                            html += '<pre class="mb-0 mt-2 p-2 rounded bg-dark text-light font-monospace" style="font-size:.73rem;max-height:160px;overflow:auto">' + esc(outTxt) + '</pre>';
                        }
                        html += '</div>';
                    });
                    html += '</div>';
                }
                gdSshRes.innerHTML = html;

                // 1-click подтверждение ключа хоста прямо внутри модалки без потери формы
                gdSshRes.querySelectorAll('.gd-confirm-hk').forEach(function (btn) {
                    btn.addEventListener('click', function () {
                        var h = data.hosts[Number(btn.dataset.hkIdx)];
                        if (!h || !h.hostKey) { return; }
                        btn.disabled = true;
                        btn.textContent = 'Сохраняем…';
                        postFormJson(confirmUrl, h.hostKey).then(function (r) {
                            if (r.ok) {
                                btn.className = 'btn btn-sm btn-success py-0 px-2';
                                btn.innerHTML = '<i class="bi bi-check2 me-1"></i>Ключ подтверждён — повторите прогон';
                            } else {
                                btn.disabled = false;
                                btn.textContent = 'Ошибка: ' + (r.error || '');
                            }
                        }).catch(function () {
                            btn.disabled = false;
                            btn.textContent = 'Повторить подтверждение';
                        });
                    });
                });
            }).catch(function (err) {
                if (seq !== sshRunSeq) { return; }
                if (abortedByUser) {
                    gdSshRes.innerHTML = '<div class="alert alert-secondary py-2 px-3 mb-0 small">' +
                        '<i class="bi bi-slash-circle me-1"></i>Ожидание отменено в браузере. Если команда ещё идёт на сервере, она завершится по таймауту (' + timeoutSec + ' с).</div>';
                } else if (abortedByTimeout) {
                    gdSshRes.innerHTML = '<div class="alert alert-danger py-2 px-3 mb-0 small">' +
                        '<i class="bi bi-hourglass-bottom me-1"></i>Превышено время ожидания ответа от сервера (' + Math.round(clientDeadlineMs / 1000) + ' с). Уменьшите таймаут скрипта и проверьте, не зависает ли команда.</div>';
                } else {
                    gdSshRes.innerHTML = '<div class="alert alert-danger py-2 px-3 mb-0 small">Ошибка сети: ' + esc(err && err.message ? err.message : String(err)) + '</div>';
                }
            }).finally(function () {
                if (seq !== sshRunSeq) { return; }
                if (sshTimerId) { clearInterval(sshTimerId); sshTimerId = null; }
                sshAbortCtrl = null;
                gdSshBtn.disabled = false;
                gdSshBtn.innerHTML = gdSshBtnDefaultHtml;
            });
        }

        if (gdSshBtn && gdSshRes) {
            gdSshBtn.addEventListener('click', runSshTest);
        }
        guardModalEl.addEventListener('hide.bs.modal', function () {
            sshRunSeq++;
            cancelInFlightSsh();
            if (gdSshBtn) {
                gdSshBtn.disabled = false;
                gdSshBtn.innerHTML = gdSshBtnDefaultHtml;
            }
        });

        function fillGuardForm(g) {
            resetTestResults();
            if (!g) {
                if (gdTitle) { gdTitle.innerHTML = '<i class="bi bi-shield-check me-2 text-primary"></i>Новая автопроверка (Prometheus → SSH)'; }
                gdId.value = '';
                gdName.value = '';
                gdWhen.value = 'BOTH';
                gdPromql.value = '';
                gdCmp.value = 'GT';
                gdThr.value = '80';
                gdUnit.value = '%';
                gdHost.value = '';
                gdPort.value = '22';
                if (gdCred && gdCred.options.length > 0 && !gdCred.options[0].disabled) { gdCred.selectedIndex = 0; }
                gdScript.value = '';
                gdTimeout.value = '120';
                gdCooldown.value = '60';
                gdSudo.checked = false;
                gdEnabled.checked = true;
                gdBlock.checked = false;
                return;
            }
            if (gdTitle) { gdTitle.innerHTML = '<i class="bi bi-pencil-square me-2 text-primary"></i>Редактировать автопроверку'; }
            gdId.value = String(g.id);
            gdName.value = g.name || '';
            gdWhen.value = g.runWhen || 'BOTH';
            gdPromql.value = g.promql || '';
            gdCmp.value = g.cmp || 'GT';
            gdThr.value = g.threshold != null ? String(g.threshold) : '80';
            gdUnit.value = g.unit || '';
            gdHost.value = g.sshHost || '';
            gdPort.value = g.sshPort != null ? String(g.sshPort) : '22';
            if (gdCred && g.sshCredentialId != null) { gdCred.value = String(g.sshCredentialId); }
            gdScript.value = g.script || '';
            gdTimeout.value = g.timeoutSec != null ? String(g.timeoutSec) : '120';
            gdCooldown.value = g.cooldownSec != null ? String(g.cooldownSec) : '60';
            gdSudo.checked = !!g.useSudo;
            gdEnabled.checked = g.enabled !== false;
            gdBlock.checked = !!g.blockOnFail;
        }

        guardModalEl.addEventListener('show.bs.modal', function (ev) {
            var trigger = ev.relatedTarget;
            var editId = trigger && trigger.dataset && trigger.dataset.guardId ? Number(trigger.dataset.guardId) : null;
            if (editId) {
                var found = readGuardsList().find(function (x) { return x.id === editId; });
                fillGuardForm(found || null);
            } else {
                fillGuardForm(null);
            }
        });

        guardModalEl.querySelectorAll('[data-gd-preset]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var p = GD_PRESETS[btn.dataset.gdPreset];
                if (!p) { return; }
                if (!gdName.value.trim()) { gdName.value = p.name; }
                gdPromql.value = p.promql;
                gdCmp.value = p.cmp;
                gdThr.value = p.threshold;
                gdUnit.value = p.unit;
                gdPromql.focus();
            });
        });
    }
})();
