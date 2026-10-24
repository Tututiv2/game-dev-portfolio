/**
 * In-page digital gain (Web Audio). Works in Chrome, Edge, Brave, and other Chromium browsers.
 * Brave: if a site is still silent, try Shields down for that site (lion icon) and reload.
 */
(function () {
  "use strict";

  const FLAG = "data-vi-boost-ok";

  let ctx = null;
  let masterGain = null;

  function getGraph() {
    if (!ctx) {
      ctx = new AudioContext({ latencyHint: "interactive" });
      masterGain = ctx.createGain();
      masterGain.connect(ctx.destination);
    }
    return { ctx, masterGain };
  }

  function setGainValue(value) {
    getGraph();
    masterGain.gain.value = value;
  }

  function readGainAndApply() {
    chrome.storage.local.get({ gain: 1.0 }, (r) => {
      const v = typeof r.gain === "number" && r.gain >= 0 ? r.gain : 1.0;
      setGainValue(v);
    });
  }

  function attachElement(el) {
    if (el.nodeName !== "AUDIO" && el.nodeName !== "VIDEO") {
      return;
    }
    if (el.hasAttribute(FLAG)) {
      return;
    }

    getGraph();

    try {
      const source = ctx.createMediaElementSource(el);
      source.connect(masterGain);
      el.setAttribute(FLAG, "1");
    } catch {
      // Cross-origin media without CORS, or the element already has a source graph.
    }
  }

  /** document + all nested shadow roots (YouTube, custom players) */
  function scanTree(root) {
    if (!root || !root.querySelectorAll) {
      return;
    }
    root.querySelectorAll("audio, video").forEach(attachElement);
    const all = root.querySelectorAll("*");
    for (const el of all) {
      if (el.shadowRoot) {
        scanTree(el.shadowRoot);
      }
    }
  }

  function boot() {
    readGainAndApply();

    chrome.storage.onChanged.addListener((changes, area) => {
      if (area !== "local" || !changes.gain) {
        return;
      }
      const n = changes.gain.newValue;
      if (typeof n === "number" && n >= 0) {
        setGainValue(n);
      }
    });

    const fullRescan = () => scanTree(document.documentElement);
    fullRescan();

    const observer = new MutationObserver((mutations) => {
      for (const m of mutations) {
        for (const n of m.addedNodes) {
          if (n.nodeType === 1) {
            scanTree(n);
          }
        }
      }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    // Sites that build the player late or inside shadow: catch when playback starts.
    const tryAttach = (ev) => {
      const t = ev.target;
      if (t && (t.nodeName === "AUDIO" || t.nodeName === "VIDEO")) {
        attachElement(t);
        return;
      }
      fullRescan();
    };
    document.addEventListener("play", tryAttach, true);
    document.addEventListener("playing", tryAttach, true);
    document.addEventListener("loadstart", tryAttach, true);

    // Short burst of rescans for lazy/SPA players (YouTube, etc.)
    let n = 0;
    const iv = setInterval(() => {
      fullRescan();
      if (++n >= 20) {
        clearInterval(iv);
      }
    }, 1000);

    const resume = () => {
      if (ctx && ctx.state === "suspended") {
        ctx.resume().catch(() => {});
      }
    };
    document.addEventListener("click", resume, { capture: true, passive: true });
    document.addEventListener("pointerdown", resume, { capture: true, passive: true });
    document.addEventListener("keydown", resume, { capture: true, passive: true });
  }

  function start() {
    if (document.documentElement) {
      boot();
      return;
    }
    const id = setInterval(() => {
      if (document.documentElement) {
        clearInterval(id);
        boot();
      }
    }, 0);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})();
