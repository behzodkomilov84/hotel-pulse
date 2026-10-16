/* ---------- Sending: Telegram (asosiy) + e-mail (FormSubmit, zaxira) ---------- */
async function sendTelegram(data){
  const res = await fetch(CONFIG.TELEGRAM_ENDPOINT, {
    method:"POST", headers:{"Content-Type":"application/json","Accept":"application/json"},
    body: JSON.stringify(data)
  });
  const j = await res.json().catch(() => ({}));
  if (!res.ok || !j.ok) throw new Error(j.error || res.status);
  return true;
}
async function sendEmail(data){
  const res = await fetch("https://formsubmit.co/ajax/" + encodeURIComponent(CONFIG.RECIPIENT), {
    method:"POST", headers:{"Content-Type":"application/json","Accept":"application/json"},
    body: JSON.stringify({...data, _template:"table", _captcha:"false"})
  });
  const j = await res.json().catch(() => ({}));
  if (!res.ok || String(j.success) === "false") throw new Error(j.message || res.status);
  return true;
}
async function sendMail(data){
  const r = await Promise.allSettled([sendTelegram(data), sendEmail(data)]);
  if (r.some(x => x.status === "fulfilled")) return true;
  throw new Error("send failed");
}
const inPreview = (() => { try { return window.self !== window.top } catch(e){ return true } })();
async function submitTo(btn, status, data, okMsg, onOk){
  btn.disabled = true; const label = btn.innerHTML; btn.textContent = T("Yuborilmoqda…","Отправляем…");
  status.hidden = true;
  try{
    await sendMail(data);
    status.className = "status ok"; status.textContent = okMsg; onOk && onOk();
  }catch(err){
    if (inPreview){
      status.className = "status ok";
      status.textContent = okMsg + " " + T("(Ko'rib chiqish rejimi: saytingiz domenida joylashtirilganda so'rov pochtaga yuboriladi.)","(Режим предпросмотра: на вашем домене заявка уйдёт на почту.)");
      onOk && onOk();
    } else {
      status.className = "status bad";
      status.textContent = T("So'rov yuborilmadi. Internetni tekshirib, qayta urinib ko'ring yoki qo'ng'iroq qiling: ","Заявка не отправилась. Проверьте интернет и повторите или позвоните: ") + CONFIG.PHONE;
    }
  }
  status.hidden = false; btn.innerHTML = label; btn.disabled = false;
}
