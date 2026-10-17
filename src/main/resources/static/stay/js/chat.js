/* ---------- Chat ---------- */
const FAQ = [
  {k:["narx","price","цен","стоим","qancha","сколько","tarif","тариф","нарх","қанча"], uz:()=> "Кечалик нархлар:\n" + ROOMS.map(r=>`• ${r.name} — ${fmt(r.price)} сўмдан`).join("\n") + "\nАниқ нарх саналарга боғлиқ — брон сўрови қолдиринг, администратор тасдиқлайди.", ru:()=> "Цены за ночь:\n" + ROOMS.map(r=>`• ${r.name} — от ${fmt(r.price)} сум`).join("\n") + "\nТочная цена зависит от дат — оставьте запрос, администратор подтвердит.", q:{uz:"Нархлар",ru:"Цены"}},
  {k:["kirish","chiqish","vaqt","заезд","выезд","check","время","soat","кириш","чиқиш","вақт","соат"], uz:()=>"Жойлашиш — 14:00 дан, чиқиш — 12:00 гача. Эрта кириш ёки кеч чиқиш бўш хона бўлса келишилади.", ru:()=>"Заезд — с 14:00, выезд — до 12:00. Ранний заезд или поздний выезд — по наличию, по согласованию.", q:{uz:"Кириш/чиқиш вақти",ru:"Время заезда"}},
  {k:["qayer","manzil","joylash","yo'l","где","адрес","добрат","как доехать","location","km","км","қаер","манзил","жойлаш","йўл"], uz:()=>"Чорвоқ Дарвозаси мажмуаси, Чорвоқ сув омборининг биринчи қатори. Тошкентдан тахминан 80 км, автомобилда ~1,5 соат. Меҳмонлар учун автотураргоҳ бор.", ru:()=>"Комплекс Chorvoq Darvozasi, первая линия Чарвакского водохранилища. ≈ 80 км от Ташкента, ~1,5 часа на машине. Для гостей есть парковка.", q:{uz:"Қандай бориш мумкин",ru:"Как добраться"}},
  {k:["bron","бронь","заброн","book","band","брон","банд"], uz:()=>"Брон қилиш учун саҳифа тепасидаги «Брон қилиш» тугмасини босинг ёки шу ерда телефонингизни қолдиринг — администратор қўнғироқ қилади.", ru:()=>"Чтобы забронировать, нажмите «Забронировать» вверху страницы или оставьте здесь телефон — администратор перезвонит.", q:{uz:"Қандай брон қиламан",ru:"Как забронировать"}, act:"book"},
  {k:["nonushta","ovqat","завтрак","еда","питан","oshxona","кухн","нонушта","овқат","ошхона"], uz:()=>"Ҳар бир апартаментда тўлиқ жиҳозланган ошхона бор. Нонушта ва бошқа қўшимча хизматларни администратордан буюртма қилиш мумкин; мажмуада ресторанлар ҳам бор.", ru:()=>"В каждом апартаменте полностью оборудованная кухня. Завтраки и доп. услуги можно заказать у администратора; в комплексе есть рестораны.", q:{uz:"Овқатланиш",ru:"Питание"}},
  {k:["akvapark","basseyn","аквапарк","бассейн","plyaj","пляж"], uz:()=>"Мажмуада аквапарк, марказий парк ва бассейн, соҳил бўйи сайр йўлаклари ва спорт майдончалари бор — барчаси пиёда масофада.", ru:()=>"В комплексе аквапарк, центральный парк с бассейном, набережная и спортивные площадки — всё в пешей доступности.", q:{uz:"Аквапарк ва бассейн",ru:"Аквапарк и бассейн"}},
  {k:["rezident","kvartiram","egasi","собственник","резидент","моя квартира","квартир","квартирам","эгаси"], uz:()=>"Чорвоқ Дарвозасида квартирангиз бўлса, уни апарт-отелга қўшиб, меҳмонлар тўловидан 60% олишингиз мумкин. «Резидент бўлиш» саҳифасида сўровнома бор.", ru:()=>"Если у вас квартира в Chorvoq Darvozasi, её можно подключить к апарт-отелю и получать 60% выручки. Заявка — на странице «Стать резидентом».", q:{uz:"Квартирам бор",ru:"У меня есть квартира"}, act:"res"},
  {k:["hayvon","it ","mushuk","животн","собак","кошк","pet","ҳайвон","ит ","мушук"], uz:()=>"Уй ҳайвонлари билан яшаш администратор билан олдиндан келишилади. Телефонингизни қолдиринг — аниқлаштириб берамиз.", ru:()=>"Проживание с животными — по предварительному согласованию с администратором. Оставьте телефон — уточним.", q:null, act:"lead"}
];
let chatStarted = false;
function addMsg(text, who){ const d = document.createElement("div"); d.className = "msg " + who; d.textContent = text; $("#msgs").appendChild(d); $("#msgs").scrollTop = 1e6; return d }
function addChips(){
  const w = document.createElement("div"); w.className = "chips";
  FAQ.filter(f => f.q).forEach(f => { const b = document.createElement("button"); b.type = "button"; b.textContent = f.q[LANG]; b.onclick = () => ask(f.q[LANG], f); w.appendChild(b) });
  const b = document.createElement("button"); b.type = "button"; b.textContent = T("Администратор билан боғланиш","Связаться с администратором"); b.onclick = () => { addMsg(b.textContent,"me"); leadForm() }; w.appendChild(b);
  $("#msgs").appendChild(w); $("#msgs").scrollTop = 1e6;
}
function ask(text, f){
  addMsg(text, "me");
  if (!f){ const t = text.toLowerCase(); f = FAQ.find(x => x.k.some(k => t.includes(k))) }
  setTimeout(() => {
    if (f){
      addMsg(f[LANG](), "bot");
      if (f.act === "book"){ const w = document.createElement("div"); w.className = "chips"; const b = document.createElement("button"); b.type="button"; b.textContent = T("Брон формасини очиш","Открыть форму брони"); b.onclick = () => { toggleChat(false); openBooking("") }; w.appendChild(b); $("#msgs").appendChild(w) }
      if (f.act === "res"){ const w = document.createElement("div"); w.className = "chips"; const b = document.createElement("button"); b.type="button"; b.textContent = T("Сўровномага ўтиш","Перейти к заявке"); b.onclick = () => { toggleChat(false); location.hash = "rezident" }; w.appendChild(b); $("#msgs").appendChild(w) }
      if (f.act === "lead") leadForm(text);
    } else {
      addMsg(T("Бу саволга администраторимиз аниқ жавоб беради. Исмингиз ва телефонингизни қолдиринг — тез орада боғланамиз.","На этот вопрос точно ответит администратор. Оставьте имя и телефон — скоро свяжемся."), "bot");
      leadForm(text);
    }
    $("#msgs").scrollTop = 1e6;
  }, 350);
}
function leadForm(question){
  const f = document.createElement("form"); f.className = "lead-form"; f.noValidate = true;
  f.innerHTML = `<input name="n" placeholder="${T("Исмингиз","Ваше имя")}" autocomplete="name">
    <input name="p" type="tel" value="+998 " autocomplete="tel">
    <textarea name="q" rows="2" placeholder="${T("Саволингиз","Ваш вопрос")}"></textarea>
    <button class="btn btn-gold" type="submit" style="padding:10px 14px;font-size:14px">${T("Юбориш","Отправить")}</button>
    <div class="status" hidden></div>`;
  f.q.value = question || "";
  f.addEventListener("submit", async e => {
    e.preventDefault();
    if (f.n.value.trim().length < 2 || !phoneOk(f.p.value)){ toast(T("Исм ва телефон рақамини тўғри киритинг","Укажите имя и корректный телефон")); return }
    await submitTo(f.querySelector("button"), f.querySelector(".status"), {
      _subject: `Chorvoq Stay — chatdan savol: ${f.n.value.trim()}`,
      "So'rov turi": "Chat (mehmon savoli)", "Ism": f.n.value.trim(), "Telefon": f.p.value.trim(),
      "Savol": f.q.value.trim() || "—", "Til": LANG.toUpperCase()
    }, T("Раҳмат! Администратор тез орада боғланади.","Спасибо! Администратор скоро свяжется."), () => { f.querySelectorAll("input,textarea,button").forEach(x => x.disabled = true) });
  });
  $("#msgs").appendChild(f); $("#msgs").scrollTop = 1e6;
}
function toggleChat(open){
  $("#chat").hidden = !open; $("#chatBtn").setAttribute("aria-expanded", open);
  $("#chatBtn .dot").hidden = true;
  if (open && !chatStarted){ chatStarted = true; addMsg(T("Ассалому алайкум! Chorvoq Stay меҳмонлар хизмати. Саволингизни ёзинг ёки мавзуни танланг:","Здравствуйте! Служба гостей Chorvoq Stay. Напишите вопрос или выберите тему:"), "bot"); addChips() }
  if (open) setTimeout(() => $("#chatInput").focus(), 30);
}
$("#chatBtn").addEventListener("click", () => toggleChat($("#chat").hidden));
$("#chatClose").addEventListener("click", () => toggleChat(false));
$("#chatSend").addEventListener("submit", e => { e.preventDefault(); const v = $("#chatInput").value.trim(); if (!v) return; $("#chatInput").value = ""; ask(v) });
