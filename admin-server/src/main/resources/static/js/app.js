// CSRF для htmx: токен берётся из <meta name="_csrf">, уходит заголовком X-CSRF-TOKEN.
document.body.addEventListener('htmx:configRequest', function (e) {
    var meta = document.querySelector('meta[name="_csrf"]');
    if (meta) { e.detail.headers['X-CSRF-TOKEN'] = meta.content; }
});

// Живой журнал операции: SSE -> <pre data-stream="...">. События: log (строки), step (обновить статус), done.
(function () {
    var pre = document.getElementById('deploy-log');
    if (!pre) { return; }
    var autoscroll = true;
    pre.addEventListener('scroll', function () {
        autoscroll = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 20;
    });
    var es = new EventSource(pre.dataset.stream);
    function append(text) {
        pre.textContent += (pre.textContent ? '\n' : '') + text;
        if (autoscroll) { pre.scrollTop = pre.scrollHeight; }
    }
    function refresh() {
        var st = document.getElementById('deploy-status');
        if (st && window.htmx) { htmx.trigger(st, 'refresh'); }
    }
    es.addEventListener('log', function (ev) { append(ev.data); });
    // страховка: смена статуса без события step (например, ожидание подтверждения ключа хоста) тоже должна обновить страницу
    var poll = setInterval(refresh, 3000);
    es.addEventListener('step', refresh);
    es.addEventListener('done', function () { es.close(); clearInterval(poll); refresh(); });
    es.onerror = function () { es.close(); refresh(); };
})();

// Индекс для новой строки в динамических таблицах форм (операции модели, метрики SUT): max(data-i) + 1.
function nextRowIndex(selector) {
    var max = -1;
    document.querySelectorAll(selector).forEach(function (r) { max = Math.max(max, +r.dataset.i); });
    return max + 1;
}

// Подсветка текущего раздела в верхнем меню.
(function () {
    var path = location.pathname;
    var best = null;
    document.querySelectorAll('.app-nav .navbar-nav > .nav-item > a.nav-link[href]').forEach(function (a) {
        var h = a.getAttribute('href');
        if (h && h !== '#' && (h === '/' ? path === '/' : path.indexOf(h) === 0) && (!best || h.length > best.getAttribute('href').length)) { best = a; }
    });
    // раздел «Тесты» объединяет и /quick/test/..., а «Запуски» - и /runs/...
    if (!best) {
        var map = [['/quick/test', '/quick/tests'], ['/deployments', null]];
        map.forEach(function (m) {
            if (!best && path.indexOf(m[0]) === 0 && m[1]) { best = document.querySelector('.app-nav a.nav-link[href$="' + m[1] + '"]'); }
        });
    }
    if (best) { best.classList.add('active'); }
})();
