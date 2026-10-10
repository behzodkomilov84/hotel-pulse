// Mehmonxona ekrani (hisobotlar bloklari): ko'rish va tahrirlash rejimi.
// Tahrirlashda: bloklarni sudrab yoki ↑ ↓ bilan joyini o'zgartirish, ✕ — olib tashlash, "Saqlash" — serverga tartib.
(function () {
    // Tasdiq so'raladigan formalar (data-confirm="...").
    document.querySelectorAll('form[data-confirm]').forEach(f =>
        f.addEventListener('submit', e => { if (!confirm(f.dataset.confirm)) e.preventDefault(); }));

    const ws = document.getElementById('workspace');
    const toggle = document.querySelector('[data-ws-edit]');
    const toolbar = document.querySelector('[data-ws-toolbar]');
    if (!ws || !toggle || !toolbar) return;

    const blocks = () => [...ws.querySelectorAll(':scope > .ws-block')];
    const order = () => blocks().map(b => b.dataset.key).join(',');
    const initial = order();
    let dragged = null;

    function setEditing(on) {
        document.body.classList.toggle('ws-editing', on);
        toolbar.hidden = !on;
        toggle.hidden = on;
        blocks().forEach(b => b.draggable = on);
        if (on) toolbar.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    }

    toggle.addEventListener('click', () => setEditing(true));
    toolbar.querySelector('[data-ws-cancel]').addEventListener('click', () => {
        if (order() !== initial) {
            window.location.reload();   // o'zgarishlar bekor — sahifa avvalgi holatida
        } else {
            setEditing(false);
        }
    });

    // ↑ ↓ ✕
    ws.addEventListener('click', e => {
        const block = e.target.closest('.ws-block');
        if (!block || !document.body.classList.contains('ws-editing')) return;
        if (e.target.closest('[data-ws-up]') && block.previousElementSibling) {
            ws.insertBefore(block, block.previousElementSibling);
        } else if (e.target.closest('[data-ws-down]') && block.nextElementSibling) {
            ws.insertBefore(block.nextElementSibling, block);
        } else if (e.target.closest('[data-ws-remove]')) {
            block.classList.add('ws-removing');
            setTimeout(() => block.remove(), 160);
            return;
        } else {
            return;
        }
        block.classList.add('ws-moved');
        setTimeout(() => block.classList.remove('ws-moved'), 400);
        block.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    });

    // Sudrab joylash (kompyuterda).
    ws.addEventListener('dragstart', e => {
        dragged = e.target.closest('.ws-block');
        if (!dragged) return;
        dragged.classList.add('ws-dragging');
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', dragged.dataset.key);
    });
    ws.addEventListener('dragend', () => {
        if (dragged) dragged.classList.remove('ws-dragging');
        dragged = null;
    });
    ws.addEventListener('dragover', e => {
        if (!dragged) return;
        e.preventDefault();
        const over = e.target.closest('.ws-block');
        if (!over || over === dragged) return;
        const r = over.getBoundingClientRect();
        const after = (e.clientY - r.top) > r.height / 2;
        ws.insertBefore(dragged, after ? over.nextElementSibling : over);
    });

    // Saqlash va "Hisobot qo'shish" — joriy tartib (keys) bilan.
    const save = toolbar.querySelector('[data-ws-save]');
    save.addEventListener('submit', () => { save.querySelector('[name=keys]').value = order(); });
    document.querySelectorAll('form[data-ws-add]').forEach(f =>
        f.addEventListener('submit', () => { f.querySelector('[name=keys]').value = order(); }));

    // Yangi qo'shilgan blokka o'tilganda (#block-...) — tahrirlash rejimida qolsin.
    try {
        if (location.hash.startsWith('#block-') && sessionStorage.getItem('ws-editing') === '1') {
            sessionStorage.removeItem('ws-editing');
            setEditing(true);
        }
    } catch (e) { /* bo'sh */ }
    document.querySelectorAll('form[data-ws-add]').forEach(f =>
        f.addEventListener('submit', () => { try { sessionStorage.setItem('ws-editing', '1'); } catch (e) { /* bo'sh */ } }));
    save.addEventListener('submit', () => { try { sessionStorage.removeItem('ws-editing'); } catch (e) { /* bo'sh */ } });
    try { if (!location.hash.startsWith('#block-')) sessionStorage.removeItem('ws-editing'); } catch (e) { /* bo'sh */ }
})();
