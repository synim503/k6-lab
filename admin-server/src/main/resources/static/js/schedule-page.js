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

    // ==================== Table Filtering & Search ====================
    var tbody = document.getElementById('sch-tbody');
    var searchEl = document.getElementById('sch-search');
    var filterBtns = document.querySelectorAll('[data-sch-filter]');
    var activeFilter = 'all';

    function applyFilter() {
        if (!tbody) { return; }
        var q = searchEl ? searchEl.value.trim().toLowerCase() : '';
        var shown = 0;
        tbody.querySelectorAll('tr.sch-row').forEach(function (tr) {
            var st = tr.dataset.status || '';
            var matchSt = activeFilter === 'all'
                || (activeFilter === 'pending' && (st === 'PENDING' || st === 'STARTING'))
                || (activeFilter === 'started' && st === 'STARTED')
                || (activeFilter === 'other' && (st === 'FAILED' || st === 'CANCELLED'));
            var matchQ = !q || tr.textContent.toLowerCase().indexOf(q) >= 0;
            var ok = matchSt && matchQ;
            tr.style.display = ok ? '' : 'none';
            if (ok) { shown++; }
        });
        var emptyRow = document.getElementById('sch-filter-empty');
        if (emptyRow) { emptyRow.classList.toggle('d-none', shown > 0); }
    }

    if (searchEl) { searchEl.addEventListener('input', applyFilter); }
    filterBtns.forEach(function (btn) {
        btn.addEventListener('click', function () {
            activeFilter = btn.dataset.schFilter;
            filterBtns.forEach(function (b) { b.classList.toggle('active', b === btn); });
            applyFilter();
        });
    });

    // ==================== Dropzones for Import Modals ====================
    function wireDropzone(dzId, fileId, lblId, modalId) {
        var dz = document.getElementById(dzId), fi = document.getElementById(fileId), lb = document.getElementById(lblId);
        if (dz && fi && lb) {
            dz.addEventListener('click', function () { fi.click(); });
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

    function loadScriptMeta(scriptId, wantedProfileId, wantedCount) {
        if (!scriptId) { return; }
        qForm.action = qForm.dataset.actionTpl.replace('__ID__', String(scriptId));
        if (qMeta) { qMeta.innerHTML = '<span class="spinner-border spinner-border-sm text-primary"></span>'; }
        if (qErr) { qErr.classList.add('d-none'); qErr.textContent = ''; }
        qSubmit.disabled = true;

        fetch(qForm.dataset.metaBase + '/' + encodeURIComponent(scriptId), {credentials: 'same-origin'})
            .then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); })
            .then(function (d) {
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
            })
            .catch(function () {
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
        });

        qModalEl.querySelectorAll('[data-qm-time]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var p = btn.dataset.qmTime, d = new Date();
                qModalEl.querySelectorAll('[data-qm-time]').forEach(function (b) { b.classList.toggle('active', b === btn); });
                if (p === 'front' || p === 'back') {
                    qAt.value = '';
                    qIso.value = '';
                    if (qPos) { qPos.value = p; }
                    return;
                }
                if (qPos) { qPos.value = ''; }
                if (p === 'auto') { qAt.value = ''; qIso.value = ''; return; }
                if (p === '+15m') { d.setMinutes(d.getMinutes() + 15); }
                else if (p === '+1h') { d.setHours(d.getHours() + 1); }
                else if (p === '22:00') { d.setHours(22, 0, 0, 0); if (d <= new Date()) { d.setDate(d.getDate() + 1); } }
                else if (p === '02:00') { d.setHours(2, 0, 0, 0); if (d <= new Date()) { d.setDate(d.getDate() + 1); } }
                qAt.value = toLocalVal(d);
                syncQuickIso();
            });
        });

        qModalEl.querySelectorAll('[data-qm-pct]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                qOpsBox.querySelectorAll('input[name^="pct_"]').forEach(function (i) { i.value = btn.dataset.qmPct; });
            });
        });

        qModalEl.querySelectorAll('input[name="envMode"]').forEach(function (r) {
            r.addEventListener('change', function () { qEnv.disabled = !document.getElementById('qm-env-custom').checked; });
        });

        qModalEl.addEventListener('show.bs.modal', function (ev) {
            var trigger = ev.relatedTarget;
            var presetScript = trigger && trigger.dataset.repeatScript ? trigger.dataset.repeatScript : qScript.value;
            var presetProf = trigger && trigger.dataset.repeatProfile ? trigger.dataset.repeatProfile : null;
            var presetCnt = trigger && trigger.dataset.repeatCount ? trigger.dataset.repeatCount : null;
            if (presetScript) { qScript.value = presetScript; }
            qAt.value = '';
            qIso.value = '';
            if (qPos) { qPos.value = ''; }
            qModalEl.querySelectorAll('[data-qm-time]').forEach(function (b) { b.classList.toggle('active', b.dataset.qmTime === 'auto'); });
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
            if (!src || !navigator.clipboard) { return; }
            navigator.clipboard.writeText(src.textContent.trim()).then(function () {
                var prev = btn.innerHTML;
                btn.innerHTML = '<i class="bi bi-check2 me-1"></i>Скопировано';
                setTimeout(function () { btn.innerHTML = prev; }, 1600);
            });
        });
    });
})();
