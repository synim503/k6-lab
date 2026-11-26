// Общие утилиты k6-lab (экранирование HTML, CSRF, безопасный fetch HTML, учёт активных AJAX-целей).
window.K6 = (function () {
    var busyTargets = Object.create(null);

    function escapeHtml(v) {
        return String(v == null ? '' : v)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function getCsrf() {
        var meta = document.querySelector('meta[name="_csrf"]');
        var hdr = document.querySelector('meta[name="_csrf_header"]');
        return {
            token: meta && meta.content ? meta.content : '',
            header: hdr && hdr.content ? hdr.content : 'X-CSRF-TOKEN'
        };
    }

    function syncCsrf(doc) {
        if (!doc || !doc.querySelector) { return; }
        var fresh = doc.querySelector('meta[name="_csrf"]');
        var cur = document.querySelector('meta[name="_csrf"]');
        if (fresh && cur && fresh.content) { cur.content = fresh.content; }
    }

    function setBtnSpinner(btn, labelText) {
        btn.textContent = '';
        var sp = document.createElement('span');
        sp.className = 'spinner-border spinner-border-sm' + (labelText ? ' me-1' : '');
        sp.setAttribute('role', 'status');
        sp.setAttribute('aria-hidden', 'true');
        btn.appendChild(sp);
        if (labelText) { btn.appendChild(document.createTextNode(labelText)); }
    }

    function markBusy(selectors, delta) {
        (selectors || []).forEach(function (sel) {
            var next = (busyTargets[sel] || 0) + delta;
            if (next <= 0) { delete busyTargets[sel]; } else { busyTargets[sel] = next; }
        });
    }

    function isAjaxBusy(selector) {
        return !!(selector && busyTargets[selector] > 0);
    }

    function fetchHtml(url, opts) {
        opts = opts || {};
        var method = (opts.method || 'GET').toUpperCase();
        var headers = Object.assign({'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html'}, opts.headers || {});
        if (method !== 'GET' && method !== 'HEAD') {
            var csrf = getCsrf();
            if (csrf.token && !headers[csrf.header]) { headers[csrf.header] = csrf.token; }
        }
        return fetch(url, Object.assign({
            method: method,
            credentials: 'same-origin',
            redirect: 'follow'
        }, opts, {headers: headers})).then(function (res) {
            return res.text().then(function (html) {
                var finalUrl = new URL(res.url || url, location.origin);
                if (finalUrl.pathname === '/login') {
                    location.href = finalUrl.href;
                    var err = new Error('Требуется авторизация');
                    err.redirectedToLogin = true;
                    throw err;
                }
                var doc = new DOMParser().parseFromString(html, 'text/html');
                syncCsrf(doc);
                return {res: res, html: html, doc: doc, url: finalUrl.href, finalUrl: finalUrl};
            });
        });
    }

    return {
        escapeHtml: escapeHtml,
        getCsrf: getCsrf,
        syncCsrf: syncCsrf,
        setBtnSpinner: setBtnSpinner,
        markBusy: markBusy,
        isAjaxBusy: isAjaxBusy,
        fetchHtml: fetchHtml
    };
})();

// CSRF для htmx: токен берётся из <meta name="_csrf">, уходит заголовком X-CSRF-TOKEN.
document.body.addEventListener('htmx:configRequest', function (e) {
    var csrf = window.K6.getCsrf();
    if (csrf.token) { e.detail.headers[csrf.header] = csrf.token; }
});

// Сохранение горизонтального скролла таблиц при htmx-обновлении (например, #gen-table).
(function () {
    var savedScroll = {};
    document.body.addEventListener('htmx:beforeSwap', function (e) {
        var t = e.detail && e.detail.target;
        if (!t || !t.id) { return; }
        var wrap = t.querySelector('.table-responsive') || (t.classList && t.classList.contains('table-responsive') ? t : null);
        if (wrap) { savedScroll[t.id] = wrap.scrollLeft; }
    });
    document.body.addEventListener('htmx:afterSwap', function (e) {
        var t = e.detail && e.detail.target;
        if (!t || !t.id || savedScroll[t.id] == null) { return; }
        var wrap = t.querySelector('.table-responsive') || (t.classList && t.classList.contains('table-responsive') ? t : null);
        if (wrap) { wrap.scrollLeft = savedScroll[t.id]; }
        delete savedScroll[t.id];
    });
})();

// Глобальные всплывающие уведомления (window.k6Toast).
window.k6Toast = function (msg, kind, opts) {
    var box = document.getElementById('k6-toast-box');
    if (!box) {
        box = document.createElement('div');
        box.id = 'k6-toast-box';
        box.setAttribute('role', 'status');
        box.setAttribute('aria-live', 'polite');
        document.body.appendChild(box);
    }
    var type = (kind === 'ok' || kind === 'err' || kind === 'warn' || kind === 'info') ? kind : 'info';
    var icon = type === 'ok' ? 'bi-check-circle-fill'
        : (type === 'err' ? 'bi-exclamation-triangle-fill'
            : (type === 'warn' ? 'bi-exclamation-circle-fill' : 'bi-info-circle-fill'));
    var el = document.createElement('div');
    el.className = 'k6-toast ' + type;
    var ic = document.createElement('i');
    ic.className = 'bi ' + icon + ' flex-shrink-0';
    var txt = document.createElement('span');
    txt.textContent = String(msg || '');
    var closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'k6-toast-close';
    closeBtn.setAttribute('aria-label', 'Закрыть');
    closeBtn.innerHTML = '&times;';
    el.appendChild(ic);
    el.appendChild(txt);
    el.appendChild(closeBtn);
    box.appendChild(el);

    var ttl = (opts && opts.duration) || (type === 'err' ? 6000 : 3800);
    var hovered = false;
    var timer = null;
    function dismiss() {
        if (!el.isConnected) { return; }
        el.style.opacity = '0';
        el.style.transition = 'opacity .18s';
        setTimeout(function () { el.remove(); }, 200);
    }
    function arm() {
        clearTimeout(timer);
        timer = setTimeout(function () { if (!hovered) { dismiss(); } }, ttl);
    }
    closeBtn.addEventListener('click', dismiss);
    el.addEventListener('mouseenter', function () { hovered = true; clearTimeout(timer); });
    el.addEventListener('mouseleave', function () { hovered = false; arm(); });
    arm();
    return el;
};

// Копирование текста в буфер обмена с fallback для HTTP (без HTTPS) и визуальной реакцией на кнопке.
window.k6Copy = function (text, btn) {
    var val = String(text == null ? '' : text);
    function ok() {
        if (btn) {
            btn.classList.add('is-copied');
            var orig = btn.dataset.k6CopyOrig;
            if (orig == null) { btn.dataset.k6CopyOrig = btn.innerHTML; orig = btn.innerHTML; }
            var iconOnly = !btn.textContent.trim();
            btn.innerHTML = iconOnly ? '<i class="bi bi-check-lg"></i>' : '<i class="bi bi-check-lg me-1"></i>Скопировано';
            clearTimeout(btn._k6CopyT);
            btn._k6CopyT = setTimeout(function () {
                btn.classList.remove('is-copied');
                if (btn.dataset.k6CopyOrig != null) { btn.innerHTML = btn.dataset.k6CopyOrig; }
            }, 1600);
        } else {
            window.k6Toast('Скопировано в буфер обмена', 'ok', {duration: 2200});
        }
    }
    function fallback() {
        var ta = document.createElement('textarea');
        ta.value = val;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        var done = false;
        try { done = document.execCommand('copy'); } catch (_) {}
        ta.remove();
        if (done) { ok(); } else { window.k6Toast('Не удалось скопировать в буфер', 'err'); }
    }
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(val).then(ok, fallback);
    } else {
        fallback();
    }
};

