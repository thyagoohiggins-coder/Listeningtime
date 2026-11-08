/**
 * Listening Time — servidor de sincronização (Cloudflare Worker + Durable Object).
 *
 * Cada "espaço" é identificado pelo SHA-256 do código secreto compartilhado entre
 * os aparelhos. Cada aparelho envia SOMENTE o que ele mesmo contou; o servidor
 * devolve os dados dos outros aparelhos. Assim nada é contado em dobro.
 *
 * POST /v1/sync   Authorization: Bearer <código>
 *   { device, name, platform, counts: {"AAAA-MM-DD|App": seg}, clears: {"AAAA-MM-DD": ts} }
 * ->{ clears, devices: [{ id, name, platform, updatedAt, counts }] }   (sem o próprio aparelho)
 */
import { DurableObject } from "cloudflare:workers";

const KEY_RE = /^\d{4}-\d{2}-\d{2}\|[^|]{1,40}$/;
const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_ENTRIES = 20000;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Max-Age": "86400",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS },
  });
}

function cleanCounts(raw) {
  const out = {};
  let n = 0;
  for (const [k, v] of Object.entries(raw || {})) {
    if (!KEY_RE.test(k) || typeof v !== "number" || !Number.isFinite(v)) continue;
    out[k] = Math.max(0, Math.min(86400, Math.floor(v)));
    if (++n >= MAX_ENTRIES) break;
  }
  return out;
}

function cleanClears(raw) {
  const out = {};
  for (const [k, v] of Object.entries(raw || {})) {
    if (DAY_RE.test(k) && typeof v === "number" && Number.isFinite(v) && v > 0) out[k] = Math.floor(v);
  }
  return out;
}

const dropDays = (counts, days) => {
  for (const k of Object.keys(counts)) if (days.has(k.slice(0, 10))) delete counts[k];
};

export class SyncSpace extends DurableObject {
  async sync(body) {
    const st = this.ctx.storage;
    const device = String(body.device || "").slice(0, 64);
    const counts = cleanCounts(body.counts);
    const clientClears = cleanClears(body.clears);
    const clears = (await st.get("clears")) || {};

    // 1) Limpezas que este aparelho ainda não aplicou: descarta as contagens dele desses dias.
    const notApplied = new Set(Object.keys(clears).filter((d) => clears[d] > (clientClears[d] || 0)));
    dropDays(counts, notApplied);

    // 2) Limpezas novas vindas deste aparelho: valem para todos os outros aparelhos.
    const fresh = new Set();
    for (const [d, ts] of Object.entries(clientClears)) {
      if (ts > (clears[d] || 0)) { clears[d] = ts; fresh.add(d); }
    }

    const stored = await st.list({ prefix: "dev:" });
    const devices = [];
    const writes = { clears };
    for (const [key, dev] of stored) {
      if (key === `dev:${device}`) continue;
      if (fresh.size) { dropDays(dev.counts, fresh); writes[key] = dev; }
      devices.push({ id: key.slice(4), name: dev.name, platform: dev.platform, updatedAt: dev.updatedAt, counts: dev.counts });
    }
    writes[`dev:${device}`] = {
      name: String(body.name || "Aparelho").slice(0, 40),
      platform: String(body.platform || "").slice(0, 20),
      updatedAt: Date.now(),
      counts,
    };
    await st.put(writes);
    return { clears, devices, serverTime: Date.now() };
  }
}

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/v1/health")) {
      return json({ ok: true, service: "listeningtime-sync" });
    }

    if (request.method === "POST" && url.pathname === "/v1/sync") {
      const code = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
      if (code.length < 16) return json({ error: "código de sincronização inválido" }, 401);
      const len = Number(request.headers.get("Content-Length") || 0);
      if (len > 2_000_000) return json({ error: "dados grandes demais" }, 413);
      let body;
      try { body = await request.json(); } catch { return json({ error: "JSON inválido" }, 400); }
      if (!body || typeof body.device !== "string" || !body.device) return json({ error: "campo device ausente" }, 400);
      const stub = env.SYNC.get(env.SYNC.idFromName(await sha256(code)));
      return json(await stub.sync(body));
    }

    return json({ error: "não encontrado" }, 404);
  },
};
