/* Background: soma o tempo, guarda por dia/app, badge no ícone, notificação e pausa. */
if (typeof APPS === "undefined" && typeof importScripts === "function") importScripts("shared.js");

const GRACE_MS = 60_000; // pausas menores que isso continuam a mesma sessão
const NOTIF_ID = "lt-session";

let session = null; // { app, secs, carry, lastTick }
let lastAccept = 0;
let badgeTimer = null;
let queue = Promise.resolve();

// Serializa tudo para não haver condição de corrida no storage.
function enqueue(fn) {
  queue = queue.then(fn).catch((e) => console.error(e));
  return queue;
}

async function isPaused() {
  return !!(await chrome.storage.local.get("paused")).paused;
}

async function addSecs(app, secs) {
  if (secs <= 0) return;
  const k = `t|${dayKey()}|${app}`;
  const cur = (await chrome.storage.local.get(k))[k] || 0;
  await chrome.storage.local.set({ [k]: cur + secs });
}

async function totalsFor(app) {
  const all = await chrome.storage.local.get(null);
  const now = new Date();
  const s = computeStats(all, now.getFullYear(), now.getMonth() + 1);
  return { today: s.todayByApp[app] || 0, month: s.monthByApp[app] || 0 };
}

async function restoreSession() {
  if (session) return;
  const { live } = await chrome.storage.local.get("live");
  if (live && Date.now() - live.lastTick < GRACE_MS) {
    session = { app: live.app, secs: live.secs, carry: 0, lastTick: live.lastTick };
  }
}

async function onTick(app, dt) {
  const { disabledApps = [] } = await chrome.storage.local.get("disabledApps");
  if (disabledApps.includes(app)) return; // contagem desligada para este app
  await restoreSession();
  const now = Date.now();
  // Duas abas tocando ao mesmo tempo não contam em dobro.
  const real = lastAccept ? Math.max(0, Math.min(dt, (now - lastAccept) / 1000)) : dt;
  lastAccept = now;
  const paused = await isPaused();

  const continuing = session && session.app === app && now - session.lastTick < GRACE_MS;
  if (!continuing) session = { app, secs: 0, carry: 0, lastTick: now };
  else if (!paused) session.carry += real;
  session.lastTick = now;

  const whole = Math.floor(session.carry);
  if (whole > 0) {
    session.carry -= whole;
    session.secs += whole;
    await addSecs(app, whole);
  }
  await chrome.storage.local.set({ live: { app, secs: session.secs, lastTick: now, paused } });
  setBadge(app, session.secs, paused);
  if (!continuing) await notify(app, paused);
}

function setBadge(app, secs, paused) {
  const m = Math.floor(secs / 60);
  const text = paused ? "II" : secs < 3600 ? `${m}m` : `${Math.floor(secs / 3600)}:${pad2(m % 60)}`;
  chrome.action.setBadgeText({ text });
  chrome.action.setBadgeBackgroundColor({ color: paused ? "#5b6880" : colorOf(app) });
  try { chrome.action.setBadgeTextColor({ color: "#ffffff" }); } catch (_) { /* Firefox antigo */ }
  chrome.action.setTitle({ title: `Listening Time — ${app} ${paused ? "(contagem pausada)" : clock(secs)}` });
  clearTimeout(badgeTimer);
  badgeTimer = setTimeout(() => chrome.action.setBadgeText({ text: "" }), 4000);
}

async function notify(app, paused) {
  const { notifications = true } = await chrome.storage.local.get("notifications");
  if (!notifications) return;
  const t = await totalsFor(app);
  const opts = {
    type: "basic",
    iconUrl: chrome.runtime.getURL("icons/icon128.png"),
    title: paused ? `⏸ ${app} — contagem pausada` : `● ${app} — contando tempo de tela`,
    message: `Hoje: ${hm(t.today)}  ·  Mês: ${hm(t.month)}`,
    silent: true,
    priority: 0,
  };
  try {
    await chrome.notifications.create(NOTIF_ID, {
      ...opts,
      buttons: [{ title: paused ? "▶ Retomar contagem" : "⏸ Pausar contagem" }],
    });
  } catch (_) {
    // Firefox não suporta botões em notificações
    await chrome.notifications.create(NOTIF_ID, opts);
  }
}

async function setPaused(value) {
  await chrome.storage.local.set({ paused: value });
  const { live } = await chrome.storage.local.get("live");
  if (live) await chrome.storage.local.set({ live: { ...live, paused: value } });
  if (session && Date.now() - session.lastTick < 5000) {
    setBadge(session.app, session.secs, value);
    await notify(session.app, value);
  } else {
    chrome.notifications.clear(NOTIF_ID);
  }
}

// App desligado no meio da sessão: encerra na hora.
chrome.storage.onChanged.addListener((ch) => {
  if (!ch.disabledApps) return;
  const off = ch.disabledApps.newValue || [];
  enqueue(async () => {
    if (session && off.includes(session.app)) {
      session = null;
      await chrome.storage.local.set({ live: null });
      chrome.action.setBadgeText({ text: "" });
      chrome.notifications.clear(NOTIF_ID);
    }
  });
});

