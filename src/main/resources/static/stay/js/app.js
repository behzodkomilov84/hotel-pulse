/* ---------- Init ---------- */
initDates();
let saved = "uz"; try{ saved = localStorage.getItem("cs_lang") || "uz" }catch(e){}
setLang(saved === "ru" ? "ru" : "uz");
route();