document.addEventListener('click', function (ev) {
    var btn = ev.target && ev.target.closest ? ev.target.closest('[data-copy], [data-copy-target]') : null;
    if (!btn) { return; }
    ev.preventDefault();
    ev.stopPropagation();
    var text = btn.dataset.copy;
    if (text == null && btn.dataset.copyTarget) {
        var el = document.querySelector(btn.dataset.copyTarget);
        text = el ? (el.value != null ? el.value : el.textContent) : '';
    }
    window.k6Copy(text || '', btn);
});

// Проверка: выделяет ли пользователь текст (чтобы клик по строке таблицы не сбивал копирование).
window.k6IsSelecting = function () {
    var sel = window.getSelection ? window.getSelection() : null;
    return !!(sel && String(sel).trim().length > 0);
};

// Глобальная навигация по строкам tr.run-row[data-href] с защитой от выделения и кликов по интерактивным элементам.
document.addEventListener('click', function (ev) {
    var row = ev.target && ev.target.closest ? ev.target.closest('tr.run-row[data-href]') : null;
    if (!row) { return; }
    if (ev.target.closest('a, button, input, select, textarea, label, form, .dropdown-menu, [data-no-row-click]')) { return; }
    if (window.k6IsSelecting()) { return; }
    var href = row.dataset.href;
    if (!href) { return; }
    if (ev.ctrlKey || ev.metaKey) { window.open(href, '_blank'); } else { location.href = href; }
});

// Живой журнал операции: SSE -> <pre data-stream="...">. События: log (строки), step (обновить статус), done.
(function () {
    var pre = document.getElementById('deploy-log');
    if (!pre) { return; }
    var autoscroll = true;
    var downBtn = document.getElementById('deploy-log-down');
    var badge = document.getElementById('deploy-sse-badge');
    var done = false;
    var hasLines = pre.textContent.length > 0;
    var errCount = 0;
    var MAX_ERR = 5;
    function syncDown() {
        if (downBtn) { downBtn.classList.toggle('d-none', autoscroll); }
    }
    pre.addEventListener('scroll', function () {
        autoscroll = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 24;
        syncDown();
    });
    if (downBtn) {
        downBtn.addEventListener('click', function () {
            autoscroll = true;
            pre.scrollTop = pre.scrollHeight;
            syncDown();
        });
    }
    var es = new EventSource(pre.dataset.stream);
    function append(text) {
        pre.appendChild(document.createTextNode((hasLines ? '\n' : '') + text));
        hasLines = true;
        if (autoscroll) { pre.scrollTop = pre.scrollHeight; }
    }
    function refresh() {
        if (document.hidden || (window.K6 && window.K6.isAjaxBusy('#deploy-status'))) { return; }
        var st = document.getElementById('deploy-status');
        if (st && window.htmx) { htmx.trigger(st, 'refresh'); }
    }
    es.onopen = function () {
        errCount = 0;
        if (!done && badge) { badge.className = 'badge text-bg-success'; badge.textContent = 'подключено'; }
    };
    es.addEventListener('log', function (ev) { errCount = 0; append(ev.data); });
    // страховка: смена статуса без события step (например, ожидание подтверждения ключа хоста) тоже должна обновить страницу
    var poll = setInterval(refresh, 3000);
    es.addEventListener('step', refresh);
    es.addEventListener('done', function () {
        done = true;
        es.close();
        clearInterval(poll);
        if (badge) { badge.className = 'badge text-bg-secondary'; badge.textContent = 'завершено'; }
        refresh();
    });
    es.onerror = function () {
        if (done) { es.close(); clearInterval(poll); return; }
        errCount++;
        if (errCount >= MAX_ERR) {
            es.close();
            clearInterval(poll);
            if (badge) { badge.className = 'badge text-bg-warning'; badge.textContent = 'поток остановлен'; }
            refresh();
        } else if (badge) {
            badge.className = 'badge text-bg-warning';
            badge.textContent = 'переподключение…';
        }
    };
    window.addEventListener('beforeunload', function () {
        es.close();
        clearInterval(poll);
    });
})();