// ------------------------------------------------------------ sincronização

const SYNC_ALARM = "lt-sync";
chrome.alarms.create(SYNC_ALARM, { periodInMinutes: 5 });
chrome.alarms.onAlarm.addListener((a) => { if (a.name === SYNC_ALARM) syncNow().catch(() => {}); });

async function deviceId() {
  let { deviceId } = await chrome.storage.local.get("deviceId");
  if (!deviceId) {
    deviceId = "web-" + newSyncCode().slice(0, 12).toLowerCase();
    await chrome.storage.local.set({ deviceId });
  }
  return deviceId;
}

/** Envia o que este navegador contou e recebe o que os outros aparelhos contaram. */
async function syncNow() {
  const { sync } = await chrome.storage.local.get("sync");
  if (!sync || !sync.url || !sync.code) return { ok: false, error: "não configurado" };
  // 1) foto dos dados locais (dentro da fila, para não misturar com os ticks)
  let body;
  await enqueue(async () => {
    const all = await chrome.storage.local.get(null);
    const counts = {};
    for (const [k, v] of Object.entries(all)) {
      const p = k.split("|");
      if (p.length === 3 && p[0] === "t" && typeof v === "number") counts[`${p[1]}|${p[2]}`] = v;
    }
    body = { device: await deviceId(), name: sync.name || "Navegador", platform: "browser", counts, clears: all.clears || {} };
  });
  let res;
  try {
    const r = await fetch(sync.url.replace(/\/+$/, "") + "/v1/sync", {
      method: "POST",
      headers: { Authorization: "Bearer " + sync.code, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    res = await r.json();
    if (!r.ok) throw new Error(res.error || "HTTP " + r.status);
  } catch (e) {
    await chrome.storage.local.set({ syncStatus: { at: Date.now(), ok: false, error: String(e.message || e) } });
    return { ok: false, error: String(e.message || e) };
  }
  // 2) aplica limpezas feitas em outros aparelhos e guarda os dados deles
  await enqueue(async () => {
    const all = await chrome.storage.local.get(null);
    const clears = { ...(all.clears || {}) };
    const toClear = Object.entries(res.clears || {}).filter(([d, ts]) => ts > (clears[d] || 0)).map(([d]) => d);
    const rm = Object.keys(all).filter((k) => k.startsWith("t|") && toClear.includes(k.slice(2, 12)));
    if (rm.length) await chrome.storage.local.remove(rm);
    for (const d of toClear) clears[d] = res.clears[d];
    const remote = {};
    for (const dev of res.devices || []) {
      for (const [k, v] of Object.entries(dev.counts || {})) remote[k] = (remote[k] || 0) + v;
    }
    await chrome.storage.local.set({
      clears, remote,
      syncDevices: (res.devices || []).map((d) => ({ name: d.name, platform: d.platform, updatedAt: d.updatedAt })),
      syncStatus: { at: Date.now(), ok: true },
    });
  });
  return { ok: true };
}

/** Apaga a contagem de um dia (aqui e, via sincronização, nos outros aparelhos). */
async function clearDay(day) {
  await enqueue(async () => {
    const all = await chrome.storage.local.get(null);
    await chrome.storage.local.remove(Object.keys(all).filter((k) => k.startsWith(`t|${day}|`)));
    const remote = { ...(all.remote || {}) };
    for (const k of Object.keys(remote)) if (k.startsWith(day + "|")) delete remote[k];
    const clears = { ...(all.clears || {}), [day]: Date.now() };
    await chrome.storage.local.set({ remote, clears });
    if (session && dayKey() === day) { session.secs = 0; session.carry = 0; }
  });
  syncNow().catch(() => {});
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === "syncNow") { syncNow().then(sendResponse); return true; }
  if (msg.type === "clearDay" && /^\d{4}-\d{2}-\d{2}$/.test(msg.day)) { clearDay(msg.day).then(() => sendResponse({ ok: true })); return true; }
  return false;
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === "tick" && typeof msg.app === "string") {
    enqueue(() => onTick(msg.app, Number(msg.dt) || 1));
  } else if (msg.type === "stop") {
    enqueue(async () => { chrome.action.setBadgeText({ text: "" }); });
  } else if (msg.type === "setPaused") {
    enqueue(() => setPaused(!!msg.value)).then(() => sendResponse({ ok: true }));
    return true;
  }
  return false;
});

chrome.commands.onCommand.addListener((cmd) => {
  if (cmd === "toggle-pause") enqueue(async () => setPaused(!(await isPaused())));
});

if (chrome.notifications.onButtonClicked) {
  chrome.notifications.onButtonClicked.addListener((id) => {
    if (id === NOTIF_ID) enqueue(async () => setPaused(!(await isPaused())));
  });
}
chrome.notifications.onClicked.addListener((id) => {
  if (id === NOTIF_ID) chrome.tabs.create({ url: chrome.runtime.getURL("dashboard.html") });
});
