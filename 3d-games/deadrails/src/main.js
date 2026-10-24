import { io } from "socket.io-client";
import { createWorld3D } from "./world3d.js";

const STORAGE_KEY = "desert_rails_v01";
let state = { distance: 0, hour: 8, fuel: 0, phase: "transit" };
let config = {};
let you = { id: "", name: "" };
let prevPhase = "transit";
let lastLogSig = "";
let lastShopSig = "";

const $ = (id) => document.getElementById(id);

const els = {
  dist: $("stat-distance"),
  nextOp: $("stat-next-outpost"),
  cash: $("stat-cash"),
  bonds: $("stat-bonds"),
  fuelBar: $("bar-fuel"),
  healthBar: $("bar-health"),
  integBar: $("bar-integrity"),
  log: $("event-log"),
  hudTime: $("hud-time"),
  hudNight: $("hud-night"),
  hudBarricade: $("hud-barricade"),
  btnDrive: $("btn-drive"),
  btnStoke: $("btn-stoke-corpse"),
  btnStokeNews: $("btn-stoke-newspaper"),
  btnBarricade: $("btn-barricade"),
  btnBandage: $("btn-bandage"),
  btnShoot: $("btn-shoot"),
  modal: $("modal-root"),
  modalTitle: $("modal-title"),
  modalBody: $("modal-body"),
  modalActions: $("modal-actions"),
  net: $("net-status"),
  crew: $("net-crew")
};

const invEls = {
  coal: $("inv-coal"),
  gold: $("inv-gold"),
  corpse: $("inv-corpse"),
  scrap: $("inv-scrap"),
  newspaper: $("inv-newspaper")
};

const meta = loadMeta();

const socket = io({
  path: "/socket.io",
  transports: ["websocket", "polling"],
  autoConnect: true,
  query: { name: "Crew" + Math.floor(Math.random() * 900 + 100) }
});

socket.on("connect", () => {
  if (els.net) {
    els.net.textContent = "Online";
    els.net.className = "tag";
  }
});
socket.on("disconnect", () => {
  if (els.net) {
    els.net.textContent = "Offline";
    els.net.className = "tag danger";
  }
});

socket.on("you", (p) => {
  you = p || you;
});
socket.on("state", (s) => {
  state = s;
  onState();
});
socket.on("config", (c) => {
  config = c;
  onState();
});
socket.on("players", (list) => {
  if (els.crew) els.crew.textContent = "Crew: " + (list && list.length ? list.map((p) => p.name).join(", ") : "—");
});
socket.on("err", (e) => {
  const msg = e && (e.message || e.error);
  if (!msg || !els.log) return;
  const li = document.createElement("li");
  li.textContent = "! " + msg;
  els.log.appendChild(li);
  while (els.log.children.length > 20) els.log.removeChild(els.log.firstChild);
});

function loadMeta() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw);
  } catch (_) {}
  return { bonds: 0, bestDistance: 0 };
}
function saveMeta() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(meta));
  } catch (_) {}
}

function act(type, extra) {
  socket.emit("action", { type, ...(extra || {}) });
}