// Подсветка текущего раздела в верхнем меню (включая пункты внутри «Ещё» и /tuning).
(function () {
    var path = location.pathname;
    var best = null;
    document.querySelectorAll('.app-nav a.nav-link[href], .app-nav a.dropdown-item[href]').forEach(function (a) {
        var h = a.getAttribute('href');
        if (!h || h === '#') { return; }
        var match = (h === '/') ? (path === '/') : (path === h || path.indexOf(h + '/') === 0);
        if (match && (!best || h.length > best.getAttribute('href').length)) { best = a; }
    });
    // страница запуска /runs/... относится к разделу «Скрипты»
    if (!best && (path === '/runs' || path.indexOf('/runs/') === 0)) {
        best = document.querySelector('.app-nav a.nav-link[href$="/scripts"]');
    }
    if (!best && (path === '/tuning' || path.indexOf('/tuning/') === 0)) {
        best = document.querySelector('.app-nav a.nav-link[href$="/generators"]');
    }
    if (best) {
        best.classList.add('active');
        best.setAttribute('aria-current', 'page');
        var dd = best.closest('.nav-item.dropdown');
        if (dd) {
            var toggle = dd.querySelector(':scope > a.nav-link');
            if (toggle) { toggle.classList.add('active'); }
        }
    }
})();

// Автоскрытие успешных flash-алертов через 7 секунд (останавливается при наведении).
(function () {
    document.querySelectorAll('main > .alert-success.alert-dismissible').forEach(function (el) {
        var hovered = false;
        var timer = null;
        function close() {
            if (hovered || !el.isConnected) { return; }
            if (window.bootstrap && bootstrap.Alert) {
                bootstrap.Alert.getOrCreateInstance(el).close();
            } else {
                el.remove();
            }
        }
        el.addEventListener('mouseenter', function () { hovered = true; clearTimeout(timer); });
        el.addEventListener('mouseleave', function () { hovered = false; timer = setTimeout(close, 3000); });
        timer = setTimeout(close, 7000);
    });
})();

// Автоматическая кнопка «показать/скрыть» для полей паролей + фильтры таблиц (переиспользуется после AJAX-swap).
window.k6InitSubtree = function (root) {
    var scope = root || document;
    scope.querySelectorAll('input[type="password"]:not([data-no-pw-toggle])').forEach(function (inp) {
        if (inp.closest('.k6-pw-wrap') || inp.closest('.input-group')) { return; }
        var wrap = document.createElement('div');
        wrap.className = 'k6-pw-wrap';
        inp.parentNode.insertBefore(wrap, inp);
        wrap.appendChild(inp);
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'k6-pw-toggle';
        btn.title = 'Показать пароль';
        btn.setAttribute('aria-label', 'Показать пароль');
        btn.innerHTML = '<i class="bi bi-eye"></i>';
        btn.addEventListener('click', function () {
            var show = inp.type === 'password';
            inp.type = show ? 'text' : 'password';
            btn.title = show ? 'Скрыть пароль' : 'Показать пароль';
            btn.setAttribute('aria-label', btn.title);
            btn.innerHTML = show ? '<i class="bi bi-eye-slash"></i>' : '<i class="bi bi-eye"></i>';
        });
        wrap.appendChild(btn);
    });

    scope.querySelectorAll('[data-table-filter]').forEach(function (input) {
        if (input.dataset.k6FilterBound === '1') { return; }
        input.dataset.k6FilterBound = '1';
        var sel = input.dataset.tableFilter;
        var chipSel = input.dataset.chipGroup;
        var emptySel = input.dataset.emptyTarget;
        var countSel = input.dataset.countTarget;
        var activeChip = '';
        if (chipSel) {
            var onBtn = document.querySelector(chipSel + '.on');
            if (onBtn) { activeChip = onBtn.dataset.chipVal || ''; }
        }

        function apply() {
            var q = (input.value || '').trim().toLowerCase();
            var rows = document.querySelectorAll(sel);
            var visible = 0;
            rows.forEach(function (row) {
                var text = (row.dataset.search || row.textContent || '').toLowerCase();
                var chipVal = row.dataset.filterVal || '';
                var okQ = !q || text.indexOf(q) >= 0;
                var okC = !activeChip || chipVal === activeChip;
                var show = okQ && okC;
                row.classList.toggle('d-none', !show);
                if (show) { visible++; }
            });
            if (emptySel) {
                var emp = document.querySelector(emptySel);
                if (emp) { emp.classList.toggle('d-none', visible > 0 || rows.length === 0); }
            }
            if (countSel) {
                var cnt = document.querySelector(countSel);
                if (cnt) { cnt.textContent = (q || activeChip) ? ('Показано ' + visible + ' из ' + rows.length) : ''; }
            }
        }

        input._k6ApplyFilter = apply;
        input.addEventListener('input', apply);
        if (chipSel) {
            document.querySelectorAll(chipSel).forEach(function (btn) {
                if (btn.dataset.k6ChipBound === '1') { return; }
                btn.dataset.k6ChipBound = '1';
                btn.addEventListener('click', function () {
                    activeChip = btn.dataset.chipVal || '';
                    document.querySelectorAll(chipSel).forEach(function (b) { b.classList.toggle('on', b === btn); });
                    apply();
                });
            });
        }
        apply();
    });

    if (root && root !== document && window.htmx && typeof window.htmx.process === 'function') {
        window.htmx.process(root);
    }
};
window.k6InitSubtree(document);

