import { UNIVERSE_VERSION, defaultUniverse } from "./universeSchema.js";

const SESSION_KEY = "selectedUniverseV1";
const LIBRARY_KEY = "universeLibraryV1";

function asNumber(v, fallback = 0) {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

export function validateUniverse(universe) {
  const errors = [];
  if (!universe || typeof universe !== "object") {
    return { ok: false, errors: ["Universe must be an object."] };
  }
  if (Number(universe.version) !== UNIVERSE_VERSION) {
    errors.push(`Universe schema mismatch. Expected version ${UNIVERSE_VERSION}.`);
  }
  if (!Array.isArray(universe.bodies) || universe.bodies.length === 0) {
    errors.push("Universe must include at least one body.");
  }
  const byId = new Map();
  (universe.bodies || []).forEach((body, index) => {
    if (!body || typeof body !== "object") {
      errors.push(`Body at index ${index} is invalid.`);
      return;
    }
    if (!body.id || typeof body.id !== "string") {
      errors.push(`Body at index ${index} is missing id.`);
      return;
    }
    if (byId.has(body.id)) errors.push(`Duplicate body id: ${body.id}`);
    byId.set(body.id, body);
    if (asNumber(body.radius, -1) <= 0) errors.push(`Body ${body.id} must have radius > 0.`);
    if (!body.orbit || typeof body.orbit !== "object") {
      errors.push(`Body ${body.id} must include orbit settings.`);
    }
    if (!body.rotation || typeof body.rotation !== "object") {
      errors.push(`Body ${body.id} must include rotation settings.`);
    }
  });

  (universe.bodies || []).forEach((body) => {
    if (!body || !body.id) return;
    if (body.parentId && !byId.has(body.parentId)) {
      errors.push(`Body ${body.id} parentId points to unknown body ${body.parentId}.`);
    }
  });

  const marks = new Map();
  const hasCycle = (id) => {
    const mark = marks.get(id);
    if (mark === 1) return true;
    if (mark === 2) return false;
    marks.set(id, 1);
    const body = byId.get(id);
    if (body && body.parentId && byId.has(body.parentId) && hasCycle(body.parentId)) return true;
    marks.set(id, 2);
    return false;
  };
  for (const id of byId.keys()) {
    if (hasCycle(id)) {
      errors.push(`Orbit hierarchy contains a cycle at ${id}.`);
      break;
    }
  }
  return { ok: errors.length === 0, errors };
}

export function normalizeUniverse(universe) {
  const base = universe && typeof universe === "object" ? universe : defaultUniverse();
  return {
    version: asNumber(base.version, UNIVERSE_VERSION),
    name: base.name || "Custom Universe",
    units: base.units || "meters",
    meta: {
      ...(base.meta || {}),
      savedAt: new Date().toISOString(),
    },
    bodies: (base.bodies || []).map((body, index) => ({
      id: body.id || `body-${index + 1}`,
      name: body.name || body.id || `Body ${index + 1}`,
      type: body.type || "planet",
      color: asNumber(body.color, 0xffffff),
      radius: Math.max(10, asNumber(body.radius, 1000)),
      terrainStyle: body.terrainStyle || "rocky",
      surfaceGravity: asNumber(body.surfaceGravity, body.type === "sun" ? 0 : 9.8),
      parentId: body.parentId || null,
      orbit: {
        semiMajor: Math.max(0, asNumber(body.orbit && body.orbit.semiMajor, 0)),
        eccentricity: Math.max(0, asNumber(body.orbit && body.orbit.eccentricity, 0)),
        inclination: asNumber(body.orbit && body.orbit.inclination, 0),
        phase: asNumber(body.orbit && body.orbit.phase, 0),
        orbitSpeed: asNumber(body.orbit && body.orbit.orbitSpeed, 0),
      },
      rotation: {
        spinSpeed: asNumber(body.rotation && body.rotation.spinSpeed, 0.01),
        axialTilt: asNumber(body.rotation && body.rotation.axialTilt, 0),
      },
    })),
  };
}

export function parseUniverse(text) {
  const parsed = JSON.parse(text);
  const normalized = normalizeUniverse(parsed);
  const check = validateUniverse(normalized);
  if (!check.ok) {
    throw new Error(check.errors.join("\n"));
  }
  return normalized;
}

export function serializeUniverse(universe) {
  const normalized = normalizeUniverse(universe);
  return JSON.stringify(normalized, null, 2);
}

export function saveUniverseToSession(universe) {
  const normalized = normalizeUniverse(universe);
  const check = validateUniverse(normalized);
  if (!check.ok) throw new Error(check.errors.join("\n"));
  try {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(normalized));
  } catch (err) {
    throw new Error("Could not store the universe for this session (storage full or blocked).");
  }
}

export function loadUniverseFromSession() {
  const raw = sessionStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    const normalized = normalizeUniverse(parsed);
    const check = validateUniverse(normalized);
    if (!check.ok) return null;
    return normalized;
  } catch {
    return null;
  }
}

export function clearUniverseSession() {
  sessionStorage.removeItem(SESSION_KEY);
}

export function loadUniverseLibrary() {
  const raw = localStorage.getItem(LIBRARY_KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .map((entry) => {
        if (!entry || typeof entry !== "object") return null;
        const normalized = normalizeUniverse(entry.universe);
        const check = validateUniverse(normalized);
        if (!check.ok) return null;
        return {
          id: String(entry.id || `u-${Date.now()}`),
          name: entry.name || normalized.name || "Unnamed Universe",
          savedAt: entry.savedAt || new Date().toISOString(),
          universe: normalized,
        };
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}

function saveUniverseLibrary(entries) {
  try {
    localStorage.setItem(LIBRARY_KEY, JSON.stringify(entries));
  } catch (err) {
    throw new Error("Could not save the universe library (localStorage full or blocked).");
  }
}

export function upsertUniverseInLibrary(universe, preferredId = "") {
  const normalized = normalizeUniverse(universe);
  const check = validateUniverse(normalized);
  if (!check.ok) throw new Error(check.errors.join("\n"));
  const entries = loadUniverseLibrary();
  const id = preferredId || normalized.meta?.libraryId || `u-${Date.now().toString(36)}-${Math.floor(Math.random() * 9999)}`;
  const next = {
    id,
    name: normalized.name || "Unnamed Universe",
    savedAt: new Date().toISOString(),
    universe: {
      ...normalized,
      meta: { ...(normalized.meta || {}), libraryId: id },
    },
  };
  const idx = entries.findIndex((entry) => entry.id === id);
  if (idx >= 0) entries[idx] = next;
  else entries.unshift(next);
  saveUniverseLibrary(entries.slice(0, 50));
  return id;
}

export function removeUniverseFromLibrary(id) {
  const entries = loadUniverseLibrary().filter((entry) => entry.id !== id);
  saveUniverseLibrary(entries);
}
