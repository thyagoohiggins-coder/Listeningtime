/* Código compartilhado: apps, detecção de sites, armazenamento e estatísticas. */
/* eslint-disable no-unused-vars */

// Mesma ordem e cores do app Android (validadas para daltonismo no fundo escuro).
const APPS = [
  { name: "Netflix", color: "#e66767" },
  { name: "Prime Video", color: "#3987e5" },
  { name: "YouTube", color: "#c98500" },
  { name: "Apple TV", color: "#199e70" },
  { name: "Crunchyroll", color: "#d95926" },
  { name: "HBO Max", color: "#9085e9" },
  { name: "Disney+", color: "#d55181" },
];

// path: só conta nessas páginas (evita trailers automáticos da página inicial).
const SITES = [
  { app: "Netflix", hosts: ["netflix.com"], path: /^\/watch\// },
  { app: "Prime Video", hosts: ["primevideo.com"] },
  { app: "Prime Video", hosts: ["amazon.com", "amazon.com.br"], path: /\/gp\/video\//i },
  { app: "YouTube", hosts: ["youtube.com", "youtube-nocookie.com"], path: /^\/(watch|shorts|live|embed)/ },
  { app: "HBO Max", hosts: ["max.com", "hbomax.com"] },
  { app: "Crunchyroll", hosts: ["crunchyroll.com"] },
  { app: "Apple TV", hosts: ["tv.apple.com"] },
  { app: "Disney+", hosts: ["disneyplus.com"] },
];

function detectApp(loc) {
  const host = loc.hostname.toLowerCase();
  for (const s of SITES) {
    if (!s.hosts.some((h) => host === h || host.endsWith("." + h))) continue;
    if (s.path && !s.path.test(loc.pathname)) continue;
    return s.app;
  }
  return null;
}

function colorOf(name) {
  const a = APPS.find((x) => x.name === name);
  return a ? a.color : "#8b98ae";
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

/** Data local "AAAA-MM-DD". */
function dayKey(d = new Date()) {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** Relógio: 1:02:03 ou 02:03 */
function clock(secs) {
  secs = Math.max(0, Math.floor(secs));
  const h = Math.floor(secs / 3600), m = Math.floor((secs % 3600) / 60), s = secs % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`;
}

/** Duração compacta: 2h 05m, 45m, <1m, 0m */
function hm(secs) {
  secs = Math.floor(secs || 0);
  if (secs > 0 && secs < 60) return "<1m";
  const h = Math.floor(secs / 3600), m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h ${pad2(m)}m` : `${m}m`;
}

/** Converte o storage ("t|AAAA-MM-DD|App" -> segundos) em lista de registros. */
function entriesFrom(all) {
  const out = [];
  for (const [k, v] of Object.entries(all)) {
    const p = k.split("|");
    if (p.length === 3 && p[0] === "t" && typeof v === "number") out.push({ day: p[1], app: p[2], secs: v });
  }
  return out;
}

/** Estatísticas de um mês (month = 1..12) — mesma lógica do app Android. */
function computeStats(all, year, month) {
  const entries = entriesFrom(all);
  const daysInMonth = new Date(year, month, 0).getDate();
  const prefix = `${year}-${pad2(month)}-`;
  const today = dayKey();
  const byDay = Array.from({ length: daysInMonth + 1 }, () => ({}));
  const monthByApp = {}, allByApp = {}, todayByApp = {};
  const allDays = new Set();
  const add = (o, k, v) => (o[k] = (o[k] || 0) + v);
  for (const e of entries) {
    add(allByApp, e.app, e.secs);
    if (e.secs > 0) allDays.add(e.day);
    if (e.day === today) add(todayByApp, e.app, e.secs);
    if (e.day.startsWith(prefix)) {
      const d = parseInt(e.day.slice(8), 10);
      if (d >= 1 && d <= daysInMonth) {
        add(byDay[d], e.app, e.secs);
        add(monthByApp, e.app, e.secs);
      }
    }
  }
  const sum = (o) => Object.values(o).reduce((a, b) => a + b, 0);
  const dayTotal = (d) => sum(byDay[d]);
  let bestDay = null, maxDayTotal = 0, activeDaysInMonth = 0;
  for (let d = 1; d <= daysInMonth; d++) {
    const t = dayTotal(d);
    if (t > 0) activeDaysInMonth++;
    if (t > maxDayTotal) { maxDayTotal = t; bestDay = d; }
  }
  return {
    year, month, daysInMonth, byDay, monthByApp, allByApp, todayByApp, dayTotal,
    monthTotal: sum(monthByApp), allTotal: sum(allByApp), todayTotal: sum(todayByApp),
    activeDaysInMonth, activeDaysAll: allDays.size, bestDay, maxDayTotal,
  };
}

/** Dias seguidos com uso até hoje (se hoje ainda não teve uso, conta até ontem). */
function streak(all) {
  const days = new Set(entriesFrom(all).filter((e) => e.secs > 0).map((e) => e.day));
  const d = new Date();
  if (!days.has(dayKey(d))) d.setDate(d.getDate() - 1);
  let n = 0;
  while (days.has(dayKey(d))) { n++; d.setDate(d.getDate() - 1); }
  return n;
}

/** Considera "ao vivo" se o último tick tem menos de 4 s. */
function liveNow(live) {
  if (!live || !live.app) return null;
  const age = (Date.now() - live.lastTick) / 1000;
  if (age > 4) return null;
  return { ...live, display: live.secs + (live.paused ? 0 : Math.min(age, 2)) };
}