// Быстрый переход по разделам (Ctrl+K / Cmd+K).
(function () {
    var modalEl = document.getElementById('k6-cmdk-modal');
    if (!modalEl) { return; }
    var inputEl = document.getElementById('k6-cmdk-input');
    var listEl = document.getElementById('k6-cmdk-list');
    var openBtn = document.getElementById('k6-cmdk-open');
    var items = [];
    var filtered = [];
    var idx = 0;

    function collectItems() {
        var out = [];
        var seen = {};
        document.querySelectorAll('.app-nav a.nav-link[href], .app-nav a.dropdown-item[href]').forEach(function (a) {
            var href = a.getAttribute('href');
            if (!href || href === '#' || seen[href]) { return; }
            seen[href] = true;
            var ic = a.querySelector('i');
            var iconCls = ic ? Array.from(ic.classList).filter(function (c) { return c === 'bi' || c.indexOf('bi-') === 0; }).join(' ') : 'bi bi-arrow-right';
            var group = a.classList.contains('dropdown-item') ? 'Ещё' : 'Навигация';
            out.push({title: a.textContent.trim(), href: href, icon: iconCls, group: group});
        });
        var extras = [
            {title: 'Новый скрипт', href: '/scripts/new', icon: 'bi bi-plus-circle', group: 'Быстрые действия'},
            {title: 'Добавить генератор', href: '/generators/add', icon: 'bi bi-plus-lg', group: 'Быстрые действия'}
        ];
        extras.forEach(function (ex) {
            if (!seen[ex.href]) { out.push(ex); }
        });
        return out;
    }

    function render() {
        var q = (inputEl.value || '').trim().toLowerCase();
        filtered = items.filter(function (it) {
            return !q || it.title.toLowerCase().indexOf(q) >= 0 || it.href.toLowerCase().indexOf(q) >= 0;
        });
        if (idx >= filtered.length) { idx = Math.max(0, filtered.length - 1); }
        listEl.innerHTML = '';
        if (!filtered.length) {
            listEl.innerHTML = '<div class="text-center text-muted small py-4">Ничего не найдено</div>';
            return;
        }
        var lastGroup = null;
        filtered.forEach(function (it, i) {
            if (it.group !== lastGroup) {
                lastGroup = it.group;
                var gh = document.createElement('div');
                gh.className = 'k6-cmdk-group';
                gh.textContent = it.group;
                listEl.appendChild(gh);
            }
            var a = document.createElement('a');
            a.className = 'k6-cmdk-item' + (i === idx ? ' is-active' : '');
            a.href = it.href;
            a.setAttribute('role', 'option');
            a.innerHTML = '<i class="' + it.icon + '"></i><span></span><span class="k6-cmdk-sub"></span>';
            a.children[1].textContent = it.title;
            a.children[2].textContent = it.href;
            a.addEventListener('mouseenter', function () { idx = i; syncActive(); });
            listEl.appendChild(a);
        });
    }

    function syncActive() {
        var nodes = listEl.querySelectorAll('.k6-cmdk-item');
        nodes.forEach(function (n, i) {
            n.classList.toggle('is-active', i === idx);
            if (i === idx) { n.scrollIntoView({block: 'nearest'}); }
        });
    }

    function openPalette() {
        if (!window.bootstrap || !bootstrap.Modal) { return; }
        items = collectItems();
        inputEl.value = '';
        idx = 0;
        render();
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
    }

    modalEl.addEventListener('shown.bs.modal', function () { inputEl.focus(); });
    if (openBtn) { openBtn.addEventListener('click', openPalette); }
    inputEl.addEventListener('input', function () { idx = 0; render(); });
    inputEl.addEventListener('keydown', function (e) {
        if (e.key === 'ArrowDown') { e.preventDefault(); if (filtered.length) { idx = (idx + 1) % filtered.length; syncActive(); } }
        else if (e.key === 'ArrowUp') { e.preventDefault(); if (filtered.length) { idx = (idx - 1 + filtered.length) % filtered.length; syncActive(); } }
        else if (e.key === 'Enter' && filtered[idx]) { e.preventDefault(); location.href = filtered[idx].href; }
    });
    document.addEventListener('keydown', function (e) {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
            e.preventDefault();
            openPalette();
        }
    });
})();

// Защита от двойного сабмита обычных POST-форм со спиннером на кнопке (для data-ajax спиннер управляется движком AJAX).
(function () {
    var lockedBtns = [];
    document.addEventListener('submit', function (ev) {
        var form = ev.target;
        if (!form || form.tagName !== 'FORM' || ev.defaultPrevented) { return; }
        if ((form.method || '').toLowerCase() !== 'post') { return; }
        var sub = ev.submitter || null;
        if ((sub && sub.dataset && sub.dataset.ajax) || (form.dataset && form.dataset.ajax)) { return; }
        if (form.dataset.noLoading === 'true' || form.getAttribute('hx-post') || form.target === '_blank') { return; }
        var btn = sub || form.querySelector('button[type="submit"]:not(:disabled)');
        if (!btn || btn.disabled || btn.dataset.noLoading === 'true') { return; }
        setTimeout(function () {
            if (ev.defaultPrevented || btn.disabled) { return; }
            btn.dataset.k6OrigHtml = btn.innerHTML;
            btn.disabled = true;
            lockedBtns.push(btn);
            window.K6.setBtnSpinner(btn, btn.textContent.trim());
        }, 0);
    });
    window.addEventListener('pageshow', function () {
        lockedBtns.forEach(function (btn) {
            btn.disabled = false;
            if (btn.dataset.k6OrigHtml != null) { btn.innerHTML = btn.dataset.k6OrigHtml; }
        });
        lockedBtns = [];
    });
})();

