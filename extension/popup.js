/* Popup: ao vivo + pausar + resumo de hoje. */
document.getElementById("ver").textContent = "v" + chrome.runtime.getManifest().version;

async function render(full) {
  const all = await chrome.storage.local.get(null);
  const paused = !!all.paused;
  const live = liveNow(all.live);
  const color = live && !paused ? colorOf(live.app) : null;
  document.getElementById("live").innerHTML = `${statusHTML(live, paused)}
    <div class="clock num ${live ? "" : "off"}" ${color ? `style="text-shadow:0 0 22px ${color}55"` : ""}>${clock(live ? live.display : 0)}</div>
    <div class="sub">${live ? "sessão atual" : "abra um vídeo em um dos serviços suportados"}</div>
    ${pauseButtonHTML(paused)}`;
  if (!full) return;
  const n = new Date();
  const S = computeStats(all, n.getFullYear(), n.getMonth() + 1);
  const items = APPS.filter((a) => S.todayByApp[a.name]).sort((a, b) => S.todayByApp[b.name] - S.todayByApp[a.name]);
  document.getElementById("today").innerHTML = ringSVG(S.todayByApp, S.todayTotal, 120, "HOJE", "") +
    `<div class="list">${items.length ? items.map((a) => `<div class="row"><span class="dot" style="background:${a.color}"></span>${a.name}<span class="num">${hm(S.todayByApp[a.name])}</span></div>`).join("")
      : `<span class="t2">Nada assistido hoje.</span>`}</div>`;
  document.getElementById("stats").innerHTML =
    `<div><div class="k">NO MÊS</div><div class="v num">${hm(S.monthTotal)}</div></div>
     <div><div class="k">TOTAL GERAL</div><div class="v num" style="color:var(--accent)">${hm(S.allTotal)}</div></div>`;
}

document.addEventListener("click", async (e) => {
  if (e.target.closest('[data-action="toggle-pause"]')) { await togglePause(); render(false); }
});
render(true);
let n = 0;
setInterval(() => render(++n % 10 === 0), 1000);