function onState() {
  const c = config.playerMaxHealth ? config : { playerMaxHealth: 100, fuelMax: 100, trainIntegrityMax: 100, outpostEveryMeters: 10000 };
  if (els.dist) els.dist.textContent = ((state.distance | 0) / 1000).toFixed(2) + " km";
  if (els.nextOp) {
    const next = state._nextOutpost != null ? state._nextOutpost : 10000;
    els.nextOp.textContent = (next / 1000).toFixed(1) + " km";
  }
  if (els.cash) els.cash.textContent = String(Math.floor(state.cash | 0));
  if (els.bonds) els.bonds.textContent = String(meta.bonds);
  if (els.fuelBar) els.fuelBar.style.width = Math.min(100, ((state.fuel | 0) / (c.fuelMax || 100)) * 100) + "%";
  if (els.healthBar) els.healthBar.style.width = Math.min(100, ((state.health | 0) / (c.playerMaxHealth || 100)) * 100) + "%";
  if (els.integBar) els.integBar.style.width = Math.min(100, ((state.trainIntegrity | 0) / (c.trainIntegrityMax || 100)) * 100) + "%";
  if (els.hudTime) els.hudTime.textContent = "Time " + (Number(state.hour) || 0).toFixed(1) + "h";
  if (els.hudNight) {
    els.hudNight.textContent = state._isNight ? "Night: " + (state._moonLabel || "") : "Day";
    els.hudNight.className = "tag" + (state._isNight ? " night" : "");
  }
  if (els.hudBarricade) els.hudBarricade.textContent = "Barricade " + (state.barricadeLevel | 0);
  if (els.btnDrive) els.btnDrive.textContent = (state.isDriving ? "Stop (S)" : "Drive (D)");
  if (els.btnStoke) els.btnStoke.disabled = (state.inv && state.inv.corpse) < 1;
  if (els.btnStokeNews) els.btnStokeNews.disabled = (state.inv && state.inv.newspaper) < 1;
  if (els.btnBarricade) {
    const v = state.inv || {};
    els.btnBarricade.disabled = !((v.newspaper | 0) >= 1 || (v.scrap | 0) >= 1);
  }
  if (els.btnBandage) els.btnBandage.disabled = ((state.inv && state.inv.bandage) | 0) < 1;
  if (els.btnShoot) els.btnShoot.disabled = !state.hasRevolver || (state.ammo | 0) < 1;

  const v = state.inv || {};
  if (invEls.coal) invEls.coal.textContent = String(v.coal | 0);
  if (invEls.gold) invEls.gold.textContent = String(v.gold_bar | 0);
  if (invEls.corpse) invEls.corpse.textContent = String(v.corpse | 0);
  if (invEls.scrap) invEls.scrap.textContent = String(v.scrap | 0);
  if (invEls.newspaper) invEls.newspaper.textContent = String(v.newspaper | 0);

  if (els.log && Array.isArray(state.logs)) {
    const lines = state.logs.slice(-20);
    const sig = lines.join("\n");
    if (sig !== lastLogSig) {
      lastLogSig = sig;
      els.log.innerHTML = "";
      for (const line of lines) {
        const li = document.createElement("li");
        li.textContent = line;
        els.log.appendChild(li);
      }
    }
  }

  if (state.phase === "outpost" && config.prices) {
    if (prevPhase !== "outpost") openOutpostModal();
    else refreshShopTable();
  } else if (state.phase === "finale" && (state.finaleStep | 0) === 0) {
    if (els.modal && els.modal.hidden) openFinaleModal();
  } else if (state.phase === "finale" && (state.finaleStep | 0) === 1) {
    if (els.modal) els.modal.hidden = true;
  } else if (state.phase === "won") {
    if (prevPhase !== "won" && (state.bondsEarned | 0) > 0) {
      meta.bonds = (meta.bonds | 0) + (state.bondsEarned | 0);
      if ((state.distance | 0) > (meta.bestDistance | 0)) meta.bestDistance = state.distance | 0;
      saveMeta();
    }
    if (prevPhase !== "won") showWinModal();
  } else if (state.phase === "dead") {
    if (prevPhase !== "dead") showDeadModal();
  } else if (state.phase === "transit") {
    if (els.modal) els.modal.hidden = true;
  }

  prevPhase = state.phase;
}
function openOutpostModal() {
  if (!els.modal || !config.prices) return;
  if (els.modalTitle) els.modalTitle.textContent = "Outpost " + (state.outpostIndex | 0);
  if (els.modalBody) {
    const km = (state.lastOutpostDistance / 1000).toFixed(0);
    els.modalBody.innerHTML =
      "<p>Safe zone at <strong>" +
      km +
      " km</strong>. One stash for the whole crew — spend wisely.</p>";
    const shop = document.createElement("div");
    shop.className = "shop-block";
    shop.innerHTML = buildShopTable();
    els.modalBody.appendChild(shop);
    lastShopSig = shopSig();
  }
  if (els.modalActions) {
    els.modalActions.innerHTML = "";
    const leave = document.createElement("button");
    leave.className = "primary";
    leave.type = "button";
    leave.textContent = "Leave outpost (continue)";
    leave.addEventListener("click", () => act("leaveOutpost"));
    els.modalActions.appendChild(leave);
  }
  els.modal.hidden = false;
}

function buildShopTable() {
  const p = config.prices;
  const sp = config.sellPrices;
  if (!p || !sp) return "";
  return (
    "<table class='shop-table'><thead><tr><th>Buy</th><th>Price</th><th>Action</th></tr></thead><tbody>" +
    rowBuy("Coal (×1)", p.coal, "buy-coal", state.shopCoal) +
    rowBuy("Newspaper", p.newspaper, "buy-news", 1) +
    rowBuy("Bandage", p.bandage, "buy-bandage", 1) +
    rowBuy("Ammo (×5)", p.revolver_ammo * 5, "buy-ammo5", state.shopAmmo >= 5 ? 1 : 0) +
    rowBuy("Revolver", p.revolver, "buy-revolver", !state.hasRevolver ? 1 : 0) +
    "<tr><td colspan='3'><strong>Sell</strong></td></tr>" +
    sellRow("Gold bar", sp.gold_bar, "sell-gold", state.inv && state.inv.gold_bar) +
    sellRow("Corpse", sp.corpse, "sell-corpse", state.inv && state.inv.corpse) +
    sellRow("Scrap", sp.scrap, "sell-scrap", state.inv && state.inv.scrap) +
    "</tbody></table>"
  );
}
function rowBuy(label, price, act, left) {
  if (left <= 0) return "<tr><td>" + label + "</td><td>—</td><td>Out of stock</td></tr>";
  return (
    "<tr><td>" + label + "</td><td>$" + price + "</td><td><button type='button' class='shop-act' data-act='" + act + "'>Buy</button></td></tr>"
  );
}
function sellRow(label, price, act, have) {
  if (!have) return "<tr><td>" + label + "</td><td>—</td><td>None</td></tr>";
  return (
    "<tr><td>" + label + " (x" + have + ")</td><td>+$" + price + "</td><td><button type='button' class='shop-act' data-act='" + act + "'>Sell 1</button></td></tr>"
  );
}

