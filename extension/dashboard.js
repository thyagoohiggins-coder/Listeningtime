/* Painel completo da extensão. */
const MONTHS = ["JANEIRO", "FEVEREIRO", "MARÇO", "ABRIL", "MAIO", "JUNHO", "JULHO", "AGOSTO", "SETEMBRO", "OUTUBRO", "NOVEMBRO", "DEZEMBRO"];
const WD = ["DOM", "SEG", "TER", "QUA", "QUI", "SEX", "SÁB"];
const WD1 = ["D", "S", "T", "Q", "Q", "S", "S"];

const now0 = new Date();
let year = now0.getFullYear(), month = now0.getMonth() + 1, selected = now0.getDate();
let S = null, ALL = {}, paused = false, live = null;
const app = document.getElementById("app");
const tip = document.getElementById("tip");

const isCurrentMonth = () => { const n = new Date(); return n.getFullYear() === year && n.getMonth() + 1 === month; };
const todayDay = () => (isCurrentMonth() ? new Date().getDate() : 0);
const sel = (q) => app.querySelector(q);

async function load() {
  ALL = await chrome.storage.local.get(null);
  paused = !!ALL.paused;
  live = liveNow(ALL.live);
  S = computeStats(ALL, year, month);
  if (selected > S.daysInMonth) selected = 1;
}

function heatLevel(secs) {
  if (!secs || !S.maxDayTotal) return 0;
  return Math.max(1, Math.ceil((secs / S.maxDayTotal) * 5));
}

// ---------------------------------------------------------------- seções

function heroHTML() {
  const items = APPS.filter((a) => S.todayByApp[a.name]).sort((a, b) => S.todayByApp[b.name] - S.todayByApp[a.name]);
  const list = items.length
    ? items.map((a) => `<div class="row"><span class="dot" style="background:${a.color}"></span><span>${a.name}</span>
        <span class="num">${hm(S.todayByApp[a.name])}<span class="pct">${Math.round((S.todayByApp[a.name] / S.todayTotal) * 100)}%</span></span></div>`).join("")
    : `<div class="t2" style="font-size:12px">Nada assistido hoje ainda.</div>`;
  return `<section class="hud hero">
    <div class="label"><b>01</b> Hoje</div>
    <div class="ring-wrap">${ringSVG(S.todayByApp, S.todayTotal, 230)}<div class="today-list">${list}</div></div>
    ${gaugesHTML()}
    <div class="live-box" id="live-box">${liveHTML()}</div>
  </section>`;
}

function gaugesHTML() {
  // média diária do mês atual, sem contar hoje
  const n = new Date();
  const cur = computeStats(ALL, n.getFullYear(), n.getMonth() + 1);
  const prevDays = [];
  for (let d = 1; d < n.getDate(); d++) if (cur.dayTotal(d) > 0) prevDays.push(cur.dayTotal(d));
  const avg = prevDays.length ? prevDays.reduce((a, b) => a + b, 0) / prevDays.length : 0;
  const pct = avg ? Math.round((cur.todayTotal / avg) * 100) : null;
  const filled = pct === null ? 0 : Math.min(10, Math.round(pct / 10));
  const st = streak(ALL);
  const week = [];
  for (let i = 6; i >= 0; i--) { const d = new Date(); d.setDate(d.getDate() - i); week.push(entriesFrom(ALL).some((e) => e.day === dayKey(d) && e.secs > 0)); }
  return `<div class="gauges">
    <div class="gauge"><div class="k">Hoje vs. média</div><div class="v num">${pct === null ? "—" : pct + "%"}</div>
      <div class="blocks">${Array.from({ length: 10 }, (_, i) => `<i class="${i < filled ? "on" : ""}"></i>`).join("")}</div>
      <div class="s">média ${hm(avg)} por dia ativo</div></div>
    <div class="gauge"><div class="k">Sequência</div><div class="v num">${st} ${st === 1 ? "dia" : "dias"}</div>
      <div class="blocks">${week.map((on) => `<i class="${on ? "on" : ""}"></i>`).join("")}</div>
      <div class="s">últimos 7 dias</div></div>
  </div>`;
}

function liveHTML() {
  const secs = live ? live.display : 0;
  const color = live && !paused ? colorOf(live.app) : null;
  return `${statusHTML(live, paused)}
    <div class="clock num ${live ? "" : "off"}" ${color ? `style="text-shadow:0 0 24px ${color}55"` : ""}>${clock(secs)}</div>
    <div class="live-sub">${live ? "sessão atual" : "o cronômetro inicia quando um vídeo começa a tocar"}</div>
    ${pauseButtonHTML(paused)}`;
}

