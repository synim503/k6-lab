// Live-страница run: SSE-логи с фильтрами и поиском, обновление блока статуса по событиям.
(function () {
    var root = document.getElementById('run-live');
    if (!root) { return; }
    var log = document.getElementById('run-log');
    var genSel = document.getElementById('log-gen');
    var levelSel = document.getElementById('log-level');
    var search = document.getElementById('log-search');
    var pauseBtn = document.getElementById('log-pause');
    var counter = document.getElementById('log-count');
    var lines = [];          // все полученные строки (в браузере хранится не больше MAX)
    var MAX = 5000;
    var autoscroll = true;
    var NL = String.fromCharCode(10);

    function fmt(l) {
        var d = new Date(l.t);
        var ts = d.toTimeString().substring(0, 8);
        return ts + ' ' + (l.g ? '[' + l.g + '] ' : '') + l.l + ' ' + l.m;
    }
    function visible(l) {
        if (genSel.value && l.g !== genSel.value) { return false; }
        if (levelSel.value && l.l !== levelSel.value) { return false; }
        var q = search.value.trim().toLowerCase();
        return !q || l.m.toLowerCase().indexOf(q) >= 0;
    }
    // выделение в логе: пока пользователь что-то выделяет, DOM не перерисовываем и не прокручиваем (иначе выделение пропадает)
    function selecting() {
        var sel = window.getSelection();
        return !!sel && !sel.isCollapsed && sel.rangeCount > 0 && log.contains(sel.anchorNode);
    }
    var dirty = false;       // полный пересбор отложен до снятия выделения
    function render() {
        if (selecting()) { dirty = true; return; }
        dirty = false;
        var out = [];
        for (var i = 0; i < lines.length; i++) { if (visible(lines[i])) { out.push(fmt(lines[i])); } }
        log.textContent = out.join(NL);
        counter.textContent = out.length + ' из ' + lines.length + ' строк';
        if (autoscroll) { log.scrollTop = log.scrollHeight; }
    }
    function append(batch) {
        var added = [];
        batch.forEach(function (l) { lines.push(l); if (visible(l)) { added.push(fmt(l)); } });
        if (lines.length > MAX) {
            lines.splice(0, lines.length - MAX);
            // в буфере режем сразу, на экране - когда не выделяют текст
            dirty = true;
            if (!selecting()) { render(); return; }
        }
        if (added.length) {
            // дописываем текстовым узлом: существующие узлы (и выделение в них) остаются нетронутыми
            log.appendChild(document.createTextNode((log.firstChild ? NL : '') + added.join(NL)));
            if (autoscroll && !selecting()) { log.scrollTop = log.scrollHeight; }
        }
        counter.textContent = lines.length + ' строк в буфере';
    }
    document.addEventListener('selectionchange', function () { if (dirty && !selecting()) { render(); } });
    [genSel, levelSel].forEach(function (e) { e.addEventListener('change', render); });
    search.addEventListener('input', render);
    pauseBtn.addEventListener('click', function () {
        autoscroll = !autoscroll;
        pauseBtn.textContent = autoscroll ? 'Пауза автоскролла' : 'Продолжить автоскролл';
        if (autoscroll) { log.scrollTop = log.scrollHeight; }
    });
    log.addEventListener('scroll', function () {
        if (log.scrollTop + log.clientHeight < log.scrollHeight - 30 && autoscroll) {
            autoscroll = false; pauseBtn.textContent = 'Продолжить автоскролл';
        }
    });

    var logEs = new EventSource(root.dataset.logStream);
    logEs.addEventListener('log', function (ev) { append(JSON.parse(ev.data)); });

    function refreshStatus() {
        if (window.htmx) {
            htmx.ajax('GET', root.dataset.statusUrl, {target: '#run-status', swap: 'outerHTML'});
        }
    }
    var statusEs = new EventSource(root.dataset.statusStream);
    var pending = null;
    statusEs.addEventListener('status', function () {
        // события идут часто: обновляем блок не чаще раза в секунду
        if (pending) { return; }
        pending = setTimeout(function () { pending = null; refreshStatus(); }, 1000);
    });
    setInterval(refreshStatus, 5000);
    window.addEventListener('beforeunload', function () { logEs.close(); statusEs.close(); });
})();
