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