function totalsHTML() {
  const avg = S.activeDaysInMonth ? S.monthTotal / S.activeDaysInMonth : 0;
  return `<section class="hud totals">
    <div class="label"><b>02</b> Acumulado
      <span class="monthnav"><button data-action="prev" aria-label="Mês anterior">‹</button>
      <span class="m">${MONTHS[month - 1]} ${year}</span>
      <button data-action="next" aria-label="Próximo mês" ${isCurrentMonth() ? "disabled" : ""}>›</button></span>
    </div>
    <div class="readouts">
      <div class="readout big"><div class="k">Total geral</div><div class="v num">${hm(S.allTotal)}</div><div class="s">${S.activeDaysAll} dias com uso</div></div>
      <div class="readout"><div class="k">No mês</div><div class="v num">${hm(S.monthTotal)}</div><div class="s">${S.activeDaysInMonth} dias ativos</div></div>
      <div class="readout"><div class="k">Média/dia</div><div class="v num">${hm(avg)}</div><div class="s">por dia ativo</div></div>
      <div class="readout"><div class="k">Recorde</div><div class="v num">${S.bestDay ? hm(S.maxDayTotal) : "—"}</div><div class="s">${S.bestDay ? `dia ${pad2(S.bestDay)}` : "sem dados"}</div></div>
    </div>
  </section>`;
}

function calHTML() {
  const first = new Date(year, month - 1, 1).getDay();
  const n = new Date();
  let cells = WD1.map((w) => `<div class="wd">${w}</div>`).join("");
  for (let i = 0; i < first; i++) cells += `<div class="cell empty"></div>`;
  for (let d = 1; d <= S.daysInMonth; d++) {
    const t = S.dayTotal(d);
    const future = new Date(year, month - 1, d) > n;
    const cls = ["cell", `l${heatLevel(t)}`, d === todayDay() ? "today" : "", d === selected ? "sel" : "", future ? "future" : ""].join(" ");
    cells += `<div class="${cls}" data-day="${d}" ${future ? "" : 'tabindex="0"'}><span class="d">${pad2(d)}</span><span class="h num">${hmTiny(t)}</span></div>`;
  }
  return `<section class="hud cal">
    <div class="label"><b>03</b> Calendário · horas por dia</div>
    <div class="calgrid">${cells}</div>
    <div class="scale">MENOS <i style="background:var(--panel-2)"></i><i style="background:var(--heat-1)"></i><i style="background:var(--heat-2)"></i><i style="background:var(--heat-3)"></i><i style="background:var(--heat-4)"></i><i style="background:var(--heat-5)"></i> MAIS
      <span style="margin-left:auto">máx. ${hm(S.maxDayTotal)}</span></div>
    <div class="daydetail" id="daydetail">${dayDetailHTML(selected)}</div>
  </section>`;
}

function dayDetailHTML(d) {
  const date = new Date(year, month - 1, d);
  const items = APPS.filter((a) => S.byDay[d][a.name]).map((a) => ({ name: a.name, color: a.color, v: S.byDay[d][a.name] }));
  const total = S.dayTotal(d);
  return `<div class="head"><span>${WD[date.getDay()]} ${pad2(d)}/${pad2(month)}</span><span class="num">${hm(total)}</span></div>
    ${total ? stackBarHTML(items, total, 8) : ""}
    ${items.length ? items.map((p) => `<div class="row"><span class="dot" style="background:${p.color}"></span><span>${p.name}</span><span class="num">${hm(p.v)}</span></div>`).join("")
      : `<div class="t2" style="font-size:12px">Sem registros neste dia.</div>`}`;
}

function timelineHTML() {
  const present = APPS.filter((a) => S.monthByApp[a.name]);
  return `<section class="hud timeline">
    <div class="label"><b>04</b> Linha do tempo · ${MONTHS[month - 1].toLowerCase()}</div>
    <div class="chart" id="chart"></div>
    ${present.length > 1 ? `<div class="legend">${present.map((a) => `<span><i class="dot" style="background:${a.color}"></i>${a.name}</span>`).join("")}</div>` : ""}
  </section>`;
}

