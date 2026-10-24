/**
 * Desert Rails (Dead Rails–style) — browser prototype v0.1
 * Reads config from config.json (same folder as index.html; use a static server for reliable fetch).
 */
(function () {
  "use strict";

  /** @type {object} */
  const DEFAULT_CONFIG = {
    version: 1,
    maxDistanceMeters: 80000,
    outpostEveryMeters: 10000,
    finaleAtMeters: 80000,
    finaleBufferMeters: 2000,
    startingCash: 80,
    startingGoldBars: 1,
    startingFuelInEngine: 35,
    fuelMax: 100,
    fuelPerCoal: 22,
    fuelFromCorpse: 8,
    fuelFromNewspaper: 4,
    baseBurnPerSecondDriving: 1.1,
    speedMetersPerSecond: 7,
    stoppedThreatToIntegrity: 4.5,
    integrityDamageToPlayer: 0.8,
    playerMaxHealth: 100,
    trainIntegrityMax: 100,
    nightStartHour: 19.5,
    dayStartHour: 5.5,
    hoursPerRealSecond: 0.12,
    nightEnemyTickDamage: 0.15,
    fullMoonName: "Full Moon (wolves)",
    bloodMoonName: "Blood Moon (vampires)",
    prices: {
      coal: 5,
      newspaper: 2,
      bandage: 8,
      metal_sheet: 12,
      revolver_ammo: 2,
      revolver: 40
    },
    sellPrices: {
      gold_bar: 45,
      corpse: 4,
      scrap: 2
    },
    outpostShops: {
      stockCoal: 20,
      stockAmmo: 30
    }
  };

  let C = { ...DEFAULT_CONFIG };

  const INV_MAX = 10;
  const STORAGE_KEY = "desert_rails_v01";

  /** @type {{ bonds: number, bestDistance: number }} */
  let meta = { bonds: 0, bestDistance: 0 };

  /**
   * @typedef {"transit" | "outpost" | "finale" | "won" | "dead"} Phase
   */

  /**
   * @type {{
   *   distance: number,
   *   hour: number,
   *   phase: Phase,
   *   lastOutpostDistance: number,
   *   cash: number,
   *   health: number,
   *   trainIntegrity: number,
   *   fuel: number,
   *   isDriving: boolean,
   *   hasRevolver: boolean,
   *   ammo: number,
   *   inv: Record<string, number>,
   *   outpostIndex: number,
   *   moonIndex: number,
   *   shopCoal: number,
   *   shopAmmo: number,
   *   barricadeLevel: number,
   *   finaleStep: number,
   *   lastTs: number
   * }}
   */
  let state = createInitialState(0);

  const canvas = document.getElementById("game-canvas");
  if (!canvas || !canvas.getContext) return;
  const ctx = canvas.getContext("2d");

  const els = {
    dist: document.getElementById("stat-distance"),
    nextOp: document.getElementById("stat-next-outpost"),
    cash: document.getElementById("stat-cash"),
    bonds: document.getElementById("stat-bonds"),
    fuelBar: document.getElementById("bar-fuel"),
    healthBar: document.getElementById("bar-health"),
    integBar: document.getElementById("bar-integrity"),
    log: document.getElementById("event-log"),
    hudTime: document.getElementById("hud-time"),
    hudNight: document.getElementById("hud-night"),
    hudBarricade: document.getElementById("hud-barricade"),
    btnDrive: document.getElementById("btn-drive"),
    btnStoke: document.getElementById("btn-stoke-corpse"),
    btnStokeNews: document.getElementById("btn-stoke-newspaper"),
    btnBarricade: document.getElementById("btn-barricade"),
    btnBandage: document.getElementById("btn-bandage"),
    invCoal: document.getElementById("inv-coal"),
    invGold: document.getElementById("inv-gold"),
    invCorpse: document.getElementById("inv-corpse"),
    invScrap: document.getElementById("inv-scrap"),
    invNews: document.getElementById("inv-newspaper"),
    btnShoot: document.getElementById("btn-shoot"),
    modal: document.getElementById("modal-root"),
    modalTitle: document.getElementById("modal-title"),
    modalBody: document.getElementById("modal-body"),
    modalActions: document.getElementById("modal-actions"),
  };

  function createInitialState(startTs) {
    return {
      distance: 0,
      hour: 8,
      phase: "transit",
      lastOutpostDistance: 0,
      cash: C.startingCash,
      health: C.playerMaxHealth,
      trainIntegrity: C.trainIntegrityMax,
      fuel: C.startingFuelInEngine,
      isDriving: false,
      hasRevolver: false,
      ammo: 0,
      inv: {
        coal: 2,
        gold_bar: C.startingGoldBars,
        corpse: 0,
        scrap: 1,
        newspaper: 3,
        bandage: 1
      },
      outpostIndex: 0,
      moonIndex: 0,
      shopCoal: 0,
      shopAmmo: 0,
      barricadeLevel: 0,
      finaleStep: 0,
      lastTs: startTs
    };
  }

  function countInvItems() {
    return Object.values(state.inv).reduce((a, b) => a + b, 0);
  }

  function invCount() {
    return countInvItems();
  }

  function loadMeta() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const j = JSON.parse(raw);
        meta.bonds = Math.max(0, j.bonds | 0);
        meta.bestDistance = Math.max(0, j.bestDistance | 0);
      }
    } catch (_) {}
  }

  function saveMeta() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(meta));
    } catch (_) {}
  }

  function isNight() {
    return state.hour >= C.nightStartHour || state.hour < C.dayStartHour;
  }

  function moonLabel() {
    if (!isNight()) return "Day";
    const kinds = [
      "Normal (zombies)",
      C.fullMoonName,
      C.bloodMoonName,
      "Storm (lightning risk)"
    ];
    return kinds[state.moonIndex % 4];
  }

  function nextOutpostDistance() {
    const o = C.outpostEveryMeters;
    return Math.floor(state.distance / o + 1) * o;
  }

  function logLine(msg) {
    if (!els.log) return;
    const li = document.createElement("li");
    li.className = "new";
    li.textContent = msg;
    els.log.insertBefore(li, els.log.firstChild);
    while (els.log.children.length > 20) {
      els.log.removeChild(els.log.lastChild);
    }
  }

  function setCanvasSize() {
    const r = canvas.getBoundingClientRect();
    const w = Math.max(400, Math.floor(r.width * devicePixelRatio));
    const h = Math.max(200, Math.floor((r.height || 320) * devicePixelRatio));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
  }

  function draw() {
    setCanvasSize();
    const w = canvas.width;
    const h = canvas.height;
    const t = state.hour;
    const night = isNight();

    let top = [40, 90, 150];
    if (!night) {
      const dayBlend = 1 - Math.abs(t - 12) / 12;
      top = [90 + 40 * (1 - dayBlend), 160 + 20 * (1 - dayBlend), 220 + 20 * (1 - dayBlend)];
    } else {
      const danger = state.moonIndex % 4 === 2;
      top = danger ? [20, 15, 45] : [15, 20, 55];
    }
    const g = ctx.createLinearGradient(0, 0, 0, h * 0.55);
    g.addColorStop(0, `rgb(${top[0]},${top[1]},${top[2]})`);
    g.addColorStop(1, night ? "rgb(30,20,15)" : "rgb(190,150,100)");
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h * 0.55);

    if (night) {
      ctx.fillStyle = "rgba(0,0,0,0.25)";
      ctx.fillRect(0, 0, w, h);
    }
    if (state.phase === "outpost" || (state.distance >= C.finaleAtMeters - C.finaleBufferMeters && state.distance < C.finaleAtMeters)) {
      ctx.fillStyle = "rgba(80,120,60,0.12)";
      ctx.fillRect(0, 0, w, h * 0.45);
    }

    const yGround = h * 0.58;
    ctx.fillStyle = night ? "rgb(55,40,30)" : "rgb(180,140,90)";
    ctx.fillRect(0, yGround, w, h - yGround);

    const railY = h * 0.62;
    ctx.strokeStyle = "#333";
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.moveTo(0, railY);
    ctx.lineTo(w, railY);
    ctx.stroke();
    for (let x = -((state.distance * 0.15) % 40); x < w; x += 40) {
      ctx.fillStyle = "#666";
      ctx.fillRect(x, railY - 2, 18, 4);
    }

    const parallax = (state.distance * 0.02) % 200;
    for (let i = 0; i < 4; i++) {
      const px = (i * 200 - parallax) % (w + 200) - 100;
      ctx.fillStyle = "rgba(60,50,40,0.4)";
      ctx.beginPath();
      ctx.moveTo(px, yGround);
      ctx.lineTo(px + 30, h * 0.45);
      ctx.lineTo(px + 90, h * 0.45);
      ctx.closePath();
      ctx.fill();
    }

    const tx = w * 0.2;
    const trainY = railY - 4;
    ctx.fillStyle = "#2a1a10";
    ctx.fillRect(tx, trainY - 42, 120, 36);
    ctx.fillStyle = "#1a0e08";
    ctx.fillRect(tx - 8, trainY - 28, 24, 18);
    ctx.fillStyle = "#3a2a1a";
    for (let c = 0; c < 2; c++) {
      ctx.fillRect(tx + 50 + c * 50, trainY - 32, 44, 26);
    }
    if (state.fuel > 5) {
      ctx.fillStyle = "rgba(255,120,40,0.4)";
      ctx.beginPath();
      ctx.arc(tx + 8, trainY - 20, 8, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = "#111";
    [tx + 4, tx + 90, tx + 140].forEach((xc) => {
      ctx.beginPath();
      ctx.arc(xc, trainY + 2, 10, 0, Math.PI * 2);
      ctx.fill();
    });

    if (state.isDriving) {
      ctx.fillStyle = "rgba(200,200,200,0.2)";
      ctx.fillRect(w - 40, 8, 32, 10);
    }
  }

  function tickOutpostCheck() {
    if (state.phase !== "transit") return;
    const o = C.outpostEveryMeters;
    if (state.distance >= C.finaleAtMeters) {
      enterFinale();
      return;
    }
    const crossed =
      state.distance >= o &&
      Math.floor(state.distance / o) > Math.floor(state.lastOutpostDistance / o);
    if (crossed) {
      enterOutpost();
    }
  }

  function enterOutpost() {
    if (state.phase === "outpost" || state.distance >= C.finaleAtMeters) return;
    state.phase = "outpost";
    state.isDriving = false;
    state.outpostIndex += 1;
    state.lastOutpostDistance = Math.floor(state.distance / C.outpostEveryMeters) * C.outpostEveryMeters;
    state.shopCoal = C.outpostShops.stockCoal;
    state.shopAmmo = C.outpostShops.stockAmmo;
    if (els.modalTitle) els.modalTitle.textContent = "Outpost " + state.outpostIndex;
    if (els.modalBody) {
      els.modalBody.innerHTML =
        "<p>Safe zone. Trade, heal, and load coal. Distance: <strong>" +
        (state.lastOutpostDistance / 1000).toFixed(0) +
        " km</strong>.</p><p>Tip: buy coal before the next " +
        (C.outpostEveryMeters / 1000) +
        " km run.</p>";
    }
    if (els.modalActions) {
      els.modalActions.innerHTML = "";
      const shop = document.createElement("div");
      shop.className = "shop-block";
      shop.innerHTML = buildShopTable();
      els.modalBody.appendChild(shop);
      const leave = document.createElement("button");
      leave.className = "primary";
      leave.textContent = "Leave outpost (continue)";
      leave.addEventListener("click", () => {
        if (els.modal) els.modal.hidden = true;
        state.phase = "transit";
        logLine("You rolled west again.");
      });
      els.modalActions.appendChild(leave);
    }
    if (els.modal) els.modal.hidden = false;
    logLine("Reached outpost #" + state.outpostIndex + ".");
  }

  function buildShopTable() {
    const p = C.prices;
    return (
      "<table class='shop-table'><thead><tr><th>Buy</th><th>Price</th><th>Action</th></tr></thead><tbody>" +
      rowBuy("Coal (×1)", p.coal, "buy-coal", state.shopCoal) +
      rowBuy("Newspaper (barricade fuel)", p.newspaper, "buy-news", 30) +
      rowBuy("Bandage", p.bandage, "buy-bandage", 5) +
      rowBuy("Revolver Ammo (×5)", p.revolver_ammo * 5, "buy-ammo5", state.shopAmmo >= 5 ? 1 : 0) +
      rowBuy("Revolver (weapon)", p.revolver, "buy-revolver", !state.hasRevolver ? 1 : 0) +
      "<tr><td colspan='3'><strong>Sell</strong> (at counter)</td></tr>" +
      sellRow("Gold bar", C.sellPrices.gold_bar, "sell-gold", state.inv.gold_bar) +
      sellRow("Corpse", C.sellPrices.corpse, "sell-corpse", state.inv.corpse) +
      sellRow("Scrap", C.sellPrices.scrap, "sell-scrap", state.inv.scrap) +
      "</tbody></table>"
    );
  }

  function rowBuy(label, price, act, left) {
    if (left <= 0) return "<tr><td>" + label + "</td><td>—</td><td>Out of stock</td></tr>";
    return (
      "<tr><td>" +
      label +
      "</td><td>$" +
      price +
      "</td><td><button type='button' class='shop-act' data-act='" +
      act +
      "'>Buy</button></td></tr>"
    );
  }

  function sellRow(label, price, act, have) {
    if (have <= 0) return "<tr><td>" + label + "</td><td>—</td><td>None</td></tr>";
    return (
      "<tr><td>" +
      label +
      " (x" +
      have +
      ")</td><td>+$" +
      price +
      " ea</td><td><button type='button' class='shop-act' data-act='" +
      act +
      "'>Sell 1</button></td></tr>"
    );
  }

  function enterFinale() {
    if (state.phase !== "transit" || state.distance < C.finaleAtMeters) return;
    state.phase = "finale";
    state.isDriving = false;
    state.finaleStep = 0;
    if (els.modalTitle) els.modalTitle.textContent = "The Bridge";
    if (els.modalBody) {
      els.modalBody.innerHTML =
        "<p>Bandit fire ahead. The span must be lowered. Hold position, then cross.</p><p>Lower the bridge, survive the wave, then you win the run.</p>";
    }
    if (els.modalActions) {
      els.modalActions.innerHTML = "";
      const b1 = document.createElement("button");
      b1.className = "primary";
      b1.textContent = "Lower the bridge";
      b1.addEventListener("click", () => {
        state.finaleStep = 1;
        if (els.modal) els.modal.hidden = true;
        logLine("Bridge lowered! Incoming wave…");
        let t = 8;
        const id = setInterval(() => {
          if (state.phase !== "finale") {
            clearInterval(id);
            return;
          }
          t -= 0.1;
          state.trainIntegrity = Math.max(0, state.trainIntegrity - 0.4);
          state.health = Math.max(0, state.health - (isNight() ? 0.2 : 0.1));
          if (t <= 0) {
            clearInterval(id);
            winRun();
          }
        }, 100);
      });
      els.modalActions.appendChild(b1);
    }
    if (els.modal) els.modal.hidden = false;
  }

  function winRun() {
    state.phase = "won";
    const earned = 5 + Math.floor(state.distance / 20000);
    meta.bonds += earned;
    if (state.distance > meta.bestDistance) meta.bestDistance = Math.floor(state.distance);
    saveMeta();
    if (els.modalTitle) els.modalTitle.textContent = "Run complete";
    if (els.modalBody) {
      els.modalBody.innerHTML =
        "<p><strong>Survived the run.</strong> Bonds earned: +" +
        earned +
        ".</p><p>Your crew would be proud. (Inspired by Dead Rails–style play.)</p>";
    }
    if (els.modalActions) {
      els.modalActions.innerHTML = "";
      const r = document.createElement("button");
      r.className = "primary";
      r.textContent = "New run";
      r.addEventListener("click", () => {
        if (els.modal) els.modal.hidden = true;
        state = createInitialState(performance.now());
        logLine("A new run begins in the fort…");
      });
      els.modalActions.appendChild(r);
    }
    if (els.modal) els.modal.hidden = false;
  }

  function loseRun() {
    if (state.phase === "dead" || state.phase === "won") return;
    state.phase = "dead";
    if (els.modalTitle) els.modalTitle.textContent = "Wiped";
    if (els.modalBody) {
      els.modalBody.innerHTML =
        "<p>The desert claims another crew. Distance reached: " +
        (state.distance / 1000).toFixed(1) +
        " km.</p>";
    }
    if (els.modalActions) {
      els.modalActions.innerHTML = "";
      const r = document.createElement("button");
      r.className = "primary";
      r.textContent = "Try again";
      r.addEventListener("click", () => {
        if (els.modal) els.modal.hidden = true;
        state = createInitialState(performance.now());
        logLine("You wake at the outpost. Another chance.");
      });
      els.modalActions.appendChild(r);
    }
    if (els.modal) els.modal.hidden = false;
  }

  function update(dt) {
    if (state.phase === "dead" || state.phase === "won") return;
    if (state.phase !== "transit" && state.phase !== "finale") return;
    if (state.phase === "finale" && state.finaleStep === 0) return;

    state.hour = (state.hour + dt * C.hoursPerRealSecond) % 24;
    if (Math.random() < dt * 0.08 && isNight()) {
      const mul = 1 - state.barricadeLevel * 0.12;
      const base = C.nightEnemyTickDamage * mul;
      if (state.moonIndex % 4 === 1) {
        state.trainIntegrity = Math.max(0, state.trainIntegrity - base * 1.2);
        state.health = Math.max(0, state.health - 0.05);
      } else if (state.moonIndex % 4 === 2) {
        const breach = state.barricadeLevel < 2 ? 1.4 : 0.6;
        state.health = Math.max(0, state.health - base * breach);
      } else {
        state.trainIntegrity = Math.max(0, state.trainIntegrity - base);
        if (state.trainIntegrity < 20) {
          state.health = Math.max(0, state.health - C.integrityDamageToPlayer * dt);
        }
      }
    }

    if (state.phase === "transit" && state.isDriving && state.fuel > 0) {
      state.distance += C.speedMetersPerSecond * dt;
      state.fuel = Math.max(0, state.fuel - C.baseBurnPerSecondDriving * dt);
      tickOutpostCheck();
    }

    if (state.fuel <= 0 && state.phase === "transit") {
      state.isDriving = false;
      const threat = C.stoppedThreatToIntegrity * dt;
      state.trainIntegrity = Math.max(0, state.trainIntegrity - threat);
      if (state.trainIntegrity < 30) {
        state.health = Math.max(0, state.health - (C.integrityDamageToPlayer * 1.2 * dt));
      }
    }

    if (state.health <= 0 && state.phase !== "dead") loseRun();

    if (Math.random() < dt * 0.12 && state.phase === "transit" && !state.isDriving && state.fuel > 0) {
      if (state.inv.corpse < 3 && invCount() < INV_MAX) {
        state.inv.corpse = (state.inv.corpse || 0) + 1;
        logLine("Recovered a corpse (fuel or sell).");
      }
    }
  }

  function syncUI() {
    if (els.dist) els.dist.textContent = (state.distance / 1000).toFixed(2) + " km";
    if (els.nextOp) els.nextOp.textContent = (nextOutpostDistance() / 1000).toFixed(1) + " km";
    if (els.cash) els.cash.textContent = String(Math.floor(state.cash));
    if (els.bonds) els.bonds.textContent = String(meta.bonds);
    const fp = (state.fuel / C.fuelMax) * 100;
    if (els.fuelBar) els.fuelBar.style.width = Math.min(100, Math.max(0, fp)) + "%";
    const hp = (state.health / C.playerMaxHealth) * 100;
    if (els.healthBar) els.healthBar.style.width = Math.min(100, Math.max(0, hp)) + "%";
    const ti = (state.trainIntegrity / C.trainIntegrityMax) * 100;
    if (els.integBar) els.integBar.style.width = Math.min(100, Math.max(0, ti)) + "%";
    if (els.hudTime) els.hudTime.textContent = "Time " + state.hour.toFixed(1) + "h";
    if (els.hudNight) {
      els.hudNight.textContent = isNight() ? "Night: " + moonLabel() : "Day";
      els.hudNight.className = "tag" + (isNight() ? " night" : "");
    }
    if (els.hudBarricade) els.hudBarricade.textContent = "Barricade " + state.barricadeLevel;

    if (els.btnDrive) {
      els.btnDrive.textContent = state.isDriving ? "Stop (S)" : "Drive (D)";
    }
    if (els.btnStoke) els.btnStoke.disabled = (state.inv.corpse | 0) < 1;
    if (els.btnStokeNews) els.btnStokeNews.disabled = (state.inv.newspaper | 0) < 1;
    if (els.btnBarricade) {
      const can = (state.inv.newspaper | 0) >= 1 || (state.inv.scrap | 0) >= 1;
      els.btnBarricade.disabled = !can;
    }
    if (els.btnBandage) els.btnBandage.disabled = (state.inv.bandage | 0) < 1;
    if (els.btnShoot) {
      els.btnShoot.disabled = !state.hasRevolver || state.ammo < 1;
    }

    if (els.invCoal) els.invCoal.textContent = String(state.inv.coal | 0);
    if (els.invGold) els.invGold.textContent = String(state.inv.gold_bar | 0);
    if (els.invCorpse) els.invCorpse.textContent = String(state.inv.corpse | 0);
    if (els.invScrap) els.invScrap.textContent = String(state.inv.scrap | 0);
    if (els.invNews) els.invNews.textContent = String(state.inv.newspaper | 0);
  }

  function onKey(e) {
    if (e.code === "KeyD" || e.code === "KeyW") {
      if (state.phase === "transit" || state.phase === "finale") {
        if (state.fuel > 0) {
          state.isDriving = !state.isDriving;
        } else {
          logLine("No fuel — stoke the fire!");
        }
      }
    } else if (e.code === "KeyS" && !e.shiftKey) {
      if (state.phase === "transit" || state.phase === "finale") state.isDriving = false;
    } else if (e.code === "KeyC") {
      stokeCorpse();
    } else if (e.code === "KeyN") {
      stokeNewspaper();
    } else if (e.code === "KeyB") {
      useBandage();
    } else if (e.code === "KeyF") {
      weldBarricade();
    } else if (e.code === "KeyR" || e.key === " ") {
      e.preventDefault();
      if (state.hasRevolver && state.ammo > 0) {
        state.ammo -= 1;
        state.trainIntegrity = Math.min(C.trainIntegrityMax, state.trainIntegrity + 2);
        logLine("Snap shot. Thinning the line.");
      }
    }
  }

  function stokeCorpse() {
    if (!state.inv.corpse) return;
    state.inv.corpse -= 1;
    state.fuel = Math.min(C.fuelMax, state.fuel + C.fuelFromCorpse);
    logLine("Corpse → fire. The engine groans with heat.");
  }

  function stokeNewspaper() {
    if (!state.inv.newspaper) return;
    state.inv.newspaper -= 1;
    state.fuel = Math.min(C.fuelMax, state.fuel + C.fuelFromNewspaper);
    logLine("Tossed paper into the fire.");
  }

  function loadCoal() {
    if (!state.inv.coal) return;
    state.inv.coal -= 1;
    state.fuel = Math.min(C.fuelMax, state.fuel + C.fuelPerCoal);
    logLine("Shoveled coal.");
  }

  function weldBarricade() {
    if (state.inv.newspaper >= 1) {
      state.inv.newspaper -= 1;
    } else if (state.inv.scrap >= 1) {
      state.inv.scrap -= 1;
    } else {
      return;
    }
    state.barricadeLevel = Math.min(5, state.barricadeLevel + 1);
    logLine("Weld: barricade level " + state.barricadeLevel + " (Z / F).");
  }

  function useBandage() {
    if (!state.inv.bandage) return;
    state.inv.bandage -= 1;
    state.health = Math.min(C.playerMaxHealth, state.health + 35);
    logLine("Used bandage.");
  }

  function wireShopClicks() {
    document.addEventListener("click", (ev) => {
      const t = ev.target;
      if (!t || !t.getAttribute) return;
      if (!t.classList || !t.classList.contains("shop-act")) return;
      const act = t.getAttribute("data-act");
      if (act === "buy-coal" && state.shopCoal > 0 && state.cash >= C.prices.coal) {
        if (countInvItems() < INV_MAX) {
          state.cash -= C.prices.coal;
          state.inv.coal = (state.inv.coal | 0) + 1;
          state.shopCoal -= 1;
        } else {
          logLine("Sack full.");
        }
        refreshOpenShop();
      } else if (act === "buy-news" && state.cash >= C.prices.newspaper) {
        if (countInvItems() < INV_MAX) {
          state.cash -= C.prices.newspaper;
          state.inv.newspaper = (state.inv.newspaper | 0) + 1;
        } else {
          logLine("Sack full.");
        }
        refreshOpenShop();
      } else if (act === "buy-bandage" && state.cash >= C.prices.bandage) {
        if (countInvItems() < INV_MAX) {
          state.cash -= C.prices.bandage;
          state.inv.bandage = (state.inv.bandage | 0) + 1;
        } else {
          logLine("Sack full.");
        }
        refreshOpenShop();
      } else if (act === "buy-ammo5" && state.shopAmmo >= 5 && state.cash >= C.prices.revolver_ammo * 5) {
        if (!state.hasRevolver) {
          logLine("You need a revolver first.");
        } else {
          state.cash -= C.prices.revolver_ammo * 5;
          state.ammo += 5;
          state.shopAmmo -= 5;
        }
        refreshOpenShop();
      } else if (act === "buy-revolver" && !state.hasRevolver && state.cash >= C.prices.revolver) {
        state.cash -= C.prices.revolver;
        state.hasRevolver = true;
        state.ammo += 6;
        refreshOpenShop();
      } else if (act === "sell-gold" && (state.inv.gold_bar | 0) > 0) {
        state.inv.gold_bar -= 1;
        state.cash += C.sellPrices.gold_bar;
        refreshOpenShop();
      } else if (act === "sell-corpse" && (state.inv.corpse | 0) > 0) {
        state.inv.corpse -= 1;
        state.cash += C.sellPrices.corpse;
        refreshOpenShop();
      } else if (act === "sell-scrap" && (state.inv.scrap | 0) > 0) {
        state.inv.scrap -= 1;
        state.cash += C.sellPrices.scrap;
        refreshOpenShop();
      }
    });
  }

  function refreshOpenShop() {
    if (state.phase !== "outpost" || !els.modalBody || !els.modal || els.modal.hidden) return;
    const blocks = els.modalBody.querySelectorAll(".shop-block");
    blocks.forEach((b) => b.remove());
    const shop = document.createElement("div");
    shop.className = "shop-block";
    shop.innerHTML = buildShopTable();
    els.modalBody.appendChild(shop);
  }

  function gameLoop(ts) {
    if (!state.lastTs) state.lastTs = ts;
    const dt = Math.min(0.1, (ts - state.lastTs) / 1000);
    state.lastTs = ts;
    if (state.phase === "transit" && state.isDriving) {
      const newMoon = (Math.floor(state.distance / 25000) % 4) | 0;
      if (newMoon !== state.moonIndex) {
        state.moonIndex = newMoon;
        logLine("Moon shift: " + moonLabel() + " tonight.");
      }
    }
    update(dt);
    draw();
    syncUI();
    requestAnimationFrame(gameLoop);
  }

  function mergeConfig(j) {
    return {
      ...DEFAULT_CONFIG,
      ...j,
      prices: { ...DEFAULT_CONFIG.prices, ...(j.prices || {}) },
      sellPrices: { ...DEFAULT_CONFIG.sellPrices, ...(j.sellPrices || {}) },
      outpostShops: { ...DEFAULT_CONFIG.outpostShops, ...(j.outpostShops || {}) }
    };
  }

  async function init() {
    loadMeta();
    C = { ...DEFAULT_CONFIG };
    try {
      const r = await fetch("config.json", { cache: "no-store" });
      if (r.ok) C = mergeConfig(await r.json());
    } catch (_) {
      C = { ...DEFAULT_CONFIG };
    }
    state = createInitialState(performance.now());
    logLine("Welcome. D/W drive, S stop, C corpse fuel, N paper, F barricade, B bandage, click Load coal.");
    if (els.btnDrive) els.btnDrive.addEventListener("click", () => {
      if (state.phase === "transit" || state.phase === "finale") {
        if (state.fuel > 0) state.isDriving = !state.isDriving;
        else logLine("Engine dry.");
      }
    });
    if (els.btnStoke) els.btnStoke.addEventListener("click", stokeCorpse);
    if (els.btnStokeNews) els.btnStokeNews.addEventListener("click", stokeNewspaper);
    if (els.btnBarricade) els.btnBarricade.addEventListener("click", weldBarricade);
    if (els.btnBandage) els.btnBandage.addEventListener("click", useBandage);
    if (els.btnShoot) {
      els.btnShoot.addEventListener("click", () => {
        if (state.hasRevolver && state.ammo > 0) {
          state.ammo -= 1;
          state.trainIntegrity = Math.min(C.trainIntegrityMax, state.trainIntegrity + 3);
          logLine("Fired. (+train integrity / fewer breaches)");
        }
      });
    }
    const btnLoad = document.getElementById("btn-load-coal");
    if (btnLoad) btnLoad.addEventListener("click", loadCoal);
    window.addEventListener("keydown", onKey);
    wireShopClicks();
    requestAnimationFrame(gameLoop);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