// ==================== Кастомные попапы подтверждения и ввода (window.k6Confirm / window.k6Prompt) ====================
(function () {
    var DANGER_RE = /удал|убьёт|убить|снести|останов|отмен|сброс|забыть|очист|откат|жёстк|прерв|замен|пропад/i;

    function detectVariant(msg, explicit) {
        if (explicit === 'danger' || explicit === 'warning' || explicit === 'primary') { return explicit; }
        return DANGER_RE.test(msg || '') ? 'danger' : 'primary';
    }

    function detectOkLabel(msg, variant, explicit) {
        if (explicit) { return explicit; }
        var low = (msg || '').toLowerCase();
        if (low.indexOf('удал') >= 0) { return 'Удалить'; }
        if (low.indexOf('прерв') >= 0 || low.indexOf('жёстк') >= 0) { return 'Прервать'; }
        if (low.indexOf('останов') >= 0) { return 'Остановить'; }
        if (low.indexOf('отмен') >= 0) { return 'Да, отменить'; }
        if (low.indexOf('сброс') >= 0) { return 'Сбросить'; }
        if (low.indexOf('очист') >= 0) { return 'Очистить'; }
        if (low.indexOf('отправ') >= 0 || low.indexOf('раскат') >= 0 || low.indexOf('примен') >= 0) { return 'Применить'; }
        if (low.indexOf('запуст') >= 0 || low.indexOf('выполн') >= 0) { return 'Выполнить'; }
        return variant === 'danger' ? 'Подтвердить' : 'Продолжить';
    }

    // Очередь модальных окон: следующий k6Confirm/k6Prompt ждёт завершения hidden.bs.modal предыдущего,
    // чтобы не попадать в Bootstrap _isTransitioning deadlock и не наслаивать обработчики.
    var modalQueue = Promise.resolve();
    function enqueueModal(fn) {
        var run = modalQueue.then(fn, fn);
        modalQueue = run.then(function () {}, function () {});
        return run;
    }

    function raiseTopBackdrop() {
        var backs = document.querySelectorAll('.modal-backdrop');
        if (backs.length > 1) {
            backs[backs.length - 1].classList.add('k6-top-backdrop');
        }
    }

    function restoreBodyModalState() {
        if (document.querySelector('.modal.show')) {
            document.body.classList.add('modal-open');
        }
    }

    window.k6Confirm = function (input) {
        var opts = typeof input === 'string' ? {message: input} : (input || {});
        var modalEl = document.getElementById('k6-confirm-modal');
        if (!modalEl || !window.bootstrap || !bootstrap.Modal) {
            return Promise.resolve(window.confirm(opts.message || 'Подтвердите действие'));
        }
        return enqueueModal(function () {
            var titleEl = document.getElementById('k6-confirm-title');
            var msgEl = document.getElementById('k6-confirm-msg');
            var listEl = document.getElementById('k6-confirm-list');
            var iconEl = document.getElementById('k6-confirm-icon');
            var okBtn = document.getElementById('k6-confirm-ok');
            var cancelBtn = document.getElementById('k6-confirm-cancel');

            var variant = detectVariant(opts.message, opts.variant);
            var title = opts.title || (variant === 'danger' ? 'Подтвердите действие' : 'Подтверждение');
            var okText = detectOkLabel(opts.message, variant, opts.confirmText);
            var cancelText = opts.cancelText || 'Отмена';

            titleEl.textContent = title;
            msgEl.textContent = opts.message || '';
            okBtn.textContent = okText;
            cancelBtn.textContent = cancelText;
            cancelBtn.classList.toggle('d-none', !!opts.hideCancel);

            iconEl.className = 'k6-confirm-icon is-' + variant;
            iconEl.innerHTML = variant === 'danger'
                ? '<i class="bi bi-exclamation-triangle-fill"></i>'
                : (variant === 'warning' ? '<i class="bi bi-question-circle-fill"></i>' : '<i class="bi bi-info-circle-fill"></i>');
            okBtn.className = 'btn btn-sm px-3 ' + (variant === 'danger' ? 'btn-danger' : (variant === 'warning' ? 'btn-warning' : 'btn-primary'));

            if (Array.isArray(opts.items) && opts.items.length > 0) {
                listEl.innerHTML = '';
                var maxShow = 8;
                opts.items.slice(0, maxShow).forEach(function (it) {
                    var li = document.createElement('li');
                    li.textContent = String(it);
                    listEl.appendChild(li);
                });
                if (opts.items.length > maxShow) {
                    var more = document.createElement('li');
                    more.className = 'text-muted fst-italic';
                    more.textContent = '… и ещё ' + (opts.items.length - maxShow);
                    listEl.appendChild(more);
                }
                listEl.classList.remove('d-none');
            } else {
                listEl.innerHTML = '';
                listEl.classList.add('d-none');
            }

            return new Promise(function (resolve) {
                var settled = false;
                var result = false;
                var modal = bootstrap.Modal.getOrCreateInstance(modalEl);

                function onOk() {
                    result = true;
                    modal.hide();
                }
                function onShown() {
                    raiseTopBackdrop();
                    // Для опасных действий фокусируем «Отмену», чтобы случайный Enter не удалил данные
                    if (variant === 'danger' && !opts.hideCancel) {
                        cancelBtn.focus();
                    } else {
                        okBtn.focus();
                    }
                }
                function onHidden() {
                    if (settled) { return; }
                    settled = true;
                    okBtn.removeEventListener('click', onOk);
                    modalEl.removeEventListener('shown.bs.modal', onShown);
                    modalEl.removeEventListener('hidden.bs.modal', onHidden);
                    restoreBodyModalState();
                    resolve(result);
                }

                okBtn.addEventListener('click', onOk);
                modalEl.addEventListener('shown.bs.modal', onShown, {once: true});
                modalEl.addEventListener('hidden.bs.modal', onHidden);
                modal.show();
                raiseTopBackdrop();
            });
        });
    };

    window.k6Prompt = function (input) {
        var opts = typeof input === 'string' ? {title: input} : (input || {});
        var modalEl = document.getElementById('k6-prompt-modal');
        if (!modalEl || !window.bootstrap || !bootstrap.Modal) {
            return Promise.resolve(window.prompt(opts.label || opts.title || 'Введите значение', opts.value || ''));
        }
        return enqueueModal(function () {
            var formEl = document.getElementById('k6-prompt-form');
            var titleEl = document.getElementById('k6-prompt-title');
            var labelEl = document.getElementById('k6-prompt-label');
            var inputEl = document.getElementById('k6-prompt-input');
            var okBtn = document.getElementById('k6-prompt-ok');

            titleEl.innerHTML = '<i class="bi bi-pencil-square me-2 text-primary"></i>' + window.K6.escapeHtml(opts.title || 'Введите значение');
            labelEl.textContent = opts.label || '';
            inputEl.value = opts.value || '';
            inputEl.placeholder = opts.placeholder || '';
            inputEl.classList.remove('is-invalid');
            okBtn.textContent = opts.confirmText || 'Сохранить';

            return new Promise(function (resolve) {
                var settled = false;
                var result = null;
                var modal = bootstrap.Modal.getOrCreateInstance(modalEl);

                function onSubmit(e) {
                    e.preventDefault();
                    var v = inputEl.value.trim();
                    if (!v) { inputEl.classList.add('is-invalid'); inputEl.focus(); return; }
                    result = v;
                    modal.hide();
                }
                function onShown() {
                    raiseTopBackdrop();
                    inputEl.focus();
                    inputEl.select();
                }
                function onHidden() {
                    if (settled) { return; }
                    settled = true;
                    formEl.removeEventListener('submit', onSubmit);
                    modalEl.removeEventListener('shown.bs.modal', onShown);
                    modalEl.removeEventListener('hidden.bs.modal', onHidden);
                    restoreBodyModalState();
                    resolve(result);
                }

                formEl.addEventListener('submit', onSubmit);
                modalEl.addEventListener('shown.bs.modal', onShown, {once: true});
                modalEl.addEventListener('hidden.bs.modal', onHidden);
                modal.show();
                raiseTopBackdrop();
            });
        });
    };

    // Пока открыт попап подтверждения/ввода, ставим на паузу фоновые GET-обновления htmx,
    // чтобы swap не отсоединил форму/кнопку и не сбросил отмеченные чекбоксы.
    document.body.addEventListener('htmx:beforeRequest', function (ev) {
        if (ev.detail && ev.detail.verb === 'get' && document.querySelector('#k6-confirm-modal.show, #k6-prompt-modal.show')) {
            ev.preventDefault();
        }
    });

    function submitWithSubmitter(form, submitter) {
        var tempAppended = false;
        if (!form.isConnected) {
            form.style.display = 'none';
            document.body.appendChild(form);
            tempAppended = true;
        }
        form.dataset.k6Confirmed = '1';
        try {
            if (submitter && form.requestSubmit && form.contains(submitter)) {
                submitter.dataset.k6Confirmed = '1';
                form.requestSubmit(submitter);
            } else if (!submitter && form.requestSubmit) {
                form.requestSubmit();
            } else if (submitter && submitter.getAttribute('formaction')) {
                var prevAction = form.getAttribute('action');
                form.setAttribute('action', submitter.getAttribute('formaction'));
                if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
                if (prevAction != null) { form.setAttribute('action', prevAction); }
            } else {
                form.submit();
            }
        } finally {
            if (tempAppended) {
                setTimeout(function () { if (form.isConnected) { form.remove(); } }, 0);
            }
        }
    }

    // Глобальный перехватчик форм с data-confirm="..."
    document.addEventListener('submit', function (ev) {
        var form = ev.target;
        if (!form || form.tagName !== 'FORM') { return; }
        var submitter = ev.submitter || null;
        var msg = (submitter && submitter.dataset && submitter.dataset.confirm) || (form.dataset && form.dataset.confirm);
        if (!msg) { return; }
        if (form.dataset.k6Confirmed === '1') {
            delete form.dataset.k6Confirmed;
            if (submitter && submitter.dataset) { delete submitter.dataset.k6Confirmed; }
            return;
        }
        ev.preventDefault();
        var src = (submitter && submitter.dataset && submitter.dataset.confirm) ? submitter : form;
        window.k6Confirm({
            message: msg,
            title: src.dataset.confirmTitle,
            confirmText: src.dataset.confirmOk,
            variant: src.dataset.confirmVariant
        }).then(function (ok) {
            if (ok) { submitWithSubmitter(form, submitter); }
        });
    }, true);

    // Глобальный перехватчик кнопок/ссылок с data-confirm="..." (для случаев без form или с formaction)
    document.addEventListener('click', function (ev) {
        var el = ev.target && ev.target.closest ? ev.target.closest('button[data-confirm], input[type="submit"][data-confirm], a[data-confirm]') : null;
        if (!el) { return; }
        if (el.disabled) { return; }
        if (el.dataset.k6Confirmed === '1') {
            delete el.dataset.k6Confirmed;
            return;
        }
        // Для submit-кнопок внутри формы событие submit обработает перехватчик выше (в том числе при requestSubmit)
        if ((el.tagName === 'BUTTON' && (el.type || 'submit') === 'submit') || el.tagName === 'INPUT') {
            if (el.form) { return; }
        }
        ev.preventDefault();
        window.k6Confirm({
            message: el.dataset.confirm,
            title: el.dataset.confirmTitle,
            confirmText: el.dataset.confirmOk,
            variant: el.dataset.confirmVariant
        }).then(function (ok) {
            if (!ok) { return; }
            if (el.tagName === 'A' && el.href) {
                location.href = el.href;
            } else {
                el.dataset.k6Confirmed = '1';
                el.click();
            }
        });
    });
})();

