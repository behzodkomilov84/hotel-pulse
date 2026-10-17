/* ---------- Resident form ---------- */
function areaHint(){
  const a = parseFloat($("#rArea").value), h = $("#areaHint"); if (!h) return;
  if (!a){ h.textContent = ""; return }
  const cat = a < 32 ? "Studio" : a < 52 ? "One-Bedroom Suite" : a < 75 ? "Two-Bedroom Suite" : a < 106 ? "Family Suite" : "Grand Terrace Residence";
  h.textContent = T("Тахминий тоифа: ","Ориентировочная категория: ") + cat;
}
$("#rArea").addEventListener("input", areaHint);
$("#resForm").addEventListener("submit", async e => {
  e.preventDefault();
  const f = e.target, cond = f.querySelector("input[name=cond]:checked");
  let ok = true;
  ok = mark($("#rName"), $("#rName").value.trim().split(/\s+/).length < 2) && ok;
  ok = mark($("#rPhone"), !phoneOk($("#rPhone").value)) && ok;
  ok = mark($("#rBldg"), !$("#rBldg").value.trim()) && ok;
  ok = mark($("#rApt"), !$("#rApt").value.trim()) && ok;
  const a = parseFloat($("#rArea").value);
  ok = mark($("#rArea"), !(a >= 15 && a <= 400)) && ok;
  $("#rCondF").classList.toggle("invalid", !cond); ok = !!cond && ok;
  ok = mark($("#rConsent"), !$("#rConsent").checked) && ok;
  if (!ok){ const first = f.querySelector(".invalid"); first && first.scrollIntoView({block:"center"}); return }
  await submitTo($("#rSubmit"), $("#rStatus"), {
    _subject: `Chorvoq Stay — rezident so'rovi: uy ${$("#rBldg").value.trim()}, kv. ${$("#rApt").value.trim()}`,
    "So'rov turi": "Rezident bo'lish (kvartira egasi)",
    "F.I.Sh.": $("#rName").value.trim(),
    "Telefon": $("#rPhone").value.trim(),
    "Uy raqami": $("#rBldg").value.trim(),
    "Kvartira raqami": $("#rApt").value.trim(),
    "Maydon, m²": String(a).replace(".", ","),
    "Remont holati": cond.value,
    "Izoh": $("#rNote").value.trim() || "—",
    "Til": LANG.toUpperCase()
  }, T("Раҳмат! Сўровномангиз қабул қилинди. Мутахассисимиз 1 иш куни ичида сиз билан боғланади.","Спасибо! Заявка принята. Специалист свяжется с вами в течение 1 рабочего дня."), () => { f.reset(); $("#rPhone").value = "+998 "; areaHint() });
});
