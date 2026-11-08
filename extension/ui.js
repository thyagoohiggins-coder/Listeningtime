/* Peças visuais compartilhadas entre o popup e o painel. */
/* eslint-disable no-unused-vars */

const ICON_PAUSE = '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';
const ICON_PLAY = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';

/** Mostrador circular: o dia de hoje dividido por app, com marcações de relógio. */
function ringSVG(byApp, total, size = 240, centerTop = "HOJE", centerSub = "de streaming") {
  const c = size / 2, r = size * 0.383, sw = size * 0.058;
  const C = 2 * Math.PI * r;
  const gap = total > 0 ? 3 : 0;
  let ticks = "";
  for (let i = 0; i < 48; i++) {
    const a = (i / 48) * 2 * Math.PI - Math.PI / 2;
    const major = i % 12 === 0;
    const r1 = r + sw / 2 + size * 0.03, r2 = r1 + (major ? size * 0.05 : size * 0.025);
    ticks += `<line x1="${c + r1 * Math.cos(a)}" y1="${c + r1 * Math.sin(a)}" x2="${c + r2 * Math.cos(a)}" y2="${c + r2 * Math.sin(a)}"
      stroke="${major ? "var(--accent)" : "var(--line-hi)"}" stroke-width="${major ? 2 : 1}"/>`;
  }
  let segs = "", offset = 0;
  const items = APPS.filter((a) => (byApp[a.name] || 0) > 0);
  for (const a of items) {
    const len = (byApp[a.name] / total) * C;
    const vis = Math.max(0.5, len - (items.length > 1 ? gap : 0));
    segs += `<circle cx="${c}" cy="${c}" r="${r}" fill="none" stroke="${a.color}" stroke-width="${sw}"
      stroke-dasharray="${vis} ${C - vis}" stroke-dashoffset="${-offset}" transform="rotate(-90 ${c} ${c})">
      <title>${a.name}: ${hm(byApp[a.name])}</title></circle>`;
    offset += len;
  }
  return `<svg class="ring" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}" role="img"
      aria-label="${centerTop}: ${hm(total)}">
    ${ticks}
    <circle cx="${c}" cy="${c}" r="${r}" fill="none" stroke="var(--line)" stroke-width="${sw}"/>
    ${segs}
    <text x="${c}" y="${c - size * 0.09}" text-anchor="middle" fill="var(--text-2)" font-size="${size * 0.046}" letter-spacing="2">${centerTop}</text>
    <text x="${c}" y="${c + size * 0.05}" text-anchor="middle" fill="var(--text)" font-size="${size * 0.13}" font-weight="700" class="num">${hm(total)}</text>
    <text x="${c}" y="${c + size * 0.14}" text-anchor="middle" fill="var(--muted)" font-size="${size * 0.042}">${centerSub}</text>
  </svg>`;
}

/** Status: ao vivo / pausado / monitorando. */
function statusHTML(live, paused) {
  if (paused) return `<div class="status paused"><span class="dot"></span>Contagem pausada${live ? " · " + live.app : ""}</div>`;
  if (live) return `<div class="status live"><span class="dot"></span>Ao vivo · ${live.app}</div>`;
  return `<div class="status idle"><span class="dot"></span>Monitorando · nenhum vídeo</div>`;
}

function pauseButtonHTML(paused) {
  return `<button class="pause ${paused ? "is-paused" : ""}" data-action="toggle-pause">
    ${paused ? ICON_PLAY + "Retomar contagem" : ICON_PAUSE + "Pausar contagem"}</button>`;
}

async function togglePause() {
  const { paused } = await chrome.storage.local.get("paused");
  await chrome.runtime.sendMessage({ type: "setPaused", value: !paused });
}

/** Barra horizontal segmentada (com 2px de respiro entre os segmentos). */
function stackBarHTML(parts, max, h = 8) {
  const segs = parts.filter((p) => p.v > 0).map((p) =>
    `<span style="width:${(p.v / max) * 100}%;background:${p.color}" title="${p.name}: ${hm(p.v)}"></span>`).join("");
  return `<div class="sbar" style="height:${h}px">${segs}</div>`;
}

/** Duração curtíssima para células: 2h14, 45m */
function hmTiny(secs) {
  if (!secs) return "";
  if (secs < 60) return "<1m";
  const h = Math.floor(secs / 3600), m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h${pad2(m)}` : `${m}m`;
}
