// CSRF для htmx: токен берётся из <meta name="_csrf">, уходит заголовком X-CSRF-TOKEN.
document.body.addEventListener('htmx:configRequest', function (e) {
    var meta = document.querySelector('meta[name="_csrf"]');
    if (meta) { e.detail.headers['X-CSRF-TOKEN'] = meta.content; }
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
    es.addEventListener('done', function () {
        done = true;
        es.close();
        clearInterval(poll);
        if (badge) { badge.className = 'badge text-bg-secondary'; badge.textContent = 'завершено'; }
        refresh();
    });
    es.onerror = function () {
        es.close();
        clearInterval(poll);
        if (!done && badge) { badge.className = 'badge text-bg-warning'; badge.textContent = 'поток остановлен'; }
        refresh();
    };
})();

// Индекс для новой строки в динамических таблицах форм (операции модели, метрики SUT): max(data-i) + 1.
function nextRowIndex(selector) {
    var max = -1;
    document.querySelectorAll(selector).forEach(function (r) { max = Math.max(max, +r.dataset.i); });
    return max + 1;
}

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

// Автоматическая кнопка «показать/скрыть» для полей паролей.
(function () {
    document.querySelectorAll('input[type="password"]:not([data-no-pw-toggle])').forEach(function (inp) {
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
})();

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

// Универсальная фильтрация таблиц/списков по поиску [data-table-filter] и чипам [data-chip-filter].
(function () {
    document.querySelectorAll('[data-table-filter]').forEach(function (input) {
        var sel = input.dataset.tableFilter;
        var chipSel = input.dataset.chipGroup;
        var emptySel = input.dataset.emptyTarget;
        var countSel = input.dataset.countTarget;
        var activeChip = '';

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

        input.addEventListener('input', apply);
        if (chipSel) {
            document.querySelectorAll(chipSel).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    activeChip = btn.dataset.chipVal || '';
                    document.querySelectorAll(chipSel).forEach(function (b) { b.classList.toggle('on', b === btn); });
                    apply();
                });
            });
        }
    });
})();

// Защита от двойного сабмита обычных POST-форм со спиннером на кнопке.
(function () {
    var lockedBtns = [];
    document.addEventListener('submit', function (ev) {
        var form = ev.target;
        if (!form || form.tagName !== 'FORM' || ev.defaultPrevented) { return; }
        if ((form.method || '').toLowerCase() !== 'post') { return; }
        if (form.dataset.noLoading === 'true' || form.getAttribute('hx-post') || form.target === '_blank') { return; }
        var btn = ev.submitter || form.querySelector('button[type="submit"]:not(:disabled)');
        if (!btn || btn.disabled || btn.dataset.noLoading === 'true') { return; }
        setTimeout(function () {
            if (ev.defaultPrevented || btn.disabled) { return; }
            btn.dataset.k6OrigHtml = btn.innerHTML;
            btn.disabled = true;
            lockedBtns.push(btn);
            var iconOnly = !btn.textContent.trim();
            btn.innerHTML = '<span class="spinner-border spinner-border-sm' + (iconOnly ? '' : ' me-1') + '" role="status" aria-hidden="true"></span>'
                + (iconOnly ? '' : btn.textContent.trim());
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

    function raiseTopBackdrop() {
        setTimeout(function () {
            var backs = document.querySelectorAll('.modal-backdrop');
            if (backs.length > 1) {
                backs[backs.length - 1].classList.add('k6-top-backdrop');
            }
        }, 0);
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
            function onHidden() {
                if (settled) { return; }
                settled = true;
                okBtn.removeEventListener('click', onOk);
                modalEl.removeEventListener('hidden.bs.modal', onHidden);
                restoreBodyModalState();
                resolve(result);
            }

            okBtn.addEventListener('click', onOk);
            modalEl.addEventListener('hidden.bs.modal', onHidden);
            modal.show();
            raiseTopBackdrop();
            modalEl.addEventListener('shown.bs.modal', function () {
                // Для опасных действий фокусируем «Отмену», чтобы случайный Enter не удалил данные
                if (variant === 'danger' && !opts.hideCancel) {
                    cancelBtn.focus();
                } else {
                    okBtn.focus();
                }
            }, {once: true});
        });
    };

    window.k6Prompt = function (input) {
        var opts = typeof input === 'string' ? {title: input} : (input || {});
        var modalEl = document.getElementById('k6-prompt-modal');
        if (!modalEl || !window.bootstrap || !bootstrap.Modal) {
            return Promise.resolve(window.prompt(opts.label || opts.title || 'Введите значение', opts.value || ''));
        }
        var formEl = document.getElementById('k6-prompt-form');
        var titleEl = document.getElementById('k6-prompt-title');
        var labelEl = document.getElementById('k6-prompt-label');
        var inputEl = document.getElementById('k6-prompt-input');
        var okBtn = document.getElementById('k6-prompt-ok');

        titleEl.innerHTML = '<i class="bi bi-pencil-square me-2 text-primary"></i>' + (opts.title || 'Введите значение').replace(/</g, '&lt;');
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
            function onHidden() {
                if (settled) { return; }
                settled = true;
                formEl.removeEventListener('submit', onSubmit);
                modalEl.removeEventListener('hidden.bs.modal', onHidden);
                restoreBodyModalState();
                resolve(result);
            }

            formEl.addEventListener('submit', onSubmit);
            modalEl.addEventListener('hidden.bs.modal', onHidden);
            modal.show();
            raiseTopBackdrop();
            modalEl.addEventListener('shown.bs.modal', function () {
                inputEl.focus();
                inputEl.select();
            }, {once: true});
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
        if (!form.isConnected) {
            form.style.display = 'none';
            document.body.appendChild(form);
        }
        form.dataset.k6Confirmed = '1';
        if (submitter && form.requestSubmit && form.contains(submitter)) {
            submitter.dataset.k6Confirmed = '1';
            form.requestSubmit(submitter);
        } else if (submitter && submitter.getAttribute('formaction')) {
            var prevAction = form.getAttribute('action');
            form.setAttribute('action', submitter.getAttribute('formaction'));
            form.submit();
            if (prevAction != null) { form.setAttribute('action', prevAction); }
        } else {
            form.submit();
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
