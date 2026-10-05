// HotelPulse — kichik interaktiv effektlar (kutubxonasiz).
(function () {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // Sahifa aylantirilganda yuqori menyuga chegara/soya beriladi.
    const topbar = document.querySelector('.topbar');
    if (topbar) {
        const onScroll = () => topbar.classList.toggle('scrolled', window.scrollY > 4);
        onScroll();
        window.addEventListener('scroll', onScroll, {passive: true});
    }

    // Xabarlar: yopish tugmasi; muvaffaqiyat xabari 5 soniyadan keyin o'zi yo'qoladi.
    document.querySelectorAll('.alert').forEach(alert => {
        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'alert-close';
        close.setAttribute('aria-label', 'Yopish');
        close.textContent = '×';
        close.addEventListener('click', () => alert.classList.add('hide'));
        alert.appendChild(close);
        if (alert.classList.contains('alert-success')) {
            setTimeout(() => alert.classList.add('hide'), 5000);
        }
    });

    // Raqamlarni 0 dan sanab chiqarish: "57,1%", "469 797 so'm", "50,3 mln so'm" kabi
    // matndagi birinchi sonni topib, formatini (kasr, minglik bo'shliq) saqlagan holda animatsiya qiladi.
    // Son raqam bilan boshlanib, raqam bilan tugaydi — keyingi bo'shliq (masalan "… so'm" oldidagi) suffiksda qoladi.
    const NUM = /-?\d(?:[\d  ]*\d)?(?:,\d+)?/;
    function countUp(el) {
        const original = el.textContent;
        const match = original.match(NUM);
        if (!match) return;
        const raw = match[0];
        const decimals = raw.includes(',') ? raw.split(',')[1].length : 0;
        const grouped = /[  ]/.test(raw.trim());
        const target = parseFloat(raw.replace(/[  ]/g, '').replace(',', '.'));
        if (!isFinite(target) || target === 0) return;
        const prefix = original.slice(0, match.index);
        const suffix = original.slice(match.index + raw.length);
        const format = v => {
            let s = v.toFixed(decimals);
            let [int, frac] = s.split('.');
            if (grouped) int = int.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
            return prefix + int + (frac ? ',' + frac : '') + suffix;
        };
        const duration = 900;
        const start = performance.now();
        const step = now => {
            const t = Math.min(1, (now - start) / duration);
            const eased = 1 - Math.pow(1 - t, 3);
            el.textContent = format(target * eased);
            if (t < 1) requestAnimationFrame(step);
            else el.textContent = original;
        };
        el.textContent = format(0);
        requestAnimationFrame(step);
    }

    if (!reduceMotion) {
        document.querySelectorAll('[data-count]').forEach(countUp);
    }

    // Exely sinxronlash fonda ketayotgan bo'lsa — holatni ko'rsatish uchun sahifa har 5 soniyada yangilanadi.
    if (document.querySelector('[data-running="true"]')) {
        setTimeout(() => window.location.reload(), 5000);
    }

    // Telegram'ni ulash: bot yangi oynada ochiladi — ulanish holati ko'rinishi uchun
    // sahifa 2 daqiqa davomida har 4 soniyada yangilanadi (ulangach "Ulangan" chiqadi).
    document.querySelectorAll('form[data-wait-link]').forEach(form => {
        form.addEventListener('submit', () => {
            let left = 30;
            const timer = setInterval(() => {
                if (--left <= 0) { clearInterval(timer); return; }
                fetch(window.location.href, {credentials: 'same-origin'})
                    .then(r => r.text())
                    .then(html => { if (html.includes('badge badge-ok">Ulangan')) window.location.reload(); })
                    .catch(() => {});
            }, 4000);
        });
    });

    // Modal oynalar (<dialog>): data-modal-open="id" ochadi; ×, "Bekor qilish",
    // Esc yoki orqa fonga bosish yopadi. Server xato qaytarsa (data-open-on-load) — qayta ochiladi.
    const closeModal = dlg => {
        if (!dlg.open || dlg.classList.contains('closing')) return;
        if (reduceMotion) { dlg.close(); return; }
        dlg.classList.add('closing');
        setTimeout(() => { dlg.classList.remove('closing'); dlg.close(); }, 170);
    };
    const openModal = dlg => {
        if (!dlg || dlg.open) return;
        dlg.showModal();
        const first = dlg.querySelector('input:not([type=hidden])');
        if (first) first.focus();
    };
    document.querySelectorAll('[data-modal-open]').forEach(btn =>
        btn.addEventListener('click', () => openModal(document.getElementById(btn.dataset.modalOpen))));
    document.querySelectorAll('dialog.modal').forEach(dlg => {
        dlg.querySelectorAll('[data-modal-close]').forEach(b => b.addEventListener('click', () => closeModal(dlg)));
        dlg.addEventListener('cancel', e => { e.preventDefault(); closeModal(dlg); });   // Esc
        dlg.addEventListener('click', e => { if (e.target === dlg) closeModal(dlg); });    // orqa fon
        if (dlg.dataset.openOnLoad === 'true') openModal(dlg);
    });

    // Parol maydonlari: ko'rsatish/yashirish tugmasi.
    document.querySelectorAll('.pw-toggle').forEach(btn => {
        btn.addEventListener('click', () => {
            const input = btn.parentElement.querySelector('input');
            const show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            btn.classList.toggle('on', show);
            btn.setAttribute('aria-label', show ? 'Parolni yashirish' : "Parolni ko'rsatish");
        });
    });

    // Parol o'zgartirish formasi: kuchlilik ko'rsatkichi va takror mosligi.
    document.querySelectorAll('form[data-password-form]').forEach(form => {
        const pw = form.querySelector('[name=newPassword]');
        const confirm = form.querySelector('[name=confirmPassword]');
        const bar = form.querySelector('[data-strength]');
        const label = form.querySelector('[data-strength-label]');
        const mismatch = form.querySelector('[data-confirm-error]');
        const levels = [
            {w: .15, cls: 'weak', text: 'Juda qisqa — kamida 8 belgi'},
            {w: .35, cls: 'weak', text: 'Zaif'},
            {w: .6, cls: 'warn', text: "O'rtacha"},
            {w: .8, cls: 'normal', text: 'Yaxshi'},
            {w: 1, cls: 'good', text: 'Kuchli'}
        ];
        const score = v => {
            if (v.length < 8) return 0;
            let s = 1;
            if (v.length >= 12) s++;
            if (/[a-z]/.test(v) && /[A-Z]/.test(v)) s++;
            if (/\d/.test(v) && /[^A-Za-z0-9]/.test(v)) s++;
            return Math.min(s, 4);
        };
        const checkMatch = () => {
            const bad = confirm.value.length > 0 && confirm.value !== pw.value;
            mismatch.hidden = !bad;
            confirm.setCustomValidity(bad ? 'Parollar bir xil emas' : '');
        };
        pw.addEventListener('input', () => {
            const lvl = pw.value ? levels[score(pw.value)] : null;
            bar.className = 'meter-fill' + (lvl ? ' ' + lvl.cls : '');
            bar.style.width = lvl ? (lvl.w * 100) + '%' : '0';
            label.textContent = lvl ? lvl.text : 'Kamida 8 belgi';
            checkMatch();
        });
        confirm.addEventListener('input', checkMatch);
    });

    // Ochiladigan menyular (.nav-group): bosilganda ochiladi/yopiladi (telefon ham); tashqariga bosish yoki Esc — yopadi.
    // Menyu ekrandan chiqib ketsa — o'ng chetga tekislanadi.
    (function () {
        var groups = document.querySelectorAll('.nav-group');
        if (!groups.length) return;
        function close(except) {
            groups.forEach(function (g) {
                if (g === except) return;
                g.classList.remove('open');
                g.querySelector('.nav-toggle').setAttribute('aria-expanded', 'false');
            });
        }
        function fit(group) {
            var menu = group.querySelector('.nav-menu');
            menu.classList.remove('align-right');
            if (menu.getBoundingClientRect().right > window.innerWidth - 8) menu.classList.add('align-right');
        }
        groups.forEach(function (g) {
            var btn = g.querySelector('.nav-toggle');
            btn.addEventListener('click', function (e) {
                e.stopPropagation();
                var open = !g.classList.contains('open');
                close(g);
                g.classList.toggle('open', open);
                btn.setAttribute('aria-expanded', String(open));
                if (open) fit(g);
            });
            g.addEventListener('mouseenter', function () { fit(g); });
        });
        document.addEventListener('click', function () { close(null); });
        document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(null); });
    })();

    // Izohlar (button.tip[data-tip]): telefonda title ko'rinmaydi — shuning uchun o'z oynasi.
    // Bosilganda ochiladi/yopiladi (telefon ham), sichqoncha bor qurilmada — olib borilganda ham.
    // Oyna ekran chetidan chiqmasligi uchun joylashuvi hisoblanadi; tashqariga bosish, Esc, aylantirish — yopadi.
    (function () {
        var tips = document.querySelectorAll('.tip[data-tip]');
        if (!tips.length) return;
        var pop = document.createElement('div');
        pop.className = 'tip-pop';
        pop.setAttribute('role', 'tooltip');
        pop.id = 'tip-pop';
        document.body.appendChild(pop);
        var current = null;
        var shownAt = 0;
        var hover = window.matchMedia('(hover: hover) and (pointer: fine)').matches;

        function place(btn) {
            var r = btn.getBoundingClientRect();
            var margin = 12;
            pop.style.left = '0px';
            pop.style.top = '0px';
            var w = pop.offsetWidth, h = pop.offsetHeight;
            var left = Math.min(Math.max(r.left + r.width / 2 - w / 2, margin), window.innerWidth - w - margin);
            var below = r.bottom + 8;
            var top = below + h > window.innerHeight - margin ? r.top - h - 8 : below;
            pop.style.left = left + 'px';
            pop.style.top = Math.max(margin, top) + 'px';
        }
        function show(btn) {
            if (current && current !== btn) current.setAttribute('aria-expanded', 'false');
            current = btn;
            pop.textContent = btn.getAttribute('data-tip').replace(/\\n/g, '\n');
            pop.classList.add('show');
            btn.setAttribute('aria-expanded', 'true');
            btn.setAttribute('aria-describedby', 'tip-pop');
            shownAt = window.scrollY;
            place(btn);
        }
        function hide() {
            if (!current) return;
            current.setAttribute('aria-expanded', 'false');
            current.removeAttribute('aria-describedby');
            current = null;
            pop.classList.remove('show');
        }
        tips.forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.preventDefault();
                e.stopPropagation();   // havola ichida bo'lsa ham sahifa almashmasin
                current === btn ? hide() : show(btn);
            });
            btn.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    e.stopPropagation();
                    current === btn ? hide() : show(btn);
                }
            });
            if (hover) {
                btn.addEventListener('mouseenter', function () { show(btn); });
                btn.addEventListener('mouseleave', hide);
            }
            btn.addEventListener('blur', hide);
        });
        document.addEventListener('click', hide);
        document.addEventListener('keydown', function (e) { if (e.key === 'Escape') hide(); });
        // Telefonda tugmaga bosilganda brauzer sahifani biroz surishi mumkin (fokus) — kichik surilishda
        // izoh yopilmaydi, faqat joyi yangilanadi; sezilarli aylantirishda yopiladi.
        window.addEventListener('scroll', function () {
            if (!current) return;
            if (Math.abs(window.scrollY - shownAt) > 40) hide(); else place(current);
        }, { passive: true });
        window.addEventListener('resize', hide);
    })();

    // Progress chiziqlari: data-fill="0.57" — kechikish bilan to'ladi (CSS transition).
    requestAnimationFrame(() => {
        setTimeout(() => {
            document.querySelectorAll('.meter-fill[data-fill]').forEach(el => {
                const v = Math.max(0, Math.min(1, parseFloat(el.dataset.fill) || 0));
                el.style.width = (v * 100).toFixed(1) + '%';
                el.classList.add(v >= 0.75 ? 'good' : v < 0.4 ? 'warn' : 'normal');
            });
        }, reduceMotion ? 0 : 150);
    });
})();
