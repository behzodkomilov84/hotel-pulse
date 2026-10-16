/* ---------- Chat ---------- */
const FAQ = [
  {k:["narx","price","цен","стоим","qancha","сколько","tarif","тариф"], uz:()=> "Kechalik narxlar:\n" + ROOMS.map(r=>`• ${r.name} — ${fmt(r.price)} so'mdan`).join("\n") + "\nAniq narx sanalarga bog'liq — bron so'rovi qoldiring, administrator tasdiqlaydi.", ru:()=> "Цены за ночь:\n" + ROOMS.map(r=>`• ${r.name} — от ${fmt(r.price)} сум`).join("\n") + "\nТочная цена зависит от дат — оставьте запрос, администратор подтвердит.", q:{uz:"Narxlar",ru:"Цены"}},
  {k:["kirish","chiqish","vaqt","заезд","выезд","check","время","soat"], uz:()=>"Joylashish — 14:00 dan, chiqish — 12:00 gacha. Erta kirish yoki kech chiqish bo'sh xona bo'lsa kelishiladi.", ru:()=>"Заезд — с 14:00, выезд — до 12:00. Ранний заезд или поздний выезд — по наличию, по согласованию.", q:{uz:"Kirish/chiqish vaqti",ru:"Время заезда"}},
  {k:["qayer","manzil","joylash","yo'l","где","адрес","добрат","как доехать","location","km","км"], uz:()=>"Chorvoq Darvozasi majmuasi, Chorvoq suv omborining birinchi qatori. Toshkentdan taxminan 80 km, avtomobilda ~1,5 soat. Mehmonlar uchun avtoturargoh bor.", ru:()=>"Комплекс Chorvoq Darvozasi, первая линия Чарвакского водохранилища. ≈ 80 км от Ташкента, ~1,5 часа на машине. Для гостей есть парковка.", q:{uz:"Qanday borish mumkin",ru:"Как добраться"}},
  {k:["bron","бронь","заброн","book","band"], uz:()=>"Bron qilish uchun sahifa tepasidagi «Bron qilish» tugmasini bosing yoki shu yerda telefoningizni qoldiring — administrator qo'ng'iroq qiladi.", ru:()=>"Чтобы забронировать, нажмите «Забронировать» вверху страницы или оставьте здесь телефон — администратор перезвонит.", q:{uz:"Qanday bron qilaman",ru:"Как забронировать"}, act:"book"},
  {k:["nonushta","ovqat","завтрак","еда","питан","oshxona","кухн"], uz:()=>"Har bir apartamentda to'liq jihozlangan oshxona bor. Nonushta va boshqa qo'shimcha xizmatlarni administratordan buyurtma qilish mumkin; majmuada restoranlar ham bor.", ru:()=>"В каждом апартаменте полностью оборудованная кухня. Завтраки и доп. услуги можно заказать у администратора; в комплексе есть рестораны.", q:{uz:"Ovqatlanish",ru:"Питание"}},
  {k:["akvapark","basseyn","аквапарк","бассейн","plyaj","пляж"], uz:()=>"Majmuada akvapark, markaziy park va basseyn, sohil bo'yi sayr yo'laklari va sport maydonchalari bor — barchasi piyoda masofada.", ru:()=>"В комплексе аквапарк, центральный парк с бассейном, набережная и спортивные площадки — всё в пешей доступности.", q:{uz:"Akvapark va basseyn",ru:"Аквапарк и бассейн"}},
  {k:["rezident","kvartiram","egasi","собственник","резидент","моя квартира","квартир"], uz:()=>"Chorvoq Darvozasida kvartirangiz bo'lsa, uni apart-hotelga qo'shib, mehmonlar to'lovidan 60% olishingiz mumkin. «Rezident bo'lish» sahifasida so'rovnoma bor.", ru:()=>"Если у вас квартира в Chorvoq Darvozasi, её можно подключить к апарт-отелю и получать 60% выручки. Заявка — на странице «Стать резидентом».", q:{uz:"Kvartiram bor",ru:"У меня есть квартира"}, act:"res"},
  {k:["hayvon","it ","mushuk","животн","собак","кошк","pet"], uz:()=>"Uy hayvonlari bilan yashash administrator bilan oldindan kelishiladi. Telefoningizni qoldiring — aniqlashtirib beramiz.", ru:()=>"Проживание с животными — по предварительному согласованию с администратором. Оставьте телефон — уточним.", q:null, act:"lead"}
];
let chatStarted = false;
function addMsg(text, who){ const d = document.createElement("div"); d.className = "msg " + who; d.textContent = text; $("#msgs").appendChild(d); $("#msgs").scrollTop = 1e6; return d }
function addChips(){
  const w = document.createElement("div"); w.className = "chips";
  FAQ.filter(f => f.q).forEach(f => { const b = document.createElement("button"); b.type = "button"; b.textContent = f.q[LANG]; b.onclick = () => ask(f.q[LANG], f); w.appendChild(b) });
  const b = document.createElement("button"); b.type = "button"; b.textContent = T("Administrator bilan bog'lanish","Связаться с администратором"); b.onclick = () => { addMsg(b.textContent,"me"); leadForm() }; w.appendChild(b);
  $("#msgs").appendChild(w); $("#msgs").scrollTop = 1e6;
}
function ask(text, f){
  addMsg(text, "me");
  if (!f){ const t = text.toLowerCase(); f = FAQ.find(x => x.k.some(k => t.includes(k))) }
  setTimeout(() => {
    if (f){
      addMsg(f[LANG](), "bot");
      if (f.act === "book"){ const w = document.createElement("div"); w.className = "chips"; const b = document.createElement("button"); b.type="button"; b.textContent = T("Bron formasini ochish","Открыть форму брони"); b.onclick = () => { toggleChat(false); openBooking("") }; w.appendChild(b); $("#msgs").appendChild(w) }
      if (f.act === "res"){ const w = document.createElement("div"); w.className = "chips"; const b = document.createElement("button"); b.type="button"; b.textContent = T("So'rovnomaga o'tish","Перейти к заявке"); b.onclick = () => { toggleChat(false); location.hash = "rezident" }; w.appendChild(b); $("#msgs").appendChild(w) }
      if (f.act === "lead") leadForm(text);
    } else {
      addMsg(T("Bu savolga administratorimiz aniq javob beradi. Ismingiz va telefoningizni qoldiring — tez orada bog'lanamiz.","На этот вопрос точно ответит администратор. Оставьте имя и телефон — скоро свяжемся."), "bot");
      leadForm(text);
    }
    $("#msgs").scrollTop = 1e6;
  }, 350);
}
function leadForm(question){
  const f = document.createElement("form"); f.className = "lead-form"; f.noValidate = true;
  f.innerHTML = `<input name="n" placeholder="${T("Ismingiz","Ваше имя")}" autocomplete="name">
    <input name="p" type="tel" value="+998 " autocomplete="tel">
    <textarea name="q" rows="2" placeholder="${T("Savolingiz","Ваш вопрос")}"></textarea>
    <button class="btn btn-gold" type="submit" style="padding:10px 14px;font-size:14px">${T("Yuborish","Отправить")}</button>
    <div class="status" hidden></div>`;
  f.q.value = question || "";
  f.addEventListener("submit", async e => {
    e.preventDefault();
    if (f.n.value.trim().length < 2 || !phoneOk(f.p.value)){ toast(T("Ism va telefon raqamini to'g'ri kiriting","Укажите имя и корректный телефон")); return }
    await submitTo(f.querySelector("button"), f.querySelector(".status"), {
      _subject: `Chorvoq Stay — chatdan savol: ${f.n.value.trim()}`,
      "So'rov turi": "Chat (mehmon savoli)", "Ism": f.n.value.trim(), "Telefon": f.p.value.trim(),
      "Savol": f.q.value.trim() || "—", "Til": LANG.toUpperCase()
    }, T("Rahmat! Administrator tez orada bog'lanadi.","Спасибо! Администратор скоро свяжется."), () => { f.querySelectorAll("input,textarea,button").forEach(x => x.disabled = true) });
  });
  $("#msgs").appendChild(f); $("#msgs").scrollTop = 1e6;
}
function toggleChat(open){
  $("#chat").hidden = !open; $("#chatBtn").setAttribute("aria-expanded", open);
  $("#chatBtn .dot").hidden = true;
  if (open && !chatStarted){ chatStarted = true; addMsg(T("Assalomu alaykum! Chorvoq Stay mehmonlar xizmati. Savolingizni yozing yoki mavzuni tanlang:","Здравствуйте! Служба гостей Chorvoq Stay. Напишите вопрос или выберите тему:"), "bot"); addChips() }
  if (open) setTimeout(() => $("#chatInput").focus(), 30);
}
$("#chatBtn").addEventListener("click", () => toggleChat($("#chat").hidden));
$("#chatClose").addEventListener("click", () => toggleChat(false));
$("#chatSend").addEventListener("submit", e => { e.preventDefault(); const v = $("#chatInput").value.trim(); if (!v) return; $("#chatInput").value = ""; ask(v) });