// ==================== Интерактивные AJAX-действия без перезагрузки страницы (data-ajax) ====================
(function () {
    var activeCount = 0;

    function setBar(delta) {
        activeCount = Math.max(0, activeCount + delta);
        var bar = document.getElementById('k6-ajax-bar');
        if (!bar && activeCount > 0) {
            bar = document.createElement('div');
            bar.id = 'k6-ajax-bar';
            document.body.appendChild(bar);
        }
        if (bar) { bar.classList.toggle('is-active', activeCount > 0); }
    }

    function parseSelectors(spec) {
        if (!spec || spec === 'toast' || spec === 'none') { return []; }
        return String(spec).split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    }

    function buildFormData(form, submitter) {
        var fd;
        if (submitter && form.contains(submitter)) {
            try { fd = new FormData(form, submitter); }
            catch (_) {
                fd = new FormData(form);
                if (submitter.name) { fd.append(submitter.name, submitter.value || ''); }
            }
        } else {
            fd = new FormData(form);
            if (submitter && submitter.name) { fd.append(submitter.name, submitter.value || ''); }
        }
        if (!fd.has('_csrf')) {
            var meta = document.querySelector('meta[name="_csrf"]');
            if (meta && meta.content) { fd.append('_csrf', meta.content); }
        }
        return fd;
    }

    function lockBtn(btn) {
        if (!btn || btn.disabled || btn.dataset.noLoading === 'true') { return null; }
        var orig = btn.innerHTML;
        btn.disabled = true;
        window.K6.setBtnSpinner(btn, btn.textContent.trim());
        return function unlock() {
            if (!btn.isConnected) { return; }
            btn.disabled = false;
            btn.innerHTML = orig;
        };
    }

    function closeDropdownsIn(el) {
        if (!el || !window.bootstrap || !bootstrap.Dropdown) { return; }
        el.querySelectorAll('[data-bs-toggle="dropdown"].show').forEach(function (t) {
            try { bootstrap.Dropdown.getOrCreateInstance(t).hide(); } catch (_) {}
        });
    }

    function captureState(el) {
        var st = {details: {}, scrolls: [], activeTab: null, filters: {}};
        if (!el) { return st; }
        el.querySelectorAll('details[id]').forEach(function (d) { st.details[d.id] = !!d.open; });
        el.querySelectorAll('.table-responsive').forEach(function (w, i) { st.scrolls[i] = w.scrollLeft; });
        var tab = el.querySelector('.nav-link.active[data-bs-target]');
        if (tab) { st.activeTab = tab.getAttribute('data-bs-target'); }
        el.querySelectorAll('[data-table-filter][id]').forEach(function (inp) {
            var chipVal = '';
            if (inp.dataset.chipGroup) {
                var on = document.querySelector(inp.dataset.chipGroup + '.on');
                if (on) { chipVal = on.dataset.chipVal || ''; }
            }
            st.filters[inp.id] = {value: inp.value, chipVal: chipVal};
        });
        return st;
    }

    function restoreState(el, st) {
        if (!el || !st) { return; }
        Object.keys(st.details).forEach(function (id) {
            var d = el.querySelector('#' + CSS.escape(id));
            if (d && d.tagName === 'DETAILS' && d.dataset.forceOpen !== 'true') {
                d.open = st.details[id];
            }
        });
        el.querySelectorAll('.table-responsive').forEach(function (w, i) {
            if (st.scrolls[i] != null) { w.scrollLeft = st.scrolls[i]; }
        });
        if (st.activeTab && window.bootstrap && bootstrap.Tab) {
            var tab = el.querySelector('.nav-link[data-bs-target="' + st.activeTab + '"]');
            if (tab) { try { bootstrap.Tab.getOrCreateInstance(tab).show(); } catch (_) {} }
        }
        Object.keys(st.filters).forEach(function (id) {
            var inp = el.querySelector('#' + CSS.escape(id));
            if (!inp) { return; }
            inp.value = st.filters[id].value || '';
            if (inp.dataset.chipGroup && st.filters[id].chipVal) {
                document.querySelectorAll(inp.dataset.chipGroup).forEach(function (b) {
                    b.classList.toggle('on', (b.dataset.chipVal || '') === st.filters[id].chipVal);
                });
            }
        });
    }

    function extractAndToastFlashes(doc) {
        var hasError = false;
        var count = 0;
        doc.querySelectorAll('main > .alert.alert-dismissible[role="alert"]').forEach(function (al) {
            var span = al.querySelector('span.flex-grow-1') || al;
            var text = (span.textContent || '').trim();
            if (!text) { return; }
            var kind = al.classList.contains('alert-danger') ? 'err'
                : (al.classList.contains('alert-warning') ? 'warn'
                    : (al.classList.contains('alert-success') ? 'ok' : 'info'));
            if (kind === 'err') { hasError = true; }
            window.k6Toast(text, kind);
            count++;
        });
        return {hasError: hasError, count: count};
    }

    function syncCsrf(doc) {
        var fresh = doc.querySelector('meta[name="_csrf"]');
        var cur = document.querySelector('meta[name="_csrf"]');
        if (fresh && cur && fresh.content) { cur.content = fresh.content; }
    }

    function applyDocSwap(doc, selectors, meta) {
        syncCsrf(doc);
        var swapped = [];
        selectors.forEach(function (sel) {
            var oldEl = document.querySelector(sel);
            var newEl = doc.querySelector(sel);
            if (!oldEl) { return; }
            if (!newEl) {
                // Если элемент исчез в новом HTML (например, снят баннер паузы), скрываем его содержимое, сохраняя якорь
                if (oldEl.dataset.ajaxKeepAnchor === 'true') { oldEl.innerHTML = ''; }
                return;
            }
            closeDropdownsIn(oldEl);
            var st = captureState(oldEl);
            var imported = document.importNode(newEl, true);
            oldEl.replaceWith(imported);
            restoreState(imported, st);
            if (window.k6InitSubtree) { window.k6InitSubtree(imported); }
            swapped.push(imported);
        });
        // Переприменяем внешние фильтры таблиц к обновлённым строкам
        document.querySelectorAll('[data-table-filter]').forEach(function (inp) {
            if (typeof inp._k6ApplyFilter === 'function') { inp._k6ApplyFilter(); }
        });
        document.dispatchEvent(new CustomEvent('k6:swapped', {
            detail: {
                targets: swapped,
                url: meta && meta.url ? meta.url : location.href,
                doc: doc,
                form: meta ? meta.form : null,
                hasError: !!(meta && meta.hasError)
            }
        }));
        return swapped;
    }

    var refreshInflight = Object.create(null);

    window.k6AjaxRefresh = function (selectorSpec, url) {
        var key = String(selectorSpec || '');
        var prev = refreshInflight[key];
        if (prev && prev.ctrl) {
            try { prev.ctrl.abort(); } catch (_) {}
        }
        var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        var seq = (prev ? prev.seq : 0) + 1;
        refreshInflight[key] = {ctrl: ctrl, seq: seq};

        var selectors = parseSelectors(selectorSpec);
        var targets = selectors.map(function (s) { return document.querySelector(s); }).filter(Boolean);
        targets.forEach(function (el) { el.classList.add('k6-ajax-busy'); });
        setBar(1);
        return fetch(url || location.href, {
            method: 'GET',
            credentials: 'same-origin',
            headers: {'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html'},
            signal: ctrl ? ctrl.signal : undefined
        }).then(function (res) {
            if (!res.ok) { throw new Error('HTTP ' + res.status); }
            return res.text().then(function (html) { return {html: html, url: res.url}; });
        }).then(function (r) {
            if (refreshInflight[key] && refreshInflight[key].seq !== seq) {
                var staleErr = new Error('stale');
                staleErr.aborted = true;
                throw staleErr;
            }
            var finalUrl = new URL(r.url || url || location.href, location.origin);
            if (finalUrl.pathname === '/login') {
                location.href = finalUrl.href;
                return [];
            }
            var doc = new DOMParser().parseFromString(r.html, 'text/html');
            return applyDocSwap(doc, selectors, {url: finalUrl.href, form: null, hasError: false});
        }).catch(function (err) {
            if (err && (err.name === 'AbortError' || err.aborted)) {
                var e = new Error('aborted');
                e.aborted = true;
                throw e;
            }
            throw err;
        }).finally(function () {
            if (refreshInflight[key] && refreshInflight[key].seq === seq) {
                delete refreshInflight[key];
                targets.forEach(function (el) { if (el.isConnected) { el.classList.remove('k6-ajax-busy'); } });
            }
            setBar(-1);
        });
    };

    window.k6AjaxSubmit = function (form, submitter) {
        var spec = (submitter && submitter.dataset && submitter.dataset.ajax) || (form.dataset && form.dataset.ajax);
        var selectors = parseSelectors(spec);
        var url = (submitter && submitter.getAttribute('formaction')) || form.getAttribute('action') || location.href;
        var fd = buildFormData(form, submitter);
        var dd = form.closest && form.closest('.dropdown');
        if (dd) { closeDropdownsIn(dd); }
        var unlock = lockBtn(submitter || form.querySelector('button[type="submit"]:not(:disabled)'));
        var busyEls = selectors.map(function (s) { return document.querySelector(s); }).filter(Boolean);
        busyEls.forEach(function (el) { el.classList.add('k6-ajax-busy'); });
        window.K6.markBusy(selectors, 1);
        setBar(1);

        return fetch(url, {
            method: 'POST',
            body: fd,
            credentials: 'same-origin',
            headers: {'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html'},
            redirect: 'follow'
        }).then(function (res) {
            return res.text().then(function (html) { return {res: res, html: html}; });
        }).then(function (r) {
            var finalUrl = new URL(r.res.url || url, location.origin);
            if (finalUrl.pathname === '/login') {
                location.href = finalUrl.href;
                return;
            }
            var doc = new DOMParser().parseFromString(r.html, 'text/html');
            var allowCross = (form.dataset && form.dataset.ajaxAllowRedirect) || '';
            if (spec !== 'toast' && finalUrl.pathname !== location.pathname && !allowCross) {
                location.href = finalUrl.href;
                return;
            }
            var flash = extractAndToastFlashes(doc);
            if (!r.res.ok && flash.count === 0) {
                flash.hasError = true;
                window.k6Toast('Ошибка выполнения запроса (HTTP ' + r.res.status + ')', 'err');
            }
            if (!flash.hasError && flash.count === 0 && form.dataset && form.dataset.ajaxSuccess) {
                window.k6Toast(form.dataset.ajaxSuccess, 'ok');
            }
            if (selectors.length > 0) {
                applyDocSwap(doc, selectors, {url: finalUrl.href, form: form, hasError: flash.hasError});
            } else {
                syncCsrf(doc);
                document.dispatchEvent(new CustomEvent('k6:swapped', {
                    detail: {targets: [], url: finalUrl.href, doc: doc, form: form, hasError: flash.hasError}
                }));
            }
            if (!flash.hasError && form.dataset && form.dataset.ajaxReset === 'true' && form.isConnected) {
                form.reset();
                form.querySelectorAll('.dropzone [data-dz-default]').forEach(function (lbl) {
                    lbl.innerHTML = lbl.dataset.dzDefault;
                });
            }
            var modalEl = form.closest && form.closest('.modal.show');
            if (modalEl && (!flash.hasError || form.dataset.ajaxCloseModal === 'always') && window.bootstrap && bootstrap.Modal) {
                try { bootstrap.Modal.getOrCreateInstance(modalEl).hide(); } catch (_) {}
            }
        }).catch(function () {
            window.k6Toast('Не удалось выполнить запрос — проверьте связь с сервером', 'err');
        }).finally(function () {
            if (unlock) { unlock(); }
            busyEls.forEach(function (el) { if (el.isConnected) { el.classList.remove('k6-ajax-busy'); } });
            window.K6.markBusy(selectors, -1);
            setBar(-1);
            document.dispatchEvent(new CustomEvent('k6:ajax-done', {detail: {form: form}}));
        });
    };

    document.addEventListener('submit', function (ev) {
        var form = ev.target;
        if (!form || form.tagName !== 'FORM' || ev.defaultPrevented) { return; }
        var method = (form.method || 'get').toLowerCase();
        if (method === 'get' && form.dataset && form.dataset.ajaxGet) {
            ev.preventDefault();
            var base = new URL(form.getAttribute('action') || location.href, location.origin);
            var params = new URLSearchParams(new FormData(form));
            var fullUrl = base.pathname + (params.toString() ? '?' + params.toString() : '') + (base.hash || '');
            window.k6AjaxRefresh(form.dataset.ajaxGet, fullUrl).then(function () {
                history.replaceState(null, '', fullUrl);
            }).catch(function (err) {
                if (err && err.aborted) { return; }
                window.k6Toast('Не удалось обновить данные', 'err');
            });
            return;
        }
        if (method !== 'post') { return; }
        var sub = ev.submitter || null;
        if (sub && sub.dataset && sub.dataset.noAjax === 'true') { return; }
        if (form.dataset && form.dataset.noAjax === 'true') { return; }
        var spec = (sub && sub.dataset && sub.dataset.ajax) || (form.dataset && form.dataset.ajax);
        if (!spec) { return; }
        ev.preventDefault();
        window.k6AjaxSubmit(form, sub);
    });

    document.addEventListener('click', function (ev) {
        var a = ev.target && ev.target.closest ? ev.target.closest('a[data-ajax-get]') : null;
        if (!a || ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey || ev.button !== 0) { return; }
        if (a.closest('.disabled')) { ev.preventDefault(); return; }
        ev.preventDefault();
        var href = a.getAttribute('href');
        if (!href) { return; }
        window.k6AjaxRefresh(a.dataset.ajaxGet, href).then(function () {
            history.replaceState(null, '', href);
        }).catch(function (err) {
            if (err && err.aborted) { return; }
            location.href = href;
        });
    });

    document.addEventListener('change', function (ev) {
        var el = ev.target;
        if (!el || !el.dataset) { return; }
        if (el.dataset.autoSubmit === 'change' && el.form) {
            if (typeof el.form.requestSubmit === 'function') { el.form.requestSubmit(); } else { el.form.submit(); }
            return;
        }
        if (el.tagName !== 'SELECT' || !el.dataset.ajaxGet) { return; }
        var param = el.dataset.ajaxParam || el.name;
        if (!param) { return; }
        var u = new URL(location.href);
        if (el.value) { u.searchParams.set(param, el.value); } else { u.searchParams.delete(param); }
        var nextUrl = u.pathname + (u.search ? u.search : '') + (u.hash || '');
        window.k6AjaxRefresh(el.dataset.ajaxGet, nextUrl).then(function () {
            history.replaceState(null, '', nextUrl);
        }).catch(function (err) {
            if (err && err.aborted) { return; }
            location.href = nextUrl;
        });
    });
})();
