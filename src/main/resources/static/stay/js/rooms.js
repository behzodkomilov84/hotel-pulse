/* ---------- Rooms ---------- */
function renderRooms(){
  $("#rooms").innerHTML = ROOMS.map(r => `
    <article class="room">
      <div class="plan"><img src="${r.plan}" alt="${r.name} — ${T("режа","планировка")}" loading="lazy"></div>
      <div class="body">
        <h3>${r.name}</h3>
        <div class="tags"><span>${r.area}</span><span>${T("Сиғим","Вместимость")}: ${r.cap}${r.extra ? " + " + r.extra : ""}</span><span>${r.who[LANG]}</span></div>
        <ul>${r[LANG].map(x => `<li>${x}</li>`).join("")}</ul>
        <div class="foot">
          <div class="price"><small>${T("кечаси","за ночь от")}</small><b>${fmt(r.price)} <em>${T("сўмдан","сум")}</em></b></div>
          <button class="btn btn-ink" type="button" data-open-booking="${r.id}">${T("Брон қилиш","Забронировать")}</button>
        </div>
      </div>
    </article>`).join("");
}
