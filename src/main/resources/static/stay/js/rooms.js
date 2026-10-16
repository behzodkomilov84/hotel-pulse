/* ---------- Rooms ---------- */
function renderRooms(){
  $("#rooms").innerHTML = ROOMS.map(r => `
    <article class="room">
      <div class="plan"><img src="${r.plan}" alt="${r.name} — ${T("reja","планировка")}" loading="lazy"></div>
      <div class="body">
        <h3>${r.name}</h3>
        <div class="tags"><span>${r.area}</span><span>${T("Sig'im","Вместимость")}: ${r.cap}${r.extra ? " + " + r.extra : ""}</span><span>${r.who[LANG]}</span></div>
        <ul>${r[LANG].map(x => `<li>${x}</li>`).join("")}</ul>
        <div class="foot">
          <div class="price"><small>${T("kechasi","за ночь от")}</small><b>${fmt(r.price)} <em>${T("so'mdan","сум")}</em></b></div>
          <button class="btn btn-ink" type="button" data-open-booking="${r.id}">${T("Bron qilish","Забронировать")}</button>
        </div>
      </div>
    </article>`).join("");
}