function shopSig() {
  const v = state.inv || {};
  return [
    state.cash | 0,
    state.shopCoal | 0,
    state.shopAmmo | 0,
    state.hasRevolver ? 1 : 0,
    v.gold_bar | 0,
    v.corpse | 0,
    v.scrap | 0
  ].join(",");
}

function refreshShopTable() {
  if (state.phase !== "outpost" || !els.modalBody) return;
  const sig = shopSig();
  if (sig === lastShopSig) return;
  lastShopSig = sig;
  const blocks = els.modalBody.querySelectorAll(".shop-block");
  blocks.forEach((b) => b.remove());
  const shop = document.createElement("div");
  shop.className = "shop-block";
  shop.innerHTML = buildShopTable();
  els.modalBody.appendChild(shop);
}

document.addEventListener("click", (ev) => {
  const t = ev.target;
  if (t && t.classList && t.classList.contains("shop-act") && t.getAttribute) {
    const a = t.getAttribute("data-act");
    if (a) act("shop", { act: a, type: "shop" });
  }
});

function openFinaleModal() {
  if (!els.modal) return;
  if (els.modalTitle) els.modalTitle.textContent = "The Bridge";
  if (els.modalBody) {
    els.modalBody.innerHTML = "<p>Lower the span, then hold through the attack.</p>";
  }
  if (els.modalActions) {
    els.modalActions.innerHTML = "";
    const b = document.createElement("button");
    b.className = "primary";
    b.type = "button";
    b.textContent = "Lower the bridge";
    b.addEventListener("click", () => {
      act("lowerBridge");
      if (els.modal) els.modal.hidden = true;
    });
    els.modalActions.appendChild(b);
  }
  els.modal.hidden = false;
}

function showWinModal() {
  if (!els.modal) return;
  if (els.modalTitle) els.modalTitle.textContent = "Run complete";
  if (els.modalBody) {
    els.modalBody.innerHTML = "<p>Survived. Bonds in your ledger (local save).</p>";
  }
  if (els.modalActions) {
    els.modalActions.innerHTML = "";
    const b = document.createElement("button");
    b.className = "primary";
    b.type = "button";
    b.textContent = "New run";
    b.addEventListener("click", () => {
      act("newRun");
      if (els.modal) els.modal.hidden = true;
    });
    els.modalActions.appendChild(b);
  }
  els.modal.hidden = false;
  if (els.bonds) els.bonds.textContent = String(meta.bonds);
}

function showDeadModal() {
  if (!els.modal) return;
  if (els.modalTitle) els.modalTitle.textContent = "Wiped";
  if (els.modalBody) {
    els.modalBody.innerHTML = "<p>The desert won this time.</p>";
  }
  if (els.modalActions) {
    els.modalActions.innerHTML = "";
    const b = document.createElement("button");
    b.className = "primary";
    b.type = "button";
    b.textContent = "Try again";
    b.addEventListener("click", () => {
      act("tryAgain");
      if (els.modal) els.modal.hidden = true;
    });
    els.modalActions.appendChild(b);
  }
  els.modal.hidden = false;
}

if (els.btnDrive) els.btnDrive.addEventListener("click", () => act("toggleDrive"));
$("btn-load-coal")?.addEventListener("click", () => act("loadCoal"));
if (els.btnStoke) els.btnStoke.addEventListener("click", () => act("stokeCorpse"));
if (els.btnStokeNews) els.btnStokeNews.addEventListener("click", () => act("stokeNewspaper"));
if (els.btnBarricade) els.btnBarricade.addEventListener("click", () => act("barricade"));
if (els.btnBandage) els.btnBandage.addEventListener("click", () => act("bandage"));
if (els.btnShoot) els.btnShoot.addEventListener("click", () => act("shoot"));

window.addEventListener("keydown", (e) => {
  if (e.code === "KeyD" || e.code === "KeyW") act("toggleDrive");
  else if (e.code === "KeyS" && !e.shiftKey) act("stop");
  else if (e.code === "KeyC") act("stokeCorpse");
  else if (e.code === "KeyN") act("stokeNewspaper");
  else if (e.code === "KeyB") act("bandage");
  else if (e.code === "KeyF") act("barricade");
  else if (e.code === "Space") {
    e.preventDefault();
    act("shoot");
  }
});

const root = $("three-root");
if (root) {
  createWorld3D(root, {
    getDistance: () => state.distance | 0,
    getHour: () => state.hour | 0,
    getIsNight: () => !!state._isNight
  });
}

onState();
