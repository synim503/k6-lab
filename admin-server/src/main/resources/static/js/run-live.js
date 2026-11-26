// Live-страница run: SSE-логи с фильтрами и поиском, обновление блока статуса по событиям.
(function () {
    var root = document.getElementById('run-live');
    if (!root) { return; }
    var log = document.getElementById('run-log');
    var genSel = document.getElementById('log-gen');
    var search = document.getElementById('log-search');
    var pauseBtn = document.getElementById('log-pause');
    var downBtn = document.getElementById('log-down');
    var wrapBtn = document.getElementById('log-wrap');
    var copyBtn = document.getElementById('log-copy');
    var clearBtn = document.getElementById('log-clear');
    var counter = document.getElementById('log-count');
    var errBadge = document.getElementById('log-err-badge');
    var logsTabBtn = document.getElementById('logs-tab-btn');
    var lvlChips = document.querySelectorAll('#log-lvl-chips [data-lvl]');

    var lines = [];          // все полученные строки (в браузере хранится не больше MAX)
    var MAX = 5000;
    var autoscroll = true;
    var activeLevel = '';
    var shownCount = 0;
    var unreadErrors = 0;

    function isLogsTabOpen() {
        var pane = document.getElementById('tab-logs');
        return !!(pane && pane.classList.contains('active'));
    }

    function syncErrBadge() {
        if (!errBadge) { return; }
        errBadge.textContent = String(unreadErrors);
        errBadge.classList.toggle('d-none', unreadErrors <= 0);
    }
    if (logsTabBtn) {
        logsTabBtn.addEventListener('shown.bs.tab', function () {
            unreadErrors = 0;
            syncErrBadge();
            if (autoscroll) { log.scrollTop = log.scrollHeight; }
        });
    }

    function syncLevelCounts() {
        var cnt = {all: lines.length, ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0};
        for (var i = 0; i < lines.length; i++) {
            var lv = lines[i].l;
            if (cnt[lv] != null) { cnt[lv]++; }
        }
        var set = function (id, v) { var el = document.getElementById(id); if (el) { el.textContent = String(v); } };
        set('log-cnt-all', cnt.all);
        set('log-cnt-err', cnt.ERROR);
        set('log-cnt-warn', cnt.WARN);
        set('log-cnt-info', cnt.INFO);
        set('log-cnt-debug', cnt.DEBUG);
    }

    function syncCounter() {
        if (!counter) { return; }
        var filtered = !!(genSel && genSel.value) || !!activeLevel || !!(search && search.value.trim());
        counter.textContent = filtered ? (shownCount + ' из ' + lines.length) : (lines.length + ' строк');
    }

    function syncScrollButtons() {
        if (pauseBtn) {
            pauseBtn.innerHTML = autoscroll
                ? '<i class="bi bi-pause-fill me-1"></i>Пауза'
                : '<i class="bi bi-play-fill me-1"></i>Автоскролл';
            pauseBtn.classList.toggle('btn-outline-secondary', autoscroll);
            pauseBtn.classList.toggle('btn-outline-primary', !autoscroll);
        }
        if (downBtn) { downBtn.classList.toggle('d-none', autoscroll); }
    }

    // строка лога = div из span-ов (время, генератор, уровень, текст); между span-ами пробелы, чтобы копировалось как обычный текст
    function part(parent, cls, text) {
        var s = document.createElement('span'); s.className = cls; s.textContent = text;
        if (parent.firstChild) { parent.appendChild(document.createTextNode(' ')); }
        parent.appendChild(s);
    }
    function fmt(l) {
        var row = document.createElement('div');
        row.className = 'll ll-' + l.l;
        part(row, 'll-t', new Date(l.t).toTimeString().substring(0, 8));
        if (l.g) { part(row, 'll-g', '[' + l.g + ']'); }
        part(row, 'll-l', l.l);
        part(row, 'll-m', l.m);
        return row;
    }
    // список генераторов в фильтре пополняется по мере прихода логов: на момент открытия страницы запуск мог ещё не назначить генераторы
    var knownGens = {};
    if (genSel) { [].forEach.call(genSel.options, function (o) { if (o.value) { knownGens[o.value] = true; } }); }
    function noteGen(g) {
        if (!genSel || !g || knownGens[g]) { return; }
        knownGens[g] = true;
        var o = document.createElement('option'); o.value = g; o.textContent = g;
        var after = null;
        [].forEach.call(genSel.options, function (x) { if (x.value && !after && x.value > g) { after = x; } });
        genSel.insertBefore(o, after);
    }
    function visible(l) {
        if (genSel && genSel.value && l.g !== genSel.value) { return false; }
        if (activeLevel && l.l !== activeLevel) { return false; }
        var q = search ? search.value.trim().toLowerCase() : '';
        return !q || l.m.toLowerCase().indexOf(q) >= 0 || (l.g && l.g.toLowerCase().indexOf(q) >= 0);
    }
    // выделение в логе: пока пользователь что-то выделяет, DOM не перерисовываем и не прокручиваем (иначе выделение пропадает)
    function selecting() {
        var sel = window.getSelection();
        return !!sel && !sel.isCollapsed && sel.rangeCount > 0 && log.contains(sel.anchorNode);
    }
    var dirty = false;       // полный пересбор отложен до снятия выделения
    function showEmptyPlaceholder() {
        var empty = document.createElement('div');
        empty.className = 'log-empty';
        empty.textContent = lines.length ? 'По текущему фильтру строк не найдено.' : 'Лог пока пуст.';
        log.appendChild(empty);
    }
    function render() {
        if (selecting()) { dirty = true; return; }
        dirty = false;
        var frag = document.createDocumentFragment();
        shownCount = 0;
        for (var i = 0; i < lines.length; i++) {
            if (visible(lines[i])) { frag.appendChild(fmt(lines[i])); shownCount++; }
        }
        log.textContent = '';
        if (shownCount === 0) { showEmptyPlaceholder(); } else { log.appendChild(frag); }
        syncLevelCounts();
        syncCounter();
        if (autoscroll) { log.scrollTop = log.scrollHeight; }
    }
    function append(batch) {
        var added = document.createDocumentFragment();
        var addedCount = 0;
        var newErrors = 0;
        batch.forEach(function (l) {
            noteGen(l.g);
            lines.push(l);
            if (l.l === 'ERROR') { newErrors++; }
            if (visible(l)) { added.appendChild(fmt(l)); addedCount++; }
        });
        if (newErrors > 0 && !isLogsTabOpen()) {
            unreadErrors += newErrors;
            syncErrBadge();
        }
        var drop = 0;
        if (lines.length > MAX) {
            // в буфере режем сразу; на экране убираем столько же видимых строк с начала, не пересобирая весь список
            var cut = lines.splice(0, lines.length - MAX);
            cut.forEach(function (l) { if (visible(l)) { drop++; } });
        }
        if (addedCount) {
            var ph = log.querySelector('.log-empty');
            if (ph) { ph.remove(); }
            log.appendChild(added);
            shownCount += addedCount;
        }
        if (drop && !selecting()) {
            while (drop-- > 0 && log.firstChild) { log.removeChild(log.firstChild); shownCount = Math.max(0, shownCount - 1); }
            drop = 0;
        } else if (drop) {
            dirty = true;
        }
        if (autoscroll && !selecting()) { log.scrollTop = log.scrollHeight; }
        syncLevelCounts();
        syncCounter();
    }

    document.addEventListener('visibilitychange', function () { if (!document.hidden) { render(); } });
    document.addEventListener('selectionchange', function () { if (dirty && !selecting()) { render(); } });
    if (genSel) { genSel.addEventListener('change', render); }
    lvlChips.forEach(function (btn) {
        btn.addEventListener('click', function () {
            activeLevel = btn.dataset.lvl || '';
            lvlChips.forEach(function (b) { b.classList.toggle('on', b === btn); });
            render();
        });
    });
    var searchTimer = null;
    if (search) {
        search.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(render, 120);
        });
    }
    if (pauseBtn) {
        pauseBtn.addEventListener('click', function () {
            autoscroll = !autoscroll;
            syncScrollButtons();
            if (autoscroll) { log.scrollTop = log.scrollHeight; }
        });
    }
    if (downBtn) {
        downBtn.addEventListener('click', function () {
            autoscroll = true;
            log.scrollTop = log.scrollHeight;
            syncScrollButtons();
        });
    }
    if (wrapBtn) {
        wrapBtn.addEventListener('click', function () {
            var noWrap = log.classList.toggle('no-wrap');
            wrapBtn.classList.toggle('active', !noWrap);
        });
    }
    if (copyBtn) {
        copyBtn.addEventListener('click', function () {
            var txt = lines.filter(visible).map(function (l) {
                return new Date(l.t).toTimeString().substring(0, 8) + (l.g ? ' [' + l.g + ']' : '') + ' ' + l.l + ' ' + l.m;
            }).join('\n');
            if (window.k6Copy) { window.k6Copy(txt, copyBtn); }
        });
    }
    if (clearBtn) {
        clearBtn.addEventListener('click', function () {
            lines = [];
            render();
        });
    }
    log.addEventListener('scroll', function () {
        if (log.scrollTop + log.clientHeight < log.scrollHeight - 30 && autoscroll) {
            autoscroll = false;
            syncScrollButtons();
        }
    });
    render();
    syncScrollButtons();

    var isFinal = root.dataset.final === 'true';
    var logEs = new EventSource(root.dataset.logStream);
    logEs.addEventListener('log', function (ev) {
        append(JSON.parse(ev.data));
        // Для уже завершённого запуска сервер шлёт один снимок логов и завершает стрим;
        // закрываем EventSource, чтобы браузер не переподключался и не дублировал все строки.
        if (isFinal) { logEs.close(); }
    });
    logEs.addEventListener('done', function () {
        logEs.close();
    });
    logEs.onerror = function () {
        if (isFinal) { logEs.close(); }
    };

    // Блок статуса обновляется по событиям SSE и по таймеру.
    var inflight = false, again = false;
    function refreshStatus() {
        if (isFinal || !window.htmx || document.hidden) { return; }
        if (window.K6 && window.K6.isAjaxBusy('#run-status')) { return; }
        if (inflight) { again = true; return; }
        inflight = true;
        var finish = function () { inflight = false; if (again && !isFinal) { again = false; refreshStatus(); } };
        var statusTarget = document.getElementById('run-status');
        var p = htmx.ajax('GET', root.dataset.statusUrl, {target: '#run-status', swap: 'outerHTML', source: statusTarget || undefined});
        if (p && typeof p.then === 'function') { p.then(finish, finish); } else { setTimeout(finish, 1500); }
        setTimeout(function () { if (inflight) { finish(); } }, 8000);
    }
    function stampUpdated() {
        var el = document.querySelector('#gens-box .gens-updated');
        if (el) { el.textContent = 'обновлено ' + new Date().toTimeString().substring(0, 8); }
    }
    var statusEs = null;
    var statusTimer = null;
    var pending = null;
    function stopLiveStreams(finalStatus) {
        if (isFinal) { return; }
        isFinal = true;
        root.dataset.final = 'true';
        if (pending) { clearTimeout(pending); pending = null; }
        if (statusTimer) { clearInterval(statusTimer); statusTimer = null; }
        if (statusEs) { statusEs.close(); statusEs = null; }
        setTimeout(function () { logEs.close(); }, 2000);
        window.dispatchEvent(new CustomEvent('k6:run-final', {detail: {status: finalStatus || ''}}));
        // Обновляем вкладку «Результаты», чтобы сразу подгрузились итоги после завершения запуска
        var resBox = document.getElementById('results-box');
        if (resBox && window.htmx) {
            var resPane = document.getElementById('tab-results');
            if (resPane && resPane.classList.contains('active')) {
                htmx.trigger(resBox, 'refresh');
            } else {
                resBox.dataset.needsRefresh = '1';
                setTimeout(function () { htmx.trigger(resBox, 'refresh'); delete resBox.dataset.needsRefresh; }, 2500);
            }
        }
    }
    if (!isFinal) {
        statusEs = new EventSource(root.dataset.statusStream);
        statusEs.addEventListener('status', function () {
            if (pending || isFinal) { return; }
            pending = setTimeout(function () { pending = null; refreshStatus(); }, 1000);
        });
        statusEs.addEventListener('done', function () {
            if (statusEs) { statusEs.close(); statusEs = null; }
            if (!isFinal) { refreshStatus(); }
        });
        statusTimer = setInterval(refreshStatus, 5000);
    }
    function gensOpen() { try { return sessionStorage.getItem('run-gens-open') === '1'; } catch (e) { return false; } }
    function applyGens() { var d = document.getElementById('gens-box'); if (d) { d.open = gensOpen(); } }
    document.addEventListener('toggle', function (e) {
        if (e.target && e.target.id === 'gens-box') { try { sessionStorage.setItem('run-gens-open', e.target.open ? '1' : '0'); } catch (x) { /* без сохранения */ } }
    }, true);
    function onStatusUpdated() {
        applyGens();
        stampUpdated();
        var stEl = document.getElementById('run-status');
        var st = stEl && stEl.dataset ? stEl.dataset.runStatus : '';
        if (!isFinal && (st === 'FINISHED' || st === 'FAILED' || st === 'ABORTED')) {
            stopLiveStreams(st);
        }
    }
    document.body.addEventListener('htmx:afterSwap', onStatusUpdated);
    document.addEventListener('k6:swapped', onStatusUpdated);
    document.body.addEventListener('htmx:afterSettle', applyGens);
    applyGens();
    if (!isFinal) { stampUpdated(); }
    document.addEventListener('change', function (e) {
        var sel = e.target;
        if (!sel || sel.id !== 'rep-time') { return; }
        document.querySelectorAll('.rep-link').forEach(function (a) {
            var base = a.getAttribute('data-base') || a.getAttribute('href');
            a.setAttribute('data-base', base);
            var u = new URL(base, location.origin);
            if (sel.value) { u.searchParams.set('time', sel.value); } else { u.searchParams.delete('time'); }
            a.setAttribute('href', u.pathname + u.search);
        });
    });
    window.addEventListener('beforeunload', function () {
        logEs.close();
        if (statusEs) { statusEs.close(); }
        if (statusTimer) { clearInterval(statusTimer); }
    });
})();
