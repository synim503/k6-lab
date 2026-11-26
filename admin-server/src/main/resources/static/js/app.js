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
    es.onerror = function () { es.close(); clearInterval(poll); refresh(); };
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
    // страница запуска /runs/... относится к разделу «Скрипты»
    if (!best && path.indexOf('/runs') === 0) { best = document.querySelector('.app-nav a.nav-link[href$="/scripts"]'); }
    if (best) { best.classList.add('active'); }
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
        if (low.indexOf('останов') >= 0 || low.indexOf('прерв') >= 0) { return 'Остановить'; }
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
                okBtn.focus();
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
