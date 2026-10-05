// Qarzdorlik sahifasidagi tahlil kartasi: faqat tugma bosilganda serverdan tahlilni olib, ko'rsatadi.
(function () {
    const card = document.getElementById('aiCard');
    if (!card) return;

    const btn = card.querySelector('[data-ai-run]');
    const label = card.querySelector('[data-ai-label]');
    const body = card.querySelector('[data-ai-body]');
    const sub = card.querySelector('[data-ai-sub]');
    const MIN_LOADING_MS = 700; // animatsiya "miltillab" o'tib ketmasligi uchun

    function setBusy(busy) {
        btn.disabled = busy;
        card.classList.toggle('busy', busy);
        label.textContent = busy ? 'Tahlil qilinmoqda…' : 'Qayta tahlil';
    }

    function showLoading() {
        body.innerHTML =
            '<div class="ai-loading">' +
            '<div class="ai-dots"><span></span><span></span><span></span></div>' +
            '<div class="ai-skeleton"><i></i><i></i><i></i><i></i></div>' +
            '</div>';
    }

    function showError(message) {
        const p = document.createElement('p');
        p.className = 'ai-error';
        p.textContent = message;
        body.replaceChildren(p);
    }

    btn.addEventListener('click', async () => {
        setBusy(true);
        showLoading();
        const started = Date.now();
        try {
            const res = await fetch(card.dataset.url, { credentials: 'same-origin', headers: { 'Accept': 'text/html' } });
            if (res.redirected || res.status === 401) {
                throw new Error('Sessiya tugagan — sahifani yangilang va qayta kiring.');
            }
            if (!res.ok) throw new Error('Server xatosi (' + res.status + '). Qayta urinib ko\'ring.');
            const html = await res.text(); // server shabloni — barcha matnlar escape qilingan
            await new Promise(r => setTimeout(r, Math.max(0, MIN_LOADING_MS - (Date.now() - started))));
            body.innerHTML = html;
            const now = new Date();
            sub.textContent = 'Tahlil: ' + now.toLocaleDateString('ru-RU') + ' '
                + now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
            showError(e.message);
        } finally {
            setBusy(false);
        }
    });
})();
