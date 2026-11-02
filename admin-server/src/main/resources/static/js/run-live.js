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
    function render() {
        var out = [];
        for (var i = 0; i < lines.length; i++) { if (visible(lines[i])) { out.push(fmt(lines[i])); } }
        log.textContent = out.join('\n');
        counter.textContent = out.length + ' из ' + lines.length + ' строк';
        if (autoscroll) { log.scrollTop = log.scrollHeight; }
    }
    function append(batch) {
        var added = [];
        batch.forEach(function (l) { lines.push(l); if (visible(l)) { added.push(fmt(l)); } });
        if (lines.length > MAX) { lines.splice(0, lines.length - MAX); render(); return; }
        if (added.length) {
            log.textContent += (log.textContent ? '\n' : '') + added.join('\n');
            if (autoscroll) { log.scrollTop = log.scrollHeight; }
        }
        counter.textContent = lines.length + ' строк в буфере';
    }
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
