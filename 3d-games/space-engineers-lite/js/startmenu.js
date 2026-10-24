import {
  clearUniverseSession,
  loadUniverseFromSession,
  loadUniverseLibrary,
  normalizeUniverse,
  parseUniverse,
  removeUniverseFromLibrary,
  saveUniverseToSession,
  upsertUniverseInLibrary,
} from "./universeIO.js";
import { defaultUniverse } from "./universeSchema.js";

const multiplayerPanel = document.getElementById("multiplayer-panel");
const statusEl = document.getElementById("menu-status");
const loadModal = document.getElementById("load-modal");
const loadList = document.getElementById("load-list");

function setStatus(msg, error = false) {
  statusEl.textContent = msg;
  statusEl.style.color = error ? "#ffb0b0" : "#b8f7ca";
}

document.getElementById("btn-single").addEventListener("click", () => {
  clearUniverseSession();
  window.location.href = "index.html";
});

document.getElementById("btn-multi").addEventListener("click", () => {
  multiplayerPanel.classList.remove("hidden");
  setStatus("Multiplayer opened. Create or load a universe.");
});

document.getElementById("btn-back").addEventListener("click", () => {
  multiplayerPanel.classList.add("hidden");
  setStatus("Choose a mode.");
});

document.getElementById("btn-settings").addEventListener("click", () => {
  setStatus("Settings is a placeholder for now.");
});

document.getElementById("btn-character").addEventListener("click", () => {
  setStatus("Character customization is a placeholder for now.");
});

document.getElementById("btn-create-universe").addEventListener("click", () => {
  window.location.href = "editor.html";
});

function launchUniverse(universe) {
  try {
    const normalized = normalizeUniverse(universe);
    const id = upsertUniverseInLibrary(normalized, normalized.meta?.libraryId || "");
    normalized.meta = { ...(normalized.meta || {}), libraryId: id };
    saveUniverseToSession(normalized);
    window.location.href = "index.html";
  } catch (err) {
    setStatus(err.message || "Could not launch this universe.", true);
  }
}

function entryRow(title, subtitle, onLoad, onDelete = null) {
  const row = document.createElement("div");
  row.className = "universe-row";
  const info = document.createElement("div");
  const name = document.createElement("div");
  name.textContent = title;
  const meta = document.createElement("div");
  meta.className = "universe-meta";
  meta.textContent = subtitle;
  info.appendChild(name);
  info.appendChild(meta);

  const loadBtn = document.createElement("button");
  loadBtn.className = "menu-btn";
  loadBtn.textContent = "Load";
  loadBtn.addEventListener("click", onLoad);
  row.appendChild(info);
  row.appendChild(loadBtn);

  if (onDelete) {
    const delBtn = document.createElement("button");
    delBtn.className = "menu-btn ghost";
    delBtn.textContent = "Delete";
    delBtn.addEventListener("click", onDelete);
    row.appendChild(delBtn);
  } else {
    const spacer = document.createElement("div");
    row.appendChild(spacer);
  }
  return row;
}

function refreshLoadList() {
  loadList.innerHTML = "";
  const currentSession = loadUniverseFromSession();
  if (currentSession) {
    loadList.appendChild(
      entryRow(
        `${currentSession.name || "Session Universe"} (current session)`,
        `${currentSession.bodies.length} bodies`,
        () => launchUniverse(currentSession)
      )
    );
  }

  const library = loadUniverseLibrary();
  if (library.length === 0 && !currentSession) {
    loadList.appendChild(
      entryRow("Default Universe", "Built-in starter world", () => launchUniverse(defaultUniverse()))
    );
    return;
  }

  library.forEach((entry) => {
    const savedAt = entry.savedAt ? new Date(entry.savedAt).toLocaleString() : "Unknown date";
    const subtitle = `${entry.universe.bodies.length} bodies · saved ${savedAt}`;
    loadList.appendChild(
      entryRow(entry.name, subtitle, () => launchUniverse(entry.universe), () => {
        removeUniverseFromLibrary(entry.id);
        refreshLoadList();
      })
    );
  });
}

document.getElementById("btn-open-load").addEventListener("click", () => {
  refreshLoadList();
  loadModal.classList.remove("hidden");
});

document.getElementById("btn-close-load").addEventListener("click", () => {
  loadModal.classList.add("hidden");
});

loadModal.addEventListener("click", (ev) => {
  if (ev.target === loadModal) loadModal.classList.add("hidden");
});

document.getElementById("file-load-universe").addEventListener("change", async (ev) => {
  const input = ev.target;
  const file = input.files && input.files[0];
  if (!file) return;
  try {
    const text = await file.text();
    const universe = parseUniverse(text);
    upsertUniverseInLibrary(universe, universe.meta?.libraryId || "");
    saveUniverseToSession(universe);
    setStatus(`Loaded ${file.name}. Launching game...`);
    window.location.href = "index.html";
  } catch (err) {
    setStatus(err.message || "Could not load universe file.", true);
  } finally {
    input.value = "";
  }
});
