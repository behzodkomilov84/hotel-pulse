let LANG = "uz";
const T = (uz, ru) => LANG === "ru" ? ru : uz;
const $ = s => document.querySelector(s);
const fmt = n => n.toLocaleString("ru-RU").replace(/ /g, " ");
const dec = n => n.toFixed(1).replace(".", ",");
const phoneOk = v => v.replace(/\D/g,"").length >= 12;
function mark(el, bad){ el.closest(".f").classList.toggle("invalid", bad); return !bad }
function toast(t){ const d = document.createElement("div"); d.className = "toast"; d.textContent = t; document.body.appendChild(d); setTimeout(() => d.remove(), 1800) }
