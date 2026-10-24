import './game.js';

const statusEl = document.getElementById('mp-status');
const hostInput = document.getElementById('mp-host-id');

let peer = null;
let conn = null;
let hostWorldTimer = null;
let playerTimer = null;

function setStatus(text) {
  if (statusEl) statusEl.textContent = text;
}

function clearTimers() {
  if (hostWorldTimer) {
    clearInterval(hostWorldTimer);
    hostWorldTimer = null;
  }
  if (playerTimer) {
    clearInterval(playerTimer);
    playerTimer = null;
  }
}

function wireConnection(c, iAmHost) {
  conn = c;
  clearTimers();

  c.on('open', () => {
    try {
      const mine = window.LumberGame?.getRuntimePlanetId?.() ?? 'home';
      c.send(JSON.stringify({ type: 'hello', planetId: mine }));
    } catch (_) {}
    setStatus(iAmHost ? 'Client connected' : 'Connected to host');
    playerTimer = setInterval(() => {
      const G = window.LumberGame;
      if (!G || !c.open) return;
      try {
        c.send(JSON.stringify({ type: 'player', player: G.getPlayerState() }));
      } catch (_) {}
    }, 90);
    if (iAmHost) {
      hostWorldTimer = setInterval(() => {
        const G = window.LumberGame;
        if (!G || !c.open) return;
        try {
          c.send(JSON.stringify({ type: 'world', logs: G.getLogStates() }));
        } catch (_) {}
      }, 160);
    }
  });

  c.on('close', () => {
    setStatus('Disconnected');
    window.LumberGame?.applyRemotePlayer(null);
    clearTimers();
  });
  c.on('error', (err) => {
    console.warn(err);
    setStatus('Connection error');
  });

  c.on('data', (raw) => {
    let msg;
    try {
      msg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch {
      return;
    }
    const G = window.LumberGame;
    if (!G) return;
    if (msg.type === 'hello' && typeof msg.planetId === 'string') {
      const mine = G.getRuntimePlanetId?.() ?? 'home';
      if (msg.planetId !== mine) {
        setStatus(`Same planet required (host/client map): peer “${msg.planetId}”, you “${mine}”.`);
        try {
          c.close();
        } catch (_) {}
        return;
      }
    }
    if (msg.type === 'player' && msg.player) G.applyRemotePlayer(msg.player);
    if (!iAmHost && msg.type === 'world' && Array.isArray(msg.logs)) {
      G.applyLogStates(msg.logs, false);
    }
  });
}

function destroyPeer() {
  clearTimers();
  if (conn) {
    try {
      conn.close();
    } catch (_) {}
  }
  conn = null;
  if (peer) {
    try {
      peer.destroy();
    } catch (_) {}
  }
  peer = null;
  window.LumberGame?.applyRemotePlayer(null);
}

function host() {
  if (typeof Peer === 'undefined') {
    setStatus('PeerJS missing (check script / network)');
    return;
  }
  destroyPeer();
  setStatus('Starting host…');
  peer = new Peer(undefined, { debug: 0 });

  peer.on('open', (id) => {
    setStatus(`Host ready — ID: ${id}`);
  });

  peer.on('connection', (c) => {
    if (conn && conn.open) {
      c.close();
      return;
    }
    wireConnection(c, true);
  });

  peer.on('error', (e) => {
    console.warn(e);
    setStatus(`Peer error: ${e.type || e.message || 'unknown'}`);
  });
}

function join() {
  if (typeof Peer === 'undefined') {
    setStatus('PeerJS missing (check script / network)');
    return;
  }
  const hostId = (hostInput?.value || '').trim();
  if (!hostId) {
    setStatus('Paste host ID first');
    return;
  }
  destroyPeer();
  setStatus('Connecting…');
  peer = new Peer(undefined, { debug: 0 });

  peer.on('open', () => {
    const c = peer.connect(hostId, { reliable: true });
    wireConnection(c, false);
  });

  peer.on('error', (e) => {
    console.warn(e);
    setStatus(`Peer error: ${e.type || e.message || 'unknown'}`);
  });
}

document.getElementById('mp-host')?.addEventListener('click', host);
document.getElementById('mp-join')?.addEventListener('click', join);

window.LumberNet = {
  disconnect() {
    destroyPeer();
    setStatus('Offline — use Host or Join');
  },
};

setStatus('Offline — use Host or Join');
