/* ---------- Narx va sig'im — HotelPulse bazasidan (admin: Бошқарув → Chorvoq Stay → Тоифалар ва нархлар) ----------
   Tavsif va rejalar shu faylda (data/rooms.js) qoladi; API ishlamasa — sayt statik narxlar bilan ishlayveradi. */
(function(){
  fetch("/api/stay/rooms", {headers:{"Accept":"application/json"}})
    .then(r => r.ok ? r.json() : Promise.reject(r.status))
    .then(list => {
      if (!Array.isArray(list) || !list.length) return;
      const byId = Object.fromEntries(list.map(x => [x.id, x]));
      for (let i = ROOMS.length - 1; i >= 0; i--){
        const live = byId[ROOMS[i].id];
        if (!live){ ROOMS.splice(i, 1); continue }            // admin'da yashirilgan toifa
        ROOMS[i].name = live.name || ROOMS[i].name;
        if (live.area) ROOMS[i].area = live.area.replace(/m²/g, "м²");   // sayt faqat kirillcha
        ROOMS[i].cap = live.cap; ROOMS[i].extra = live.extra; ROOMS[i].price = live.price;
      }
      setLang(LANG);
    })
    .catch(() => {});
})();