function drawChart() {
  const host = document.getElementById("chart");
  if (!host) return;
  const W = Math.max(300, host.clientWidth), H = 220, padL = 34, padB = 22, padT = 8;
  const plotW = W - padL, plotH = H - padB - padT, base = H - padB;
  const yMax = Math.max(1, Math.ceil(S.maxDayTotal / 3600));
  const step = yMax <= 4 ? 1 : yMax <= 8 ? 2 : yMax <= 16 ? 4 : 6;
  const slot = plotW / S.daysInMonth, bw = Math.max(3, slot * 0.56);
  let g = "";
  for (let h = 0; h <= yMax; h += step) {
    const y = base - (plotH * h) / yMax;
    g += `<line x1="${padL}" x2="${W}" y1="${y}" y2="${y}" stroke="var(--line)" ${h ? 'stroke-dasharray="2 4"' : ""}/>
      <text x="0" y="${y + 4}" fill="var(--muted)" font-size="10">${h}h</text>`;
  }
  let bars = "";
  for (let d = 1; d <= S.daysInMonth; d++) {
    const cx = padL + slot * (d - 0.5);
    if (d === selected) bars += `<rect x="${cx - slot / 2}" y="${padT}" width="${slot}" height="${plotH}" fill="rgba(61,214,245,.07)"/>`;
    const segs = APPS.filter((a) => S.byDay[d][a.name] > 0);
    let top = base;
    segs.forEach((a, i) => {
      const h = (plotH * (S.byDay[d][a.name] / 3600)) / yMax;
      const y = top - h;
      const last = i === segs.length - 1;
      const r = Math.min(3, h / 2, bw / 2);
      bars += last
        ? `<path d="M${cx - bw / 2},${top} V${y + r} Q${cx - bw / 2},${y} ${cx - bw / 2 + r},${y} H${cx + bw / 2 - r} Q${cx + bw / 2},${y} ${cx + bw / 2},${y + r} V${top} Z" fill="${a.color}"/>`
        : `<rect x="${cx - bw / 2}" y="${y}" width="${bw}" height="${h}" fill="${a.color}"/>`;
      top = y - 2;
    });
    if (d === 1 || d % 5 === 0 || d === todayDay() || d === selected) {
      const col = d === selected ? "var(--text)" : d === todayDay() ? "var(--accent)" : "var(--muted)";
      bars += `<text x="${cx}" y="${H - 5}" fill="${col}" font-size="10" text-anchor="middle">${d}</text>`;
    }
    bars += `<rect class="hit" data-day="${d}" x="${cx - slot / 2}" y="0" width="${slot}" height="${H}" fill="transparent"/>`;
  }
  host.innerHTML = `<svg viewBox="0 0 ${W} ${H}" height="${H}" role="img" aria-label="Horas por dia do mês, empilhadas por app">${g}${bars}</svg>`;
}

function rankHTML() {
  const rows = [...APPS].sort((a, b) => (S.monthByApp[b.name] || 0) - (S.monthByApp[a.name] || 0) || (S.allByApp[b.name] || 0) - (S.allByApp[a.name] || 0));
  const max = Math.max(1, ...rows.map((a) => S.monthByApp[a.name] || 0));
  return `<section class="hud rank">
    <div class="label"><b>05</b> Ranking do mês</div>
    ${rows.map((a, i) => {
      const m = S.monthByApp[a.name] || 0;
      const share = S.monthTotal ? Math.round((m / S.monthTotal) * 100) : 0;
      return `<div class="rank-row ${m || S.allByApp[a.name] ? "" : "zero"}">
        <span class="n num">${pad2(i + 1)}</span>
        <span class="name"><span class="dot" style="background:${a.color}"></span>${a.name}</span>
        <span class="val num">${hm(m)}</span>
        <div class="meta">${stackBarHTML([{ name: a.name, color: a.color, v: m }], max, 4)}
          <span class="num">${share}% · hoje ${hm(S.todayByApp[a.name])} · total ${hm(S.allByApp[a.name])}</span></div>
      </div>`;
    }).join("")}
  </section>`;
}

function daysHTML() {
  const rows = [];
  for (let d = S.daysInMonth; d >= 1; d--) {
    const t = S.dayTotal(d);
    if (!t) continue;
    const parts = APPS.map((a) => ({ name: a.name, color: a.color, v: S.byDay[d][a.name] || 0 }));
    rows.push(`<div class="log-row ${d === selected ? "sel" : ""}" data-day="${d}">
      <span class="dn num">${pad2(d)}</span><span class="wd">${WD[new Date(year, month - 1, d).getDay()]}</span>
      ${stackBarHTML(parts, S.maxDayTotal, 6)}<span class="t num">${hm(t)}</span></div>`);
  }
  return `<section class="hud days">
    <div class="label"><b>06</b> Registro diário</div>
    <div class="log">${rows.join("") || `<div class="t2" style="font-size:12px">Nenhum registro neste mês.</div>`}</div>
  </section>`;
}

