/* Roda nas páginas dos serviços de streaming: avisa o background quando um vídeo está tocando. */
(() => {
  let videos = [];
  let lastScan = 0;
  let last = performance.now();
  let wasPlaying = false;

  // Procura <video> no documento e dentro de shadow DOMs abertos (Disney+, Max…).
  function scan() {
    const found = new Set(document.querySelectorAll("video"));
    const walk = (root, depth) => {
      if (depth > 6) return;
      for (const el of root.querySelectorAll("*")) {
        if (el.shadowRoot) {
          el.shadowRoot.querySelectorAll("video").forEach((v) => found.add(v));
          walk(el.shadowRoot, depth + 1);
        }
      }
    };
    try { walk(document, 0); } catch (_) { /* ignora */ }
    videos = [...found];
  }

  function isPlaying() {
    const now = performance.now();
    if (now - lastScan > 4000 || videos.some((v) => !v.isConnected)) { scan(); lastScan = now; }
    const pip = document.pictureInPictureElement;
    if (document.visibilityState !== "visible" && !pip) return false;
    return videos.some((v) => {
      if (v.paused || v.ended || v.readyState < 3) return false;
      if (v === pip || v === document.fullscreenElement) return true;
      const r = v.getBoundingClientRect();
      return r.width >= 200 && r.height >= 112; // ignora miniaturas/prévias pequenas
    });
  }

  function send(msg) {
    try {
      chrome.runtime.sendMessage(msg).catch(() => {});
    } catch (_) {
      // extensão atualizada/recarregada: este script ficou órfão
      clearInterval(timer);
    }
  }

  const timer = setInterval(() => {
    const now = performance.now();
    const dt = Math.min(5, (now - last) / 1000);
    last = now;
    // Em sites de página única (YouTube) a URL muda sem recarregar: detecta a cada tick.
    const app = detectApp(location);
    const playing = !!app && isPlaying();
    if (playing) send({ type: "tick", app, dt });
    else if (wasPlaying) send({ type: "stop" });
    wasPlaying = playing;
  }, 1000);

  window.addEventListener("pagehide", () => { if (wasPlaying) send({ type: "stop" }); });
})();
