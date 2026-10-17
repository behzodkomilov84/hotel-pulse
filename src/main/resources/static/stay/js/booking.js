/* ---------- Booking ---------- */
const iso = d => new Date(d.getTime() - d.getTimezoneOffset()*60000).toISOString().slice(0,10);
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate()+n); return x };
const today = new Date(); today.setHours(0,0,0,0);
function fillSelects(){
  ["#bbGuests","#bmGuests"].forEach(s => {
    const el = $(s), v = el.value || "2";
    el.innerHTML = Array.from({length:10}, (_, i) => `<option value="${i+1}">${i+1} ${T("киши","чел.")}</option>`).join("");
    el.value = v;
  });
  ["#bbRoom","#bmRoom"].forEach(s => {
    const el = $(s), v = el.value || "";
    el.innerHTML = `<option value="">${T("Ҳар қандай","Любая")}</option>` + ROOMS.map(r => `<option value="${r.id}">${r.name}</option>`).join("");
    el.value = v;
  });
}
function initDates(){
  [["#bbIn","#bbOut"],["#bmIn","#bmOut"]].forEach(([a,b]) => {
    $(a).min = iso(today); $(b).min = iso(addDays(today,1));
    $(a).value = iso(addDays(today,7)); $(b).value = iso(addDays(today,9));
  });
}
function nights(){ const a = new Date($("#bmIn").value), b = new Date($("#bmOut").value); return Math.round((b-a)/864e5) }
function updateSummary(){
  const box = $("#bmSummary"); if (!box) return;
  const n = nights(), g = +$("#bmGuests").value, r = ROOMS.find(x => x.id === $("#bmRoom").value)
    || ROOMS.find(x => x.cap + x.extra >= g) || ROOMS[ROOMS.length-1];
  const auto = !$("#bmRoom").value;
  let rows = `<dt>${T("Кечалар","Ночей")}</dt><dd>${n > 0 ? n : "—"}</dd>`;
  rows += `<dt>${T("Тоифа","Категория")}</dt><dd>${r.name}${auto ? " · " + T("тавсия","рекомендуем") : ""}</dd>`;
  if (n > 0) rows += `<dt>${T("Тахминий нарх","Ориентир. стоимость")}</dt><dd>${T("","от ")}${fmt(r.price*n)} ${T("сўмдан","сум")}</dd>`;
  box.innerHTML = rows;
}
function openBooking(roomId){
  $("#bmIn").value = $("#bbIn").value; $("#bmOut").value = $("#bbOut").value;
  $("#bmGuests").value = $("#bbGuests").value;
  $("#bmRoom").value = roomId || $("#bbRoom").value || "";
  if (roomId){ const r = ROOMS.find(x => x.id === roomId); if (+$("#bmGuests").value > r.cap + r.extra) $("#bmGuests").value = r.cap }
  $("#bmStatus").hidden = true; $("#bookForm").querySelectorAll(".f").forEach(f => f.classList.remove("invalid"));
  $("#bmSubmit").disabled = false;
  updateSummary();
  $("#bookModal").hidden = false; document.body.style.overflow = "hidden";
  setTimeout(() => $("#bmName").focus(), 30);
}
function closeBooking(){ $("#bookModal").hidden = true; document.body.style.overflow = "" }
document.addEventListener("click", e => {
  const b = e.target.closest("[data-open-booking]"); if (b){ openBooking(b.dataset.openBooking); }
  if (e.target.closest("[data-close]") || e.target.id === "bookModal") closeBooking();
  const c = e.target.closest("[data-copy]");
  if (c){ const t = c.dataset.copy; (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(() => toast(T("Нусхаланди","Скопировано")), () => toast(t)); }
});
document.addEventListener("keydown", e => { if (e.key === "Escape"){ if (!$("#bookModal").hidden) closeBooking(); else if (!$("#chat").hidden) toggleChat(false) } });
$("#bookbar").addEventListener("submit", e => {
  e.preventDefault();
  if ($("#bbOut").value <= $("#bbIn").value) $("#bbOut").value = iso(addDays(new Date($("#bbIn").value), 1));
  openBooking("");
});
$("#bbIn").addEventListener("change", () => { $("#bbOut").min = iso(addDays(new Date($("#bbIn").value),1)); if ($("#bbOut").value <= $("#bbIn").value) $("#bbOut").value = iso(addDays(new Date($("#bbIn").value),2)) });
["#bmIn","#bmOut","#bmGuests","#bmRoom"].forEach(s => $(s).addEventListener("change", updateSummary));
$("#bookForm").addEventListener("submit", async e => {
  e.preventDefault();
  const g = +$("#bmGuests").value, r = ROOMS.find(x => x.id === $("#bmRoom").value);
  let ok = true;
  ok = mark($("#bmIn"), !$("#bmIn").value || $("#bmIn").value < iso(today)) && ok;
  ok = mark($("#bmOut"), !(nights() > 0)) && ok;
  ok = mark($("#bmRoom"), !!r && g > r.cap + r.extra) && ok;
  ok = mark($("#bmName"), $("#bmName").value.trim().length < 2) && ok;
  ok = mark($("#bmPhone"), !phoneOk($("#bmPhone").value)) && ok;
  if (!ok) return;
  const rr = r || ROOMS.find(x => x.cap + x.extra >= g) || ROOMS[4];
  await submitTo($("#bmSubmit"), $("#bmStatus"), {
    _subject: `Chorvoq Stay — bron so'rovi: ${$("#bmName").value.trim()}`,
    "So'rov turi": "Bron (mehmon)",
    "Ism": $("#bmName").value.trim(),
    "Telefon": $("#bmPhone").value.trim(),
    "Kelish": $("#bmIn").value, "Ketish": $("#bmOut").value, "Kechalar": nights(),
    "Mehmonlar": g, "Toifa": (r ? "" : "(tavsiya) ") + rr.name,
    "Taxminiy narx, so'm": fmt(rr.price*nights()),
    "Istaklar": $("#bmNote").value.trim() || "—",
    "Til": LANG.toUpperCase()
  }, T("Раҳмат! Сўровингиз қабул қилинди. Администратор тез орада сиз билан боғланади.","Спасибо! Запрос принят. Администратор скоро свяжется с вами."), () => $("#bookForm").reset());
});
