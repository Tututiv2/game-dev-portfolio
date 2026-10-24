// Export / import the save as a base64 string for backup or sharing.

import { serialize } from "./save.js";

// UTF-8 safe base64 (handles any JSON content).
function toB64(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
function fromB64(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

export function exportSave(state) {
  return "AETHER1:" + toB64(JSON.stringify(serialize(state)));
}

// Returns a parsed save object (raw) or throws.
export function importSave(str) {
  const trimmed = String(str).trim();
  const payload = trimmed.startsWith("AETHER1:") ? trimmed.slice(8) : trimmed;
  const json = fromB64(payload);
  const data = JSON.parse(json);
  if (typeof data !== "object" || data === null) throw new Error("invalid save");
  return data;
}
