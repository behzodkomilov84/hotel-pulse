/* ---------- Income (resident) ---------- */
function renderIncome(){
  const max = Math.max(...INCOME.map(x => x.y));
  $("#bars").innerHTML = INCOME.map(x => `
    <div class="bar"><span>${x.n}</span><div class="track"><div class="fill" style="width:${(x.y/max*100).toFixed(1)}%">${dec(x.y)} ${T("млн","млн")}</div></div></div>`).join("");
  $("#incTable").innerHTML = INCOME.map(x => `
    <tr><td>${x.n}</td><td class="n">${x.bud}</td>${x.m.map(v => `<td class="n">${dec(v)}</td>`).join("")}<td class="n">≈ ${x.pb} ${T("ой","мес.")}</td></tr>`).join("");
}
