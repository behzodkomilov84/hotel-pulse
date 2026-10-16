/* ---------- Router & menu ---------- */
const HOME_IDS = ["top","xonalar","aksiyalar","elonlar","majmua","jamoa","aloqa"];
function route(){
  const h = location.hash.slice(1);
  const res = h === "rezident" || h === "ariza";
  $("#viewHome").hidden = res; $("#viewRes").hidden = !res;
  $("#navRes").toggleAttribute("aria-current", res); if (res) $("#navRes").setAttribute("aria-current","page");
  $("#nav").classList.remove("open"); $("#burger").setAttribute("aria-expanded","false");
  if (h === "rezident") window.scrollTo(0,0);
  else if (h){ const el = document.getElementById(h); el && requestAnimationFrame(() => el.scrollIntoView()) }
  else window.scrollTo(0,0);
}
window.addEventListener("hashchange", route);
$("#burger").addEventListener("click", () => { const o = $("#nav").classList.toggle("open"); $("#burger").setAttribute("aria-expanded", o) });
