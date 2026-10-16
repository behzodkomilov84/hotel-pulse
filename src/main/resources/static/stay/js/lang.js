/* ---------- Language ---------- */
function setLang(l){
  LANG = l;
  document.documentElement.lang = l;
  document.querySelectorAll(".lang button").forEach(b => b.setAttribute("aria-pressed", b.dataset.lang === l));
  document.querySelectorAll("[data-ph-uz]").forEach(el => el.placeholder = el.dataset["ph" + (l === "ru" ? "Ru" : "Uz")]);
  try{ localStorage.setItem("cs_lang", l) }catch(e){}
  renderRooms(); renderIncome(); fillSelects(); updateSummary(); areaHint();
}
document.querySelectorAll(".lang button").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang)));