function sysHTML() {
  const notif = ALL.notifications !== false;
  return `<section class="hud sys" style="--hud-c: var(--line-hi)">
    <div class="label"><b>07</b> Sistema</div>
    <div class="sys-row">
      <label class="switch"><input type="checkbox" data-action="notif" ${notif ? "checked" : ""}> Notificação ao começar um vídeo</label>
      <span class="t2" style="font-size:12px">Atalho pausar/retomar: <kbd>Alt</kbd> + <kbd>Shift</kbd> + <kbd>P</kbd></span>
      <span style="margin-left:auto"></span>
      <button class="btn" data-action="export">Exportar dados (JSON)</button>
    </div>
    <div class="muted" style="font-size:11px;margin-top:12px">v${chrome.runtime.getManifest().version} · os dados ficam apenas neste navegador</div>
  </section>`;
}

// ---------------------------------------------------------------- render

function renderTopStatus() {
  document.getElementById("top-status").innerHTML = statusHTML(live, paused);
}

async function render() {
  await load();
  app.innerHTML = heroHTML() + totalsHTML() + calHTML() + timelineHTML() + rankHTML() + daysHTML() + sysHTML();
  drawChart();
  renderTopStatus();
}

function selectDay(d) {
  selected = d;
  app.querySelectorAll(".cell.sel, .log-row.sel").forEach((e) => e.classList.remove("sel"));
  app.querySelectorAll(`[data-day="${d}"]`).forEach((e) => { if (!e.classList.contains("hit")) e.classList.add("sel"); });
  document.getElementById("daydetail").innerHTML = dayDetailHTML(d);
  drawChart();
}

function showTip(d, x, y) {
  const items = APPS.filter((a) => S.byDay[d][a.name]);
  tip.innerHTML = `<div class="tip-h">${WD[new Date(year, month - 1, d).getDay()]} ${pad2(d)}/${pad2(month)} · ${hm(S.dayTotal(d))}</div>` +
    (items.length ? items.map((a) => `<div class="row"><span class="dot" style="background:${a.color}"></span>${a.name}<span class="num">${hm(S.byDay[d][a.name])}</span></div>`).join("")
      : `<div class="row">sem registros</div>`);
  tip.style.display = "block";
  const r = tip.getBoundingClientRect();
  tip.style.left = Math.min(window.innerWidth - r.width - 8, x + 14) + "px";
  tip.style.top = Math.max(8, y - r.height - 12) + "px";
}

app.addEventListener("click", async (e) => {
  const a = e.target.closest("[data-action]");
  if (a) {
    const act = a.dataset.action;
    if (act === "toggle-pause") { await togglePause(); }
    else if (act === "prev" || act === "next") {
      const dt = new Date(year, month - 1 + (act === "prev" ? -1 : 1), 1);
      year = dt.getFullYear(); month = dt.getMonth() + 1;
      selected = isCurrentMonth() ? new Date().getDate() : 1;
      render();
    } else if (act === "export") {
      const blob = new Blob([JSON.stringify(ALL, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      Object.assign(document.createElement("a"), { href: url, download: `listening-time-${dayKey()}.json` }).click();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
    }
    return;
  }
  const day = e.target.closest("[data-day]");
  if (day && !day.classList.contains("future")) selectDay(+day.dataset.day);
});
app.addEventListener("change", (e) => {
  if (e.target.dataset.action === "notif") chrome.storage.local.set({ notifications: e.target.checked });
});
app.addEventListener("keydown", (e) => {
  const day = e.target.closest(".cell[data-day]");
  if (day && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); selectDay(+day.dataset.day); }
});
app.addEventListener("mousemove", (e) => {
  const t = e.target.closest(".hit, .cell[data-day]");
  if (t) showTip(+t.dataset.day, e.clientX, e.clientY); else tip.style.display = "none";
});
app.addEventListener("mouseleave", () => (tip.style.display = "none"));
window.addEventListener("resize", drawChart);

// Atualizações ao vivo
let lastFull = Date.now();
chrome.storage.onChanged.addListener((ch) => {
  if ("paused" in ch) { render(); return; }
  if (Date.now() - lastFull > 10_000 && Object.keys(ch).some((k) => k.startsWith("t|"))) { lastFull = Date.now(); render(); }
});
setInterval(async () => {
  const st = await chrome.storage.local.get(["live", "paused"]);
  const wasLive = !!live;
  live = liveNow(st.live); paused = !!st.paused;
  if (wasLive !== !!live) { render(); return; }
  const box = document.getElementById("live-box");
  if (box && !box.matches(":hover")) box.innerHTML = liveHTML();
  renderTopStatus();
}, 1000);
render();
